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
import com.ash.engine.Step;
import com.ash.engine.Steps;
import com.ash.engine.Table;
import com.ash.engine.Tables;
import com.ash.risk.kernel.workbook.AnswerBook;
import com.ash.risk.market.calc.VarBook;
import com.ash.risk.market.calc.VarCalc;
import com.ash.risk.market.calc.VarLens;
import com.ash.risk.market.calc.VarMath;
import com.ash.tools.xlsx.Sheet;
import com.ash.tools.xlsx.Workbook;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * VaR and ES of every node at a horizon, on the normal or the stress scenario set (stressed VaR, VAR_GUIDE.md sections 18.6 and 18.8): the
 * 1-day figures, the 10-day figures by the square root of 10 (the 1-day ones times √10 exactly), or the 10-day figures of the overlapping
 * sums of 10 consecutive scenario days, all by the moved {@link VarLens}. The book is the one of the set the request means.
 */
final class MarketHorizon {

    private final Descriptor descriptor;
    private final MarketOptions options;

    MarketHorizon(Descriptor descriptor, MarketOptions options) {
        this.descriptor = descriptor;
        this.options = options;
    }

    /** The lens a request asks for. */
    VarLens lens(Request request) throws EngineException {
        int days = "10d".equals(String.valueOf(request.param("horizon"))) ? VarLens.TEN : 1;
        VarLens.Method m = days == 1 ? VarLens.Method.NONE : VarLens.Method.parse(String.valueOf(request.param("ten-day-method")));
        VarCalc c = options.calc();
        return new VarLens(days, m, confidence(request, "lens-confidence", c.confidence()),
                confidence(request, "lens-es-confidence", c.esConfidence()),
                Boolean.TRUE.equals(request.param("stressed")));
    }

