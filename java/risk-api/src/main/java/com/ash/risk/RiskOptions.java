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
package com.ash.risk;

import com.ash.engine.text.StrictYaml;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * The product's options (confidence, rank rule, hierarchies, linear algebra, …) as one immutable map, read from an absolute file or built
 * in code. The product reads options and never writes a configuration file. In a host the same names are the engines' descriptor
 * options, set in the host's engine file.
 */
public record RiskOptions(Map<String, Object> values) {

    public RiskOptions {
        values = Collections.unmodifiableMap(new TreeMap<>(values));
    }

    /** Reads a YAML file of options (an absolute path); the descriptor's checks apply when the runtime is built. */
    public static RiskOptions read(Path absolute) throws IOException {
        if (!absolute.isAbsolute()) {
            throw new IllegalArgumentException("an absolute path is expected: " + absolute);
        }
        return new RiskOptions(StrictYaml.readMap(Files.readString(absolute, StandardCharsets.UTF_8)));
    }

    public static RiskOptions of(Map<String, Object> values) {
        return new RiskOptions(values);
    }

    /** One option's value, or null. */
    public Object get(String name) {
        return values.get(name);
    }
}
