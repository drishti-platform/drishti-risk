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

/**
 * A scope as text in the product's scope grammar (a position, a book, a node, a basket, any set, "the bank minus a desk":
 * {@code DESK:RATES - BOOK:X1}). The kernel parses and resolves it; the API carries the text.
 */
public record Scope(String text) {

    public Scope {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("a scope is not empty");
        }
        text = text.strip();
    }

    /** The scope of the whole book of the date. */
    public static Scope all() {
        return new Scope("ALL");
    }

    public static Scope parse(String text) {
        return new Scope(text);
    }
}
