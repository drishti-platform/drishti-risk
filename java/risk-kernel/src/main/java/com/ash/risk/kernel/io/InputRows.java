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
package com.ash.risk.kernel.io;

import com.ash.engine.Batch;
import com.ash.engine.Control;
import com.ash.engine.EngineException;
import com.ash.engine.Filter;
import com.ash.engine.InputTable;
import com.ash.engine.Inputs;
import com.ash.engine.Missing;
import com.ash.engine.Scan;
import com.ash.engine.Snapshot;
import com.ash.engine.VectorScan;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Reads an engine input into plain rows, the one way both engines of the product do it: the table when it is there with every column the
 * engine cannot do without (otherwise what is missing is recorded by name, never substituted), its rows as maps of column to value (a number
 * column as a {@code Double}, everything else as text), sorted by the input's key so that the order of delivery never moves a bit
 * (ENGINE_SPEC 5.2 rule 3). A vector column is read through the vector scan, keyed by the key columns joined by {@code \u0000}.
 */
public final class InputRows {

    private InputRows() {
    }

    /** How one input is read: its name, contract, key (the sort order), the columns it cannot do without and its number columns. */
    public record Spec(String name, String contract, List<String> key, List<String> needed, Set<String> numbers) {
        public Spec {
            key = List.copyOf(key);
            needed = List.copyOf(needed);
            numbers = Set.copyOf(numbers);
        }
    }

    /** The table when it is there with every needed column; otherwise null, with what is missing recorded under its name. */
    public static InputTable usable(Spec s, Inputs inputs, Snapshot snapshot, Map<String, Missing> missing) {
        InputTable t = inputs.table(s.name());
        if (t == null || !t.present()) {
            missing.put(s.name(), new Missing(s.name(), s.contract(), snapshot, List.of(), List.of()));
            return null;
        }
        List<String> lacking = s.needed().stream().filter(c -> !t.columns().contains(c)).toList();
        if (!lacking.isEmpty()) {
            missing.put(s.name(), new Missing(s.name(), s.contract(), snapshot, lacking, List.of()));
            return null;
        }
        return t;
    }

    /** The rows of an input (empty when it is absent or lacks a needed column; then {@code missing} says so), sorted by its key. */
    public static List<Map<String, Object>> read(Spec s, Inputs inputs, Snapshot snapshot, Map<String, Missing> missing, Control control,
            Collection<String> skip) throws EngineException {
        InputTable t = usable(s, inputs, snapshot, missing);
        if (t == null) {
            return List.of();
        }
        List<String> cols = new ArrayList<>(new TreeSet<>(t.columns()));
        cols.removeAll(skip);
        List<Map<String, Object>> out = new ArrayList<>();
        t.scan(new Scan(List.copyOf(cols), Filter.ALL, 4_096), b -> {
            control.check();
            for (int i = 0; i < b.rows(); i++) {
                out.add(row(b, cols, s.numbers(), i));
            }
        });
        out.sort(byKey(s.key()));
        return List.copyOf(out);
    }

    /** The vectors of an input's vector column by its key columns' values joined by {@code \u0000}; a null vector stays null. */
    public static Map<String, double[]> vectors(Spec s, String vector, Inputs inputs, Control control) throws EngineException {
        Map<String, double[]> out = new HashMap<>();
        InputTable t = inputs.table(s.name());
        if (t == null || !t.present() || !t.columns().contains(vector)) {
            return out;
        }
        t.vectors(new VectorScan(vector, s.key(), Filter.ALL), (id, values, length, texts) -> {
            String k = texts == null || texts.length == 0 ? id : String.join("\u0000", Arrays.stream(texts).map(String::valueOf).toList());
            out.put(k, length < 0 ? null : Arrays.copyOf(values, length));
        });
        control.check();
        return out;
    }

