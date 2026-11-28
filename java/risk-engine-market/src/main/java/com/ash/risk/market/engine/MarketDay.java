/*
 * Drishti Risk · market and counterparty risk as engines.
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

import com.ash.engine.Control;
import com.ash.engine.EngineException;
import com.ash.engine.Filter;
import com.ash.engine.InputTable;
import com.ash.engine.Inputs;
import com.ash.engine.Missing;
import com.ash.engine.Scan;
import com.ash.engine.Snapshot;
import com.ash.engine.VectorScan;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * One business date's small inputs (the scenario sets, the FX, the limits, the baskets), read at the session's open and kept in a
 * canonical order (sorted by key: a sum in floating point depends on order, and the order of delivery is free, ENGINE_SPEC 5.2 rule 3).
 * The vector inputs are not read here: each use reads them in one pass ({@link MarketRows}) and keeps nothing it was delivered (5.2 rule
 * 9); the inputs beyond the scenario vectors ({@link MarketExtras}) are read at the first output that needs them. {@code inputs} stays
 * valid until the session is closed (4.1).
 */
final class MarketDay {

    static final String SCENARIO_PV = "scenario-pv";
    static final String PNL_VECTOR = "pnl-vector";
    static final String SCENARIO_SET = "scenario-set";
    static final String FX = "fx-scenario";
    static final String LIMIT = "var-limit";
    static final String BASKET = "system-basket";
    static final Map<String, String> CONTRACT = Map.of(SCENARIO_PV, "risk/scenario-pv@1", PNL_VECTOR, "risk/pnl-vector@1", SCENARIO_SET,
            "risk/scenario-set@1", FX, "risk/fx-scenario@1", LIMIT, "risk/var-limit@1", BASKET, "risk/system-basket@1");

    /** The columns without which an input cannot be used (the rest are optional attributes). */
    private static final Map<String, List<String>> NEEDED = Map.of(
            SCENARIO_PV, List.of("SCENARIO_PV_ID", "SCENARIO_SET_ID", "CURRENCY", "BASE_PV", "SCENARIO_PV"),
            PNL_VECTOR, List.of("VECTOR_ID", "SCENARIO_SET_ID", "PNL"),
            SCENARIO_SET, List.of("SCENARIO_SET_ID", "SCENARIO_DATES"),
            FX, List.of("FX_SCENARIO_ID", "SCENARIO_SET_ID", "CURRENCY", "RATE", "RATES"),
            LIMIT, List.of("HIERARCHY", "NODE_ID", "LIMIT_VAR"),
            BASKET, List.of("PORTFOLIO_ID", "SCOPE"));

    /** A vector input: its name, key column, vector column and the column of its PV. */
    record Vectors(String name, String key, String vector, String pv) {}

    static final Vectors PV_INPUT = new Vectors(SCENARIO_PV, "SCENARIO_PV_ID", "SCENARIO_PV", "BASE_PV");
    static final Vectors PNL_INPUT = new Vectors(PNL_VECTOR, "VECTOR_ID", "PNL", "PV");

    record ScenarioSet(String id, String type, List<LocalDate> dates) {}

    record Fx(String set, String currency, double rate, double[] rates) {}

    record Basket(String id, String name, String type, String owner, String scope) {}

    final Snapshot snapshot;
    final LocalDate date;
    final List<ScenarioSet> sets;
    final List<Fx> fx;
    final Map<String, Double> nodeLimits;
    final Map<String, Double> basketLimits;
    final List<Basket> baskets;
    /** The small inputs and the vector inputs absent or lacking columns, by input name (the extras' own: {@link #missing(String)}). */
    private final Map<String, Missing> missing;
    private final Inputs inputs;
    /** The vector inputs usable on the date (present with every needed column): scenario PVs, P&amp;L vectors. */
    private final InputTable pv;
    private final InputTable pnl;
    private volatile MarketExtras extras;
    /** The host's own float32 rows of the date (InputTable.vectorView {@value #ROWS_VIEW}: zero copy, R2_CUT_PLAN 3a), or null. */
    final Object rowsView;
    static final String ROWS_VIEW = "float32-rows";
    private final Map<String, Missing> extrasMissing = new java.util.concurrent.ConcurrentHashMap<>();

