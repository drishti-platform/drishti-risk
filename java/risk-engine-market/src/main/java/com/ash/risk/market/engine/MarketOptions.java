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
import com.ash.risk.kernel.scope.HierarchySpec;
import com.ash.risk.kernel.scope.PositionRules;
import com.ash.risk.market.calc.VarCalc;
import com.ash.risk.market.calc.VarMath;
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
        Map<String, String> words) {

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
        return new MarketOptions(List.copyOf(specs), rules, calc, (int) o.integer("threads"), o.text("linear-algebra"), Map.copyOf(words));
    }
}