    /** A row's key as {@link #vectors} joins it. */
    public static String key(Map<String, Object> row, List<String> key) {
        List<String> parts = new ArrayList<>(key.size());
        for (String k : key) {
            Object v = row.get(k);
            parts.add(v instanceof Double d && d == Math.rint(d) && !Double.isInfinite(d) ? Long.toString(d.longValue()) : String.valueOf(v));
        }
        return String.join("\u0000", parts);
    }

    private static Map<String, Object> row(Batch b, List<String> cols, Set<String> numbers, int i) {
        Map<String, Object> m = new HashMap<>();
        for (String c : cols) {
            if (b.isNull(c, i)) {
                continue;
            }
            m.put(c, numbers.contains(c) ? (Object) number(b, c, i) : text(b, c, i));
        }
        return m;
    }

    /** A number cell, whether the contract carries it as a number or an integer. */
    private static double number(Batch b, String c, int i) {
        try {
            return b.numbers(c)[i];
        } catch (IllegalArgumentException | UnsupportedOperationException e) {
            try {
                return b.integers(c)[i];
            } catch (IllegalArgumentException | UnsupportedOperationException e2) {
                String s = b.texts(c)[i];
                return s == null || s.isBlank() ? Double.NaN : Double.parseDouble(s.strip());
            }
        }
    }

    /** A text cell, whatever the column's carrier (a flag as true/false, an integer without a decimal point). */
    private static String text(Batch b, String c, int i) {
        try {
            return b.texts(c)[i];
        } catch (IllegalArgumentException | UnsupportedOperationException e) {
            try {
                return Long.toString(b.integers(c)[i]);
            } catch (IllegalArgumentException | UnsupportedOperationException e2) {
                try {
                    return Boolean.toString(b.flags(c)[i]);
                } catch (IllegalArgumentException | UnsupportedOperationException e3) {
                    double d = b.numbers(c)[i];
                    return d == Math.rint(d) && !Double.isInfinite(d) ? Long.toString((long) d) : Double.toString(d);
                }
            }
        }
    }

    private static Comparator<Map<String, Object>> byKey(List<String> key) {
        return (x, y) -> {
            for (String k : key) {
                int c = compare(x.get(k), y.get(k));
                if (c != 0) {
                    return c;
                }
            }
            return 0;
        };
    }

    private static int compare(Object a, Object b) {
        if (a == null || b == null) {
            return a == null ? (b == null ? 0 : -1) : 1;
        }
        if (a instanceof Double x && b instanceof Double y) {
            return Double.compare(x, y);
        }
        return String.valueOf(a).compareTo(String.valueOf(b));
    }

    /** A text value of a row: null when absent or empty. */
    public static String text(Map<String, Object> r, String c) {
        Object v = r == null ? null : r.get(c);
        if (v == null) {
            return null;
        }
        String s = v instanceof Double d && d == Math.rint(d) && !Double.isInfinite(d) ? Long.toString(d.longValue()) : v.toString();
        return s.isEmpty() ? null : s;
    }

    /** A number value of a row: NaN when absent or not a number. */
    public static double number(Map<String, Object> r, String c) {
        Object v = r == null ? null : r.get(c);
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v == null) {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(v.toString().strip());
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /** A flag value of a row ({@code true}, {@code Y}, {@code 1}). */
    public static boolean flag(Map<String, Object> r, String c) {
        String s = text(r, c);
        return s != null && (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("y") || s.equals("1") || s.equalsIgnoreCase("yes"));
    }

    /** A list cell as delivered (a JSON-like list or values joined by commas); a null item stays null. */
    public static List<String> list(Object v) {
        List<String> out = new ArrayList<>();
        if (v == null) {
            return out;
        }
        for (String s : v.toString().replaceAll("[\\[\\]\"']", " ").split(",")) {
            String x = s.strip();
            out.add(x.isEmpty() || x.equals("null") ? null : x);
        }
        return out;
    }

    /** Whether a row passes a visibility filter over its text values. */
    public static boolean visible(Filter see, Map<String, Object> row) {
        return see == null || see == Filter.ALL || see.test(c -> text(row, c));
    }
}
