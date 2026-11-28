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
package com.ash.risk.ccr.engine;

import com.ash.engine.Cell;
import com.ash.engine.Descriptor;
import com.ash.engine.Control;
import com.ash.engine.EngineException;
import com.ash.engine.EngineException.Kind;
import com.ash.engine.Explanation;
import com.ash.engine.Request;
import com.ash.engine.Step;
import com.ash.engine.Steps;
import com.ash.engine.Table;
import com.ash.engine.Tables;
import com.ash.risk.ccr.calc.ExposureBook;
import com.ash.risk.ccr.calc.ExposureCalc;
import com.ash.risk.ccr.calc.ExposureGrid;
import com.ash.risk.ccr.calc.ExposureMath;
import com.ash.risk.ccr.calc.ExposureWhatIfMath;
import com.ash.risk.ccr.calc.LimitMeasure;
import com.ash.risk.ccr.calc.TenorBucket;
import com.ash.risk.kernel.workbook.AnswerBook;
import com.ash.tools.xlsx.Sheet;
import com.ash.tools.xlsx.Workbook;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The pre-deal check (EXPOSURE_GUIDE.md section 18.9), the exact method: the template's paths on the date's run, scaled to the notional
 * (and reversed when asked), added to the netting set it goes into, path by path, by the moved {@link ExposureWhatIfMath}; the peak of the
 * limit's measure, and each tenor bucket's, held to the counterparty's credit limit ({@link LimitMeasure}, {@link TenorBucket}) under the
 * one limit rule. {@code approve}: the limit and every bucket within; {@code decline}: the limit or a bucket over; {@code refer}: anything
 * less than exact (no limit on record, a measure the engine does not compute, the trade alone in a netting set of its own). Whether the
 * asker sees the whole counterparty is the host's to decide before it asks (an answer never depends on rows outside the visibility).
 */
final class CcrPreDeal {

    /** One check, computed. */
    record Check(String counterparty, String template, double factor, String set, CcrExtras.Limit limit, LimitMeasure measure, double q,
            ExposureWhatIfMath.Result result, double[] times, double before, double after, String status, List<Bucket> buckets, String decision,
            List<String> reasons) {}

    /** One tenor bucket before and after. */
    record Bucket(TenorBucket bucket, int points, double before, double after, String status, List<Integer> at) {}

    private final Descriptor descriptor;
    private final CcrOptions options;
    private final CcrDay day;

    CcrPreDeal(Descriptor descriptor, CcrOptions options, CcrDay day) {
        this.descriptor = descriptor;
        this.options = options;
        this.day = day;
    }

    /** The evaluator of a scope: the session's book under the request's visibility. */
    interface Scopes {
        ExposureCalc.Result evaluate(String scope) throws EngineException;

        java.util.Map<String, double[]> paths(java.util.Collection<String> trades);
    }

