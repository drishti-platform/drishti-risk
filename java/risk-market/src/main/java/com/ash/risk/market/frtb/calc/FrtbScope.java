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
package com.ash.risk.market.frtb.calc;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * The sensitivity records' own scope words (FRTB_SA_GUIDE.md section 9): {@code BANK}, {@code DESK:}, {@code BOOK:}, {@code LE:},
 * {@code DIV:}, {@code LL:}, {@code FDESK:}, {@code POS:} and {@code TRD:}, each with values joined by commas, the terms joined by
 * {@code +} and {@code −} (a record named by a minus term is out whatever else names it). The one reading of them, for the moved service
 * and the engine alike.
 */
public final class FrtbScope {

    private FrtbScope() {
    }

    /**
     * Whether a record is in a scope.
     *
     * @param positionKey the record's position (its POSITION_ID, else its trade id)
     * @param tradeId its trade id (null for a position record)
     * @param tradeIds the trades a position record covers
     * @param attribute its columns ({@code DESK}, {@code TT_BOOK}, ...)
     * @throws IllegalArgumentException a scope word that is not one of these, in words
     */
    public static boolean matches(String scope, String positionKey, String tradeId, Collection<String> tradeIds, Function<String, String> attribute) {
        boolean in = false;
        String s = scope.strip().replace(" - ", " -").replace(" + ", " +");
        for (String term : s.split("\\s+")) {
            boolean minus = term.startsWith("-");
            String t = term.replaceFirst("^[+-]", "");
            if (t.isEmpty()) {
                continue;
            }
            boolean hit = term(t, positionKey, tradeId, tradeIds, attribute);
            if (minus && hit) {
                return false;
            }
            in |= !minus && hit;
        }
        return in;
    }

    private static boolean term(String t, String positionKey, String tradeId, Collection<String> tradeIds, Function<String, String> attribute) {
        String u = t.toUpperCase(Locale.ROOT);
        if (u.equals("BANK")) {
            return true;
        }
        int c = t.indexOf(':');
        if (c < 0) {
            return false;
        }
        String word = u.substring(0, c);
        List<String> vals = List.of(t.substring(c + 1).split(","));
        String field = switch (word) {
            case "DESK" -> "DESK";
            case "BOOK" -> "TT_BOOK";
            case "LE" -> "LEGAL_ENTITY";
            case "DIV" -> "DIVISION";
            case "LL" -> "LIMIT_LETTER";
            case "FDESK" -> "FRTB_DESK";
            case "POS" -> "#POS";
            case "TRD" -> "#TRD";
            default -> null;
        };
        if (field == null) {
            throw new IllegalArgumentException("the scope word " + word + " is not one of the records' own: a scope is BANK, DESK:, BOOK:, LE:,"
                    + " DIV:, LL:, FDESK:, POS: or TRD:");
        }
        if (field.equals("#POS")) {
            return vals.contains(positionKey);
        }
        if (field.equals("#TRD")) {
            return vals.contains(tradeId) || tradeIds != null && tradeIds.stream().anyMatch(vals::contains);
        }
        String v = attribute.apply(field);
        return v != null && vals.stream().anyMatch(x -> x.equalsIgnoreCase(v));
    }
}
