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
package com.ash.risk.guards;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Every line of business is isolated (owner, 2026-11-22): {@code risk-<lob>} and {@code risk-engine-<lob>} depend on {@code risk-api} and
 * {@code risk-kernel} only, never on another line of business, by POM and by source; the kernel names no line of business; each line's
 * classes live in {@code com.ash.risk.<lob>}. The exemptions the move left (java/SPLIT_EXEMPTIONS.md, section 4) are listed here file by
 * file and only shrink: an exempt file that no longer crosses fails too, so its row is deleted with the fix.
 */
class LineOfBusinessIsolationTest {

    /** The lines of business; a new one is added here with its two modules. */
    static final List<String> LINES = List.of("market", "ccr", "liquidity", "climate");

    /** Files that still name another line of business, until K6 (SPLIT_EXEMPTIONS.md section 4). */
    static final Set<String> CROSSING_UNTIL_K6 = Set.of(
            "java/risk-ccr/src/main/java/com/ash/risk/ccr/exposure/ExposureLoader.java",
            "java/risk-ccr/src/main/java/com/ash/risk/ccr/simm/SimmData.java",
            "java/risk-ccr/src/main/java/com/ash/risk/ccr/simm/SimmService.java",
            "java/risk-ccr/src/test/java/com/ash/risk/ccr/simm/SimmTestData.java",
            "java/risk-market/src/test/java/com/ash/risk/market/frtb/calc/FrtbHandBuiltCasesTest.java");

    private static final Map<String, String> MODULE_OF = Map.of("market", "risk-market", "ccr", "risk-ccr", "liquidity", "risk-liquidity",
            "climate", "risk-climate");

    @Test
    void aLineDependsOnTheApiAndTheKernelOnly() {
        List<String> bad = new ArrayList<>();
        for (String lob : LINES) {
            for (String module : List.of("risk-" + lob, "risk-engine-" + lob)) {
                Path pom = Repo.JAVA.resolve(module).resolve("pom.xml");
                if (!Files.exists(pom)) {
                    bad.add(module + ": the module is missing");
                    continue;
                }
                String text = Repo.read(pom).replaceAll("(?s)<!--.*?-->", "").replaceAll("(?s)<parent>.*?</parent>", "");
                Matcher m = Pattern.compile("<groupId>com\\.ash\\.risk</groupId><artifactId>([^<]+)</artifactId>").matcher(text);
                while (m.find()) {
                    String dep = m.group(1);
                    boolean ok = dep.equals("risk-api") || dep.equals("risk-kernel") || module.startsWith("risk-engine-") && dep.equals("risk-" + lob);
                    if (!ok) {
                        bad.add(module + " depends on " + dep);
                    }
                }
            }
        }
        assertEquals(List.of(), bad, "a line of business depends on another (or on more than the API and the kernel)");
    }

    @Test
    void noSourceNamesAnotherLine() {
        List<String> bad = new ArrayList<>();
        Set<String> stillCrossing = new TreeSet<>();
        for (String lob : LINES) {
            for (String module : List.of("risk-" + lob, "risk-engine-" + lob)) {
                Path dir = Repo.JAVA.resolve(module).resolve("src");
                if (!Files.isDirectory(dir)) {
                    continue;
                }
                for (Path p : Repo.files(dir, ".java")) {
                    String src = Repo.read(p);
                    String rel = Repo.rel(p);
                    for (String other : LINES) {
                        if (!other.equals(lob) && Pattern.compile("\\bcom\\.ash\\.risk\\." + other + "\\b").matcher(src).find()) {
                            if (CROSSING_UNTIL_K6.contains(rel)) {
                                stillCrossing.add(rel);
                            } else {
                                bad.add(rel + " names com.ash.risk." + other);
                            }
                        }
                    }
                    if (!Pattern.compile("^package com\\.ash\\.risk\\." + lob + "(\\.|;)", Pattern.MULTILINE).matcher(src).find()) {
                        bad.add(rel + " is not in com.ash.risk." + lob);
                    }
                }
            }
        }
        assertEquals(List.of(), bad, "sources that cross lines of business");
        Set<String> healed = new TreeSet<>(CROSSING_UNTIL_K6);
        healed.removeAll(stillCrossing);
        assertEquals(Set.of(), healed, "exempt files that no longer cross: delete their rows here and in SPLIT_EXEMPTIONS.md");
    }

    @Test
    void theKernelNamesNoLine() {
        List<String> bad = new ArrayList<>();
        for (Path p : Repo.files(Repo.JAVA.resolve("risk-kernel/src"), ".java")) {
            String src = Repo.read(p);
            for (String lob : LINES) {
                if (Pattern.compile("\\bcom\\.ash\\.risk\\." + lob + "\\b").matcher(src).find()) {
                    bad.add(Repo.rel(p) + " names com.ash.risk." + lob);
                }
            }
        }
        String pom = Repo.read(Repo.JAVA.resolve("risk-kernel/pom.xml"));
        for (String module : MODULE_OF.values()) {
            if (pom.contains("<artifactId>" + module + "</artifactId>")) {
                bad.add("risk-kernel/pom.xml depends on " + module);
            }
        }
        assertEquals(List.of(), bad, "the kernel names a line of business");
    }
}