    Check check(Request request, ExposureBook b, Scopes scopes, Control control) throws EngineException {
        String cp = String.valueOf(request.param("counterparty")).strip();
        String template = String.valueOf(request.param("template")).strip();
        if (cp.isEmpty() || template.isEmpty()) {
            throw new EngineException("RSK-3150", Kind.REFUSED, "a pre-deal check names the counterparty and a template trade (the exact method)");
        }
        CcrExtras.Limit limit = day.limits(control).limits().get(cp);
        LimitMeasure measure = limit == null ? null : LimitMeasure.of(limit.measure(), options.pfeConfidence());
        double q = measure == null ? options.pfeConfidence() : measure.q();
        List<String> reasons = new ArrayList<>();
        int t = b.tradeOrdinal(template);
        if (t < 0) {
            throw new EngineException("RSK-3151", Kind.REFUSED, "no trade " + template + " the request sees on the run " + b.runId()
                    + ": a template is a booked trade with exposure paths");
        }
        ExposureCalc.Result base = scopes.evaluate("CPTY:" + cp);
        List<String> sets = new ArrayList<>();
        Map<String, ExposureWhatIfMath.NettingSet> joinable = new LinkedHashMap<>();
        double life = 0;
        for (ExposureCalc.Piece pc : base.pieces) {
            ExposureBook.SetInfo si = b.set(pc.set);
            life = Math.max(life, si.lifeYears());
            if (pc.whole) {
                sets.add(si.id());
                double[] e = pc.e;                                     // a whole set summed from the book's matrices keeps none of its own:
                double[] n = pc.n;                                     // the set alone, as Drishti's what-if reads it (its NSET scope)
                if (e == null || n == null) {
                    e = b.exposure(pc.set);
                    n = new double[e.length];
                    b.addNegative(pc.set, n);
                }
                joinable.put(si.id(), new ExposureWhatIfMath.NettingSet(si.id(), b.value(pc.set), e, n, pc.csa, pc.v0, pc.ce));
            }
        }
        ExposureBook.TradeInfo ti = b.trade(t);
        double tn = Math.abs(ti.notional());
        double notional = ((Number) request.param("notional")).doubleValue();
        double factor = (Boolean.TRUE.equals(request.param("reverse")) ? -1 : 1) * (notional > 0 && tn > 0 ? notional / tn : 1);
        String set = String.valueOf(request.param("netting-set")).strip();
        if (!set.isEmpty() && !sets.contains(set)) {
            throw new EngineException("RSK-3152", Kind.REFUSED, "the netting set " + set + " is not one of " + cp + "'s: "
                    + (sets.isEmpty() ? "none" : String.join(", ", sets)));
        }
        if (set.isEmpty()) {
            String own = b.set(ti.set()).id();
            if (cp.equals(b.set(ti.set()).counterparty()) && sets.contains(own)) {
                set = own;
            } else if (sets.size() == 1) {
                set = sets.get(0);
            } else {
                reasons.add(cp + " has " + sets.size() + " netting sets (" + String.join(", ", sets) + "): the trade is shown in a netting set of its own,"
                        + " uncollateralised; name the one it goes into");
            }
        }
        double[] tp = scopes.paths(List.of(ti.id())).get(ti.id());
        if (tp == null) {
            throw new EngineException("RSK-3153", Kind.REFUSED, "the template " + template + " has no paths on the run");
        }
        double[] mtm = new double[tp.length];
        for (int i = 0; i < tp.length; i++) {
            mtm[i] = factor * tp[i];
        }
        ExposureGrid g = b.grid();
        double[] times = ExposureCalc.times(g);
        ExposureWhatIfMath.Candidate cand = new ExposureWhatIfMath.Candidate("CANDIDATE-1", ti.product() + " like " + ti.id(), set, mtm,
                factor * ti.mtm0(), Math.max(ti.lifeYears(), 0));
        ExposureWhatIfMath.Scope scope = new ExposureWhatIfMath.Scope(base.e, base.n, base.stats.ce, life);
        ExposureWhatIfMath.Result r;
        try {
            r = ExposureWhatIfMath.of(scope, joinable, List.of(cand), g, b.paths(), times, q, b.model(), options.rule(), "CPTY:" + cp);
        } catch (IllegalArgumentException e) {
            throw new EngineException("RSK-3154", Kind.REFUSED, e.getMessage());
        }
        if (limit == null) {
            reasons.add("no credit limit on record for " + cp + " (credit-limit): credit decides");
        } else if (measure == null) {
            reasons.add("the limit " + limit.id() + " is set on " + limit.measure() + ", which the exposure engine does not compute: credit decides");
        }
        LimitMeasure m = measure == null ? LimitMeasure.of("PFE", q) : measure;
        double before = m.value(r.before());
        double after = m.value(r.after());
        boolean breach = false;
        boolean near = false;
        String status = null;
        List<Bucket> buckets = new ArrayList<>();
        if (limit != null && measure != null) {
            status = options.limits().status(after / limit.amount());
            breach = after > limit.amount();
            near = "near".equals(status);
            if (measure.profiled()) {
                for (TenorBucket bk : limit.buckets()) {
                    double b0 = 0;
                    double b1 = 0;
                    List<Integer> at = new ArrayList<>();
                    for (int i = 0; i < times.length; i++) {
                        if (bk.holds(times[i])) {
                            b0 = Math.max(b0, profile(r.before(), m, i));
                            b1 = Math.max(b1, profile(r.after(), m, i));
                            at.add(i);
                        }
                    }
                    String st = at.isEmpty() ? "no-dates" : options.limits().status(b1 / bk.limit());
                    breach |= !at.isEmpty() && b1 > bk.limit();
                    near |= "near".equals(st);
                    buckets.add(new Bucket(bk, at.size(), at.isEmpty() ? Double.NaN : b0, at.isEmpty() ? Double.NaN : b1, st, List.copyOf(at)));
                }
                status = breach ? "breach" : near ? "near" : "within";
            }
        }
        String decision;
        if (!reasons.isEmpty()) {
            decision = "refer";
        } else if (breach) {
            decision = "decline";
            reasons.add("exact: with the trade, " + m.text() + " or a tenor bucket goes over its limit");
        } else {
            decision = "approve";
            reasons.add("exact: with the trade, " + m.text() + " is " + Math.round(after) + " of the limit " + Math.round(limit.amount())
                    + (near ? "; near the limit" : ""));
        }
        return new Check(cp, ti.id(), factor, set.isEmpty() ? null : set, limit, measure, q, r, times, before, after, status, List.copyOf(buckets),
                decision, List.copyOf(reasons));
    }

