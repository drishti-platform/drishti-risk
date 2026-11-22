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

    record PathRow(String trade, int k, Map<String, Object> row, double[] mtm) {}

    final Snapshot snapshot;
    final LocalDate date;
    final Map<String, Object> run;
    final List<PathRow> paths;
    final Map<String, Map<String, Object>> sets;
    final Map<String, Map<String, Object>> csas;
    final Map<String, Map<String, Object>> balances;
    final Map<String, Double> mtm0;
    final Map<String, Missing> missing;

    private CcrDay(Snapshot snapshot, Map<String, Object> run, List<PathRow> paths, Map<String, Map<String, Object>> sets,
            Map<String, Map<String, Object>> csas, Map<String, Map<String, Object>> balances, Map<String, Double> mtm0, Map<String, Missing> missing) {
        this.snapshot = snapshot;
        this.date = LocalDate.parse(snapshot.dimensions().get("date"));
        this.run = run;
        this.paths = paths;
        this.sets = sets;
        this.csas = csas;
        this.balances = balances;
        this.mtm0 = mtm0;
        this.missing = missing;
    }

    static CcrDay read(Snapshot snapshot, Inputs inputs, Control control) throws EngineException {
        Map<String, Missing> missing = new TreeMap<>();
        Map<String, List<Map<String, Object>>> rows = new HashMap<>();
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
            List<Map<String, Object>> out = new ArrayList<>();
            t.scan(new Scan(cols, Filter.ALL, 4_096), b -> {
                control.check();
                for (int i = 0; i < b.rows(); i++) {
                    out.add(row(b, cols, i));
                }
            });
            rows.put(name, out);
        }
        List<PathRow> paths = new ArrayList<>();
        if (!missing.containsKey(PATH)) {
            Map<String, double[]> v = new HashMap<>();
            inputs.table(PATH).vectors(new VectorScan("MTM", List.of("TRADE_ID", "GRID_INDEX"), Filter.ALL), (id, values, length, texts) ->
                    v.put(texts[0] + "\u0000" + texts[1], length < 0 ? null : java.util.Arrays.copyOf(values, length)));
            for (Map<String, Object> r : rows.get(PATH)) {
                String trade = String.valueOf(r.get("TRADE_ID"));
                int k = (int) num(r, "GRID_INDEX");
                double[] m = v.get(trade + "\u0000" + k);
                if (m == null) {
                    m = v.get(trade + "\u0000" + r.get("GRID_INDEX"));
                }
                paths.add(new PathRow(trade, k, r, m));
            }
            paths.sort(Comparator.comparing(PathRow::trade).thenComparingInt(PathRow::k));
        }
        Map<String, Object> run = rows.get(RUN).stream().min(Comparator.comparing(r -> String.valueOf(r.get("RUN_ID")))).orElse(null);
        Map<String, Double> mtm0 = new TreeMap<>();
        rows.get(VALUATION).forEach(r -> mtm0.put(String.valueOf(r.get("TRADE_ID")), num(r, "MTM")));
        return new CcrDay(snapshot, run, List.copyOf(paths), byKey(rows.get(SET), "NETTING_SET_ID"), byKey(rows.get(CSA), "CSA_ID"),
                byKey(rows.get(BALANCE), "BALANCE_ID"), mtm0, missing);
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

    /** The run's grid. */
    ExposureGrid grid() {
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
        ExposureBook.Builder b = new ExposureBook.Builder(g, p, text(run, "RUN_ID"), text(run, "BATCH_ID"), Map.of(), o.model(), 0);
        for (PathRow r : paths) {
            Map<String, Object> m = r.row();
            if (r.mtm() == null || see != Filter.ALL && !see.test(c -> text(m, c))) {
                continue;
            }
            b.row(r.trade(), text(m, "NETTING_SET"), new String[] {text(m, "COUNTERPARTY"), text(m, "LEGAL_ENTITY"), text(m, "DESK"),
                text(m, "TT_BOOK"), text(m, "ASSET_CLASS"), text(m, "PRODUCT"), text(m, "SOURCE_SYSTEM")}, text(m, "MATURITY_DATE"),
                    num(m, "NOTIONAL"), r.k(), r.mtm(), p);
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

    long bytes() {
        long b = 0;
        for (PathRow r : paths) {
            b += 128 + (r.mtm() == null ? 0 : 8L * r.mtm().length);
        }
        return b;
    }
}
