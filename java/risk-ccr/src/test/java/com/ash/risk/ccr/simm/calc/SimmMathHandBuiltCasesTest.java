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
package com.ash.risk.ccr.simm.calc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

import org.junit.jupiter.api.Test;

/** The design's SIMM hand-built cases 6 and 11 (moved from FrtbHandBuiltCasesTest when the market and CCR modules split): to the cent. */
class SimmMathHandBuiltCasesTest {

    static final double CENT = 0.005;

    @Test
    void case6SimmAggregationWithToyParameters() {
        double k = SimmMath.bucketK(new double[] {50 * -1000, 60 * 400}, new double[] {1, 1}, new double[][] {{1, 0.8}, {0.8, 1}}, true);
        assertThat(k).isCloseTo(34_000, offset(CENT));
        assertThat(SimmMath.productClass(new double[] {50_000, 30_000}, new double[][] {{1, 0.3}, {0.3, 1}})).isCloseTo(65_574.39, offset(CENT));
    }

    @Test
    void case11SimmConcentrationPerIssuer() {
        double crX = SimmMath.concentration(-4_000, 1_000);
        double crY = SimmMath.concentration(1_000, 1_000);
        assertThat(crX).isEqualTo(2);
        assertThat(crY).isEqualTo(1);
        double k = SimmMath.bucketK(new double[] {100 * -4_000 * crX, 100 * 1_000 * crY}, new double[] {crX, crY}, new double[][] {{1, 0.4}, {0.4, 1}},
                false);
        assertThat(k).isCloseTo(786_129.76, offset(CENT));
    }

}