    /** The profile value of the measure at a primary point: PFE at the one confidence, or EE. */
    static double profile(ExposureMath.Stats s, LimitMeasure m, int i) {
        return m.column().equals("pfe") ? s.pfe[0][i] : s.ee[i];
    }

    Table table(Check c) {
        Double lim = c.limit() == null ? null : c.limit().amount();
        Double ub = lim == null ? null : c.before() / lim;
        Double ua = lim == null ? null : c.after() / lim;
        return Tables.of(descriptor.schema("pre-deal")).row(c.counterparty(), c.decision(), String.join(" | ", c.reasons()), c.template(), c.factor(),
                c.set(), c.limit() == null ? null : c.limit().id(), c.measure() == null ? null : c.measure().text(), lim, c.before(), c.after(),
                c.after() - c.before(), ub, ua, c.status(), options.alpha() * c.result().before().eepe, options.alpha() * c.result().after().eepe).build();
    }

    Table buckets(Check c) {
        Tables.Builder t = Tables.of(descriptor.schema("pre-deal-buckets"));
        for (Bucket b : c.buckets()) {
            t.row(b.bucket().name(), b.bucket().limit(), (long) b.points(), nn(b.before()), nn(b.after()),
                    Double.isFinite(b.after()) ? b.after() / b.bucket().limit() : null, b.status());
        }
        return t.build();
    }

    private static Double nn(double d) {
        return Double.isFinite(d) ? d : null;
    }

    Explanation explain(Check c, Cell cell) throws EngineException {
        LimitMeasure m = c.measure() == null ? LimitMeasure.of("PFE", c.q()) : c.measure();
        if (cell.output().equals("pre-deal-buckets")) {
            for (Bucket b : c.buckets()) {
                if (!b.bucket().name().equals(cell.key().get("BUCKET")) || b.points() == 0) {
                    continue;
                }
                List<Double> before = new ArrayList<>();
                List<Double> after = new ArrayList<>();
                for (int i : b.at()) {
                    before.add(profile(c.result().before(), m, i));
                    after.add(profile(c.result().after(), m, i));
                }
                List<Step> steps = new ArrayList<>();
                steps.add(Steps.of("before", "The peak of " + m.text() + " over the bucket's grid points before the trade", "max($.values)",
                        Map.of("values", before), b.before()));
                steps.add(Steps.of("after", "and after it", "max($.values)", Map.of("values", after), b.after()));
                switch (cell.column()) {
                    case "BEFORE" -> {
                        return new Explanation(cell, b.before(), steps.subList(0, 1));
                    }
                    case "AFTER" -> {
                        return new Explanation(cell, b.after(), steps.subList(1, 2));
                    }
                    case "USE_AFTER" -> {
                        steps.add(Steps.of("use", "The peak after over the bucket's limit", "step.after / $.limit", Map.of("limit", b.bucket().limit()),
                                b.after() / b.bucket().limit()));
                        return new Explanation(cell, b.after() / b.bucket().limit(), steps);
                    }
                    default -> throw new EngineException("ENG-3005", Kind.REFUSED, cell.column() + " is not a measure of pre-deal-buckets");
                }
            }
            throw new EngineException("ENG-3005", Kind.REFUSED, "the bucket is not in this request's answer");
        }
        String why = "the netted, collateralised exposure of the counterparty's netting sets with the template's paths added, path by path;"
                + " recomputed in the workbook";
        return switch (cell.column()) {
            case "BEFORE" -> new Explanation(cell, c.before(), List.of(Steps.opaque("before", m.text() + " of " + c.counterparty() + " before", why,
                    "Main!BEFORE", c.before())));
            case "AFTER" -> new Explanation(cell, c.after(), List.of(Steps.opaque("after", m.text() + " of " + c.counterparty() + " with the trade", why,
                    "Main!AFTER", c.after())));
            case "INCREMENT" -> new Explanation(cell, c.after() - c.before(), List.of(Steps.of("increment", "After less before", "$.after - $.before",
                    Map.of("after", c.after(), "before", c.before()), c.after() - c.before())));
            case "USE_AFTER" -> {
                if (c.limit() == null) {
                    throw new EngineException("ENG-3005", Kind.REFUSED, "no limit: the use is null");
                }
                yield new Explanation(cell, c.after() / c.limit().amount(), List.of(Steps.of("use", "After over the limit", "$.after / $.limit",
                        Map.of("after", c.after(), "limit", c.limit().amount()), c.after() / c.limit().amount())));
            }
            case "EAD_BEFORE", "EAD_AFTER" -> {
                double eepe = cell.column().equals("EAD_BEFORE") ? c.result().before().eepe : c.result().after().eepe;
                yield new Explanation(cell, options.alpha() * eepe, List.of(Steps.of("ead", "The IMM EAD: alpha times the effective EPE",
                        "$.alpha * $.eepe", Map.of("alpha", options.alpha(), "eepe", eepe), options.alpha() * eepe)));
            }
            default -> throw new EngineException("ENG-3005", Kind.REFUSED, cell.column() + " is not a measure of pre-deal");
        };
    }

