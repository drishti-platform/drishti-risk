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

import com.ash.engine.Answer;
import com.ash.engine.Answers;
import com.ash.engine.Cell;
import com.ash.engine.Control;
import com.ash.engine.EngineException;
import com.ash.engine.EngineException.Kind;
import com.ash.engine.EngineMeter;
import com.ash.engine.Explaining;
import com.ash.engine.Explanation;
import com.ash.engine.Filter;
import com.ash.engine.MemoryBudget;
import com.ash.engine.Missing;
import com.ash.engine.Request;
import com.ash.engine.Session;
import com.ash.engine.Snapshot;
import com.ash.engine.Table;
import com.ash.engine.Visibility;
import com.ash.risk.kernel.workbook.AnswerBook;
import com.ash.risk.market.calc.VarLens;
import com.ash.risk.market.frtb.calc.FrtbSa;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A market-risk session: one business date's inputs and the books built from them, one per (scenario set, window, currency,
 * visibility). A book is built at its first ask and kept for the session; a viewer's book is built from the rows that viewer may see,
 * so an answer never depends on rows outside the visibility (filter equivalence holds by construction). The outputs over earlier dates
 * and over the sensitivities read their own inputs under their own filters; each output that lacks an input it needs is answered
 * NOT_FOUND by name while the others are answered (a partial answer).
 */
final class MarketSession implements Session, Explaining {

    /** The outputs answered from a book of scenario vectors. */
    static final Set<String> BOOK = Set.of("node-var", "node-components", "basket-var", "headline", "node-pvar", "node-pvar-components",
            "node-var-horizon", "what-if");
    /** The inputs each other output cannot be answered without. */
    static final Map<String, List<String>> NEEDS = Map.ofEntries(
            Map.entry("backtest", List.of(MarketExtras.HISTORY, MarketExtras.DESK_PNL)),
            Map.entry("backtest-days", List.of(MarketExtras.HISTORY, MarketExtras.DESK_PNL)),
            Map.entry("official-history", List.of(MarketExtras.HISTORY)),
            Map.entry("official-backtest", List.of(MarketExtras.HISTORY)),
            Map.entry("period-stats", List.of(MarketExtras.HISTORY)),
            Map.entry("official-statistics", List.of(MarketExtras.HISTORY)),
            Map.entry("regulatory-report", List.of(MarketExtras.HISTORY, MarketExtras.REPORT_ROW)),
            Map.entry("stress-window", List.of(MarketExtras.PNL_HISTORY)),
            Map.entry("sensitivity-sum", List.of(MarketExtras.SENSITIVITY, MarketExtras.FACTOR)),
            Map.entry("sensitivity-ladder", List.of(MarketExtras.SENSITIVITY, MarketExtras.FACTOR)),
            Map.entry("frtb-sa", List.of(MarketExtras.SENSITIVITY, MarketExtras.FACTOR)),
            Map.entry("frtb-contributions", List.of(MarketExtras.SENSITIVITY, MarketExtras.FACTOR)));

    private final MarketRiskEngine engine;
    private final Snapshot snapshot;
    private final MarketDay day;
    private final Map<MarketBooks.Key, MarketBooks.Built> books = new ConcurrentHashMap<>();
    private final List<MemoryBudget.Reservation> holds = new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile boolean closed;

    MarketSession(MarketRiskEngine engine, Snapshot snapshot, MarketDay day) {
        this.engine = engine;
        this.snapshot = snapshot;
        this.day = day;
    }

    @Override
    public Snapshot snapshot() {
        return snapshot;
    }

    @Override
    public long memoryBytes() {
        if (closed) {
            return 0;
        }
        long b = day.bytes();
        for (MarketBooks.Built x : books.values()) {
            b += x.book().heapBytes();
        }
        return b;
    }

    @Override
    public Answer ask(Request request, Control control) throws EngineException {
        engine.running();
        return engine.meter().measure(() -> answer(request, control), day.pv.size() + day.pnl.size(), 0);
    }

    /** What one request computed, kept for the explanation and the workbook of the same request. */
    private final class Computed {
        final Request request;
        final Control control;
        final Map<String, Object> made = new LinkedHashMap<>();
        MarketBooks.Built built;
        MarketBooks.Built horizonBook;
        boolean noFx;

        Computed(Request request, Control control) {
            this.request = request;
            this.control = control;
        }

