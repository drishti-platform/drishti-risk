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
package com.ash.risk.market.engine;

import com.ash.engine.Cell;
import com.ash.engine.Descriptor;
import com.ash.engine.EngineException;
import com.ash.engine.EngineException.Kind;
import com.ash.engine.Explanation;
import com.ash.engine.Request;
import com.ash.engine.Steps;
import com.ash.engine.Table;
import com.ash.engine.Tables;
import com.ash.risk.kernel.workbook.AnswerBook;
import com.ash.risk.market.calc.VarBook;
import com.ash.risk.market.calc.VarCalc;
import com.ash.risk.market.calc.VarMath;
import com.ash.risk.market.calc.VarWhatIfMath;
import com.ash.tools.xlsx.Sheet;
import com.ash.tools.xlsx.Workbook;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What-if on supplied vectors (VAR_GUIDE.md section 18.12): a node's VaR and ES before and after hypothetical trades whose scenario P&amp;L
 * the caller supplies in the {@code trades} table (the engine prices nothing; a trade is its label, a factor and its vector in USD on the
 * set's scenarios, oldest first), by the moved {@link VarWhatIfMath}: each trade's standalone, incremental (in order and alone) and
 * component VaR, and the stressed VaR of the worst window of the stress length before and after. The rows are plain data of the request
 * (ENGINE_SPEC 8.8 rule 2): nothing of them is kept beyond it.
 */
final class MarketWhatIf {

    /** The trades as given, each scaled by its factor and cut to the book's window. */
    record Asked(String node, double[] before, List<VarWhatIfMath.Item> items, VarWhatIfMath.Result result) {}

    private final Descriptor descriptor;
    private final MarketOptions options;

    MarketWhatIf(Descriptor descriptor, MarketOptions options) {
        this.descriptor = descriptor;
        this.options = options;
    }

    Asked ask(MarketBooks.Built built, Request request) throws EngineException {
        return ask(built, request, null);
    }

    /**
     * The same on a row set ({@code rows}: any scope the host resolved, {@code before} its summed USD P&amp;L as scope-var sums it), or on
     * the node when {@code before} is null.
     */
    Asked ask(MarketBooks.Built built, Request request, double[] rowsBefore) throws EngineException {
        VarBook b = built.book();
        String node = String.valueOf(request.param("node"));
        if (rowsBefore != null) {
            node = node.isBlank() ? "ROWS" : node;
        } else if (node.isBlank()) {
            node = null;
            for (String h : b.hierarchies()) {
                if (b.root(h) != null) {
                    node = b.root(h).id();
                    break;
                }
            }
        }
        if (rowsBefore == null && (node == null || b.node(node) == null)) {
            throw new EngineException("RSK-3120", Kind.REFUSED, "the node " + node + " is not one this request sees");
        }
        if (!"USD".equalsIgnoreCase(String.valueOf(request.param("currency")))) {
            throw new EngineException("RSK-3121", Kind.REFUSED, "the what-if's vectors are in USD: ask it in USD");
        }
        double[] before = rowsBefore != null ? rowsBefore : b.aggregate(node);
        int n = before.length;
        Object p = request.param("trades");
        List<VarWhatIfMath.Item> items = new ArrayList<>();
        if (p instanceof Table t && t.rows() > 0) {
            String[] labels = t.texts("LABEL");
            double[] factors = t.numbers("FACTOR");
            double[][] pnl = t.lists("PNL");
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < t.rows(); i++) {
                double[] v = pnl[i];
                if (v == null || v.length < n) {
                    throw new EngineException("RSK-3122", Kind.REFUSED, "the trade " + labels[i] + " has " + (v == null ? 0 : v.length)
                            + " scenario P&Ls; the set's window has " + n);
                }
                double f = t.isNull("FACTOR", i) ? 1.0 : factors[i];
                double[] cut = new double[n];
                for (int j = 0; j < n; j++) {
                    cut[j] = f * v[v.length - n + j];
                }
                String label = labels[i] == null || labels[i].isBlank() ? "TRADE-" + (i + 1) : labels[i];
                if (!seen.add(label)) {
                    throw new EngineException("RSK-3123", Kind.REFUSED, "two trades are labelled " + label);
                }
                items.add(new VarWhatIfMath.Item(label, f, cut));
            }
        }
        VarWhatIfMath.Result r = VarWhatIfMath.of(before, items, options.calc(), options.regulatory().stressLength());
        return new Asked(node, before, List.copyOf(items), r);
    }

    Table table(Asked a) {
        VarWhatIfMath.Result r = a.result();
        Tables.Builder t = Tables.of(descriptor.schema("what-if"));
        t.row("BEFORE", null, r.beforeVar(), r.beforeEs(), null, null, null, null, finite(r.stressedBefore()));
        t.row("AFTER", null, r.afterVar(), r.afterEs(), r.changeVar(), null, null, null, finite(r.stressedAfter()));
        for (VarWhatIfMath.ItemResult i : r.items()) {
            t.row("TRADE:" + i.label(), i.factor(), i.standaloneVar(), i.standaloneEs(), i.incrementalVar(), i.incrementalAlone(), i.componentVar(),
                    i.componentEs(), null);
        }
        return t.build();
    }

    private static Double finite(double d) {
        return Double.isFinite(d) ? d : null;
    }

    Explanation explain(Asked a, MarketBooks.Built built, Cell cell) throws EngineException {
        String row = cell.key().get("ROW");
        VarWhatIfMath.Result r = a.result();
        VarCalc calc = options.calc();
        if ("BEFORE".equals(row) && ("VAR".equals(cell.column()) || "ES".equals(cell.column()))) {
            return MarketExplain.ofVector(cell, a.before(), built.scenarios(), calc, cell.column());
        }
        if ("AFTER".equals(row) && ("VAR".equals(cell.column()) || "ES".equals(cell.column()))) {
            return MarketExplain.ofVector(cell, r.after(), built.scenarios(), calc, cell.column());
        }
        if ("AFTER".equals(row) && "INCREMENTAL_VAR".equals(cell.column())) {
            return new Explanation(cell, r.changeVar(), List.of(Steps.of("change", "The node's VaR after the trades less its VaR before",
                    "$.after - $.before", Map.of("after", r.afterVar(), "before", r.beforeVar()), r.changeVar())));
        }
        if (row != null && row.startsWith("TRADE:")) {
            for (int i = 0; i < r.items().size(); i++) {
                VarWhatIfMath.ItemResult x = r.items().get(i);
                if (!row.equals("TRADE:" + x.label())) {
                    continue;
                }
                switch (cell.column()) {
                    case "VAR", "ES" -> {
                        return MarketExplain.ofVector(cell, a.items().get(i).pnl(), built.scenarios(), calc, cell.column());
                    }
                    case "INCREMENTAL_VAR" -> {
                        return new Explanation(cell, x.incrementalVar(), List.of(Steps.opaque("incremental",
                                "VaR with the trades up to this one less VaR with those before it",
                                "the trades are added in the order given; recomputed in the workbook", "Main!" + x.label(), x.incrementalVar())));
                    }
                    default -> {
                    }
                }
            }
        }
        throw new EngineException("ENG-3005", Kind.REFUSED, "what-if." + cell.column() + " of " + row + " is not a measure cell of this answer");
    }

    /** The workbook part: the node's and each trade's vectors as values; the cumulative P&amp;L after each trade and the figures as formulas. */
    AnswerBook.Part part(Asked a, MarketBooks.Built built) {
        return new AnswerBook.Part() {
            private int first;

            @Override
            public int data(Sheet data, int at) {
                first = at;
                int n = a.before().length;
                Sheet h = data.row(at).text(0, "SCENARIO", "header").text(1, "DATE", "header").text(2, a.node(), "header");
                for (int i = 0; i < a.items().size(); i++) {
                    h.text(3 + i, a.items().get(i).label(), "header");
                }
                for (int j = 0; j < n; j++) {
                    Sheet r = data.row(at + 1 + j).number(0, j, "int").text(1, built.scenarios().get(j).toString(), "normal")
                            .number(2, a.before()[j], "decimal");
                    for (int i = 0; i < a.items().size(); i++) {
                        r.number(3 + i, a.items().get(i).pnl()[j], "decimal");
                    }
                }
                return at + n;
            }

            @Override
            public int main(Workbook wb, Sheet main, int at) {
                VarCalc c = options.calc();
                int n = a.before().length;
                int k = VarMath.rank(n, c.confidence());
                int kes = VarMath.rank(n, c.esConfidence());
                int m = a.items().size();
                // the P&L after the first i trades, i = 0..m (column B: the node; C onwards: one more trade each)
                int head = at;
                Sheet h = main.row(head).text(0, "SCENARIO", "header").text(1, "BEFORE", "header");
                for (int i = 0; i < m; i++) {
                    h.text(2 + i, "WITH " + a.items().get(i).label(), "header");
                }
                double[] run = a.before().clone();
                double[][] cum = new double[m][];
                for (int i = 0; i < m; i++) {
                    VarMath.addInto(run, a.items().get(i).pnl());
                    cum[i] = run.clone();
                }
                for (int j = 0; j < n; j++) {
                    int dr = first + 1 + j;
                    Sheet r = main.row(head + 1 + j).number(0, j, "int").formula(1, AnswerBook.cell(2, dr), a.before()[j], "decimal");
                    for (int i = 0; i < m; i++) {
                        r.formula(2 + i, AnswerBook.letters(1 + i) + (head + 1 + j) + "+" + AnswerBook.cell(3 + i, dr), cum[i][j], "decimal");
                    }
                }
                int row = head + n + 2;
                String before = "$B$" + (head + 1) + ":$B$" + (head + n);
                String after = m == 0 ? before : "$" + AnswerBook.letters(1 + m) + "$" + (head + 1) + ":$" + AnswerBook.letters(1 + m) + "$" + (head + n);
                VarWhatIfMath.Result r = a.result();
                main.row(row).text(0, "ROW", "header").text(1, "VAR", "header").text(2, "ES", "header").text(3, "INCREMENTAL_VAR", "header");
                int beforeRow = row + 1;
                main.row(beforeRow).text(0, "BEFORE", "normal").formula(1, var(before, n, k), r.beforeVar(), "decimal")
                        .formula(2, es(before, kes), r.beforeEs(), "decimal");
                main.row(beforeRow + 1).text(0, "AFTER", "normal").formula(1, var(after, n, k), r.afterVar(), "decimal")
                        .formula(2, es(after, kes), r.afterEs(), "decimal").formula(3, "B" + (beforeRow + 1) + "-B" + beforeRow, r.changeVar(), "decimal");
                row = beforeRow + 1;
                for (int i = 0; i < m; i++) {
                    row++;
                    String own = AnswerBook.range(3 + i, first + 1, first + n);
                    String upTo = "$" + AnswerBook.letters(2 + i) + "$" + (head + 1) + ":$" + AnswerBook.letters(2 + i) + "$" + (head + n);
                    String prior = "$" + AnswerBook.letters(1 + i) + "$" + (head + 1) + ":$" + AnswerBook.letters(1 + i) + "$" + (head + n);
                    VarWhatIfMath.ItemResult x = r.items().get(i);
                    main.row(row).text(0, "TRADE:" + x.label(), "normal").formula(1, var(own, n, k), x.standaloneVar(), "decimal")
                            .formula(2, es(own, kes), x.standaloneEs(), "decimal")
                            .formula(3, "(" + var(upTo, n, k) + ")-(" + var(prior, n, k) + ")", x.incrementalVar(), "decimal");
                }
                wb.name(AnswerBook.name("what-if", "VAR"), main.name(), "$B$" + beforeRow);
                wb.name(AnswerBook.name("what-if", "ES"), main.name(), "$C$" + beforeRow);
                wb.name(AnswerBook.name("what-if", "INCREMENTAL_VAR"), main.name(), "$D$" + (beforeRow + 1));
                return row;
            }

            private String var(String range, int n, int k) {
                return options.calc().rule() == VarMath.RankRule.INTERPOLATED ? MarketHorizon.interpolated(range, n, options.calc().confidence())
                        : "-SMALL(" + range + "," + k + ")";
            }

            private String es(String range, int kes) {
                return "-" + MarketWorkbook.worstSum(range, kes) + "/" + kes;
            }
        };
    }
}