    /** The workbook part: the profile before and after at each primary point as values; the peaks, the use and the buckets as formulas. */
    AnswerBook.Part part(Check c) {
        LimitMeasure m = c.measure() == null ? LimitMeasure.of("PFE", c.q()) : c.measure();
        return new AnswerBook.Part() {
            private int first;
            private int last;

            @Override
            public int data(Sheet data, int at) {
                first = at + 1;
                data.row(at).text(0, "POINT", "header").text(1, "T", "header").text(2, "BEFORE", "header").text(3, "AFTER", "header");
                for (int i = 0; i < c.times().length; i++) {
                    data.row(at + 1 + i).number(0, i, "int").number(1, c.times()[i], "decimal").number(2, profile(c.result().before(), m, i), "decimal")
                            .number(3, profile(c.result().after(), m, i), "decimal");
                }
                last = at + c.times().length;
                return last;
            }

            @Override
            public int main(Workbook wb, Sheet main, int at) {
                boolean profiled = m.profiled();
                String b = AnswerBook.range(2, first, last);
                String a = AnswerBook.range(3, first, last);
                if (profiled) {
                    main.row(at).text(0, "BEFORE", "bold").formula(1, "MAX(" + b + ")", c.before(), "decimal");
                    main.row(at + 1).text(0, "AFTER", "bold").formula(1, "MAX(" + a + ")", c.after(), "decimal");
                } else {
                    main.row(at).text(0, "BEFORE (" + m.text() + ", the engine's)", "bold").number(1, c.before(), "decimal");
                    main.row(at + 1).text(0, "AFTER (" + m.text() + ", the engine's)", "bold").number(1, c.after(), "decimal");
                }
                main.row(at + 2).text(0, "INCREMENT", "bold").formula(1, "B" + (at + 1) + "-B" + at, c.after() - c.before(), "decimal");
                int row = at + 2;
                if (c.limit() != null) {
                    row++;
                    main.row(row).text(0, "LIMIT", "bold").number(1, c.limit().amount(), "decimal");
                    row++;
                    main.row(row).text(0, "USE_AFTER", "bold").formula(1, "B" + (at + 1) + "/B" + (row - 1), c.after() / c.limit().amount(), "decimal");
                    wb.name(AnswerBook.name("pre-deal", "USE_AFTER"), main.name(), "$B$" + row);
                }
                wb.name(AnswerBook.name("pre-deal", "BEFORE"), main.name(), "$B$" + at);
                wb.name(AnswerBook.name("pre-deal", "AFTER"), main.name(), "$B$" + (at + 1));
                wb.name(AnswerBook.name("pre-deal", "INCREMENT"), main.name(), "$B$" + (at + 2));
                boolean named = false;
                for (Bucket k : c.buckets()) {
                    if (k.points() == 0) {
                        continue;
                    }
                    row++;
                    // the bucket's points are consecutive (times rise): the peak is the MAX over their rows of the profile block
                    int lo = first + k.at().get(0);
                    int hi = first + k.at().get(k.at().size() - 1);
                    main.row(row).text(0, k.bucket().name(), "normal").formula(1, "MAX(" + AnswerBook.range(2, lo, hi) + ")", k.before(), "decimal")
                            .formula(2, "MAX(" + AnswerBook.range(3, lo, hi) + ")", k.after(), "decimal").number(3, k.bucket().limit(), "decimal")
                            .formula(4, "C" + row + "/D" + row, k.after() / k.bucket().limit(), "decimal");
                    if (!named) {
                        named = true;
                        wb.name(AnswerBook.name("pre-deal-buckets", "BEFORE"), main.name(), "$B$" + row);
                        wb.name(AnswerBook.name("pre-deal-buckets", "AFTER"), main.name(), "$C$" + row);
                        wb.name(AnswerBook.name("pre-deal-buckets", "USE_AFTER"), main.name(), "$E$" + row);
                    }
                }
                return row;
            }
        };
    }
}