        MarketBooks.Built book() throws EngineException {
            if (built == null) {
                built = MarketSession.this.book(request, control, null);
                noFx = built == null;
            }
            return built;
        }

        MarketBooks.Built horizon() throws EngineException {
            if (horizonBook == null) {
                if (Boolean.TRUE.equals(request.param("stressed"))) {
                    horizonBook = MarketSession.this.book(request, control, day.stressSet(engine.options().stressedPrefix()));
                } else {
                    horizonBook = book();
                }
            }
            return horizonBook;
        }

        @SuppressWarnings("unchecked")
        <T> T get(String key, Make<T> make) throws EngineException {
            Object have = made.get(key);
            if (have == null) {
                have = make.make();
                made.put(key, have);
            }
            return (T) have;
        }
    }

    @FunctionalInterface
    private interface Make<T> {
        T make() throws EngineException;
    }

    private Answer answer(Request request, Control control) throws EngineException {
        Map<String, Missing> missing = missing(request);
        Computed c = new Computed(request, control);
        List<Table> tables = new ArrayList<>();
        for (String out : request.outputs()) {
            control.check();
            if (missing.values().stream().anyMatch(m -> m.outputs().contains(out))) {
                continue;
            }
            Table t = table(c, out);
            if (t == null) {                                                // the reporting currency has no FX on the set
                String cur = String.valueOf(request.param("currency"));
                missing.put(MarketDay.FX + "|" + out, new Missing(MarketDay.FX, MarketDay.CONTRACT.get(MarketDay.FX), snapshot, List.of("RATES of " + cur),
                        List.of(out)));
                continue;
            }
            tables.add(t);
        }
        List<Missing> named = merge(missing);
        if (tables.isEmpty()) {
            return Answers.notFound(engine.context(), this, request, named);
        }
        return named.isEmpty() ? Answers.complete(engine.context(), this, request, tables) : Answers.of(engine.context(), this, request, tables, named);
    }

    /** One table of a request; null when its book cannot be built (no FX for the reporting currency). */
    private Table table(Computed c, String out) throws EngineException {
        Request request = c.request;
        MarketOptions o = engine.options();
        MarketTables t = new MarketTables(engine.descriptor(), o, engine.sums());
        if (MarketScopes.OUTPUTS.contains(out)) {
            return new MarketScopes(engine.descriptor(), o, engine.sums(), this).table(c.request, c.control, out);
        }
        if (BOOK.contains(out)) {
            boolean lensed = out.equals("node-var-horizon") || out.equals("node-pvar") && lensed(request);   // node-pvar through a lens
            MarketBooks.Built b = lensed ? c.horizon() : c.book();
            if (b == null) {
                return null;
            }
            return switch (out) {
                case "node-var" -> t.nodeVar(b.book());
                case "node-components" -> t.nodeComponents(b.book(), c.control);
                case "basket-var" -> t.basketVar(b, day, String.valueOf(request.param("currency")));
                case "headline" -> t.headline(b.book());
                case "node-pvar" -> new MarketParametric(engine.descriptor(), o).nodes(b.book(), lensed ? new MarketHorizon(engine.descriptor(), o)
                        .lens(request) : null);
                case "node-pvar-components" -> new MarketParametric(engine.descriptor(), o).components(b.book(), c.control);
                case "node-var-horizon" -> {
                    MarketHorizon h = new MarketHorizon(engine.descriptor(), o);
                    VarLens lens = h.lens(request);
                    yield h.nodes(b, b.scenarios().isEmpty() ? "" : setOf(request), lens);
                }
                default -> {
                    MarketWhatIf w = new MarketWhatIf(engine.descriptor(), o);
                    double[] rows = request.param("rows") instanceof java.util.Collection<?> rc && !rc.isEmpty()   // a row set: any scope
                            ? new MarketScopes(engine.descriptor(), o, engine.sums(), this).usdRows(request, c.control) : null;
                    yield w.table(c.get("what-if", () -> w.ask(b, request, rows)));
                }
            };
        }
        MarketHistory h = new MarketHistory(engine.descriptor(), o, day);
        MarketSensitivities s = new MarketSensitivities(engine.descriptor(), o, day);
        return switch (out) {
            case "backtest" -> h.backtest(backtests(c, h));
            case "backtest-days" -> h.backtestDays(backtests(c, h));
            case "official-history" -> h.officialDays(c.get("official-history", () -> h.officialHistory(request, see(request, MarketExtras.HISTORY))));
            case "official-backtest" -> h.officialBacktest(c.get("official-history", () -> h.officialHistory(request, see(request, MarketExtras.HISTORY))));
            case "official-statistics" -> h.officialStatistics(request, see(request, MarketExtras.HISTORY), see(request, MarketExtras.DESK_PNL));
            case "period-stats" -> h.periodTable(c.get("period-stats", () -> h.periodStats(request, see(request, MarketExtras.HISTORY))));
            case "regulatory-report" -> h.reportTable(c.get("regulatory-report", () -> h.reports(request, see(request, MarketExtras.HISTORY))));
            case "stress-window" -> h.windowTable(c.get("stress-window", () -> h.windows(request, see(request, MarketExtras.PNL_HISTORY))));
            case "sensitivity-sum" -> s.sumTable(c.get("sensitivity-sum", () -> s.sums(scoped(c, s), String.valueOf(request.param("by")))));
            case "sensitivity-ladder" -> s.ladderTable(c.get("sensitivity-ladder", () -> s.ladder(scoped(c, s), request)));
            case "frtb-sa" -> s.frtbTable(frtb(c, s));
            case "frtb-contributions" -> s.contributionTable(frtb(c, s), String.valueOf(request.param("attribute")));
            default -> throw new EngineException("ENG-3003", Kind.REFUSED, "market-risk has no output " + out);
        };
    }

