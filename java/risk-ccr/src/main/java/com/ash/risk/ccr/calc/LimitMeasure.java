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
 * The measure a credit limit is set on (EXPOSURE_GUIDE.md section 18.9): {@code PFE 95 peak} or {@code peak PFE} (with its confidence),
 * {@code EEPE}, {@code EPE}, {@code EE peak}; {@link #of} answers null for one the exposure engine does not compute.
 *
 * @param figure {@code peakPfe}, {@code eepe}, {@code epe} or {@code peakEe}
 * @param q the PFE confidence
 * @param text the measure as stored
 */
public record LimitMeasure(String figure, double q, String text) {

    private static final Pattern PCT = Pattern.compile("(\\d{2}(?:\\.\\d+)?)");

    public static LimitMeasure of(String stored, double defaultQ) {
        String m = stored == null || stored.isBlank() ? "PFE" : stored.trim().toUpperCase(Locale.ROOT);
        if (m.contains("PFE")) {
            Matcher x = PCT.matcher(m);
            double q = x.find() ? Double.parseDouble(x.group(1)) / 100 : defaultQ;
            return q > 0.5 && q < 1 ? new LimitMeasure("peakPfe", q, stored == null ? "peak PFE" : stored.trim()) : null;
        }
        if (m.contains("EEPE")) {
            return new LimitMeasure("eepe", defaultQ, stored.trim());
        }
        if (m.matches(".*\\bEPE\\b.*")) {
            return new LimitMeasure("epe", defaultQ, stored.trim());
        }
        if (m.matches(".*\\bEE\\b.*")) {
            return new LimitMeasure("peakEe", defaultQ, stored.trim());
        }
        return null;
    }

    /** True for a measure read from the profile (bucket limits apply). */
    public boolean profiled() {
        return figure.equals("peakPfe") || figure.equals("peakEe");
    }

    /** The profile column of the measure. */
    public String column() {
        return figure.equals("peakPfe") ? "pfe" : "ee";
    }

    /** The measure's value in a set of statistics (at the one confidence they hold). */
    public double value(ExposureMath.Stats s) {
        return switch (figure) {
            case "peakPfe" -> s.peakPfe[0];
            case "eepe" -> s.eepe;
            case "epe" -> s.epe;
            default -> s.peakEe;
        };
    }
}
