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
package com.ash.risk.ccr.engine;

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
import com.ash.risk.ccr.calc.CsaTerms;
import com.ash.risk.ccr.calc.ExposureBook;
import com.ash.risk.ccr.calc.ExposureGrid;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * One business date's CCR inputs in a canonical order (paths sorted by trade and grid index; every other input by its key), and the
 * exposure book built from them per visibility, by Drishti's own builder. Netting sets without a CSA are unmargined; a balance's
 * holdings are its NET (posted negative), as the loader reads a balance without positions.
 *
 * <p>The paths are read in one pass each: the columns by batch into one array a column, each delivered vector copied once from the host's
 * buffer into its row; no map a row, no second copy. They are the session's one copy of the trades' paths (a set cut by a scope, a trade's
 * share and a what-if copy read them by trade through an index built once), counted in the session's memory. The limits and SIMM's inputs
 * are read at the first output that needs them ({@link CcrExtras}).
 */
final class CcrDay {

    static final String RUN = "simulation-run";
    static final String PATH = "exposure-path";
    static final String SET = "netting-set";
    static final String CSA = "csa";
    static final String BALANCE = "collateral-balance";
    static final String VALUATION = "trade-valuation";
    static final Map<String, String> CONTRACT = Map.of(RUN, "risk/simulation-run@1", PATH, "risk/exposure-path@1", SET, "risk/netting-set@1",
            CSA, "risk/csa@1", BALANCE, "risk/collateral-balance@1", VALUATION, "risk/trade-valuation@1");
    private static final Set<String> NUMBERS = Set.of("PATHS", "GRID_INDEX", "NOTIONAL", "THRESHOLD_THEM", "THRESHOLD_US", "MTA", "MTA_THEM",
            "MTA_US", "INDEPENDENT_AMOUNT", "ROUNDING", "MPOR_DAYS", "MARGIN_FREQUENCY_DAYS", "NET", "IM_HELD", "MTM");
    private static final Map<String, List<String>> NEEDED = Map.of(RUN, List.of("RUN_ID", "PATHS", "GRID_DATES"),
            PATH, List.of("TRADE_ID", "GRID_INDEX", "MTM"), SET, List.of("NETTING_SET_ID"), CSA, List.of("CSA_ID"), BALANCE, List.of("BALANCE_ID"),
            VALUATION, List.of("TRADE_ID", "MTM"));

    final Snapshot snapshot;
    final LocalDate date;
    final Map<String, Object> run;
    final PathRows paths;
    final Map<String, Map<String, Object>> sets;
    final Map<String, Map<String, Object>> csas;
    final Map<String, Map<String, Object>> balances;
    final Map<String, Double> mtm0;
    private final Map<String, Missing> missing;
    private final Inputs inputs;
    private final long bytes;
    private volatile ExposureGrid grid;
    private volatile CcrExtras.Limits limits;
    private volatile CcrExtras.SimmInputs simm;

    private CcrDay(Snapshot snapshot, Inputs inputs, Map<String, Object> run, PathRows paths, Map<String, Map<String, Object>> sets,
            Map<String, Map<String, Object>> csas, Map<String, Map<String, Object>> balances, Map<String, Double> mtm0, Map<String, Missing> missing) {
        this.snapshot = snapshot;
        this.inputs = inputs;
        this.date = LocalDate.parse(snapshot.dimensions().get("date"));
        this.run = run;
        this.paths = paths;
        this.sets = sets;
        this.csas = csas;
        this.balances = balances;
        this.mtm0 = mtm0;
        this.missing = missing;
        this.bytes = paths.bytes() + 256L * (sets.size() + csas.size() + balances.size() + mtm0.size());
    }