    /** True when a request asks a horizon or the stress set (node-pvar then fits each node's vector through that lens). */
    private static boolean lensed(Request request) {
        return Boolean.TRUE.equals(request.param("stressed")) || "10d".equals(String.valueOf(request.param("horizon")))
                || nonZero(request.param("lens-confidence")) || nonZero(request.param("lens-es-confidence"));
    }

    private static boolean nonZero(Object q) {
        return q instanceof Number n ? n.doubleValue() != 0 : q != null && !String.valueOf(q).isBlank() && Double.parseDouble(String.valueOf(q)) != 0;
    }

    private String setOf(Request request) {
        if (Boolean.TRUE.equals(request.param("stressed"))) {
            MarketDay.ScenarioSet s = day.stressSet(engine.options().stressedPrefix());
            return s == null ? "" : s.id();
        }
        MarketDay.ScenarioSet s = day.set(String.valueOf(request.param("scenario-set")));
        return s == null ? "" : s.id();
    }

    private List<MarketHistory.Test> backtests(Computed c, MarketHistory h) throws EngineException {
        return c.get("backtest", () -> h.backtests(c.request, see(c.request, MarketExtras.HISTORY), see(c.request, MarketExtras.DESK_PNL)));
    }

    private MarketSensitivities.Scoped scoped(Computed c, MarketSensitivities s) throws EngineException {
        return c.get("scoped", () -> s.scoped(c.request, see(c.request, MarketExtras.SENSITIVITY)));
    }

    private FrtbSa.Result frtb(Computed c, MarketSensitivities s) throws EngineException {
        return c.get("frtb", () -> {
            try {
                return s.frtb(scoped(c, s), c.request);
            } catch (IllegalArgumentException e) {
                throw new EngineException("RSK-3144", Kind.REFUSED, e.getMessage());
            }
        });
    }

    /** One missing input per name, naming every output it stops. */
    private static List<Missing> merge(Map<String, Missing> missing) {
        Map<String, Missing> byInput = new LinkedHashMap<>();
        for (Missing m : missing.values()) {
            Missing was = byInput.get(m.input());
            if (was == null) {
                byInput.put(m.input(), m);
            } else {
                List<String> outs = new ArrayList<>(was.outputs());
                m.outputs().stream().filter(x -> !outs.contains(x)).forEach(outs::add);
                List<String> cols = new ArrayList<>(was.columns());
                m.columns().stream().filter(x -> !cols.contains(x)).forEach(cols::add);
                byInput.put(m.input(), new Missing(was.input(), was.contract(), was.snapshot(), cols, outs));
            }
        }
        return List.copyOf(byInput.values());
    }

