/*
 * Project Drishti · Any data. Any domain. One grammar.
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
package com.ash.risk.ccr.calc;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A credit limit's tenor bucket (EXPOSURE_GUIDE.md section 18.9): its name as stored ({@code 0-1Y}, {@code 1-3Y}, {@code 3M-1Y},
 * {@code 10Y+}), the years it spans ({@code [from, to)}, {@code to} infinite for an open bucket) and its limit. The pre-deal check holds
 * the profile's peak within each bucket to the bucket's own limit.
 */
public record TenorBucket(String name, double from, double to, double limit) {

    private static final Pattern RANGE = Pattern.compile("(\\d+(?:\\.\\d+)?)([DWMY]?)(?:-(\\d+(?:\\.\\d+)?)([DWMY])|(\\+))");

    /** The bucket of a name; null when the name is not a tenor range. */
    public static TenorBucket of(String name, double limit) {
        String n = name == null ? "" : name.trim().toUpperCase(Locale.ROOT).replace(" ", "");
        Matcher m = RANGE.matcher(n);
        if (!m.matches()) {
            return null;
        }
        String u2 = m.group(4) == null ? "Y" : m.group(4);
        double from = years(Double.parseDouble(m.group(1)), m.group(2).isEmpty() ? u2 : m.group(2));
        double to = m.group(5) != null ? Double.POSITIVE_INFINITY : years(Double.parseDouble(m.group(3)), u2);
        return new TenorBucket(name.trim(), from, to, limit);
    }

    private static double years(double n, String unit) {
        return switch (unit) {
            case "D" -> n / 365.0;
            case "W" -> n * 7 / 365.0;
            case "M" -> n / 12.0;
            default -> n;
        };
    }

    /** True when a primary date {@code t} years ahead falls in the bucket. */
    public boolean holds(double t) {
        return t >= from - 1e-12 && t < to - 1e-12;
    }
}
