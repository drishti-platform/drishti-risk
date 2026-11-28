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
package com.ash.risk.market.engine;

import com.ash.risk.kernel.vector.OrdinalSet;
import com.ash.risk.market.calc.VarBook;
import com.ash.risk.market.calc.VarCalc;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What a session derives from one built book and keeps with it (ENGINE_SPEC 8.6 rule 5: session state, counted in the session's
 * {@code memoryBytes}, released with the book at close; no eviction of its own): each node's and each whole position's measures under
 * the engine's calculator (the order and tail every figure of a node starts from: {@code calc.measures} of the same vector, so the same
 * bits), and the rows each visibility signature may read (the visibility a selection over the book of every row, computed once per
 * signature, not per sum).
 */
final class MarketDerived {

    private final VarBook book;
    private final VarCalc calc;
    private final Map<String, VarCalc.Measures> nodes = new ConcurrentHashMap<>();
    private final Map<Integer, VarCalc.Measures> positions = new ConcurrentHashMap<>();
    private final Map<String, OrdinalSet> visible = new ConcurrentHashMap<>();

    MarketDerived(VarBook book, VarCalc calc) {
        this.book = book;
        this.calc = calc;
    }

    /** The measures of node {@code id}'s aggregate under the engine's calculator. */
    VarCalc.Measures node(String id) {
        return nodes.computeIfAbsent(id, k -> calc.measures(book.aggregate(k)));
    }

    /** The measures of whole position {@code pi}'s vector ({@link VarBook#positionVector}) under the engine's calculator. */
    VarCalc.Measures position(int pi) {
        return positions.computeIfAbsent(pi, k -> calc.measures(book.positionVector(k)));
    }

    /** The rows a visibility signature may read, when made already; else null. */
    OrdinalSet visible(String signature) {
        return visible.get(signature);
    }

    /** Keeps the rows of a signature (two callers racing keep the first; both made the same set). */
    OrdinalSet keepVisible(String signature, OrdinalSet rows) {
        OrdinalSet had = visible.putIfAbsent(signature, rows);
        return had != null ? had : rows;
    }

    /** Bytes held: per measure its order and tail, per visible set its own bytes. */
    long bytes() {
        long b = 0;
        for (Map<?, VarCalc.Measures> m : java.util.List.<Map<?, VarCalc.Measures>>of(nodes, positions)) {
            for (VarCalc.Measures x : m.values()) {
                b += 96 + 4L * x.order().length + 4L * x.tail().length;
            }
        }
        for (OrdinalSet s : visible.values()) {
            b += 64 + s.heapBytes();
        }
        return b;
    }
}