    /** What the outputs asked need and the date does not have, by input and output: the answer names it; nothing is substituted. */
    private Map<String, Missing> missing(Request request) {
        Map<String, Missing> out = new LinkedHashMap<>();
        List<String> book = request.outputs().stream().filter(x -> BOOK.contains(x) || MarketScopes.OUTPUTS.contains(x)).toList();
        if (!book.isEmpty()) {
            for (String name : List.of(MarketDay.SCENARIO_SET, MarketDay.FX)) {
                Missing m = day.missing.get(name);
                if (m != null) {
                    out.put(name, new Missing(m.input(), m.contract(), m.snapshot(), m.columns(), book));
                }
            }
            if (day.pv.isEmpty() && day.pnl.isEmpty()) {
                Missing m = day.missing.getOrDefault(MarketDay.SCENARIO_PV,
                        new Missing(MarketDay.SCENARIO_PV, MarketDay.CONTRACT.get(MarketDay.SCENARIO_PV), snapshot, List.of(), List.of()));
                out.put(MarketDay.SCENARIO_PV, new Missing(m.input(), m.contract(), m.snapshot(), m.columns(), book));
            }
            if (request.outputs().contains("basket-var") && day.missing.containsKey(MarketDay.BASKET)) {
                Missing m = day.missing.get(MarketDay.BASKET);
                out.put(MarketDay.BASKET, new Missing(m.input(), m.contract(), m.snapshot(), m.columns(), List.of("basket-var")));
            }
            if (!out.containsKey(MarketDay.SCENARIO_SET)) {
                List<String> normal = book.stream().filter(x -> !x.equals("node-var-horizon") || !Boolean.TRUE.equals(request.param("stressed"))).toList();
                if (!normal.isEmpty() && day.set(String.valueOf(request.param("scenario-set"))) == null) {
                    out.put(MarketDay.SCENARIO_SET + "|normal", new Missing(MarketDay.SCENARIO_SET, MarketDay.CONTRACT.get(MarketDay.SCENARIO_SET),
                            snapshot, List.of("SCENARIO_SET_ID " + request.param("scenario-set")), normal));
                }
                if (book.contains("node-var-horizon") && Boolean.TRUE.equals(request.param("stressed"))
                        && day.stressSet(engine.options().stressedPrefix()) == null) {
                    out.put(MarketDay.SCENARIO_SET + "|stress", new Missing(MarketDay.SCENARIO_SET, MarketDay.CONTRACT.get(MarketDay.SCENARIO_SET),
                            snapshot, List.of("SET_TYPE STRESS"), List.of("node-var-horizon")));
                }
            }
        }
        for (String o : request.outputs()) {
            for (String input : NEEDS.getOrDefault(o, List.of())) {
                Missing m = day.missing.get(input);
                if (m != null) {
                    out.put(input + "|" + o, new Missing(m.input(), m.contract(), m.snapshot(), m.columns(), List.of(o)));
                }
            }
        }
        return out;
    }

    /** The visibility over the two vector inputs, each its own filter (a request without them is refused, ENG-4001, ENG-4002). */
    static Filter[] visibility(Request request) throws EngineException {
        return new Filter[] {see(request, MarketDay.SCENARIO_PV), see(request, MarketDay.PNL_VECTOR)};
    }

    /** The rows of one input the request may read (ENGINE_SPEC 8.6 rule 1: nothing is read as "all" by default). */
    static Filter see(Request request, String input) throws EngineException {
        Visibility v = request.visibility();
        if (v == null) {
            throw new EngineException("ENG-4001", Kind.NOT_PERMITTED, "the request has no visibility");
        }
        Filter f = v.rows().get(input);
        if (f == null) {
            throw new EngineException("ENG-4002", Kind.NOT_PERMITTED, "the request names no rows of " + input + " it may read");
        }
        return f;
    }

    /** The book of a request (of {@code over} when given: the stress set), built once per key. Null when the reporting currency has no FX. */
    MarketBooks.Built book(Request request, Control control, MarketDay.ScenarioSet over) throws EngineException {
        Filter[] see = visibility(request);
        // a caller who sees every row shares the book of every row (one book, the visibility a selection: EN-63)
        String signature = see[0] == Filter.ALL && see[1] == Filter.ALL ? ALL : request.visibility().signature();
        return book(request, control, over, see, signature,
                String.valueOf(request.param("currency")).toUpperCase(java.util.Locale.ROOT));
    }

    /** The visibility signature of the one book every caller's row set selects from (EN-63: visibility a selection, never a book). */
    static final String ALL = "\u0000all";

