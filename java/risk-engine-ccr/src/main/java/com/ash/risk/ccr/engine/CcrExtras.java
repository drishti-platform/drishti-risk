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
import com.ash.risk.kernel.io.InputRows;
import com.ash.risk.kernel.io.InputRows.Spec;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The CCR engine's inputs beyond the paths and the terms, read once when the session opens in the canonical order (each sorted by its
 * key): the credit limits with their tenor buckets (the pre-deal check), the sensitivity elements with their factors' SIMM mapping, the
 * bank's SIMM parameter files and the FX mids (SIMM). Every one is optional; an output that needs one that is absent answers NOT_FOUND
 * naming it.
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

    /** A counterparty's credit limit: its id, amount (USD), measure and tenor buckets. */
    record Limit(String id, String counterparty, double amount, String measure, List<TenorBucket> buckets) {}

    final Map<String, Limit> limits;
    final List<Map<String, Object>> sensitivities;
    final List<Map<String, Object>> factors;
    final Map<String, String> simmFiles;
    final Map<String, Double> usdPer;
    final Set<String> present;

    private CcrExtras(Map<String, Limit> limits, List<Map<String, Object>> sensitivities, List<Map<String, Object>> factors, Map<String, String> simmFiles,
            Map<String, Double> usdPer, Set<String> present) {
        this.limits = limits;
        this.sensitivities = sensitivities;
        this.factors = factors;
        this.simmFiles = simmFiles;
        this.usdPer = usdPer;
        this.present = present;
    }

    static CcrExtras read(Snapshot snapshot, Inputs inputs, Map<String, Missing> missing, Control control) throws EngineException {
        Map<String, List<Map<String, Object>>> rows = new TreeMap<>();
        for (Spec s : SPECS.values()) {
            rows.put(s.name(), InputRows.read(s, inputs, snapshot, missing, control, List.of()));
        }
        Map<String, List<TenorBucket>> buckets = new TreeMap<>();
        for (Map<String, Object> r : rows.get(BUCKET)) {
            double v = InputRows.number(r, "LIMIT");
            TenorBucket b = Double.isNaN(v) || v <= 0 ? null : TenorBucket.of(InputRows.text(r, "BUCKET"), v);
            if (b != null) {
                buckets.computeIfAbsent(InputRows.text(r, "LIMIT_ID"), x -> new ArrayList<>()).add(b);
            }
        }
        Map<String, Limit> limits = new TreeMap<>();
        for (Map<String, Object> r : rows.get(LIMIT)) {
            double amount = InputRows.number(r, "LIMIT");
            String cp = InputRows.text(r, "COUNTERPARTY");
            if (cp == null || !(amount > 0)) {
                continue;
            }
            String id = InputRows.text(r, "LIMIT_ID");
            List<TenorBucket> bs = new ArrayList<>(buckets.getOrDefault(id, List.of()));
            bs.sort((a, b) -> Double.compare(a.from(), b.from()));
            limits.putIfAbsent(cp, new Limit(id, cp, amount, InputRows.text(r, "MEASURE"), List.copyOf(bs)));   // the first by id: one per counterparty
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
        Set<String> present = new java.util.TreeSet<>(SPECS.keySet());
        present.removeAll(missing.keySet());
        return new CcrExtras(Map.copyOf(limits), rows.get(SENSITIVITY), rows.get(FACTOR), Map.copyOf(files), Map.copyOf(usdPer), Set.copyOf(present));
    }

    long bytes() {
        long b = 256L * (limits.size() + sensitivities.size() + factors.size());
        for (String f : simmFiles.values()) {
            b += 2L * f.length();
        }
        return b;
    }
}