    /** A confidence the request names (the host's offered lens: 0.975, 0.99, ...), else (absent or 0) the engine's configured one. */
    static double confidence(Request request, String name, double configured) throws EngineException {
        Object q = request.param(name);
        double v = q == null ? 0 : q instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(q));
        if (v == 0) {
            return configured;
        }
        if (!(v > 0 && v < 1)) {
            throw new EngineException("ENG-3001", EngineException.Kind.REFUSED, name + " is a probability between 0 and 1, not " + q);
        }
        return v;
    }

    Table nodes(MarketBooks.Built built, String set, VarLens lens) {
        VarBook b = built.book();
        Tables.Builder t = Tables.of(descriptor.schema("node-var-horizon"));
        for (VarBook.Node x : b.nodes().values()) {
            double[] one = b.aggregate(x.id());
            VarCalc.Measures ms = lens.measures(options.calc(), one, lens.apply(one));
            t.row(x.id(), x.hierarchy(), set, lens.horizon(), lens.methodName(), (long) lens.width(one.length), ms.var(), ms.es());
        }
        return t.build();
    }

    Explanation explain(MarketBooks.Built built, Cell cell, VarLens lens, MarketExplain historical) throws EngineException {
        VarBook b = built.book();
        String id = cell.key().get("NODE_ID");
        if (id == null || b.node(id) == null) {
            throw new EngineException("ENG-3005", Kind.REFUSED, "the node " + id + " is not in this request's answer");
        }
        if (!"VAR".equals(cell.column()) && !"ES".equals(cell.column())) {
            throw new EngineException("ENG-3005", Kind.REFUSED, cell.column() + " is not a measure of node-var-horizon");
        }
        double[] one = b.aggregate(id);
        VarCalc.Measures ms = lens.measures(options.calc(), one, lens.apply(one));
        double value = "VAR".equals(cell.column()) ? ms.var() : ms.es();
        if (lens.method() != VarLens.Method.SUM) {
            Explanation h = historical.explain(built, null, new Cell("node-var", cell.column(), cell.key()));
            if (lens.method() == VarLens.Method.NONE) {
                return new Explanation(cell, h.value(), h.steps());
            }
            List<Step> steps = new ArrayList<>(h.steps());
            String last = steps.get(steps.size() - 1).name();
            steps.add(Steps.of("ten-day", "The 10-day figure: the 1-day one times the square root of 10", "step." + last + " * $.root",
                    Map.of("root", VarLens.SQRT10), value));
            return new Explanation(cell, value, steps);
        }
        double[] ten = lens.apply(one);
        int k = VarMath.rank(ten.length, "VAR".equals(cell.column()) ? options.calc().confidence() : options.calc().esConfidence());
        if ("VAR".equals(cell.column())) {
            if (options.calc().rule() != VarMath.RankRule.KTH_WORST) {
                return new Explanation(cell, value, List.of(Steps.opaque("var", "10-day VaR by the interpolated rule over the overlapping sums",
                        "interpolated between two ranks of the 10-day sums; recomputed in the workbook", "Main!" + id, value)));
            }
            int w = ms.order()[k - 1];
            List<Map<String, Object>> days = new ArrayList<>();
            for (int j = w; j < w + VarLens.TEN; j++) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("SCENARIO", (long) j);
                m.put("DATE", built.scenarios().get(j).toString());
                m.put("PNL", one[j]);
                days.add(m);
            }
            return new Explanation(cell, value, List.of(
                    Steps.of("window", "The 10-day window at rank k = " + k + " of " + ten.length + ", worst first: the sum of its 10 scenario days",
                            "sum($.days, 'PNL')", Map.of("days", days), ten[w]),
                    Steps.of("var", "VaR is the loss there", "0 - step.window", Map.of(), value)));
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        double s = 0;
        for (int i = 0; i < k; i++) {
            int w = ms.order()[i];
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("RANK", (long) (i + 1));
            m.put("FIRST_DAY", built.scenarios().get(w).toString());
            m.put("PNL", ten[w]);
            rows.add(m);
            s += ten[w];
        }
        return new Explanation(cell, value, List.of(
                Steps.of("tail", "The sum of the " + k + " worst 10-day sums", "sum($.rows, 'PNL')", Map.of("rows", rows), s),
                Steps.of("es", "ES is minus their mean", "0 - step.tail / $.k", Map.of("k", (long) k), value)));
    }

    /** The workbook part: each node's 1-day P&amp;L as values; the 10-day sums (when asked) and the figures as formulas. */
    AnswerBook.Part part(MarketBooks.Built built, VarLens lens) {
        return new AnswerBook.Part() {
            private List<VarBook.Node> nodes;
            private List<double[]> vectors;
            private int first;
            private int n;

            @Override
            public int data(Sheet data, int at) {
                VarBook b = built.book();
                nodes = new ArrayList<>(b.nodes().values());
                vectors = new ArrayList<>();
                nodes.forEach(x -> vectors.add(b.aggregate(x.id())));
                n = b.width();
                first = at;
                Sheet h = data.row(at).text(0, "SCENARIO", "header").text(1, "DATE", "header");
                for (int c = 0; c < nodes.size(); c++) {
                    h.text(c + 2, nodes.get(c).id(), "header");
                }
                for (int j = 0; j < n; j++) {
                    Sheet r = data.row(at + 1 + j).number(0, j, "int").text(1, built.scenarios().get(j).toString(), "normal");
                    for (int c = 0; c < nodes.size(); c++) {
                        r.number(c + 2, vectors.get(c)[j], "decimal");
                    }
                }
                return at + n;
            }

            @Override
            public int main(Workbook wb, Sheet main, int at) {
                VarCalc c = options.calc();
                boolean sum = lens.method() == VarLens.Method.SUM;
                int width = lens.width(n);
                int k = VarMath.rank(width, c.confidence());
                int kes = VarMath.rank(width, c.esConfidence());
                main.row(at).text(0, "Horizon", "bold").text(1, lens.label(), "normal");
                main.row(at + 1).text(0, "k (VaR)", "bold").number(1, k, "int");
                main.row(at + 2).text(0, "k (ES)", "bold").number(1, kes, "int");
                int row = at + 4;
                int winFirst = row + 1;
                if (sum) {                                                  // the overlapping 10-day sums, one column per node
                    Sheet h = main.row(row).text(0, "WINDOW", "header");
                    for (int i = 0; i < nodes.size(); i++) {
                        h.text(i + 1, nodes.get(i).id(), "header");
                    }
                    for (int w = 0; w < width; w++) {
                        row++;
                        Sheet r = main.row(row).number(0, w, "int");
                        for (int i = 0; i < nodes.size(); i++) {
                            double s = 0;
                            for (int j = w; j < w + VarLens.TEN; j++) {
                                s += vectors.get(i)[j];
                            }
                            r.formula(i + 1, "SUM(" + AnswerBook.range(i + 2, first + 1 + w, first + w + VarLens.TEN) + ")", s, "decimal");
                        }
                    }
                    row += 2;
                }
                main.row(row).text(0, "NODE_ID", "header").text(1, "VAR", "header").text(2, "ES", "header");
                int firstRow = row + 1;
                for (int i = 0; i < nodes.size(); i++) {
                    row++;
                    String range = sum ? "$" + AnswerBook.letters(i + 1) + "$" + winFirst + ":$" + AnswerBook.letters(i + 1) + "$" + (winFirst + width - 1)
                            : AnswerBook.range(i + 2, first + 1, first + n);
                    String f = sum || lens.method() == VarLens.Method.NONE ? "" : "*SQRT(10)";
                    VarCalc.Measures ms = lens.measures(c, vectors.get(i), lens.apply(vectors.get(i)));
                    String var = c.rule() == VarMath.RankRule.INTERPOLATED ? interpolated(range, width, c.confidence()) : "-SMALL(" + range + "," + k + ")";
                    main.row(row).text(0, nodes.get(i).id(), "normal").formula(1, "(" + var + ")" + f, ms.var(), "decimal")
                            .formula(2, "(-" + MarketWorkbook.worstSum(range, kes) + "/" + kes + ")" + f, ms.es(), "decimal");
                }
                if (!nodes.isEmpty()) {
                    wb.name(AnswerBook.name("node-var-horizon", "VAR"), main.name(), "$B$" + firstRow);
                    wb.name(AnswerBook.name("node-var-horizon", "ES"), main.name(), "$C$" + firstRow);
                }
                return row;
            }
        };
    }

    static String interpolated(String range, int n, double confidence) {
        double pos = n * (1.0 - confidence);
        int lo = Math.max(1, (int) Math.floor(pos));
        int hi = Math.min(n, lo + 1);
        double w = Math.min(1.0, Math.max(0.0, pos - lo));
        return "-((1-" + w + ")*SMALL(" + range + "," + lo + ")+" + w + "*SMALL(" + range + "," + hi + "))";
    }
}