    private MarketDay(Snapshot snapshot, Inputs inputs, InputTable pv, InputTable pnl, List<ScenarioSet> sets, List<Fx> fx,
            Map<String, Double> nodeLimits, Map<String, Double> basketLimits, List<Basket> baskets, Map<String, Missing> missing, Object rowsView) {
        this.rowsView = rowsView;
        this.snapshot = snapshot;
        this.inputs = inputs;
        this.pv = pv;
        this.pnl = pnl;
        this.date = LocalDate.parse(snapshot.dimensions().get("date"));
        this.sets = sets;
        this.fx = fx;
        this.nodeLimits = nodeLimits;
        this.basketLimits = basketLimits;
        this.baskets = baskets;
        this.missing = missing;
    }

    /** The date's small inputs, and whether its vector inputs are there (their rows are read by each use). */
    static MarketDay read(Snapshot snapshot, Inputs inputs, Control control) throws EngineException {
        return read(snapshot, inputs, control, false);
    }

    /** The date's small inputs; {@code zeroCopy}: the host's float32 rows asked too ({@link #rowsView}). */
    static MarketDay read(Snapshot snapshot, Inputs inputs, Control control, boolean zeroCopy) throws EngineException {
        Map<String, Missing> missing = new TreeMap<>();
        Map<String, List<Map<String, Object>>> plain = new HashMap<>();
        for (String name : List.of(SCENARIO_SET, FX, LIMIT, BASKET)) {
            InputTable t = usable(name, inputs, snapshot, missing);
            plain.put(name, t == null ? List.of() : scan(t, control));
        }
        InputTable pv = usable(SCENARIO_PV, inputs, snapshot, missing);
        InputTable pnl = usable(PNL_VECTOR, inputs, snapshot, missing);
        List<ScenarioSet> sets = new ArrayList<>();
        for (Map<String, Object> r : sorted(plain.get(SCENARIO_SET), "SCENARIO_SET_ID")) {
            sets.add(new ScenarioSet(text(r, "SCENARIO_SET_ID"), text(r, "SET_TYPE"), dates(r.get("SCENARIO_DATES"))));
        }
        List<Fx> fx = new ArrayList<>();
        Map<String, double[]> rates = vectorsOf(inputs.table(FX), "FX_SCENARIO_ID", "RATES", missing.containsKey(FX), control);
        for (Map<String, Object> r : sorted(plain.get(FX), "FX_SCENARIO_ID")) {
            double[] v = rates.get(text(r, "FX_SCENARIO_ID"));
            if (v != null) {
                fx.add(new Fx(text(r, "SCENARIO_SET_ID"), text(r, "CURRENCY"), number(r, "RATE"), v));
            }
        }
        Map<String, Double> nodeLimits = new LinkedHashMap<>();
        Map<String, Double> basketLimits = new LinkedHashMap<>();
        for (Map<String, Object> r : sorted(plain.get(LIMIT), "LIMIT_ID")) {
            boolean pf = "PORTFOLIO".equalsIgnoreCase(text(r, "HIERARCHY"));
            (pf ? basketLimits : nodeLimits).put(text(r, "NODE_ID"), number(r, "LIMIT_VAR"));
        }
        List<Basket> baskets = new ArrayList<>();
        for (Map<String, Object> r : sorted(plain.get(BASKET), "PORTFOLIO_ID")) {
            baskets.add(new Basket(text(r, "PORTFOLIO_ID"), text(r, "NAME"), text(r, "TYPE"), text(r, "OWNER"), text(r, "SCOPE")));
        }
        Object view = null;
        for (String name : zeroCopy ? List.of(SCENARIO_PV, PNL_VECTOR) : List.<String>of()) {   // the host's rows of the date, if it holds them
            InputTable t = inputs.table(name);
            if (view == null && t != null && t.present()) {
                view = t.vectorView(ROWS_VIEW);
            }
        }
        return new MarketDay(snapshot, inputs, pv, pnl, List.copyOf(sets), List.copyOf(fx), Collections.unmodifiableMap(nodeLimits),
                Collections.unmodifiableMap(basketLimits), List.copyOf(baskets), Collections.unmodifiableMap(missing), view);
    }

