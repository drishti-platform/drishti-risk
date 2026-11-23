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

import com.ash.engine.Options;
import com.ash.risk.kernel.limits.RiskLimits;
import com.ash.risk.kernel.scope.HierarchySpec;
import com.ash.risk.kernel.scope.PositionRules;
import com.ash.risk.market.calc.VarCalc;
import com.ash.risk.market.calc.ParametricVar;
import com.ash.risk.market.calc.VarMath;
import com.ash.risk.market.frtb.calc.FrtbParams;
import java.time.MonthDay;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The engine's options, read once at init into the moved code's own types: the hierarchies (from promoted columns only), the position
 * rules, the VaR method (confidence, ES confidence, rank rule, component window, scaling) and the summation threads. The settings that
 * Drishti's properties classes held become these options; the old keys are the host's aliases.
 */
record MarketOptions(List<HierarchySpec> hierarchies, PositionRules positions, VarCalc calc, int threads, String linearAlgebra,
        Map<String, String> words, ParametricVar.Settings parametric, String stressedPrefix, Regulatory regulatory, Sensitivities sensitivities,
        RiskLimits limits, FrtbParams frtb, boolean rraoFromPositions) {

    /** The backtest, the stress window selection and the period statistics: Drishti's regulatory settings as options. */
    record Regulatory(int backtestWindow, String count, List<Double> multipliers, int stressLength, int stressTop, MonthDay yearEnd,
            int averageDays, int stressWeeks) {

        /** The capital multiplier of a count (the table's last value for that many or more). */
        double multiplier(int exceptions) {
            return multipliers.get(Math.max(0, Math.min(exceptions, multipliers.size() - 1)));
        }

        /** The count the zone and the multiplier read: actual (the US rule), hypothetical, or the higher of the two. */
        int counted(int actual, int hypothetical) {
            return switch (count) {
                case "hypothetical" -> hypothetical;
                case "higher" -> Math.max(actual, hypothetical);
                default -> actual;
            };
        }
    }

    /** The sensitivity ladder: its buckets ([from, to) in years, in order) and its two-sided limits by "QUALIFIER BUCKET". */
    record Sensitivities(Map<String, double[]> grid, Map<String, Double> limits) {}



    /** The summation chunk Drishti's book and goldens use; part of "the same bits". */
    static final int PARALLEL_ROWS = 4096;
    /** The book's rows per vector chunk, as Drishti's loader and goldens build it. */
    static final int ROWS_PER_CHUNK = 65_536;

    @SuppressWarnings("unchecked")
    static MarketOptions read(Options o) {
        List<HierarchySpec> specs = new ArrayList<>();
        Map<String, String> words = new LinkedHashMap<>();
        for (Object r : (List<Object>) o.value("hierarchies")) {
            Map<String, Object> m = (Map<String, Object>) r;
            String id = String.valueOf(m.get("id"));
            Map<String, Object> spec = new LinkedHashMap<>(m);
            if (m.get("root-id") != null || m.get("root-name") != null) {
                Map<String, Object> root = new LinkedHashMap<>();
                root.put("id", m.get("root-id"));
                root.put("name", m.get("root-name"));
                spec.put("root", root);
            }
            spec.remove("root-id");
            spec.remove("root-name");
            spec.remove("id");
            spec.put("limits", "var-limit");
            HierarchySpec h = HierarchySpec.of(id, spec);
            specs.add(h);
            if (h.word() != null && !h.word().isBlank()) {
                words.put(h.word(), h.id());                                // the scope words, as Drishti's resolver is given them
            }
        }
        HierarchySpec.check(specs);
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("live-statuses", o.value("live-statuses"));
        section.put("rules", o.value("position-rules"));
        PositionRules rules = PositionRules.of(section);
        VarMath.RankRule rule = "interpolated".equals(o.text("rank-rule")) ? VarMath.RankRule.INTERPOLATED : VarMath.RankRule.KTH_WORST;
        VarCalc calc = new VarCalc(o.number("confidence"), o.number("es-confidence"), rule, (int) o.integer("component-window"),
                o.flag("scale-to-var"));
        ParametricVar.Settings pv = new ParametricVar.Settings(ParametricVar.Weighting.of(o.text("pvar-weighting")), o.number("pvar-lambda"));
        List<Double> mult = new ArrayList<>();
        for (Object x : (List<Object>) o.value("backtest-multipliers")) {
            mult.add(((Number) x).doubleValue());
        }
        if (mult.isEmpty()) {
            throw new IllegalArgumentException("backtest-multipliers needs at least one value");
        }
        Regulatory reg = new Regulatory((int) o.integer("backtest-window"), o.text("backtest-count"), List.copyOf(mult),
                (int) o.integer("stress-window-length"), (int) o.integer("stress-window-top"), MonthDay.parse("--" + o.text("fiscal-year-end").strip()),
                (int) o.integer("average-days"), (int) o.integer("stress-weeks"));
        Map<String, double[]> grid = new LinkedHashMap<>();
        double at = 0;
        for (Object r : (List<Object>) o.value("ladder-grid")) {
            Map<String, Object> m = (Map<String, Object>) r;
            double from = ((Number) m.get("from")).doubleValue();
            double to = m.get("to") == null ? Double.POSITIVE_INFINITY : ((Number) m.get("to")).doubleValue();
            if (Math.abs(from - at) > 1e-9 || to <= from) {
                throw new IllegalArgumentException("ladder-grid has a gap or an overlap at " + m.get("bucket") + ": each bucket starts where the last ended");
            }
            grid.put(String.valueOf(m.get("bucket")), new double[] {from, to});
            at = to;
        }
        Map<String, Double> ladderLimits = new LinkedHashMap<>();
        for (Object r : (List<Object>) o.value("ladder-limits")) {
            Map<String, Object> m = (Map<String, Object>) r;
            ladderLimits.put(String.valueOf(m.get("cell")).strip().toUpperCase(java.util.Locale.ROOT), ((Number) m.get("limit")).doubleValue());
        }
        return new MarketOptions(List.copyOf(specs), rules, calc, (int) o.integer("threads"), o.text("linear-algebra"), Map.copyOf(words), pv,
                o.text("stressed-prefix"), reg, new Sensitivities(java.util.Collections.unmodifiableMap(grid), Map.copyOf(ladderLimits)),
                new RiskLimits(o.number("limit-near")), builtInFrtb(), o.flag("rrao-from-positions"));
    }

    /** The JAR's FRTB-SA parameters, checked at init (a missing or broken file refuses the engine's start in words). */
    private static FrtbParams builtInFrtb() {
        return FrtbParams.builtIn();
    }
}
