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
package com.ash.risk.ccr.engine;

import com.ash.engine.Options;
import com.ash.risk.ccr.calc.ExposureCalc;
import com.ash.risk.ccr.calc.ExposureMath;
import com.ash.risk.kernel.limits.RiskLimits;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The engine's options, read once at init into the moved code's own method parameters: the exposure method, the pre-deal check's alpha
 * and limit rule, and SIMM's product classes by product group.
 */
record CcrOptions(ExposureMath.Model model, ExposureMath.Rule rule, double[] confidences, double pfeConfidence, int window, boolean im, double alpha,
        RiskLimits limits, Map<String, String> productClasses, String defaultProductClass) {

    @SuppressWarnings("unchecked")
    static CcrOptions read(Options o) {
        List<Object> cs = (List<Object>) o.value("confidences");
        double[] c = new double[cs.size()];
        for (int i = 0; i < c.length; i++) {
            c[i] = ((Number) cs.get(i)).doubleValue();
        }
        Map<String, String> pc = new LinkedHashMap<>();
        for (Object r : (List<Object>) o.value("simm-product-classes")) {
            Map<String, Object> m = (Map<String, Object>) r;
            pc.put(String.valueOf(m.get("group")).strip().toUpperCase(Locale.ROOT), String.valueOf(m.get("class")).strip());
        }
        return new CcrOptions(ExposureMath.Model.of(o.text("model")),
                "interpolated".equals(o.text("rule")) ? ExposureMath.Rule.INTERPOLATED : ExposureMath.Rule.KTH, c, o.number("pfe-confidence"),
                (int) o.integer("pfe-window"), !"none".equals(o.text("im-model")), o.number("alpha"), new RiskLimits(o.number("limit-near")),
                Map.copyOf(pc), o.text("simm-default-product-class"));
    }

    /** The SIMM product class of a record's product group (the bank's mapping, SIMM paragraph 6). */
    String productClass(String group) {
        String v = group == null ? null : productClasses.get(group.strip().toUpperCase(Locale.ROOT));
        return v == null ? defaultProductClass : v;
    }

    /** The parameters with another collateral model (the uncollateralised view: {@code none}). */
    ExposureCalc.Params params(ExposureMath.Model other) {
        return new ExposureCalc.Params(pfeConfidence, other, rule, confidences, window, 0);
    }

    /** The parameters Drishti's exposure service passes (its goldens: q 0.95, lagged, k-th, {0.95, 0.99}, 21, no set cap). */
    ExposureCalc.Params params() {
        return new ExposureCalc.Params(pfeConfidence, model, rule, confidences, window, 0);
    }
}
