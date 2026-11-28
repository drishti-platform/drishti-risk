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

import com.ash.engine.Control;
import com.ash.engine.EngineException;
import com.ash.engine.Filter;
import com.ash.engine.InputTable;
import com.ash.engine.Scan;
import com.ash.engine.VectorScan;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * One vector input's rows (scenario PVs or P&amp;L vectors) read in one pass for one use (a book's build, a viewer's selection) and then
 * dropped: the columns by batch into arrays, each vector straight from the host's {@link InputTable#vectors} into the caller's sink with
 * its row's texts and numbers beside it. Nothing delivered is kept beyond the call (ENGINE_SPEC 5.2 rule 9; R2 speed design 2.2): no map
 * per row, no copy of a vector; the host's buffer is read in place for the length of the sink's call.
 */
final class MarketRows {

    /** The numbers of a vector row; everything else is text. */
    static final Set<String> NUMBERS = Set.of("BASE_PV", "PV", "QUANTITY", "RATE", "LIMIT_VAR");
    /** The inputs' vector columns (the descriptor's {@code vector:} of scenario-pv, pnl-vector and fx-scenario). */
    static final Set<String> VECTOR_COLUMNS = Set.of("SCENARIO_PV", "PNL", "RATES");

    /** One delivered row while the sink reads it: its texts by column (an empty text is null) and its numbers (absent: NaN). */
    interface Row {
        String key();

        String text(String column);

        double number(String column);
    }

    /** Receives each row that has a vector, with the host's buffer ({@code values[0..length)}, reused for the next row). */
    @FunctionalInterface
    interface Sink {
        void row(Row row, double[] values, int length) throws EngineException;
    }

    private final String key;
    private final Map<String, String[]> texts = new HashMap<>();
    private final Map<String, double[]> numbers = new HashMap<>();
    private final Map<String, Integer> byKey;
    private final int size;

    private MarketRows(String key, Map<String, List<Object>> parts, int size) {
        this.key = key;
        this.size = size;
        parts.forEach((c, list) -> {
            if (NUMBERS.contains(c)) {
                double[] all = new double[size];
                int at = 0;
                for (Object a : list) {
                    double[] d = (double[]) a;
                    System.arraycopy(d, 0, all, at, d.length);
                    at += d.length;
                }
                numbers.put(c, all);
            } else {
                String[] all = new String[size];
                int at = 0;
                for (Object a : list) {
                    String[] t = (String[]) a;
                    System.arraycopy(t, 0, all, at, t.length);
                    at += t.length;
                }
                texts.put(c, all);
            }
        });
        String[] keys = texts.getOrDefault(key, new String[size]);
        this.byKey = new HashMap<>(Math.max(16, size * 4 / 3 + 1));
        for (int i = 0; i < size; i++) {
            if (keys[i] != null) {
                byKey.put(keys[i], i);                                  // a key delivered twice: its last row (as a map of rows kept it)
            }
        }
    }

    /**
     * The input's columns other than its vector, read by batch: each batch's arrays taken once per column, nulls as null text and NaN.
     * {@code columns}: the columns to read (null: every column the table has); the key is always read.
     */
    static MarketRows read(InputTable t, String key, java.util.Collection<String> columns, Control control) throws EngineException {
        TreeSet<String> all = new TreeSet<>(columns == null ? t.columns() : columns);
        all.retainAll(t.columns());
        all.removeAll(VECTOR_COLUMNS);                                 // a vector column is read with vectors(), never as a row value
        all.add(key);
        List<String> cols = List.copyOf(all);
        Map<String, List<Object>> parts = new HashMap<>();
        int[] size = {0};
        t.scan(new Scan(cols, Filter.ALL, 65_536), b -> {
            control.check();
            int n = b.rows();
            for (String c : cols) {
                Object a;
                if (NUMBERS.contains(c)) {
                    double[] d = Arrays.copyOf(b.numbers(c), n);
                    for (int i = 0; i < n; i++) {
                        if (b.isNull(c, i)) {
                            d[i] = Double.NaN;
                        }
                    }
                    a = d;
                } else {
                    String[] s = Arrays.copyOf(b.texts(c), n);
                    for (int i = 0; i < n; i++) {
                        if (b.isNull(c, i) || s[i] != null && s[i].isEmpty()) {
                            s[i] = null;
                        }
                    }
                    a = s;
                }
                parts.computeIfAbsent(c, k -> new ArrayList<>()).add(a);
            }
            size[0] += n;
        });
        return new MarketRows(key, parts, size[0]);
    }

    int size() {
        return size;
    }

    /** The row of a key, or -1. */
    int row(String k) {
        Integer i = byKey.get(k);
        return i == null ? -1 : i;
    }

    String text(String column, int row) {
        String[] a = texts.get(column);
        return a == null ? null : a[row];
    }

    double number(String column, int row) {
        double[] a = numbers.get(column);
        return a == null ? Double.NaN : a[row];
    }

    /**
     * Each delivered vector of {@code t}'s column {@code vector} with its row, in the host's order (the book's builder fixes the order:
     * a total sort by its columns and the trade id, so the answer does not depend on it). A vector whose key has no row is not a row.
     */
    void eachVector(InputTable t, String vector, Control control, Sink sink) throws EngineException {
        Cursor at = new Cursor();
        EngineException[] failed = {null};
        int[] seen = {0};
        String name = t.name();
        t.vectors(new VectorScan(vector, List.of(key), Filter.ALL), (id, values, length, ks) -> {
            if (failed[0] != null) {
                return;
            }
            if (++seen[0] % 8_192 == 0) {                               // the stage, in words, for a host that shows it (an open, a preparation)
                control.progress(size == 0 ? 0 : Math.min(1, seen[0] / (double) size), String.format(java.util.Locale.ROOT,
                        "reading %,d of %,d rows of %s", seen[0], size, name));
            }
            String k = ks != null && ks.length > 0 && ks[0] != null ? ks[0] : id;
            int r = row(k);
            if (r < 0 || length < 0) {
                return;                                                 // no row, or a null vector: not a row of the book
            }
            at.row = r;
            try {
                sink.row(at, values, length);
            } catch (EngineException e) {
                failed[0] = e;
            }
        });
        control.check();
        if (failed[0] != null) {
            throw failed[0];
        }
    }

    /** The row the sink reads now. */
    private final class Cursor implements Row {
        int row;

        @Override
        public String key() {
            return texts.get(key)[row];
        }

        @Override
        public String text(String column) {
            return MarketRows.this.text(column, row);
        }

        @Override
        public double number(String column) {
            return MarketRows.this.number(column, row);
        }
    }
}
