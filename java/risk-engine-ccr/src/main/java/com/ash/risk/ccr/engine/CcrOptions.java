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
import java.util.List;

/** The engine's options, read once at init into the moved code's own method parameters. */
record CcrOptions(ExposureMath.Model model, ExposureMath.Rule rule, double[] confidences, double pfeConfidence, int window, boolean im) {

    @SuppressWarnings("unchecked")
    static CcrOptions read(Options o) {
        List<Object> cs = (List<Object>) o.value("confidences");
        double[] c = new double[cs.size()];
        for (int i = 0; i < c.length; i++) {
            c[i] = ((Number) cs.get(i)).doubleValue();
        }
        return new CcrOptions(ExposureMath.Model.of(o.text("model")),
                "interpolated".equals(o.text("rule")) ? ExposureMath.Rule.INTERPOLATED : ExposureMath.Rule.KTH, c, o.number("pfe-confidence"),
                (int) o.integer("pfe-window"), !"none".equals(o.text("im-model")));
    }

    /** The parameters Drishti's exposure service passes (its goldens: q 0.95, lagged, k-th, {0.95, 0.99}, 21, no set cap). */
    ExposureCalc.Params params() {
        return new ExposureCalc.Params(pfeConfidence, model, rule, confidences, window, 0);
    }
}