    static CcrDay read(Snapshot snapshot, Inputs inputs, Control control) throws EngineException {
        Map<String, Missing> missing = new TreeMap<>();
        Map<String, List<Map<String, Object>>> rows = new HashMap<>();
        PathRows paths = PathRows.EMPTY;
        for (String name : List.of(RUN, PATH, SET, CSA, BALANCE, VALUATION)) {
            InputTable t = inputs.table(name);
            if (!t.present()) {
                missing.put(name, new Missing(name, CONTRACT.get(name), snapshot, List.of(), List.of()));
                rows.put(name, List.of());
                continue;
            }
            List<String> lacking = NEEDED.get(name).stream().filter(c -> !t.columns().contains(c)).toList();
            if (!lacking.isEmpty()) {
                missing.put(name, new Missing(name, CONTRACT.get(name), snapshot, lacking, List.of()));
                rows.put(name, List.of());
                continue;
            }
            List<String> cols = t.columns().stream().filter(c -> !(PATH.equals(name) && "MTM".equals(c))).sorted().toList();
            if (PATH.equals(name)) {
                paths = PathRows.read(t, cols, control);
                continue;
            }
            List<Map<String, Object>> out = new ArrayList<>();
            t.scan(new Scan(cols, Filter.ALL, 4_096), b -> {
                control.check();
                for (int i = 0; i < b.rows(); i++) {
                    out.add(row(b, cols, i));
                }
            });
            rows.put(name, out);
        }
        Map<String, Object> run = rows.get(RUN).stream().min(Comparator.comparing(r -> String.valueOf(r.get("RUN_ID")))).orElse(null);
        Map<String, Double> mtm0 = new TreeMap<>();
        rows.get(VALUATION).forEach(r -> mtm0.put(String.valueOf(r.get("TRADE_ID")), num(r, "MTM")));
        return new CcrDay(snapshot, inputs, run, paths, byKey(rows.get(SET), "NETTING_SET_ID"), byKey(rows.get(CSA), "CSA_ID"),
                byKey(rows.get(BALANCE), "BALANCE_ID"), mtm0, missing);
    }

    /** What the date does not have of one input (null when it has it): the limits and SIMM's inputs read at this first question about them. */
    Missing missing(String input, Control control) throws EngineException {
        if (CcrExtras.LIMIT_INPUTS.contains(input)) {
            return limits(control).missing().get(input);
        }
        if (CcrExtras.SIMM_INPUTS.contains(input)) {
            return simm(control).missing.get(input);
        }
        return missing.get(input);
    }

    /** The credit limits, read at the first output that needs them. */
    CcrExtras.Limits limits(Control control) throws EngineException {
        CcrExtras.Limits l = limits;
        if (l == null) {
            synchronized (this) {
                l = limits;
                if (l == null) {
                    l = CcrExtras.Limits.read(snapshot, inputs, control);
                    limits = l;
                }
            }
        }
        return l;
    }

    /** SIMM's inputs, read at the first output that needs them. */
    CcrExtras.SimmInputs simm(Control control) throws EngineException {
        CcrExtras.SimmInputs s = simm;
        if (s == null) {
            synchronized (this) {
                s = simm;
                if (s == null) {
                    s = CcrExtras.SimmInputs.read(snapshot, inputs, control);
                    simm = s;
                }
            }
        }
        return s;
    }

    private static Map<String, Map<String, Object>> byKey(List<Map<String, Object>> rows, String key) {
        Map<String, Map<String, Object>> m = new TreeMap<>();
        rows.forEach(r -> m.put(String.valueOf(r.get(key)), r));
        return m;
    }

    private static Map<String, Object> row(Batch b, List<String> cols, int i) {
        Map<String, Object> m = new HashMap<>();
        for (String c : cols) {
            if (!b.isNull(c, i)) {
                m.put(c, NUMBERS.contains(c) ? (Object) b.numbers(c)[i] : b.texts(c)[i]);
            }
        }
        return m;
    }

    static double num(Map<String, Object> r, String c) {
        Object v = r == null ? null : r.get(c);
        return v instanceof Number n ? n.doubleValue() : v == null ? Double.NaN : Double.parseDouble(v.toString());
    }

    static String text(Map<String, Object> r, String c) {
        Object v = r == null ? null : r.get(c);
        return v == null || v.toString().isEmpty() ? null : v.toString();
    }

    /**
     * The exposure paths of the date, one row a trade and grid index, sorted by trade and grid index: each column one array (a text column's
     * null cells null; a number column's null cells absent), each row's vector as delivered (null when the row has none).
     */
    static final class PathRows {
        static final PathRows EMPTY = new PathRows(new String[0], new int[0], new double[0][], Map.of(), Map.of(), Map.of());

        final String[] trade;
        final int[] k;
        final double[][] mtm;
        private final Map<String, String[]> texts;
        private final Map<String, double[]> numbers;
        private final Map<String, boolean[]> present;
        /** Each trade's rows: from, to (one past). */
        private final Map<String, int[]> byTrade = new HashMap<>();

