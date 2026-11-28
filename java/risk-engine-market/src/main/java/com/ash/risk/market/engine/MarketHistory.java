/*
 * Project Drishti · Any data. Any domain. One grammar.
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
 * All rights reserved.
 *
 * PROPRIETARY AND CONFIDENTIAL.
 *
 * This file is the confidential and proprietary property of Ashutosh Sinha.
 * Unauthorised copying, use, modification, distribution or disclosure of this
 * file, via any medium, is strictly prohibited except with the express prior
 * written permission of the copyright holder.
 *
 * See the LICENSE file in the root of this repository for the full terms.
 */
package com.ash.risk.market.engine;

import com.ash.engine.Cell;
import com.ash.engine.Descriptor;
import com.ash.engine.EngineException;
import com.ash.engine.EngineException.Kind;
import com.ash.engine.Explanation;
import com.ash.engine.Filter;
import com.ash.engine.Request;
import com.ash.engine.Step;
import com.ash.engine.Steps;
import com.ash.engine.Table;
import com.ash.engine.Tables;
import com.ash.risk.kernel.io.InputRows;
import com.ash.risk.kernel.workbook.AnswerBook;
import com.ash.risk.market.calc.Backtest;
import com.ash.risk.market.calc.Periods;
import com.ash.risk.market.calc.StressWindows;
import com.ash.risk.market.calc.VarCalc;
import com.ash.risk.market.calc.VarMath;
import com.ash.tools.xlsx.Sheet;
import com.ash.tools.xlsx.Workbook;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The outputs over earlier dates (VAR_GUIDE.md sections 18.9 to 18.14; REGULATORY_VAR_REPORTING.md): the backtest of the official VaR
 * against the desk P&amp;L ({@link Backtest}: Basel's zones, the capital multiplier, Kupiec and Christoffersen), the period statistics and
 * the regulatory tables over the official records ({@link Periods}), and the stress window selection over the long P&amp;L histories
 * ({@link StressWindows}). They read stored official records and supplied histories only: nothing is recomputed from vectors here.
 */
final class MarketHistory {

    private final Descriptor descriptor;
    private final MarketOptions options;
    private final MarketDay day;

    MarketHistory(Descriptor descriptor, MarketOptions options, MarketDay day) {
        this.descriptor = descriptor;
        this.options = options;
        this.day = day;
    }

    // ------------------------------------------------------------------------------------------------------------- the official records

    /** The official records a request sees, to the session's date: by date, then node id, then confidence. */
    private Map<LocalDate, Map<String, Map<Double, MarketExtras.Official>>> officials(Filter see) {
        Map<LocalDate, Map<String, Map<Double, MarketExtras.Official>>> out = new TreeMap<>();
        for (MarketExtras.Official o : day.extras().officials) {
            if (o.date().isAfter(day.date)) {
                continue;
            }
            Map<String, Object> keys = new HashMap<>();
            keys.put("HIERARCHY", o.hierarchy());
            keys.put("NODE_ID", o.node());
            if (!InputRows.visible(see, keys)) {
                continue;
            }
            out.computeIfAbsent(o.date(), d -> new TreeMap<>()).computeIfAbsent(o.node(), n -> new TreeMap<>()).put(o.confidence(), o);
        }
        return out;
    }

    /** The record of a node on a date at the engine's confidence (a record without a confidence counts as it). */
    private MarketExtras.Official at(Map<String, Map<Double, MarketExtras.Official>> date, String node, double confidence) {
        if (date == null || date.get(node) == null) {
            return null;
        }
        for (Map.Entry<Double, MarketExtras.Official> e : date.get(node).entrySet()) {
            if (e.getKey().isNaN() || Math.abs(e.getKey() - confidence) < 1e-9) {
                return e.getValue();
            }
        }
        return null;
    }

    /** A node's VaR on a date at a confidence: a row at it, else a record's BACKTEST_VAR at it; NaN when none carries one. */
    private static double varAt(Map<String, Map<Double, MarketExtras.Official>> date, String node, double confidence) {
        if (date == null || date.get(node) == null) {
            return Double.NaN;
        }
        double other = Double.NaN;
        for (MarketExtras.Official o : date.get(node).values()) {
            double v = o.varAt(confidence);
            if (Double.isFinite(v) && (Double.isNaN(o.confidence()) || Math.abs(o.confidence() - confidence) < 1e-9)) {
                return v;                                                 // a row at the confidence wins over a column beside another
            }
            other = Double.isFinite(v) ? v : other;
        }
        return other;
    }

    /** {@code MGMT~DESK-RATES} to {@code DESK-RATES}. */
    static String tail(String id) {
        int i = id.lastIndexOf('~');
        return i < 0 ? id : id.substring(i + 1);
    }

    /** What a node takes from the desk P&amp;L: every row (a root), or its desks' rows (in its legal entity, when it has one). */
    record Reach(String node, String hierarchy, boolean all, Set<String> desks, String legalEntity) {}

    static Reach reach(Map<String, Map<Double, MarketExtras.Official>> record, String node) {
        Map<String, MarketExtras.Official> nodes = new TreeMap<>();
        record.forEach((id, byConf) -> nodes.put(id, byConf.values().iterator().next()));
        MarketExtras.Official f = nodes.get(node);
        if (f == null) {
            return null;
        }
        String le = null;
        for (String a = node; a != null; a = nodes.get(a) == null ? null : nodes.get(a).parent()) {
            MarketExtras.Official af = nodes.get(a);
            if (af != null && "legal-entity".equals(af.level())) {
                le = tail(a);
            }
        }
        Set<String> desks = new LinkedHashSet<>();
        for (Map.Entry<String, MarketExtras.Official> e : nodes.entrySet()) {
            if ("desk".equals(e.getValue().level()) && under(nodes, e.getKey(), node)) {
                desks.add(tail(e.getKey()));
            }
        }
        return new Reach(node, f.hierarchy(), f.parent() == null, desks, le);
    }

    private static boolean under(Map<String, MarketExtras.Official> nodes, String id, String ancestor) {
        for (String a = id; a != null; a = nodes.get(a) == null ? null : nodes.get(a).parent()) {
            if (a.equals(ancestor)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------------------------------------------------- the backtest

    /** One tested day of a node at a confidence. */
    record TestedDay(LocalDate date, LocalDate prior, double var, double actual, double hypothetical, double rtpl) {

        boolean exception() {
            return actual < -var;
        }
    }

    /** A node's backtest at a confidence: its tested days (oldest first, at most the window) and the figures. */
    record Test(String node, String hierarchy, double confidence, List<TestedDay> days, Backtest backtest, int actual, int hypothetical, int rtpl,
            int counted, String zone, double multiplier) {}

    List<Test> backtests(Request request, Filter seeRecords, Filter seeDesks) {
        Map<LocalDate, Map<String, Map<Double, MarketExtras.Official>>> recs = officials(seeRecords);
        List<Test> out = new ArrayList<>();
        if (recs.isEmpty()) {
            return out;
        }
        List<LocalDate> dates = new ArrayList<>(recs.keySet());
        Map<String, Map<Double, MarketExtras.Official>> last = recs.get(dates.get(dates.size() - 1));
        String asked = String.valueOf(request.param("node"));
        Set<String> nodes = asked.isBlank() ? new TreeSet<>(last.keySet()) : Set.of(asked);
        Map<LocalDate, List<MarketExtras.DeskPnl>> pnl = new TreeMap<>();
        for (MarketExtras.DeskPnl d : day.extras().deskPnl) {
            if (!d.date().isAfter(day.date) && InputRows.visible(seeDesks, d.row())) {
                pnl.computeIfAbsent(d.date(), x -> new ArrayList<>()).add(d);
            }
        }
        int window = options.regulatory().backtestWindow();
        for (String node : nodes) {
            Reach r = reach(last, node);
            if (r == null || !r.all() && r.desks().isEmpty()) {
                continue;
            }
            // the confidences the node's newest records carry: each row's own, and the backtest's other one a record keeps beside it
            Set<Double> qs = new TreeSet<>(last.get(node).keySet());
            for (MarketExtras.Official o : last.get(node).values()) {
                if (Double.isFinite(o.backtestConfidence()) && qs.stream().noneMatch(x -> Math.abs(x - o.backtestConfidence()) < 1e-9)) {
                    qs.add(o.backtestConfidence());
                }
            }
            for (double q : qs) {
                List<TestedDay> days = new ArrayList<>();
                for (int i = Math.max(1, dates.size() - (window + 30)); i < dates.size(); i++) {
                    LocalDate d = dates.get(i);
                    double priorVar = varAt(recs.get(dates.get(i - 1)), node, q);
                    if (!Double.isFinite(priorVar)) {
                        continue;
                    }
                    double[] sum = sum(pnl.get(d), r);
                    if (sum == null) {
                        continue;
                    }
                    MarketExtras.Official today = at(recs.get(d), node, q);
                    if (today == null && recs.get(d) != null && recs.get(d).get(node) != null) {
                        today = recs.get(d).get(node).values().iterator().next();     // the record's own hypothetical P&L at any confidence
                    }
                    days.add(new TestedDay(d, dates.get(i - 1), priorVar, sum[0], sum[1], today == null ? Double.NaN : today.hypo()));
                }
                while (days.size() > window) {
                    days.remove(0);
                }
                double[] p = new double[days.size() + 1];
                double[] v = new double[days.size() + 1];
                p[0] = Double.NaN;
                v[days.size()] = Double.NaN;
                int a = 0;
                int h = 0;
                int t = 0;
                for (int i = 0; i < days.size(); i++) {
                    TestedDay x = days.get(i);
                    v[i] = x.var();
                    p[i + 1] = x.actual();
                    a += x.actual() < -x.var() ? 1 : 0;
                    h += x.hypothetical() < -x.var() ? 1 : 0;
                    t += Double.isFinite(x.rtpl()) && x.rtpl() < -x.var() ? 1 : 0;
                }
                Backtest bt = Backtest.of(p, v, Double.isNaN(q) ? options.calc().confidence() : q);
                int counted = options.regulatory().counted(a, h);
                out.add(new Test(node, r.hierarchy(), q, List.copyOf(days), bt, a, h, t, counted, Backtest.light(counted, days.size()),
                        options.regulatory().multiplier(counted)));
            }
        }
        return out;
    }

    /** The desk P&amp;L a node takes on a day: {actual, hypothetical}; null when no row is the node's. */
    private static double[] sum(List<MarketExtras.DeskPnl> rows, Reach r) {
        if (rows == null) {
            return null;
        }
        double a = 0;
        double h = 0;
        int n = 0;
        for (MarketExtras.DeskPnl x : rows) {
            if ((r.all() || r.desks().contains(x.desk())) && (r.legalEntity() == null || x.legalEntity() == null || r.legalEntity().equals(x.legalEntity()))) {
                a += Double.isFinite(x.actual()) ? x.actual() : 0;
                h += Double.isFinite(x.hypothetical()) ? x.hypothetical() : 0;
                n++;
            }
        }
        return n == 0 ? null : new double[] {a, h};
    }

    // ------------------------------------------------------------------------------------------------------------- a node's official history

    /** A node's official records (oldest first, the newest {@code days} of them) and their backtest; {@code backtest} null under two. */
    record OfficialHistory(String node, List<MarketExtras.Official> records, Backtest backtest) {}

    /**
     * A node's official history to the session's date ({@code official-history}, {@code official-backtest}): each official date's record of
     * the node at the engine's confidence (a record without one counts as it), the newest {@code days}, read as stored; the backtest holds
     * each day's hypothetical P&amp;L against the record of the day before, as Drishti's history of a node with official records does.
     */
    OfficialHistory officialHistory(Request request, Filter see) throws EngineException {
        String node = String.valueOf(request.param("node"));
        if (node.isBlank() || node.equals("null")) {
            throw new EngineException("ENG-3001", Kind.REFUSED, "official-history needs node: a node id of the official records");
        }
        Object d = request.param("days");
        int days = d instanceof Number n ? n.intValue() : 250;
        if (days < 2) {
            throw new EngineException("ENG-3001", Kind.REFUSED, "days is 2 or more, not " + days);
        }
        double q = options.calc().confidence();
        List<MarketExtras.Official> list = new ArrayList<>();
        for (Map<String, Map<Double, MarketExtras.Official>> date : officials(see).values()) {
            MarketExtras.Official o = at(date, node, q);
            if (o != null) {
                list.add(o);
            }
        }
        while (list.size() > days) {
            list.remove(0);
        }
        if (list.size() < 2) {
            return new OfficialHistory(node, List.copyOf(list), null);
        }
        double[] pnl = new double[list.size()];
        double[] var = new double[list.size()];
        for (int i = 0; i < list.size(); i++) {
            pnl[i] = list.get(i).hypo();
            var[i] = list.get(i).var();
        }
        return new OfficialHistory(node, List.copyOf(list), Backtest.of(pnl, var, q));
    }

    Table officialDays(OfficialHistory h) {
        Tables.Builder t = Tables.of(descriptor.schema("official-history"));
        for (int i = 0; i < h.records().size(); i++) {
            MarketExtras.Official o = h.records().get(i);
            t.row(h.node(), o.date().toString(), o.var(), Double.isFinite(o.es()) ? o.es() : null, Double.isFinite(o.hypo()) ? o.hypo() : null,
                    h.backtest() != null && h.backtest().exceptionFlags()[i]);
        }
        return t.build();
    }

    Table officialBacktest(OfficialHistory h) {
        Tables.Builder t = Tables.of(descriptor.schema("official-backtest"));
        Backtest b = h.backtest();
        if (b != null) {
            t.row(h.node(), (long) h.records().size(), (long) b.days(), (long) b.exceptions(), b.light(), b.kupiecLr(), b.kupiecPValue(),
                    b.christoffersenLr(), b.christoffersenPValue());
        }
        return t.build();
    }

    // ------------------------------------------------------------------------------------------------------------- statistics as shown

    /**
     * A node's period statistics with what a host shows beside them ({@code official-statistics}): read from the official records to the
     * session's date, never recomputed; the averages summed in the order a host sums them (the VaR average as a compensated mean of the
     * newest records first, the stressed average oldest first), so the figures are its bits.
     */
    Table officialStatistics(Request request, Filter seeRecords, Filter seeDesks) throws EngineException {
        Map<LocalDate, Map<String, Map<Double, MarketExtras.Official>>> recs = officials(seeRecords);
        Periods.Period p = period(request);
        String measure = String.valueOf(request.param("measure"));
        String node = String.valueOf(request.param("node"));
        if (node.isBlank() || node.equals("null")) {
            throw new EngineException("ENG-3001", Kind.REFUSED, "official-statistics needs node: a node id of the official records");
        }
        double q = options.calc().confidence();
        LocalDate oldest = recs.isEmpty() ? null : ((TreeMap<LocalDate, ?>) recs).firstKey();
        LocalDate newest = recs.isEmpty() ? null : ((TreeMap<LocalDate, ?>) recs).lastKey();
        List<MarketExtras.Official> pts = new ArrayList<>();
        for (Map.Entry<LocalDate, Map<String, Map<Double, MarketExtras.Official>>> e : recs.entrySet()) {
            if (e.getKey().isBefore(p.from()) || e.getKey().isAfter(p.to())) {
                continue;
            }
            MarketExtras.Official o = at(e.getValue(), node, q);
            if (o != null && Double.isFinite(measure(o, measure))) {
                pts.add(o);
            }
        }
        int n = options.regulatory().averageDays();
        int weeks = options.regulatory().stressWeeks();
        Tables.Builder t = Tables.of(descriptor.schema("official-statistics"));
        if (pts.isEmpty()) {
            t.row(node, measure, p.name(), p.from().toString(), p.to().toString(), day(oldest), day(newest), 0L, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, (long) n, null, null, null, (long) weeks, null, null, null, null, null);
            return t.build();
        }
        MarketExtras.Official hi = pts.get(0);
        MarketExtras.Official lo = pts.get(0);
        double sum = 0;
        for (MarketExtras.Official x : pts) {
            double v = measure(x, measure);
            sum += v;
            hi = v > measure(hi, measure) ? x : hi;
            lo = v < measure(lo, measure) ? x : lo;
        }
        MarketExtras.Official end = pts.get(pts.size() - 1);
        // the weekdays without a record: from the first record (or the period's start) to the newest record (or the period's end)
        Set<LocalDate> have = new java.util.HashSet<>();
        pts.forEach(x -> have.add(x.date()));
        Set<LocalDate> deskDays = new java.util.HashSet<>();
        boolean desks = day.extras().present.contains(MarketExtras.DESK_PNL);
        for (MarketExtras.DeskPnl d : day.extras().deskPnl) {
            if (InputRows.visible(seeDesks, d.row())) {
                deskDays.add(d.date());
            }
        }
        LocalDate last = newest == null || newest.isAfter(p.to()) ? p.to() : newest;
        LocalDate first = oldest == null ? p.from() : oldest.isAfter(p.from()) ? oldest : p.from();
        List<String> missing = new ArrayList<>();
        for (LocalDate d = first; !d.isAfter(last); d = d.plusDays(1)) {
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY && !have.contains(d) && (!desks || deskDays.contains(d))) {
                missing.add(d.toString());
            }
        }
        Object[] lastN = {null, null, null};
        Object[] stressed = {null, null, null, null, null};
        if ("var".equals(measure)) {
            List<Double> vals = new ArrayList<>();
            LocalDate from = null;
            for (LocalDate d : ((TreeMap<LocalDate, Map<String, Map<Double, MarketExtras.Official>>>) recs).headMap(end.date(), true).descendingKeySet()) {
                if (vals.size() >= n) {
                    break;
                }
                MarketExtras.Official o = at(recs.get(d), node, q);
                if (o != null && Double.isFinite(o.var())) {
                    vals.add(o.var());
                    from = d;
                }
            }
            lastN = new Object[] {(long) vals.size(), day(from), vals.isEmpty() ? null : vals.stream().mapToDouble(Double::doubleValue).average()
                    .orElse(Double.NaN)};
            LocalDate sFrom = end.date().minusWeeks(weeks).plusDays(1);
            List<String> used = new ArrayList<>();
            List<String> without = new ArrayList<>();
            double ssum = 0;
            for (Map.Entry<LocalDate, Map<String, Map<Double, MarketExtras.Official>>> e : recs.entrySet()) {
                if (e.getKey().isBefore(sFrom) || e.getKey().isAfter(end.date())) {
                    continue;
                }
                MarketExtras.Official o = at(e.getValue(), node, q);
                if (o != null) {
                    if (Double.isFinite(o.svar())) {
                        used.add(e.getKey().toString());
                        ssum += o.svar();
                    } else {
                        without.add(e.getKey().toString());
                    }
                }
            }
            stressed = new Object[] {sFrom.toString(), (long) used.size(), used.isEmpty() ? null : ssum / used.size(), String.join(",", used),
                    String.join(",", without)};
        }
        t.row(node, measure, p.name(), p.from().toString(), p.to().toString(), day(oldest), day(newest), (long) pts.size(), end.date().toString(),
                measure(end, measure), nn(end.version()), end.capture(), sum / pts.size(), hi.date().toString(), measure(hi, measure),
                nn(hi.version()), hi.capture(), lo.date().toString(), measure(lo, measure), nn(lo.version()), lo.capture(),
                String.join(",", missing), (long) n, lastN[0], lastN[1], lastN[2], (long) weeks, stressed[0], stressed[1], stressed[2],
                stressed[3], stressed[4]);
        return t.build();
    }

    private static String day(LocalDate d) {
        return d == null ? null : d.toString();
    }

    Table backtest(List<Test> tests) {
        Tables.Builder t = Tables.of(descriptor.schema("backtest"));
        for (Test x : tests) {
            Backtest b = x.backtest();
            t.row(x.node(), x.hierarchy(), x.confidence(), (long) x.days().size(), (long) x.actual(), (long) x.hypothetical(), (long) x.rtpl(),
                    (long) x.counted(), x.zone(), x.multiplier(), b.kupiecLr(), b.kupiecPValue(), b.christoffersenLr(), b.christoffersenPValue());
        }
        return t.build();
    }

    Table backtestDays(List<Test> tests) {
        Tables.Builder t = Tables.of(descriptor.schema("backtest-days"));
        for (Test x : tests) {
            for (TestedDay d : x.days()) {
                t.row(x.node(), x.confidence(), d.date().toString(), d.prior().toString(), d.var(), d.actual(), d.hypothetical(),
                        Double.isFinite(d.rtpl()) ? d.rtpl() : null, d.exception());
            }
        }
        return t.build();
    }

    Explanation explainBacktest(List<Test> tests, Cell cell) throws EngineException {
        if (!"MULTIPLIER".equals(cell.column())) {
            throw new EngineException("ENG-3005", Kind.REFUSED, cell.column() + " is not a measure of backtest");
        }
        for (Test x : tests) {
            if (x.node().equals(cell.key().get("NODE_ID")) && matches(x.confidence(), cell.key().get("CONFIDENCE"))) {
                String count = options.regulatory().count();
                List<Map<String, Object>> rows = new ArrayList<>();
                for (TestedDay d : x.days()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("DATE", d.date().toString());
                    m.put("VAR", d.var());
                    m.put("ACTUAL", d.actual());
                    m.put("HYPOTHETICAL", d.hypothetical());
                    m.put("ACTUAL_EXCEPTION", d.actual() < -d.var() ? 1L : 0L);
                    m.put("HYPOTHETICAL_EXCEPTION", d.hypothetical() < -d.var() ? 1L : 0L);
                    rows.add(m);
                }
                List<Step> steps = new ArrayList<>();
                steps.add(Steps.of("actual", "Days whose actual P&L is below minus the official VaR of the day before", "sum($.days, 'ACTUAL_EXCEPTION')",
                        Map.of("days", rows), (long) x.actual()));
                steps.add(Steps.of("hypothetical", "Days whose hypothetical P&L is below minus that VaR", "sum($.days, 'HYPOTHETICAL_EXCEPTION')",
                        Map.of("days", rows), (long) x.hypothetical()));
                String f = switch (count) {
                    case "hypothetical" -> "step.hypothetical";
                    case "higher" -> "max(step.actual, step.hypothetical)";
                    default -> "step.actual";
                };
                steps.add(Steps.of("counted", "The exceptions the zone and the multiplier read (" + count + ")", f, Map.of(), (long) x.counted()));
                steps.add(Steps.opaque("multiplier", "The capital multiplier of " + x.counted() + " exceptions",
                        "read from the multiplier table " + options.regulatory().multipliers() + " (the last for that many or more); recomputed in the workbook",
                        "Main!" + x.node(), x.multiplier()));
                return new Explanation(cell, x.multiplier(), steps);
            }
        }
        throw new EngineException("ENG-3005", Kind.REFUSED, "the cell is not in this request's answer");
    }

    private static boolean matches(double q, String text) {
        try {
            return text != null && Math.abs(Double.parseDouble(text) - q) < 1e-12;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    AnswerBook.Part backtestPart(List<Test> tests) {
        return new AnswerBook.Part() {
            private final List<int[]> blocks = new ArrayList<>();

            @Override
            public int data(Sheet data, int at) {
                data.row(at).text(0, "NODE_ID", "header").text(1, "CONFIDENCE", "header").text(2, "DATE", "header").text(3, "VAR", "header")
                        .text(4, "ACTUAL", "header").text(5, "HYPOTHETICAL", "header");
                int r = at;
                for (Test x : tests) {
                    int from = r + 1;
                    for (TestedDay d : x.days()) {
                        r++;
                        data.row(r).text(0, x.node(), "normal").number(1, x.confidence(), "decimal").text(2, d.date().toString(), "normal")
                                .number(3, d.var(), "decimal").number(4, d.actual(), "decimal").number(5, d.hypothetical(), "decimal");
                    }
                    blocks.add(new int[] {from, r});
                }
                return r;
            }

            @Override
            public int main(Workbook wb, Sheet main, int at) {
                StringBuilder table = new StringBuilder("{");
                List<Double> m = options.regulatory().multipliers();
                for (int i = 0; i < m.size(); i++) {
                    table.append(i == 0 ? "" : ",").append(m.get(i));
                }
                table.append("}");
                main.row(at).text(0, "NODE_ID", "header").text(1, "CONFIDENCE", "header").text(2, "EXCEPTIONS_ACTUAL", "header")
                        .text(3, "EXCEPTIONS_HYPOTHETICAL", "header").text(4, "COUNTED", "header").text(5, "MULTIPLIER", "header");
                int row = at;
                for (int i = 0; i < tests.size(); i++) {
                    Test x = tests.get(i);
                    int[] b = blocks.get(i);
                    row++;
                    String act;
                    String hyp;
                    if (b[1] < b[0]) {
                        act = "0";
                        hyp = "0";
                    } else {
                        String v = AnswerBook.range(3, b[0], b[1]);
                        act = "SUMPRODUCT(--(" + AnswerBook.range(4, b[0], b[1]) + "<-" + v + "))";
                        hyp = "SUMPRODUCT(--(" + AnswerBook.range(5, b[0], b[1]) + "<-" + v + "))";
                    }
                    String counted = switch (options.regulatory().count()) {
                        case "hypothetical" -> "D" + row;
                        case "higher" -> "MAX(C" + row + ",D" + row + ")";
                        default -> "C" + row;
                    };
                    main.row(row).text(0, x.node(), "normal").number(1, x.confidence(), "decimal").formula(2, act, (double) x.actual(), "int")
                            .formula(3, hyp, (double) x.hypothetical(), "int").formula(4, counted, (double) x.counted(), "int")
                            .formula(5, "INDEX(" + table + ",MIN(E" + row + "," + (m.size() - 1) + ")+1)", x.multiplier(), "decimal");
                    if (i == 0) {
                        wb.name(AnswerBook.name("backtest", "MULTIPLIER"), main.name(), "$F$" + row);
                    }
                }
                return row;
            }
        };
    }

    // ------------------------------------------------------------------------------------------------------------- period statistics

    /** One node's statistics of a measure over a period. */
    record Stats(String node, String hierarchy, Periods.Period period, List<LocalDate> dates, List<Double> values, double end, double average,
            double high, LocalDate highDate, double low, LocalDate lowDate, double averageN, List<Double> lastN, double stressedAverage,
            List<Double> stressed, int missingDays) {}

    Periods.Period period(Request request) throws EngineException {
        String text = String.valueOf(request.param("period"));
        try {
            return Periods.period(text.isBlank() ? Periods.fiscalYear(day.date, options.regulatory().yearEnd()) : text, options.regulatory().yearEnd());
        } catch (IllegalArgumentException e) {
            throw new EngineException("RSK-3130", Kind.REFUSED, e.getMessage());
        }
    }

    private static double measure(MarketExtras.Official o, String measure) {
        return switch (measure) {
            case "es" -> o.es();
            case "svar" -> o.svar();
            case "ses" -> o.ses();
            default -> o.var();
        };
    }

    Stats stats(Map<LocalDate, Map<String, Map<Double, MarketExtras.Official>>> recs, String node, String measure, Periods.Period p) {
        List<LocalDate> dates = new ArrayList<>();
        List<Double> values = new ArrayList<>();
        String hierarchy = null;
        double q = options.calc().confidence();
        for (Map.Entry<LocalDate, Map<String, Map<Double, MarketExtras.Official>>> e : recs.entrySet()) {
            if (e.getKey().isBefore(p.from()) || e.getKey().isAfter(p.to())) {
                continue;
            }
            MarketExtras.Official o = at(e.getValue(), node, q);
            if (o != null && Double.isFinite(measure(o, measure))) {
                dates.add(e.getKey());
                values.add(measure(o, measure));
                hierarchy = o.hierarchy();
            }
        }
        if (dates.isEmpty()) {
            return new Stats(node, hierarchy, p, dates, values, Double.NaN, Double.NaN, Double.NaN, null, Double.NaN, null, Double.NaN, List.of(),
                    Double.NaN, List.of(), missing(Set.of(), p));
        }
        int hi = 0;
        int lo = 0;
        double sum = 0;
        for (int i = 0; i < values.size(); i++) {
            sum += values.get(i);
            hi = values.get(i) > values.get(hi) ? i : hi;
            lo = values.get(i) < values.get(lo) ? i : lo;
        }
        LocalDate end = dates.get(dates.size() - 1);
        List<Double> lastN = new ArrayList<>();
        List<Double> stressed = new ArrayList<>();
        if ("var".equals(measure)) {
            List<LocalDate> all = new ArrayList<>(recs.keySet());
            LocalDate stressFrom = end.minusWeeks(options.regulatory().stressWeeks());
            for (int i = all.size() - 1; i >= 0; i--) {
                LocalDate d = all.get(i);
                boolean wantN = lastN.size() < options.regulatory().averageDays();
                if (!wantN && !d.isAfter(stressFrom)) {
                    break;
                }
                if (d.isAfter(end)) {
                    continue;
                }
                MarketExtras.Official o = at(recs.get(d), node, q);
                if (o == null) {
                    continue;
                }
                if (wantN && Double.isFinite(o.var())) {
                    lastN.add(0, o.var());
                }
                if (Double.isFinite(o.svar()) && d.isAfter(stressFrom)) {
                    stressed.add(0, o.svar());
                }
            }
        }
        return new Stats(node, hierarchy, p, List.copyOf(dates), List.copyOf(values), values.get(values.size() - 1), sum / values.size(),
                values.get(hi), dates.get(hi), values.get(lo), dates.get(lo), mean(lastN), List.copyOf(lastN), mean(stressed), List.copyOf(stressed),
                missing(new TreeSet<>(dates), p));
    }

    private static double mean(List<Double> v) {
        if (v.isEmpty()) {
            return Double.NaN;
        }
        double s = 0;
        for (double x : v) {
            s += x;
        }
        return s / v.size();
    }

    /**
     * Weekdays of the period, to the session's date, without a record. Every weekday counts (a holiday calendar is the host's to apply): the
     * figure depends on the official records alone, so removing an input the output does not need never moves it.
     */
    private int missing(Set<LocalDate> have, Periods.Period p) {
        int n = 0;
        LocalDate to = p.to().isAfter(day.date) ? day.date : p.to();
        for (LocalDate d = p.from(); !d.isAfter(to); d = d.plusDays(1)) {
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY && !have.contains(d)) {
                n++;
            }
        }
        return n;
    }

    List<Stats> periodStats(Request request, Filter see) throws EngineException {
        Map<LocalDate, Map<String, Map<Double, MarketExtras.Official>>> recs = officials(see);
        Periods.Period p = period(request);
        String measure = String.valueOf(request.param("measure"));
        String asked = String.valueOf(request.param("node"));
        Set<String> nodes = new TreeSet<>();
        if (!asked.isBlank()) {
            nodes.add(asked);
        } else {
            recs.values().forEach(m -> nodes.addAll(m.keySet()));
        }
        List<Stats> out = new ArrayList<>();
        for (String n : nodes) {
            out.add(stats(recs, n, measure, p));
        }
        return out;
    }

    Table periodTable(List<Stats> stats) {
        Tables.Builder t = Tables.of(descriptor.schema("period-stats"));
        for (Stats s : stats) {
            t.row(s.node(), s.hierarchy(), s.period().name(), s.period().from().toString(), s.period().to().toString(), (long) s.values().size(),
                    nn(s.end()), nn(s.average()), nn(s.high()), s.highDate() == null ? null : s.highDate().toString(), nn(s.low()),
                    s.lowDate() == null ? null : s.lowDate().toString(), nn(s.averageN()), nn(s.stressedAverage()), (long) s.missingDays());
        }
        return t.build();
    }

    static Double nn(double d) {
        return Double.isFinite(d) ? d : null;
    }

    private static List<Map<String, Object>> points(List<LocalDate> dates, List<Double> values) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ORDER", (long) i);
            if (dates != null) {
                m.put("DATE", dates.get(i).toString());
            }
            m.put("VALUE", values.get(i));
            rows.add(m);
        }
        return rows;
    }

    /** The steps of one statistic of a node (the official values it reads are given; the formula takes them to the figure). */
    List<Step> statSteps(Stats s, String column) throws EngineException {
        List<Map<String, Object>> pts = points(s.dates(), s.values());
        int n = s.values().size();
        return switch (column) {
            case "PERIOD_END" -> List.of(Steps.of("end", "The official value on the period's last official date, " + s.dates().get(n - 1),
                    "sum($.rows, 'VALUE')", Map.of("rows", List.of(pts.get(n - 1))), s.end()));
            case "AVERAGE" -> List.of(Steps.of("average", "The mean of the " + n + " official values of the period", "sum($.rows, 'VALUE') / $.n",
                    Map.of("rows", pts, "n", (long) n), s.average()));
            case "HIGH" -> List.of(Steps.of("high", "The largest official value of the period", "max($.rows[*].VALUE)", Map.of("rows", pts), s.high()));
            case "LOW" -> List.of(Steps.of("low", "The smallest official value of the period", "min($.rows[*].VALUE)", Map.of("rows", pts), s.low()));
            case "AVERAGE_N" -> List.of(Steps.of("average", "The mean of the official VaR of the last " + s.lastN().size() + " official dates to the period end",
                    "sum($.rows, 'VALUE') / $.n", Map.of("rows", points(null, s.lastN()), "n", (long) s.lastN().size()), s.averageN()));
            case "STRESSED_AVERAGE" -> List.of(Steps.of("average", "The mean of the official stressed VaR of the last "
                    + options.regulatory().stressWeeks() + " weeks to the period end", "sum($.rows, 'VALUE') / $.n",
                    Map.of("rows", points(null, s.stressed()), "n", (long) s.stressed().size()), s.stressedAverage()));
            default -> throw new EngineException("ENG-3005", Kind.REFUSED, column + " is not a measure of period-stats");
        };
    }

    Explanation explainStats(List<Stats> stats, Cell cell) throws EngineException {
        for (Stats s : stats) {
            if (s.node().equals(cell.key().get("NODE_ID"))) {
                List<Step> steps = statSteps(s, cell.column());
                if (steps.isEmpty() || !(steps.get(steps.size() - 1).value() instanceof Double v) || !Double.isFinite(v)) {
                    throw new EngineException("ENG-3005", Kind.REFUSED, "the cell is null: no official value of " + s.node() + " in " + s.period().name());
                }
                return new Explanation(cell, v, steps);
            }
        }
        throw new EngineException("ENG-3005", Kind.REFUSED, "the node " + cell.key().get("NODE_ID") + " is not in this request's answer");
    }

    /** The workbook part of the statistics: each node's official values as values; the statistics as AVERAGE, MAX, MIN formulas. */
    AnswerBook.Part statsPart(List<Stats> stats) {
        return new AnswerBook.Part() {
            private final List<int[]> blocks = new ArrayList<>();

            @Override
            public int data(Sheet data, int at) {
                data.row(at).text(0, "NODE_ID", "header").text(1, "DATE", "header").text(2, "VALUE", "header").text(3, "LAST_N", "header")
                        .text(4, "STRESSED", "header");
                int r = at;
                for (Stats s : stats) {
                    int from = r + 1;
                    int rows = Math.max(s.values().size(), Math.max(s.lastN().size(), s.stressed().size()));
                    for (int i = 0; i < rows; i++) {
                        r++;
                        Sheet x = data.row(r).text(0, s.node(), "normal");
                        if (i < s.values().size()) {
                            x.text(1, s.dates().get(i).toString(), "normal").number(2, s.values().get(i), "decimal");
                        }
                        if (i < s.lastN().size()) {
                            x.number(3, s.lastN().get(i), "decimal");
                        }
                        if (i < s.stressed().size()) {
                            x.number(4, s.stressed().get(i), "decimal");
                        }
                    }
                    blocks.add(new int[] {from, from + s.values().size() - 1, from + s.lastN().size() - 1, from + s.stressed().size() - 1});
                }
                return r;
            }

            @Override
            public int main(Workbook wb, Sheet main, int at) {
                main.row(at).text(0, "NODE_ID", "header").text(1, "PERIOD_END", "header").text(2, "AVERAGE", "header").text(3, "HIGH", "header")
                        .text(4, "LOW", "header").text(5, "AVERAGE_N", "header").text(6, "STRESSED_AVERAGE", "header");
                int row = at;
                boolean named = false;
                for (int i = 0; i < stats.size(); i++) {
                    Stats s = stats.get(i);
                    int[] b = blocks.get(i);
                    row++;
                    Sheet x = main.row(row).text(0, s.node(), "normal");
                    if (s.values().isEmpty()) {
                        continue;
                    }
                    String v = AnswerBook.range(2, b[0], b[1]);
                    x.formula(1, AnswerBook.cell(2, b[1]), s.end(), "decimal").formula(2, "AVERAGE(" + v + ")", s.average(), "decimal")
                            .formula(3, "MAX(" + v + ")", s.high(), "decimal").formula(4, "MIN(" + v + ")", s.low(), "decimal");
                    if (!s.lastN().isEmpty()) {
                        x.formula(5, "AVERAGE(" + AnswerBook.range(3, b[0], b[2]) + ")", s.averageN(), "decimal");
                    }
                    if (!s.stressed().isEmpty()) {
                        x.formula(6, "AVERAGE(" + AnswerBook.range(4, b[0], b[3]) + ")", s.stressedAverage(), "decimal");
                    }
                    if (!named) {
                        named = true;
                        for (String[] c : new String[][] {{"PERIOD_END", "B"}, {"AVERAGE", "C"}, {"HIGH", "D"}, {"LOW", "E"}, {"AVERAGE_N", "F"},
                            {"STRESSED_AVERAGE", "G"}}) {
                            wb.name(AnswerBook.name("period-stats", c[0]), main.name(), "$" + c[1] + "$" + row);
                        }
                    }
                }
                return row;
            }
        };
    }

    // ------------------------------------------------------------------------------------------------------------- regulatory tables

    /** One row of a report: its definition and its figures (null where the row has none). */
    record ReportRow(String report, long order, String label, String code, String node, String compute, boolean total, Stats now, Stats prior,
            double[] values) {}

    static final List<String> REPORT_COLUMNS = List.of("PERIOD_END", "AVERAGE", "HIGH", "LOW", "PRIOR_PERIOD_END", "MOVEMENT");

    List<ReportRow> reports(Request request, Filter see) throws EngineException {
        Map<LocalDate, Map<String, Map<Double, MarketExtras.Official>>> recs = officials(see);
        Periods.Period p = period(request);
        Periods.Period prior = Periods.priorYear(p, options.regulatory().yearEnd());
        String measure = String.valueOf(request.param("measure"));
        String asked = String.valueOf(request.param("report"));
        Map<String, List<Map<String, Object>>> byReport = new TreeMap<>();
        for (Map<String, Object> r : day.extras().reportRows) {
            String id = InputRows.text(r, "REPORT_ID");
            if (id != null && (asked.isBlank() || asked.equals(id))) {
                byReport.computeIfAbsent(id, x -> new ArrayList<>()).add(r);
            }
        }
        List<ReportRow> out = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> e : byReport.entrySet()) {
            List<ReportRow> rows = new ArrayList<>();
            ReportRow total = null;
            for (Map<String, Object> r : e.getValue()) {
                String node = InputRows.text(r, "NODE_ID");
                Stats now = node == null ? null : stats(recs, node, measure, p);
                Stats was = node == null ? null : stats(recs, node, measure, prior);
                double[] v = new double[6];
                java.util.Arrays.fill(v, Double.NaN);
                if (now != null) {
                    v[0] = now.end();
                    v[1] = now.average();
                    v[2] = now.high();
                    v[3] = now.low();
                    v[4] = was.end();
                    v[5] = Double.isFinite(v[0]) && Double.isFinite(v[4]) ? v[0] - v[4] : Double.NaN;
                }
                ReportRow rr = new ReportRow(e.getKey(), (long) InputRows.number(r, "ROW_ORDER"), InputRows.text(r, "LABEL"), InputRows.text(r, "CODE"),
                        node, InputRows.text(r, "COMPUTE"), InputRows.flag(r, "TOTAL"), now, was, v);
                rows.add(rr);
                if (rr.total()) {
                    total = rr;
                }
            }
            for (int i = 0; i < rows.size(); i++) {                       // the diversification: the total less the sum of the node rows
                ReportRow rr = rows.get(i);
                if (!"diversification".equalsIgnoreCase(rr.compute()) || total == null) {
                    continue;
                }
                double[] v = new double[6];
                for (int c = 0; c < 6; c++) {
                    double s = 0;
                    for (ReportRow o : rows) {
                        if (o != total && o.node() != null && o.compute() == null) {
                            s += o.values()[c];
                        }
                    }
                    v[c] = c == 2 || c == 3 ? Double.NaN : total.values()[c] - s;  // a high or a low does not add up
                }
                rows.set(i, new ReportRow(rr.report(), rr.order(), rr.label(), rr.code(), rr.node(), rr.compute(), rr.total(), null, null, v));
            }
            out.addAll(rows);
        }
        return out;
    }

    Table reportTable(List<ReportRow> rows) {
        Tables.Builder t = Tables.of(descriptor.schema("regulatory-report"));
        for (ReportRow r : rows) {
            double[] v = r.values();
            t.row(r.report(), r.order(), r.label(), r.code(), r.node(), nn(v[0]), nn(v[1]), nn(v[2]), nn(v[3]), nn(v[4]), nn(v[5]));
        }
        return t.build();
    }

    Explanation explainReport(List<ReportRow> rows, Cell cell) throws EngineException {
        for (ReportRow r : rows) {
            if (!r.report().equals(cell.key().get("REPORT_ID")) || !String.valueOf(r.order()).equals(cell.key().get("ROW_ORDER"))) {
                continue;
            }
            int c = REPORT_COLUMNS.indexOf(cell.column());
            if (c < 0 || !Double.isFinite(r.values()[c])) {
                throw new EngineException("ENG-3005", Kind.REFUSED, "the cell " + cell.column() + " of row " + r.order() + " is not a figure");
            }
            double value = r.values()[c];
            if (r.now() == null) {
                List<Double> parts = new ArrayList<>();
                ReportRow total = null;
                for (ReportRow o : rows) {
                    if (o.report().equals(r.report()) && o.total()) {
                        total = o;
                    } else if (o.report().equals(r.report()) && o.node() != null && o.compute() == null) {
                        parts.add(o.values()[c]);
                    }
                }
                return new Explanation(cell, value, List.of(Steps.of("diversification", "The total less the sum of the node rows",
                        "$.total - sum($.rows, 'VALUE')", Map.of("total", total == null ? Double.NaN : total.values()[c], "rows", points(null, parts)), value)));
            }
            switch (cell.column()) {
                case "PRIOR_PERIOD_END" -> {
                    return new Explanation(cell, value, statSteps(r.prior(), "PERIOD_END"));
                }
                case "MOVEMENT" -> {
                    return new Explanation(cell, value, List.of(Steps.of("movement", "The period end less the prior year's", "$.now - $.prior",
                            Map.of("now", r.values()[0], "prior", r.values()[4]), value)));
                }
                default -> {
                    return new Explanation(cell, value, statSteps(r.now(), cell.column()));
                }
            }
        }
        throw new EngineException("ENG-3005", Kind.REFUSED, "the row is not in this request's answer");
    }

    /** The workbook part of the reports: each node row's official values (this period's and the prior year's) as values; the cells as formulas. */
    AnswerBook.Part reportPart(List<ReportRow> rows) {
        return new AnswerBook.Part() {
            private final Map<Integer, int[]> blocks = new HashMap<>();

            @Override
            public int data(Sheet data, int at) {
                data.row(at).text(0, "REPORT_ID", "header").text(1, "ROW_ORDER", "header").text(2, "VALUE", "header").text(3, "PRIOR", "header");
                int r = at;
                for (int i = 0; i < rows.size(); i++) {
                    ReportRow x = rows.get(i);
                    if (x.now() == null) {
                        continue;
                    }
                    int from = r + 1;
                    int n = Math.max(1, Math.max(x.now().values().size(), x.prior().values().size()));
                    for (int j = 0; j < n; j++) {
                        r++;
                        Sheet s = data.row(r).text(0, x.report(), "normal").number(1, x.order(), "int");
                        if (j < x.now().values().size()) {
                            s.number(2, x.now().values().get(j), "decimal");
                        }
                        if (j < x.prior().values().size()) {
                            s.number(3, x.prior().values().get(j), "decimal");
                        }
                    }
                    blocks.put(i, new int[] {from, from + x.now().values().size() - 1, from + x.prior().values().size() - 1});
                }
                return r;
            }

            @Override
            public int main(Workbook wb, Sheet main, int at) {
                Sheet h = main.row(at).text(0, "REPORT_ID", "header").text(1, "ROW_ORDER", "header");
                for (int c = 0; c < REPORT_COLUMNS.size(); c++) {
                    h.text(2 + c, REPORT_COLUMNS.get(c), "header");
                }
                int row = at;
                Map<String, Integer> totalRow = new HashMap<>();
                Map<String, List<Integer>> nodeRows = new HashMap<>();
                for (ReportRow x : rows) {
                    row++;
                    if (x.total()) {
                        totalRow.put(x.report(), row);
                    } else if (x.node() != null && x.compute() == null) {
                        nodeRows.computeIfAbsent(x.report(), k -> new ArrayList<>()).add(row);
                    }
                }
                row = at;
                boolean named = false;
                for (int i = 0; i < rows.size(); i++) {
                    ReportRow x = rows.get(i);
                    row++;
                    Sheet s = main.row(row).text(0, x.report(), "normal").number(1, x.order(), "int");
                    double[] v = x.values();
                    if (x.now() != null) {
                        int[] b = blocks.get(i);
                        if (b[1] >= b[0]) {
                            String now = AnswerBook.range(2, b[0], b[1]);
                            s.formula(2, AnswerBook.cell(2, b[1]), v[0], "decimal").formula(3, "AVERAGE(" + now + ")", v[1], "decimal")
                                    .formula(4, "MAX(" + now + ")", v[2], "decimal").formula(5, "MIN(" + now + ")", v[3], "decimal");
                        }
                        if (b[2] >= b[0] && Double.isFinite(v[4])) {
                            s.formula(6, AnswerBook.cell(3, b[2]), v[4], "decimal");
                        }
                        if (Double.isFinite(v[5])) {
                            s.formula(7, "C" + row + "-G" + row, v[5], "decimal");
                        }
                    } else if (totalRow.containsKey(x.report())) {
                        for (int c : new int[] {0, 1, 4, 5}) {
                            if (!Double.isFinite(v[c])) {
                                continue;
                            }
                            String col = AnswerBook.letters(2 + c);
                            StringBuilder f = new StringBuilder(col + totalRow.get(x.report()));
                            for (int nr : nodeRows.getOrDefault(x.report(), List.of())) {
                                f.append("-").append(col).append(nr);
                            }
                            s.formula(2 + c, f.toString(), v[c], "decimal");
                        }
                    }
                    if (!named && x.now() != null) {
                        named = true;
                        for (int c = 0; c < REPORT_COLUMNS.size(); c++) {
                            wb.name(AnswerBook.name("regulatory-report", REPORT_COLUMNS.get(c)), main.name(), "$" + AnswerBook.letters(2 + c) + "$" + row);
                        }
                    }
                }
                return row;
            }
        };
    }

    // ------------------------------------------------------------------------------------------------------------- stress windows

    /** One candidate window of a node's history. */
    record Window(MarketExtras.History history, int rank, int start, int worst, double svar, double current) {}

    List<Window> windows(Request request, Filter see) {
        String asked = String.valueOf(request.param("node"));
        int len = options.regulatory().stressLength();
        VarCalc c = options.calc();
        List<Window> out = new ArrayList<>();
        for (MarketExtras.History h : day.extras().histories) {
            if (!asked.isBlank() && !asked.equals(h.node()) || !InputRows.visible(see, h.row())) {
                continue;
            }
            double[] rolling = StressWindows.rolling(h.pnl(), len, c.confidence(), c.rule());
            if (rolling.length == 0) {
                continue;                                                  // a history shorter than one window has none
            }
            double current = rolling[rolling.length - 1];
            int[] picks = StressWindows.candidates(rolling, len, options.regulatory().stressTop());
            for (int i = 0; i < picks.length; i++) {
                out.add(new Window(h, i + 1, picks[i], StressWindows.worstDay(h.pnl(), picks[i], len), rolling[picks[i]], current));
            }
        }
        return out;
    }

    Table windowTable(List<Window> windows) {
        int len = options.regulatory().stressLength();
        Tables.Builder t = Tables.of(descriptor.schema("stress-window"));
        for (Window w : windows) {
            String[] d = w.history().dates();
            Double excess = w.current() == 0 ? null : (w.svar() - w.current()) / Math.abs(w.current());
            t.row(w.history().node(), (long) w.rank(), d[w.start()], d[w.start() + len - 1], d[w.worst()], w.svar(), w.current(), excess,
                    w.history().synthetic());
        }
        return t.build();
    }

    Explanation explainWindow(List<Window> windows, Cell cell) throws EngineException {
        int len = options.regulatory().stressLength();
        for (Window w : windows) {
            if (!w.history().node().equals(cell.key().get("NODE_ID")) || !String.valueOf(w.rank()).equals(cell.key().get("RANK"))) {
                continue;
            }
            int start = switch (cell.column()) {
                case "SVAR" -> w.start();
                case "CURRENT_SVAR" -> w.history().pnl().length - len;
                default -> throw new EngineException("ENG-3005", Kind.REFUSED, cell.column() + " is not a measure of stress-window");
            };
            double[] v = java.util.Arrays.copyOfRange(w.history().pnl(), start, start + len);
            List<LocalDate> dates = new ArrayList<>();
            for (int j = start; j < start + len; j++) {
                dates.add(LocalDate.parse(w.history().dates()[j].substring(0, 10)));
            }
            Explanation e = MarketExplain.ofVector(cell, v, dates, options.calc(), "VAR");
            return new Explanation(cell, e.value(), e.steps());
        }
        throw new EngineException("ENG-3005", Kind.REFUSED, "the window is not in this request's answer");
    }

    AnswerBook.Part windowPart(List<Window> windows) {
        int len = options.regulatory().stressLength();
        return new AnswerBook.Part() {
            private final Map<String, int[]> blocks = new LinkedHashMap<>();

            @Override
            public int data(Sheet data, int at) {
                data.row(at).text(0, "NODE_ID", "header").text(1, "DATE", "header").text(2, "PNL", "header");
                int r = at;
                for (Window w : windows) {
                    if (blocks.containsKey(w.history().id())) {
                        continue;
                    }
                    int from = r + 1;
                    for (int j = 0; j < w.history().pnl().length; j++) {
                        r++;
                        data.row(r).text(0, w.history().node(), "normal").text(1, w.history().dates()[j], "normal")
                                .number(2, w.history().pnl()[j], "decimal");
                    }
                    blocks.put(w.history().id(), new int[] {from, r});
                }
                return r;
            }

            @Override
            public int main(Workbook wb, Sheet main, int at) {
                int k = VarMath.rank(len, options.calc().confidence());
                main.row(at).text(0, "NODE_ID", "header").text(1, "RANK", "header").text(2, "SVAR", "header").text(3, "CURRENT_SVAR", "header");
                int row = at;
                for (Window w : windows) {
                    int[] b = blocks.get(w.history().id());
                    row++;
                    String own = AnswerBook.range(2, b[0] + w.start(), b[0] + w.start() + len - 1);
                    String cur = AnswerBook.range(2, b[1] - len + 1, b[1]);
                    main.row(row).text(0, w.history().node(), "normal").number(1, w.rank(), "int")
                            .formula(2, var(own, k), w.svar(), "decimal").formula(3, var(cur, k), w.current(), "decimal");
                    if (row == at + 1) {
                        wb.name(AnswerBook.name("stress-window", "SVAR"), main.name(), "$C$" + row);
                        wb.name(AnswerBook.name("stress-window", "CURRENT_SVAR"), main.name(), "$D$" + row);
                    }
                }
                return row;
            }

            private String var(String range, int k) {
                return options.calc().rule() == VarMath.RankRule.INTERPOLATED ? MarketHorizon.interpolated(range, len, options.calc().confidence())
                        : "-SMALL(" + range + "," + k + ")";
            }
        };
    }
}