    /**
     * The date's book of every row (no visibility applied) in {@code currency}: the ordinals a host's row set names (EN-68). A caller's
     * visibility is then a selection over it ({@link MarketScopes#visible}), as Drishti's in-tree book applies it.
     */
    MarketBooks.Built allBook(Request request, Control control, String currency) throws EngineException {
        return book(request, control, null, new Filter[] {Filter.ALL, Filter.ALL}, ALL, currency);
    }

    MarketDay day() {
        return day;
    }

    private MarketBooks.Built book(Request request, Control control, MarketDay.ScenarioSet over, Filter[] see, String signature,
            String currency) throws EngineException {
        MarketDay.ScenarioSet set = over != null ? over : day.set(String.valueOf(request.param("scenario-set")));
        if (set == null) {
            throw new EngineException("ENG-3005", Kind.REFUSED, "the date has no such scenario set");
        }
        int window = ((Number) request.param("window")).intValue();
        window = window <= 0 || window >= set.dates().size() ? 0 : window;    // the whole set however asked: one book
        MarketBooks.Key key = new MarketBooks.Key(set.id(), window, currency, signature);
        MarketBooks.Built have = books.get(key);
        if (have != null) {
            engine.meter().cacheHit();
            return have;
        }
        engine.meter().cacheMiss();
        synchronized (books) {
            have = books.get(key);
            if (have != null) {
                return have;
            }
            control.check();
            long need = 16L * 1024 * 1024 + 2L * 8 * (long) set.dates().size() * (day.pv.size() + day.pnl.size());
            MemoryBudget.Reservation hold = engine.context().memory().reserve(need, "market-risk book " + key);
            MarketBooks.Built b;
            try {
                b = MarketBooks.build(day, set, window, currency, see[0], see[1], engine.options(), engine.sums(), engine.context().log()::info);
            } catch (RuntimeException e) {
                hold.close();
                throw e;
            }
            if (b == null) {
                hold.close();
                return null;
            }
            holds.add(hold);
            books.put(key, b);
            return b;
        }
    }

    @Override
    public Explanation explain(Request request, Cell cell, Control control) throws EngineException {
        engine.running();
        Computed c = new Computed(request, control);
        if (!missing(request).values().stream().filter(m -> m.outputs().contains(cell.output())).toList().isEmpty()) {
            throw new EngineException("ENG-3005", Kind.REFUSED, "the cell is not in this request's answer: an input it needs is missing");
        }
        MarketOptions o = engine.options();
        MarketExplain historical = new MarketExplain(o, engine.sums());
        String out = cell.output();
        if (BOOK.contains(out)) {
            if (out.equals("node-pvar") && lensed(request)) {
                throw new EngineException("ENG-3005", Kind.REFUSED, "node-pvar is explained on the 1-day normal set; through a horizon or the "
                        + "stress set its cells are not explained yet");
            }
            MarketBooks.Built b = out.equals("node-var-horizon") ? c.horizon() : c.book();
            if (b == null) {
                throw new EngineException("ENG-3005", Kind.REFUSED, "the cell is not in this request's answer: the reporting currency has no FX");
            }
            return switch (out) {
                case "node-pvar", "node-pvar-components" -> new MarketParametric(engine.descriptor(), o).explain(b, cell, historical);
                case "node-var-horizon" -> {
                    MarketHorizon h = new MarketHorizon(engine.descriptor(), o);
                    yield h.explain(b, cell, h.lens(request), historical);
                }
                case "what-if" -> {
                    MarketWhatIf w = new MarketWhatIf(engine.descriptor(), o);
                    yield w.explain(w.ask(b, request), b, cell);
                }
                default -> historical.explain(b, day, cell);
            };
        }
        MarketHistory h = new MarketHistory(engine.descriptor(), o, day);
        MarketSensitivities s = new MarketSensitivities(engine.descriptor(), o, day);
        return switch (out) {
            case "backtest" -> h.explainBacktest(backtests(c, h), cell);
            case "period-stats" -> h.explainStats(h.periodStats(request, see(request, MarketExtras.HISTORY)), cell);
            case "regulatory-report" -> h.explainReport(h.reports(request, see(request, MarketExtras.HISTORY)), cell);
            case "stress-window" -> h.explainWindow(h.windows(request, see(request, MarketExtras.PNL_HISTORY)), cell);
            case "sensitivity-sum" -> s.explainSum(s.sums(scoped(c, s), String.valueOf(request.param("by"))), cell, "ROW_KEY", "COLUMN_KEY");
            case "sensitivity-ladder" -> s.explainSum(s.ladder(scoped(c, s), request), cell, "QUALIFIER", "BUCKET");
            case "frtb-sa", "frtb-contributions" -> s.explainFrtb(frtb(c, s), cell,
                    request.params().containsKey("attribute") ? String.valueOf(request.param("attribute")) : "DESK");
            default -> throw new EngineException("ENG-3005", Kind.REFUSED, out + " has no measure cells to explain");
        };
    }