    /** What is missing of one input (the extras' inputs read first, under {@code control}, when asked of them), or null. */
    Missing missing(String input, Control control) throws EngineException {
        Missing m = missing.get(input);
        if (m == null && MarketExtras.SPECS.containsKey(input)) {
            extras(control);
            m = extrasMissing.get(input);
        }
        return m;
    }

    /** True when the date has rows in neither vector input (absent, or delivered empty); found once a session. */
    boolean noVectors() throws EngineException {
        Boolean n = noVectors;
        if (n == null) {
            n = empty(pv, PV_INPUT.key()) && empty(pnl, PNL_INPUT.key());
            noVectors = n;
        }
        return n;
    }

    private volatile Boolean noVectors;

    private static boolean empty(InputTable t, String key) throws EngineException {
        if (t == null) {
            return true;
        }
        long n = t.rows();
        if (n >= 0) {
            return n == 0;
        }
        boolean[] any = {false};                                       // a host that does not count: its key column read once
        t.scan(new Scan(List.of(key), Filter.ALL, 65_536), b -> any[0] |= b.rows() > 0);
        return !any[0];
    }

    /** The table of a usable vector input, or null. */
    InputTable vectors(Vectors which) {
        return which == PV_INPUT ? pv : which == PNL_INPUT ? pnl : null;
    }

    /** The rows the vector inputs are read with (for a budget, before a book is built): their host's count, or -1 when not known yet. */
    long vectorRows() {
        long n = 0;
        for (InputTable t : java.util.Arrays.asList(pv, pnl)) {
            if (t != null) {
                long r = t.rows();
                if (r < 0) {
                    return -1;
                }
                n += r;
            }
        }
        return n;
    }

    /** The inputs beyond the scenario vectors, read at the first output that needs them and kept for the session. */
    MarketExtras extras(Control control) throws EngineException {
        MarketExtras e = extras;
        if (e == null) {
            synchronized (this) {
                e = extras;
                if (e == null) {
                    Map<String, Missing> m = new TreeMap<>();
                    e = MarketExtras.read(snapshot, inputs, m, control);
                    extrasMissing.putAll(m);
                    extras = e;
                }
            }
        }
        return e;
    }

    /**
     * The extras of an output that needs them: read already when the request's missing inputs were named ({@link #missing(String,
     * Control)} for each input the output needs), so this is the kept value.
     */
    MarketExtras extras() {
        MarketExtras e = extras;
        if (e == null) {
            throw new IllegalStateException("the extras are read when a request names what it needs (MarketSession.missing), before any use");
        }
        return e;
    }

    /** The table when it is there with every needed column; otherwise null, with what is missing recorded. */
    private static InputTable usable(String name, Inputs inputs, Snapshot snapshot, Map<String, Missing> missing) {
        InputTable t = inputs.table(name);
        if (!t.present()) {
            missing.put(name, new Missing(name, CONTRACT.get(name), snapshot, List.of(), List.of()));
            return null;
        }
        List<String> lacking = NEEDED.get(name).stream().filter(c -> !t.columns().contains(c)).toList();
        if (!lacking.isEmpty()) {
            missing.put(name, new Missing(name, CONTRACT.get(name), snapshot, lacking, List.of()));
            return null;
        }
        return t;
    }

