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

import com.ash.engine.FloatVectors;
import com.ash.engine.RowSet;
import com.ash.risk.kernel.vector.Vectors;
import java.util.List;
import java.util.Map;

/**
 * The host's own float32 rows of a date as the book's vectors (R2_CUT_PLAN 3a, zero copy): the view the host answers to
 * {@code InputTable.vectorView("float32-rows")} ({@code positions}, {@code trades}: {@link FloatVectors} read in place;
 * {@code positionIds}, {@code tradeIds}, {@code scenarioSet}, {@code scenarioDates}, {@code positionChunks}). Every read goes through the
 * host's view (its own lifetime guard: a view read after the host closed the date is refused, never a crash); nothing is copied.
 */
final class HostRows implements Vectors {

    private final FloatVectors v;
    private final int chunks;

    private HostRows(FloatVectors v, int chunks) {
        this.v = v;
        this.chunks = Math.max(1, chunks);
    }

    /** The book with the host's rows, or null when the view is not there, not of this shape or not this book's rows. */
    @SuppressWarnings("unchecked")
    static com.ash.risk.market.calc.VarBook swapIn(Object view, com.ash.risk.market.calc.VarBook book) {
        if (!(view instanceof Map<?, ?> m) || !(m.get("positions") instanceof FloatVectors p)) {
            return null;
        }
        Object dates = m.get("scenarioDates");
        if (!(dates instanceof List<?> d) || d.size() != book.scenarioDates().size()
                || !book.scenarioDates().stream().map(Object::toString).toList().equals(d) || !book.scenarioSet().equals(String.valueOf(m.get("scenarioSet")))) {
            return null;
        }
        int chunks = m.get("positionChunks") instanceof Number n ? n.intValue() : 1;
        FloatVectors t = m.get("trades") instanceof FloatVectors x ? x : null;
        return book.withFloat32Rows(new HostRows(p, chunks), t == null ? null : new HostRows(t, 1), (String[]) m.get("positionIds"),
                (String[]) m.get("tradeIds"));
    }

    @Override
    public int width() {
        return v.width();
    }

    @Override
    public int rows() {
        return v.rows();
    }

    @Override
    public int chunkCount() {
        return chunks;
    }

    @Override
    public long bytes() {
        return (long) v.rows() * v.width() * Float.BYTES;
    }

    @Override
    public double value(int row, int j) {
        return v.value(row, j);
    }

    @Override
    public void copy(int row, double[] out) {
        java.util.Arrays.fill(out, 0, v.width(), 0.0);
        addInto(out, row);
    }

    @Override
    public void addInto(double[] acc, int row) {
        v.addInto(acc, RowSet.range(row, row + 1));
    }

    @Override
    public void addRangeInto(double[] acc, int from, int to) {
        if (from < to) {
            v.addInto(acc, RowSet.range(from, to));
        }
    }
}
