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
package com.ash.risk.market.calc;

import java.time.LocalDate;
import java.time.MonthDay;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reporting periods over a fiscal year (VAR_GUIDE.md section 18.10): {@code FY2026} (the fiscal year ending in 2026 on the year end),
 * {@code FY2026Q1} (its first quarter) or a range of dates; the fiscal year of a date and the same period a year earlier. Read by the
 * period statistics and the regulatory reports, of the engine and of the moved service alike.
 */
public final class Periods {

    private Periods() {
    }

    /** A period: its name and its first and last day. */
    public record Period(String name, LocalDate from, LocalDate to) {}

    private static final Pattern FY = Pattern.compile("(?i)^FY(\\d{4})(?:Q([1-4]))?$");
    private static final Pattern RANGE = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2})\\.\\.(\\d{4}-\\d{2}-\\d{2})$");
    /**
     * {@code FY2026} (the fiscal year ending in 2026 on the configured year end: 2025-11-01 to 2026-10-31 with {@code 10-31}),
     * {@code FY2026Q1} (its first quarter), or {@code 2026-01-01..2026-03-31}. IllegalArgumentException in words otherwise.
     */
    public static Period period(String text, MonthDay yearEnd) {
        String t = text == null ? "" : text.strip();
        Matcher m = FY.matcher(t);
        if (m.matches()) {
            int y = Integer.parseInt(m.group(1));
            LocalDate end = yearEnd.atYear(y);
            LocalDate start = yearEnd.atYear(y - 1).plusDays(1);
            if (m.group(2) != null) {
                int q = Integer.parseInt(m.group(2));
                LocalDate qs = start.plusMonths(3L * (q - 1));
                return new Period("FY" + y + "Q" + q, qs, qs.plusMonths(3).minusDays(1));
            }
            return new Period("FY" + y, start, end);
        }
        Matcher r = RANGE.matcher(t);
        if (r.matches()) {
            LocalDate a = LocalDate.parse(r.group(1));
            LocalDate b = LocalDate.parse(r.group(2));
            if (b.isBefore(a)) {
                throw new IllegalArgumentException("the period " + t + " ends before it starts");
            }
            return new Period(t, a, b);
        }
        throw new IllegalArgumentException("a period is FY2026 (the fiscal year), FY2026Q1 (a fiscal quarter) or 2026-01-01..2026-03-31, not '"
                + t + "'");
    }

    /** The fiscal year a date is in ({@code FY2026}). */
    public static String fiscalYear(LocalDate d, MonthDay yearEnd) {
        LocalDate end = yearEnd.atYear(d.getYear());
        return "FY" + (d.isAfter(end) ? d.getYear() + 1 : d.getYear());
    }

    /** The same period one year earlier (the comparative of a report). */
    public static Period priorYear(Period p, MonthDay yearEnd) {
        Matcher m = FY.matcher(p.name());
        if (m.matches()) {
            int y = Integer.parseInt(m.group(1)) - 1;
            return period("FY" + y + (m.group(2) == null ? "" : "Q" + m.group(2)), yearEnd);
        }
        return new Period(p.from().minusYears(1) + ".." + p.to().minusYears(1), p.from().minusYears(1), p.to().minusYears(1));
    }
}