        private PathRows(String[] trade, int[] k, double[][] mtm, Map<String, String[]> texts, Map<String, double[]> numbers, Map<String, boolean[]> present) {
            this.trade = trade;
            this.k = k;
            this.mtm = mtm;
            this.texts = texts;
            this.numbers = numbers;
            this.present = present;
            for (int i = 0; i < trade.length; ) {
                int j = i;
                while (j < trade.length && trade[j].equals(trade[i])) {
                    j++;
                }
                byTrade.put(trade[i], new int[] {i, j});
                i = j;
            }
        }

        int size() {
            return trade.length;
        }

        /** The cell of a column as the row's text: null when absent or empty; a number as {@link Double#toString}. */
        String text(String column, int row) {
            String[] t = texts.get(column);
            if (t != null) {
                return t[row] == null || t[row].isEmpty() ? null : t[row];
            }
            double[] d = numbers.get(column);
            return d == null || !present.get(column)[row] ? null : Double.toString(d[row]);
        }

        /** The cell of a number column; NaN when absent. */
        double number(String column, int row) {
            double[] d = numbers.get(column);
            return d == null || !present.get(column)[row] ? Double.NaN : d[row];
        }

        /** The rows of a trade (from, to one past), or null. */
        int[] rowsOf(String t) {
            return byTrade.get(t);
        }

        long bytes() {
            long b = 128L * trade.length;
            for (double[] m : mtm) {
                b += m == null ? 0 : 8L * m.length;
            }
            return b;
        }

        /**
         * Reads the input's columns by batch and its vectors in one pass each: a row matches the vector delivered for its trade and grid
         * index (the index as the row's number, the vector's as text), the rows then sorted by trade and grid index (the delivery order kept
         * among equals).
         */
        static PathRows read(InputTable t, List<String> cols, Control control) throws EngineException {
            Map<String, List<Object>> parts = new HashMap<>();
            int[] size = {0};
            t.scan(new Scan(cols, Filter.ALL, 4_096), b -> {
                control.check();
                int n = b.rows();
                for (String c : cols) {
                    Object a;
                    if (NUMBERS.contains(c)) {
                        double[] d = java.util.Arrays.copyOf(b.numbers(c), n);
                        boolean[] p = new boolean[n];
                        for (int i = 0; i < n; i++) {
                            p[i] = !b.isNull(c, i);
                        }
                        a = new Object[] {d, p};
                    } else {
                        String[] s = java.util.Arrays.copyOf(b.texts(c), n);
                        for (int i = 0; i < n; i++) {
                            if (b.isNull(c, i)) {
                                s[i] = null;
                            }
                        }
                        a = s;
                    }
                    parts.computeIfAbsent(c, x -> new ArrayList<>()).add(a);
                }
                size[0] += n;
            });
            int n = size[0];
            Map<String, String[]> texts = new HashMap<>();
            Map<String, double[]> numbers = new HashMap<>();
            Map<String, boolean[]> present = new HashMap<>();
            parts.forEach((c, list) -> {
                if (NUMBERS.contains(c)) {
                    double[] all = new double[n];
                    boolean[] p = new boolean[n];
                    int at = 0;
                    for (Object o : list) {
                        Object[] dp = (Object[]) o;
                        double[] d = (double[]) dp[0];
                        System.arraycopy(d, 0, all, at, d.length);
                        System.arraycopy((boolean[]) dp[1], 0, p, at, d.length);
                        at += d.length;
                    }
                    numbers.put(c, all);
                    present.put(c, p);
                } else {
                    String[] all = new String[n];
                    int at = 0;
                    for (Object o : list) {
                        String[] s = (String[]) o;
                        System.arraycopy(s, 0, all, at, s.length);
                        at += s.length;
                    }
                    texts.put(c, all);
                }
            });
            String[] tradeCol = texts.getOrDefault("TRADE_ID", new String[n]);
            double[] gridCol = numbers.getOrDefault("GRID_INDEX", new double[n]);
            boolean[] gridHas = present.getOrDefault("GRID_INDEX", new boolean[n]);
            String[] trade = new String[n];
            int[] k = new int[n];
            // a row reads the vector delivered under its trade and grid index as an integer, else (none, or a null one) under the index as
            // its number's text; the last vector delivered under a key is the one read
            Map<String, List<Integer>> byInt = new HashMap<>(Math.max(16, n * 4 / 3 + 1));
            Map<String, List<Integer>> byNumber = new HashMap<>(Math.max(16, n * 4 / 3 + 1));
            for (int i = 0; i < n; i++) {
                trade[i] = String.valueOf(tradeCol[i]);
                double g = gridHas[i] ? gridCol[i] : Double.NaN;
                k[i] = (int) g;
                byInt.computeIfAbsent(trade[i] + "\u0000" + k[i], x -> new ArrayList<>(1)).add(i);
                byNumber.computeIfAbsent(trade[i] + "\u0000" + (gridHas[i] ? Double.toString(g) : "null"), x -> new ArrayList<>(1)).add(i);
            }
            double[][] asInt = new double[n][];
            double[][] asNumber = new double[n][];
            t.vectors(new VectorScan("MTM", List.of("TRADE_ID", "GRID_INDEX"), Filter.ALL), (id, values, length, ks) -> {
                String key = ks[0] + "\u0000" + ks[1];
                List<Integer> a = byInt.get(key);
                List<Integer> b = byNumber.get(key);
                if (a == null && b == null) {
                    return;                                             // no row reads it
                }
                double[] v = length < 0 ? null : java.util.Arrays.copyOf(values, length);   // the host's buffer is reused: one copy, shared
                for (int r : a == null ? List.<Integer>of() : a) {
                    asInt[r] = v;
                }
                for (int r : b == null ? List.<Integer>of() : b) {
                    asNumber[r] = v;
                }
            });
            control.check();
            double[][] mtm = new double[n][];
            for (int i = 0; i < n; i++) {
                mtm[i] = asInt[i] != null ? asInt[i] : asNumber[i];
            }
            Integer[] order = new Integer[n];
            for (int i = 0; i < n; i++) {
                order[i] = i;
            }
            java.util.Arrays.sort(order, Comparator.<Integer, String>comparing(i -> trade[i]).thenComparingInt(i -> k[i]));
            String[] st = new String[n];
            int[] sk = new int[n];
            double[][] sm = new double[n][];
            for (int i = 0; i < n; i++) {
                st[i] = trade[order[i]];
                sk[i] = k[order[i]];
                sm[i] = mtm[order[i]];
            }
            texts.replaceAll((c, a) -> permute(a, order, new String[n]));
            numbers.replaceAll((c, a) -> {
                double[] o = new double[n];
                for (int i = 0; i < n; i++) {
                    o[i] = a[order[i]];
                }
                return o;
            });
            present.replaceAll((c, a) -> {
                boolean[] o = new boolean[n];
                for (int i = 0; i < n; i++) {
                    o[i] = a[order[i]];
                }
                return o;
            });
            return new PathRows(st, sk, sm, texts, numbers, present);
        }

