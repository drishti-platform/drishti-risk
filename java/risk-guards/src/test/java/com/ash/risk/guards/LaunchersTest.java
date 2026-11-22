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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The IDE launchers follow the platform's launcher standard (the same rules as tools/launchers/check_launchers.py of the other
 * product repositories): every launcher is in {@code run-configs/} at the root, is named {@code Risk: <verb> [<what>]}, its file the
 * same without the colon, sits in the IntelliJ folder {@code Risk}, points at files that exist, and has a VS Code task or launch of
 * the same name in the workspace file (and the other way round).
 */
class LaunchersTest {

    private static final String PRODUCT = "Risk";
    private static final Pattern NAME = Pattern.compile(
            PRODUCT + ": (build|test|check|install|run|lint|bundle|maintain|bench)( [^:]+)?");
    private static final Pattern CONFIG_NAME = Pattern.compile("<configuration[^>]* name=\"([^\"]*)\"");
    private static final Pattern FOLDER = Pattern.compile("folderName=\"([^\"]*)\"");
    private static final Pattern WORKDIR = Pattern.compile("name=\"workingDirPath\" value=\"([^\"]*)\"");
    private static final Pattern SHELL = Pattern.compile("\\.(sh|cmd|ps1|bat)\\b");
    private static final Pattern LABEL = Pattern.compile("\"(?:label|name)\": \"" + PRODUCT + ": [^\"]*\"");

    private static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void launchersFollowTheStandard() throws IOException {
        List<String> errs = new ArrayList<>();
        Path rc = Repo.ROOT.resolve("run-configs");
        List<String> names = new ArrayList<>();
        try (Stream<Path> s = Files.list(rc)) {
            for (Path f : s.filter(p -> p.getFileName().toString().endsWith(".run.xml")).sorted().toList()) {
                String text = read(f);
                String file = f.getFileName().toString();
                Matcher m = CONFIG_NAME.matcher(text);
                if (!m.find()) {
                    errs.add(file + ": no configuration name");
                    continue;
                }
                String name = m.group(1);
                names.add(name);
                if (!NAME.matcher(name).matches()) {
                    errs.add(file + ": name '" + name + "' is not 'Risk: <verb> [<what>]'");
                }
                if (!file.equals(name.replace(":", "") + ".run.xml")) {
                    errs.add(file + ": the file is named after the launcher without the colon");
                }
                Matcher fo = FOLDER.matcher(text);
                if (!fo.find() || !PRODUCT.equals(fo.group(1))) {
                    errs.add(file + ": folderName is not " + PRODUCT);
                }
                if (SHELL.matcher(text.replaceAll("(?s)<!--.*?-->", "")).find()) {
                    errs.add(file + ": a shell script: Java, Python and Maven only");
                }
                Matcher wd = WORKDIR.matcher(text);
                if (wd.find()) {
                    Path dir = Path.of(wd.group(1).replace("$PROJECT_DIR$", Repo.ROOT.toString()));
                    if (!Files.isRegularFile(dir.resolve("pom.xml"))) {
                        errs.add(file + ": " + dir + " has no pom.xml");
                    }
                }
            }
        }
        for (String std : List.of("build all", "test", "install release (tag only)")) {
            assertTrue(names.contains(PRODUCT + ": " + std), "the standard launcher is missing: " + std);
        }
        try (Stream<Path> s = Files.walk(Repo.ROOT)) {
            s.filter(p -> p.getFileName().toString().equals("run-configs") && !p.equals(rc) && Files.isDirectory(p)
                    && !p.toString().contains(Path.of("/target/").toString()) && !p.toString().contains(".git"))
                    .forEach(p -> errs.add(p + ": launchers live in run-configs/ at the repository root only"));
        }
        String ws = read(Repo.ROOT.resolve("drishti-risk.code-workspace")).replaceAll("(?m)^//.*$", "");
        List<String> wsNames = new ArrayList<>();
        Matcher m = LABEL.matcher(ws);
        while (m.find()) {
            String full = m.group();
            wsNames.add(full.substring(full.indexOf(": \"") + 3, full.length() - 1));
        }
        for (String n : wsNames) {
            if (!names.contains(n)) {
                errs.add("drishti-risk.code-workspace: '" + n + "' has no run-configs launcher of that name");
            }
        }
        for (String n : names) {
            if (!wsNames.contains(n)) {
                errs.add("drishti-risk.code-workspace: the launcher '" + n + "' has no VS Code task of that name");
            }
        }
        assertTrue(ws.contains("JavaSE-21"), "JavaSE-21 is configured in the workspace");
        assertEquals(List.of(), errs);
    }
}
