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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Names no host (design 9.1): no source or resource names Drishti's code, a {@code DRS-} code, a {@code drishti.} setting, Spring, or a
 * host word (pack, mnemonic, Sutra). "Drishti Risk", "Drishti Lake" and "Drishti Engine" are the products' names and are allowed by
 * exact phrase. And risk-api depends on engine-api only: its POM names that one dependency, its sources import the JDK, the engine API
 * and its own packages.
 */
class NamesNoHostTest {

    private static final Pattern HOST = Pattern.compile("com[./]ash[./]drishti|\\bDRS-\\d|\\bdrishti\\.[a-z]|org[./]springframework|\\b[Pp]acks?\\b|\\b[Mm]nemonics?\\b|\\bSutras?\\b");

    /**
     * The modules the move (K3) brought, still naming the host until K4 cuts them onto options and visibility (java/SPLIT_EXEMPTIONS.md,
     * section 4): reported, not failed. The set only shrinks.
     */
    static final java.util.Set<String> MOVED_UNTIL_K4 = java.util.Set.of("risk-kernel", "risk-market", "risk-ccr");

    @Test
    void noSourceNamesAHost() throws Exception {
        List<String> out = new ArrayList<>();
        try (Stream<Path> modules = Files.list(Repo.JAVA)) {
            for (Path m : modules.filter(p -> Files.isDirectory(p.resolve("src/main"))).sorted().toList()) {
                if (MOVED_UNTIL_K4.contains(m.getFileName().toString())) {
                    System.out.println("names no host: " + m.getFileName() + " is exempt until K4 (SPLIT_EXEMPTIONS.md)");
                    continue;
                }
                for (Path p : Repo.files(m.resolve("src/main"), ".java", ".yaml", ".properties")) {
                    if (HOST.matcher(Repo.read(p)).find()) {
                        out.add(Repo.rel(p));
                    }
                }
            }
        }
        assertEquals(List.of(), out, "sources that name a host");
    }

    @Test
    void theApiDependsOnTheEngineApiOnly() {
        String pom = Repo.read(Repo.JAVA.resolve("risk-api/pom.xml")).replaceAll("(?s)<!--.*?-->", "").replaceAll("(?s)<parent>.*?</parent>", "");
        Matcher m = Pattern.compile("<artifactId>([^<]+)</artifactId></dependency>").matcher(pom);
        List<String> deps = new ArrayList<>();
        while (m.find()) {
            deps.add(m.group(1));
        }
        assertEquals(List.of("engine-api"), deps);
        List<String> bad = new ArrayList<>();
        Pattern imp = Pattern.compile("(?m)^import (static )?([\\w.]+)");
        for (Path p : Repo.files(Repo.JAVA.resolve("risk-api/src/main"), ".java")) {
            Matcher i = imp.matcher(Repo.read(p));
            while (i.find()) {
                String n = i.group(2);
                if (!n.startsWith("java.") && !n.startsWith("com.ash.engine.") && !n.startsWith("com.ash.risk.")) {
                    bad.add(Repo.rel(p) + " imports " + n);
                }
            }
        }
        assertEquals(List.of(), bad);
    }
}