        private static String[] permute(String[] a, Integer[] order, String[] out) {
            for (int i = 0; i < out.length; i++) {
                out[i] = a[order[i]];
            }
            return out;
        }
    }

    /** A list column as delivered (a JSON-like list or values joined by commas); a null item stays null. */
    static List<String> list(Object v) {
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

    /** The run's grid, read from the run once. */
    ExposureGrid grid() {
        ExposureGrid g = grid;
        if (g == null) {
            g = readGrid();
            grid = g;
        }
        return g;
    }

    private ExposureGrid readGrid() {
        List<LocalDate> dates = new ArrayList<>();
        list(run.get("GRID_DATES")).forEach(d -> dates.add(LocalDate.parse(d)));
        List<Integer> lb = new ArrayList<>();
        list(run.get("LOOKBACK_OF")).forEach(x -> lb.add(x == null ? -1 : (int) Double.parseDouble(x)));
        LocalDate business = text(run, "BUSINESS_DATE") == null ? date : LocalDate.parse(text(run, "BUSINESS_DATE"));
        return ExposureGrid.of(business, dates, list(run.get("GRID_ROLE")), lb, list(run.get("GRID_TENORS")));
    }

    /** The exposure book of the rows the filter lets through, built as Drishti's builder builds it. */
    ExposureBook book(Filter see, CcrOptions o, Control control) throws EngineException {
        ExposureGrid g = grid();
        int p = (int) num(run, "PATHS");
        ExposureBook.Builder b = new ExposureBook.Builder(g, p, text(run, "RUN_ID"), text(run, "BATCH_ID"), Map.of(), o.model(), 0).float32(o.float32());
        PathRows r = paths;
        for (int i = 0; i < r.size(); i++) {
            final int row = i;
            if (r.mtm[i] == null || see != Filter.ALL && !see.test(c -> r.text(c, row))) {
                continue;
            }
            b.row(r.trade[i], r.text("NETTING_SET", i), new String[] {r.text("COUNTERPARTY", i), r.text("LEGAL_ENTITY", i), r.text("DESK", i),
                r.text("TT_BOOK", i), r.text("ASSET_CLASS", i), r.text("PRODUCT", i), r.text("SOURCE_SYSTEM", i)}, r.text("MATURITY_DATE", i),
                    r.number("NOTIONAL", i), r.k[i], r.mtm[i], p);
        }
        control.check();
        ExposureBook.Reference ref = new ExposureBook.Reference() {
            @Override
            public CsaTerms terms(String set) {
                return CcrDay.this.terms(set, o);
            }

            @Override
            public double mtm0(String trade) {
                Double d = mtm0.get(trade);
                return d == null ? Double.NaN : d;
            }
        };
        try {
            return b.build(ref, null, "engine", Map.of());
        } catch (java.io.IOException e) {
            throw new EngineException("ENG-9000", EngineException.Kind.FAILED, "the exposure book could not be built: " + e.getMessage());
        }
    }

    /** A set's terms from its CSA and balance, as Drishti's loader reads them (a balance without positions: its NET). */
    CsaTerms terms(String set, CcrOptions o) {
        Map<String, Object> s = sets.get(set);
        Map<String, Object> bal = s == null || text(s, "COLLATERAL_BALANCE") == null ? null : balances.get(text(s, "COLLATERAL_BALANCE"));
        double held = bal == null ? 0 : nz(num(bal, "NET"));
        double im = bal == null || !o.im() ? 0 : nz(num(bal, "IM_HELD"));
        Map<String, Object> csa = s == null || text(s, "CSA") == null ? null : csas.get(text(s, "CSA"));
        if (csa == null) {
            return new CsaTerms(null, 0, 0, 0, 0, 0, 0, 0, 1, false, held, im);
        }
        double mta = nz(num(csa, "MTA"));
        double mtaThem = csa.containsKey("MTA_THEM") ? nz(num(csa, "MTA_THEM")) : mta;
        double mtaUs = csa.containsKey("MTA_US") ? nz(num(csa, "MTA_US")) : mta;
        int mpor = csa.containsKey("MPOR_DAYS") ? (int) num(csa, "MPOR_DAYS") : 10;
        int freq = csa.containsKey("MARGIN_FREQUENCY_DAYS") ? (int) num(csa, "MARGIN_FREQUENCY_DAYS") : 1;
        String type = String.valueOf(text(csa, "MARGIN_TYPE"));
        return new CsaTerms(text(csa, "CSA_ID"), nz(num(csa, "THRESHOLD_THEM")), nz(num(csa, "THRESHOLD_US")), mtaThem, mtaUs,
                nz(num(csa, "INDEPENDENT_AMOUNT")), nz(num(csa, "ROUNDING")), mpor, freq, type.contains("VM"), held, im);
    }

    private static double nz(double d) {
        return Double.isNaN(d) ? 0 : d;
    }

    /** The bytes the date holds: its paths and reference rows, and the limits and SIMM's inputs once read. */
    long bytes() {
        CcrExtras.Limits l = limits;
        CcrExtras.SimmInputs s = simm;
        return bytes + (l == null ? 0 : l.bytes()) + (s == null ? 0 : s.bytes());
    }

    /**
     * The trades' full paths (grid points x paths, a row the date lacks zero), each a new array, for pieces that are part of a netting set;
     * every row of the trade, whatever the viewer (as the paths are read for a cut set).
     */
    Map<String, double[]> tradePaths(java.util.Collection<String> ids) {
        ExposureGrid g;
        try {
            g = grid();
        } catch (RuntimeException e) {
            return Map.of();
        }
        int p = (int) num(run, "PATHS");
        Map<String, double[]> out = new HashMap<>();
        for (String id : new java.util.HashSet<>(ids)) {
            int[] at = paths.rowsOf(id);
            if (at == null) {
                continue;
            }
            for (int i = at[0]; i < at[1]; i++) {
                if (paths.mtm[i] != null) {
                    double[] tp = out.computeIfAbsent(id, x -> new double[g.size() * p]);
                    System.arraycopy(paths.mtm[i], 0, tp, paths.k[i] * p, p);
                }
            }
        }
        return out;
    }
}
