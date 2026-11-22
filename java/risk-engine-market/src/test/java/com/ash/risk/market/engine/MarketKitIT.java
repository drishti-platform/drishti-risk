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

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.engine.tck.KitRun;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The kit on the merged engine JAR drishti-risk-market, as a host meets it: delivery (one JAR, no platform class inside, every reference
 * resolved), the descriptor, the cases under tck/cases/market-risk (hand cases and the recorder's cases from Drishti's goldens), the laws and the
 * lifecycle.
 */
class MarketKitIT {

    @Test
    void theJarPassesTheKit() throws Exception {
        Path jar = Path.of(System.getProperty("engine.jar"));
        List<KitRun.Report> reports = KitRun.jar(jar, new KitRun.Settings(Path.of(System.getProperty("engine.cases")),
                Path.of(System.getProperty("engine.kit.out")), null, null));
        assertThat(reports).extracting(KitRun.Report::engine).containsExactly("market-risk");
        for (KitRun.Report r : reports) {
            assertThat(r.failures()).as(r.label()).isEmpty();
        }
    }
}
