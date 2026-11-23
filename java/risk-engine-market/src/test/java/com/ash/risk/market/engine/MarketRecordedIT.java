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
 * The recorder's cases on the packaged JAR (R2_CUT_PLAN step 1, MC-3): Drishti's own answers at the freeze commit, every double as its raw
 * bits ({@code tck/recorded/market-risk}, written by {@code tools/split/recorder}); the engine must give the same bits for every node,
 * component and basket, 509 and 250 scenarios, USD and EUR, on the three dates.
 */
class MarketRecordedIT {

    @Test
    void theJarGivesDrishtisRecordedBits() throws Exception {
        Path jar = Path.of(System.getProperty("engine.jar"));
        Path recorded = Path.of(System.getProperty("engine.cases")).resolveSibling("recorded");
        List<KitRun.Report> reports = KitRun.jar(jar, new KitRun.Settings(recorded, Path.of(System.getProperty("engine.kit.out")).resolveSibling("kit-recorded"),
                null, null));
        assertThat(reports).extracting(KitRun.Report::engine).containsExactly("market-risk");
        assertThat(reports.get(0).failures().stream().filter(r -> r.group().equals("cases")).toList()).as("recorded cases that differ").isEmpty();
    }
}
