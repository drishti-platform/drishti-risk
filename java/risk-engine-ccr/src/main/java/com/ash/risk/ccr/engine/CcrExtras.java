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

import com.ash.engine.Control;
import com.ash.engine.EngineException;
import com.ash.engine.Inputs;
import com.ash.engine.Missing;
import com.ash.engine.Snapshot;
import com.ash.risk.ccr.calc.TenorBucket;
import com.ash.risk.ccr.simm.calc.SimmInput;
import com.ash.risk.ccr.simm.calc.SimmParams;
import com.ash.risk.kernel.io.InputRows;
import com.ash.risk.kernel.io.InputRows.Spec;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The CCR engine's inputs beyond the paths and the terms, in two groups each read at the first output that needs it (never at the open of
 * a session that does not ask for it), in the canonical order (each sorted by its key): {@link Limits}, the credit limits with their
 * tenor buckets (the counterparty summary, the tree and the pre-deal check); {@link SimmInputs}, the sensitivity elements with their
 * factors' SIMM mapping, the bank's SIMM parameter files and the FX mids (SIMM). Every one is optional; an output that needs one that is
 * absent answers NOT_FOUND naming it. What a group derives from its rows once (the factors by name, the elements by sensitivity, each
 * version's parsed parameters) is session state, kept with it and released with the session (ENGINE_SPEC 8.6 rule 5).
 */
final class CcrExtras {

    static final String LIMIT = "credit-limit";
    static final String BUCKET = "credit-limit-bucket";
    static final String SENSITIVITY = "sensitivity-element";
    static final String FACTOR = "risk-factor";
    static final String SIMM = "simm-parameters";
    static final String FX_SPOT = "fx-spot";

    static final Map<String, Spec> SPECS = Map.of(
            LIMIT, new Spec(LIMIT, "risk/credit-limit@1", List.of("LIMIT_ID"), List.of("LIMIT_ID", "COUNTERPARTY", "LIMIT"), Set.of("LIMIT")),
            BUCKET, new Spec(BUCKET, "risk/credit-limit-bucket@1", List.of("LIMIT_ID", "BUCKET"), List.of("LIMIT_ID", "BUCKET", "LIMIT"), Set.of("LIMIT")),
            SENSITIVITY, new Spec(SENSITIVITY, "risk/sensitivity-element@1", List.of("SENSITIVITY_ID", "ELEMENT"),
                    List.of("SENSITIVITY_ID", "RISK_FACTOR", "MEASURE", "VALUE"), Set.of("ELEMENT", "VALUE", "IMPLIED_VOL")),
            FACTOR, new Spec(FACTOR, "risk/risk-factor@1", List.of("RISK_FACTOR"), List.of("RISK_FACTOR", "SIMM_RISK_CLASS"), Set.of()),
            SIMM, new Spec(SIMM, "risk/simm-parameters@1", List.of("VERSION"), List.of("VERSION", "TEXT"), Set.of()),
            FX_SPOT, new Spec(FX_SPOT, "market/fx-spot@1", List.of("PAIR"), List.of("PAIR", "MID"), Set.of("MID")));

    /** The inputs of each group. */
    static final List<String> LIMIT_INPUTS = List.of(LIMIT, BUCKET);
    static final List<String> SIMM_INPUTS = List.of(SENSITIVITY, FACTOR, SIMM, FX_SPOT);

    /** A counterparty's credit limit: its id, amount (USD), measure and tenor buckets. */
    record Limit(String id, String counterparty, double amount, String measure, List<TenorBucket> buckets) {}

    private CcrExtras() {
    }

    /** The credit limits by counterparty (the first by id: one per counterparty) and what of them the date does not have. */
    record Limits(Map<String, Limit> limits, Map<String, Missing> missing) {

        static Limits read(Snapshot snapshot, Inputs inputs, Control control) throws EngineException {
            Map<String, Missing> missing = new TreeMap<>();
            List<Map<String, Object>> limitRows = InputRows.read(SPECS.get(LIMIT), inputs, snapshot, missing, control, List.of());
            List<Map<String, Object>> bucketRows = InputRows.read(SPECS.get(BUCKET), inputs, snapshot, missing, control, List.of());
            Map<String, List<TenorBucket>> buckets = new TreeMap<>();
            for (Map<String, Object> r : bucketRows) {
                double v = InputRows.number(r, "LIMIT");
                TenorBucket b = Double.isNaN(v) || v <= 0 ? null : TenorBucket.of(InputRows.text(r, "BUCKET"), v);
                if (b != null) {
                    buckets.computeIfAbsent(InputRows.text(r, "LIMIT_ID"), x -> new ArrayList<>()).add(b);
                }
            }
            Map<String, Limit> limits = new TreeMap<>();
            for (Map<String, Object> r : limitRows) {
                double amount = InputRows.number(r, "LIMIT");
                String cp = InputRows.text(r, "COUNTERPARTY");
                if (cp == null || !(amount > 0)) {
                    continue;
                }
                String id = InputRows.text(r, "LIMIT_ID");
                List<TenorBucket> bs = new ArrayList<>(buckets.getOrDefault(id, List.of()));
                bs.sort((a, b) -> Double.compare(a.from(), b.from()));
                limits.putIfAbsent(cp, new Limit(id, cp, amount, InputRows.text(r, "MEASURE"), List.copyOf(bs)));
            }
            return new Limits(Map.copyOf(limits), Map.copyOf(missing));
        }

        long bytes() {
            return 256L * limits.size();
        }
    }

    /**
     * SIMM's inputs: the sensitivity elements (in their key's order), the factors' SIMM mapping by name, the parameter files by version and
     * the FX mids (USD per unit). Built once from them: the elements' rows by sensitivity id (in row order) and each version's parameters
     * as the method parses them without overrides.
     */
    static final class SimmInputs {
        final List<Map<String, Object>> sensitivities;
        final Map<String, SimmInput.Factor> factors;
        final Map<String, String> simmFiles;
        final Map<String, Double> usdPer;
        final Map<String, Missing> missing;
        /** Each sensitivity id's element rows (their positions in {@link #sensitivities}, ascending). */
        private final Map<String, int[]> bySensitivity;
        private final Map<String, SimmParams> parsed = new java.util.concurrent.ConcurrentHashMap<>();
        private final long bytes;

        private SimmInputs(List<Map<String, Object>> sensitivities, Map<String, SimmInput.Factor> factors, Map<String, String> simmFiles,
                Map<String, Double> usdPer, Map<String, Missing> missing) {
            this.sensitivities = sensitivities;
            this.factors = factors;
            this.simmFiles = simmFiles;
            this.usdPer = usdPer;
            this.missing = missing;
            Map<String, List<Integer>> by = new HashMap<>();
            for (int i = 0; i < sensitivities.size(); i++) {
                String id = InputRows.text(sensitivities.get(i), "SENSITIVITY_ID");
                if (id != null) {
                    by.computeIfAbsent(id, x -> new ArrayList<>()).add(i);
                }
            }
            Map<String, int[]> idx = new HashMap<>(Math.max(16, by.size() * 4 / 3 + 1));
            by.forEach((k, v) -> idx.put(k, v.stream().mapToInt(Integer::intValue).toArray()));
            this.bySensitivity = idx;
            long b = 256L * (sensitivities.size() + factors.size()) + 64L * sensitivities.size();
            for (String f : simmFiles.values()) {
                b += 2L * f.length();
            }
            this.bytes = b;
        }

        static SimmInputs read(Snapshot snapshot, Inputs inputs, Control control) throws EngineException {
            Map<String, Missing> missing = new TreeMap<>();
            Map<String, List<Map<String, Object>>> rows = new TreeMap<>();
            for (String name : SIMM_INPUTS) {
                rows.put(name, InputRows.read(SPECS.get(name), inputs, snapshot, missing, control, List.of()));
            }
            Map<String, SimmInput.Factor> factors = new TreeMap<>();
            for (Map<String, Object> r : rows.get(FACTOR)) {
                String name = InputRows.text(r, "RISK_FACTOR");
                factors.put(name, new SimmInput.Factor(name, InputRows.text(r, "SIMM_RISK_CLASS"), InputRows.text(r, "FACTOR_TYPE"),
                        InputRows.text(r, "SIMM_BUCKET"), InputRows.text(r, "QUALIFIER"), InputRows.text(r, "SIMM_LABEL1"),
                        InputRows.text(r, "SIMM_LABEL2"), InputRows.text(r, "SENIORITY")));
            }
            Map<String, String> files = new TreeMap<>();
            for (Map<String, Object> r : rows.get(SIMM)) {
                files.put(InputRows.text(r, "VERSION"), String.valueOf(r.get("TEXT")));
            }
            Map<String, Double> usdPer = new TreeMap<>();
            usdPer.put("USD", 1.0);
            for (Map<String, Object> r : rows.get(FX_SPOT)) {
                String p = String.valueOf(InputRows.text(r, "PAIR")).replace("FX-", "").replace("/", "").toUpperCase(Locale.ROOT);
                double mid = InputRows.number(r, "MID");
                if (mid > 0 && p.length() == 6 && p.endsWith("USD")) {
                    usdPer.put(p.substring(0, 3), mid);
                } else if (mid > 0 && p.length() == 6 && p.startsWith("USD")) {
                    usdPer.put(p.substring(3), 1 / mid);
                }
            }
            return new SimmInputs(rows.get(SENSITIVITY), java.util.Collections.unmodifiableMap(factors), Map.copyOf(files), Map.copyOf(usdPer),
                    Map.copyOf(missing));
        }

        /** The element rows of these sensitivity ids, in the input's row order. */
        int[] rowsOf(java.util.Collection<String> ids) {
            int n = 0;
            List<int[]> parts = new ArrayList<>();
            for (String id : ids) {
                int[] r = bySensitivity.get(id);
                if (r != null) {
                    parts.add(r);
                    n += r.length;
                }
            }
            int[] out = new int[n];
            int at = 0;
            for (int[] r : parts) {
                System.arraycopy(r, 0, out, at, r.length);
                at += r.length;
            }
            java.util.Arrays.sort(out);
            return out;
        }

        /** A version's parameters as the method parses them without overrides, parsed once (a file it refuses is refused on every ask). */
        SimmParams parameters(String version) {
            SimmParams p = parsed.get(version);
            if (p == null) {
                p = SimmParams.parse(simmFiles.get(version).getBytes(StandardCharsets.UTF_8), Map.of());
                parsed.putIfAbsent(version, p);
            }
            return p;
        }

        long bytes() {
            return bytes;
        }
    }
}
