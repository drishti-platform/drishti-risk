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

import com.ash.engine.Batch;
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
 * One business date's inputs, as delivered, kept in a canonical order: every input's rows sorted by its key. The order of delivery is free
 * (ENGINE_SPEC 5.2 rule 3) and a sum in floating point depends on order, so the engine fixes it here; the reference recorder feeds
 * Drishti's own book in the same order, which is what makes the two comparable bit for bit.
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

    /** The numbers of a vector row; everything else is text. */
    private static final Set<String> NUMBERS = Set.of("BASE_PV", "PV", "QUANTITY", "RATE", "LIMIT_VAR");
    /** The columns without which an input cannot be used (the rest are optional attributes). */
    private static final Map<String, List<String>> NEEDED = Map.of(
            SCENARIO_PV, List.of("SCENARIO_PV_ID", "SCENARIO_SET_ID", "CURRENCY", "BASE_PV", "SCENARIO_PV"),
            PNL_VECTOR, List.of("VECTOR_ID", "SCENARIO_SET_ID", "PNL"),
            SCENARIO_SET, List.of("SCENARIO_SET_ID", "SCENARIO_DATES"),
            FX, List.of("FX_SCENARIO_ID", "SCENARIO_SET_ID", "CURRENCY", "RATE", "RATES"),
            LIMIT, List.of("HIERARCHY", "NODE_ID", "LIMIT_VAR"),
            BASKET, List.of("PORTFOLIO_ID", "SCOPE"));

    /** A row of a vector input: its key, its texts by column, its two numbers and its vector. */
    record Row(String key, Map<String, String> texts, double pv, double quantity, double[] vector) {
        String text(String column) {
            String v = texts.get(column);
            return v == null || v.isEmpty() ? null : v;
        }
    }

    record ScenarioSet(String id, String type, List<LocalDate> dates) {}

    record Fx(String set, String currency, double rate, double[] rates) {}

    record Basket(String id, String name, String type, String owner, String scope) {}

    final Snapshot snapshot;
    final LocalDate date;
    final List<Row> pv;
    final List<Row> pnl;
    final List<ScenarioSet> sets;
    final List<Fx> fx;
    final Map<String, Double> nodeLimits;
    final Map<String, Double> basketLimits;
    final List<Basket> baskets;
    /** Inputs absent or lacking columns, by input name. */
    final Map<String, Missing> missing;

    private MarketDay(Snapshot snapshot, List<Row> pv, List<Row> pnl, List<ScenarioSet> sets, List<Fx> fx, Map<String, Double> nodeLimits,
            Map<String, Double> basketLimits, List<Basket> baskets, Map<String, Missing> missing) {
        this.snapshot = snapshot;
        this.date = LocalDate.parse(snapshot.dimensions().get("date"));
        this.pv = pv;
        this.pnl = pnl;
        this.sets = sets;
        this.fx = fx;
        this.nodeLimits = nodeLimits;
        this.basketLimits = basketLimits;
        this.baskets = baskets;
        this.missing = missing;
    }

    static MarketDay read(Snapshot snapshot, Inputs inputs, Control control) throws EngineException {
        Map<String, Missing> missing = new TreeMap<>();
        Map<String, List<Map<String, Object>>> plain = new HashMap<>();
        for (String name : List.of(SCENARIO_SET, FX, LIMIT, BASKET)) {
            InputTable t = usable(name, inputs, snapshot, missing);
            plain.put(name, t == null ? List.of() : scan(t, control));
        }
        List<Row> pv = vectors(SCENARIO_PV, "SCENARIO_PV_ID", "SCENARIO_PV", "BASE_PV", inputs, snapshot, missing, control);
        List<Row> pnl = vectors(PNL_VECTOR, "VECTOR_ID", "PNL", "PV", inputs, snapshot, missing, control);
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
        return new MarketDay(snapshot, pv, pnl, List.copyOf(sets), List.copyOf(fx), Collections.unmodifiableMap(nodeLimits),
                Collections.unmodifiableMap(basketLimits), List.copyOf(baskets), Collections.unmodifiableMap(missing));
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
        List<String> cols = List.copyOf(new java.util.TreeSet<>(t.columns()));
        List<Map<String, Object>> out = new ArrayList<>();
        t.scan(new Scan(cols, Filter.ALL, 4_096), b -> {
            control.check();
            for (int i = 0; i < b.rows(); i++) {
                out.add(row(b, cols, i));
            }
        });
        return out;
    }

    private static Map<String, Object> row(Batch b, List<String> cols, int i) {
        Map<String, Object> m = new HashMap<>();
        for (String c : cols) {
            if (b.isNull(c, i)) {
                continue;
            }
            m.put(c, NUMBERS.contains(c) ? (Object) b.numbers(c)[i] : b.texts(c)[i]);
        }
        return m;
    }

    private static List<Row> vectors(String name, String key, String vector, String pvColumn, Inputs inputs, Snapshot snapshot,
            Map<String, Missing> missing, Control control) throws EngineException {
        InputTable t = usable(name, inputs, snapshot, missing);
        if (t == null) {
            return List.of();
        }
        List<Map<String, Object>> rows = scan(t, control);
        Map<String, double[]> v = vectorsOf(t, key, vector, false, control);
        List<Row> out = new ArrayList<>(rows.size());
        for (Map<String, Object> r : sorted(rows, key)) {
            Map<String, String> texts = new HashMap<>();
            r.forEach((c, x) -> {
                if (x instanceof String s) {
                    texts.put(c, s);
                }
            });
            String id = text(r, key);
            out.add(new Row(id, Map.copyOf(texts), number(r, pvColumn), number(r, "QUANTITY"), v.get(id)));
        }
        return List.copyOf(out);
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

    /** Bytes held by the rows (vectors dominate). */
    long bytes() {
        long b = 0;
        for (List<Row> l : List.of(pv, pnl)) {
            for (Row r : l) {
                b += 64 + 48L * r.texts().size() + (r.vector() == null ? 0 : 8L * r.vector().length);
            }
        }
        for (Fx f : fx) {
            b += 64 + 8L * f.rates().length;
        }
        return b;
    }
}
