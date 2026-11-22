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
/*
 * Drishti Risk · engines by contract, proven with a JDK only.
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
package com.ash.risk.guards;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.ash.tools.guards.kit.GuardFiles;
import com.ash.tools.guards.kit.HeaderAndLineCap;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Every source carries the copyright header, and no file is longer than the family's cap (one constant). */
class LicenceHeaderAndLineCapTest {

    private static List<Path> sources() {
        return Repo.files(Repo.ROOT, ".java", ".py", "pom.xml", ".md", ".code-workspace", ".run.xml", ".yaml", ".properties");
    }

    @Test
    void everySourceCarriesTheHeader() {
        assertEquals(List.of(), HeaderAndLineCap.withoutHeader(Repo.ROOT, sources()), "files without the copyright header");
    }

    @Test
    void noFileIsOverTheCap() {
        assertEquals(List.of(), HeaderAndLineCap.overCap(Repo.ROOT, sources()), "files over " + GuardFiles.MAX_LINES + " lines");
    }
}
