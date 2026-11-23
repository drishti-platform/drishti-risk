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
package com.ash.risk.market;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Where the moved equivalence tests find Drishti's committed reference data: the system property {@code risk.reference.dir}, an absolute
 * Drishti checkout. Without it the test is skipped with these words (the same tests run in Drishti on its own data until R2's deletion).
 */
public final class ReferenceData {

    private ReferenceData() {
    }

    /** The Drishti checkout, or the calling test is skipped. */
    public static Path drishti() {
        String d = System.getProperty("risk.reference.dir", "");
        assumeTrue(!d.isBlank() && Files.isDirectory(Path.of(d)), "no Drishti reference checkout (-Drisk.reference.dir=/abs/drishti)");
        return Path.of(d).toAbsolutePath().normalize();
    }
}