    @Override
    public void workbook(Request request, OutputStream out, Control control) throws EngineException, IOException {
        engine.running();
        Answer a = ask(request, control);
        Computed c = new Computed(request, control);
        MarketOptions o = engine.options();
        List<AnswerBook.Part> parts = new ArrayList<>();
        boolean varDone = false;
        for (String name : request.outputs()) {
            if (a.table(name) == null) {
                continue;
            }
            switch (name) {
                case "node-var", "node-components", "basket-var", "headline" -> {
                    if (!varDone) {
                        varDone = true;
                        parts.add(new MarketWorkbook(c.book(), o, request.outputs().contains("headline")));
                    }
                }
                case "node-pvar" -> {
                    if (!lensed(request)) {                                // through a horizon or the stress set: no workbook part yet
                        parts.add(new MarketParametric(engine.descriptor(), o).part(c.book(), request.outputs().contains("node-pvar-components")));
                    }
                }
                case "node-pvar-components" -> {
                    if (!request.outputs().contains("node-pvar")) {
                        parts.add(new MarketParametric(engine.descriptor(), o).part(c.book(), true));
                    }
                }
                case "node-var-horizon" -> {
                    MarketHorizon h = new MarketHorizon(engine.descriptor(), o);
                    parts.add(h.part(c.horizon(), h.lens(request)));
                }
                case "what-if" -> {
                    MarketWhatIf w = new MarketWhatIf(engine.descriptor(), o);
                    parts.add(w.part(w.ask(c.book(), request), c.book()));
                }
                case "backtest", "backtest-days" -> {
                    if (name.equals("backtest") || !request.outputs().contains("backtest")) {
                        MarketHistory h = new MarketHistory(engine.descriptor(), o, day);
                        parts.add(h.backtestPart(backtests(c, h)));
                    }
                }
                case "period-stats" -> {
                    MarketHistory h = new MarketHistory(engine.descriptor(), o, day);
                    parts.add(h.statsPart(h.periodStats(request, see(request, MarketExtras.HISTORY))));
                }
                case "regulatory-report" -> {
                    MarketHistory h = new MarketHistory(engine.descriptor(), o, day);
                    parts.add(h.reportPart(h.reports(request, see(request, MarketExtras.HISTORY))));
                }
                case "stress-window" -> {
                    MarketHistory h = new MarketHistory(engine.descriptor(), o, day);
                    parts.add(h.windowPart(h.windows(request, see(request, MarketExtras.PNL_HISTORY))));
                }
                case "sensitivity-sum" -> {
                    MarketSensitivities s = new MarketSensitivities(engine.descriptor(), o, day);
                    parts.add(s.sumPart(s.sums(scoped(c, s), String.valueOf(request.param("by"))), name));
                }
                case "sensitivity-ladder" -> {
                    MarketSensitivities s = new MarketSensitivities(engine.descriptor(), o, day);
                    parts.add(s.sumPart(s.ladder(scoped(c, s), request), name));
                }
                case "frtb-sa", "frtb-contributions" -> {
                    if (name.equals("frtb-sa") || !request.outputs().contains("frtb-sa")) {
                        MarketSensitivities s = new MarketSensitivities(engine.descriptor(), o, day);
                        parts.add(s.frtbPart(frtb(c, s)));
                    }
                }
                default -> {
                }
            }
        }
        AnswerBook.write(out, parts, String.join("; ", a.missing().stream().map(Missing::words).toList()));
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        books.values().forEach(b -> b.book().close());
        books.clear();
        holds.forEach(MemoryBudget.Reservation::close);
        holds.clear();
        engine.meter().sessionClosed();
    }

    EngineMeter meter() {
        return engine.meter();
    }
}