    private static List<Map<String, Object>> scan(InputTable t, Control control) throws EngineException {
        java.util.TreeSet<String> all = new java.util.TreeSet<>(t.columns());
        all.removeAll(MarketRows.VECTOR_COLUMNS);                       // a vector column is read with vectors(), never as a row value
        List<String> cols = List.copyOf(all);
        List<Map<String, Object>> out = new ArrayList<>();
        t.scan(new Scan(cols, Filter.ALL, 4_096), b -> {
            control.check();
            // each column's array once per batch (a batch builds it on each call), then the rows
            Object[] arrays = new Object[cols.size()];
            for (int k = 0; k < cols.size(); k++) {
                arrays[k] = MarketRows.NUMBERS.contains(cols.get(k)) ? b.numbers(cols.get(k)) : b.texts(cols.get(k));
            }
            for (int i = 0; i < b.rows(); i++) {
                Map<String, Object> m = new HashMap<>();
                for (int k = 0; k < cols.size(); k++) {
                    String c = cols.get(k);
                    if (!b.isNull(c, i)) {
                        m.put(c, arrays[k] instanceof double[] d ? (Object) d[i] : ((String[]) arrays[k])[i]);
                    }
                }
                out.add(m);
            }
        });
        return out;
    }

    private static Map<String, double[]> vectorsOf(InputTable t, String key, String vector, boolean absent, Control control)
            throws EngineException {
        Map<String, double[]> out = new HashMap<>();
        if (absent || !t.present()) {
            return out;
        }
        t.vectors(new VectorScan(vector, List.of(key), Filter.ALL), (id, values, length, texts) -> {
            String k = texts != null && texts.length > 0 && texts[0] != null ? texts[0] : id;
            out.put(k, length < 0 ? null : java.util.Arrays.copyOf(values, length));
        });
        control.check();
        return out;
    }

    private static List<Map<String, Object>> sorted(List<Map<String, Object>> rows, String key) {
        List<Map<String, Object>> s = new ArrayList<>(rows);
        s.sort(Comparator.comparing((Map<String, Object> r) -> String.valueOf(r.get(key))));
        return s;
    }

    static String text(Map<String, Object> r, String c) {
        Object v = r.get(c);
        return v == null || v.toString().isEmpty() ? null : v.toString();
    }

    static double number(Map<String, Object> r, String c) {
        Object v = r.get(c);
        return v instanceof Number n ? n.doubleValue() : Double.NaN;
    }

    /** SCENARIO_DATES as delivered: a JSON-like list ("[\"2024-09-17\", ...]") or dates joined by commas or spaces. */
    static List<LocalDate> dates(Object v) {
        List<LocalDate> out = new ArrayList<>();
        if (v == null) {
            return out;
        }
        for (String s : v.toString().replaceAll("[\\[\\]\"']", " ").split("[,\\s]+")) {
            if (!s.isBlank()) {
                out.add(LocalDate.parse(s.strip()));
            }
        }
        return out;
    }

    /** The scenario set a request means: the one named, else the date's one set that is not a stress set. Null: none. */
    ScenarioSet set(String named) {
        if (named != null && !named.isBlank()) {
            return sets.stream().filter(s -> s.id().equals(named)).findFirst().orElse(null);
        }
        List<ScenarioSet> normal = sets.stream().filter(s -> !"STRESS".equalsIgnoreCase(s.type())).toList();
        return normal.isEmpty() ? null : normal.get(0);
    }

    /** The date's stress scenario set: SET_TYPE STRESS, or (no SET_TYPE) an id with the prefix; null when the date has none. */
    ScenarioSet stressSet(String prefix) {
        for (ScenarioSet s : sets) {
            if ("STRESS".equalsIgnoreCase(s.type()) || (s.type() == null || s.type().isBlank()) && prefix != null && !prefix.isBlank()
                    && s.id().startsWith(prefix)) {
                return s;
            }
        }
        return null;
    }

    /** Bytes held: the FX vectors and the extras once read (the vector inputs' rows are never held). */
    long bytes() {
        long b = 0;
        for (Fx f : fx) {
            b += 64 + 8L * f.rates().length;
        }
        MarketExtras e = extras;
        return b + (e == null ? 0 : e.bytes());
    }
}
