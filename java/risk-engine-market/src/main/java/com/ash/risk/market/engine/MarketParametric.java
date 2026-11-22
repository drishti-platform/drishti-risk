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
import com.ash.engine.Control;
import com.ash.engine.Descriptor;
import com.ash.engine.EngineException;
import com.ash.engine.EngineException.Kind;
import com.ash.engine.Explanation;
import com.ash.engine.Step;
import com.ash.engine.Steps;
import com.ash.engine.Table;
import com.ash.engine.Tables;
import com.ash.risk.kernel.workbook.AnswerBook;
import com.ash.risk.market.calc.ParametricVar;
import com.ash.risk.market.calc.VarBook;
import com.ash.tools.xlsx.Sheet;
import com.ash.tools.xlsx.Workbook;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Parametric (variance-covariance) VaR of every node (PVAR, VAR_GUIDE.md section 18.7): the node's scenario P&amp;L fitted by its mean
 * and standard deviation (equal weights with the sample variance, or EWMA), {@code PVaR = z σ − μ} and {@code PES = σ φ(z_ES)/(1 − c_ES) − μ},
 * computed by the moved {@link ParametricVar}; each child's component {@code z cov/σ − μᵢ} (the parts add up to the node's PVaR exactly),
 * its standalone and incremental PVaR. The explanation of PVAR, PES and SIGMA is in Rachana-EL over the node's scenario P&amp;Ls; the
 * workbook recomputes every figure with AVERAGE, STDEV, NORMSINV and SUMPRODUCT.
 */
final class MarketParametric {

    private final Descriptor descriptor;
    private final MarketOptions options;

    MarketParametric(Descriptor descriptor, MarketOptions options) {
        this.descriptor = descriptor;
        this.options = options;
    }

    private ParametricVar.Figures figures(double[] v) {
        return ParametricVar.of(v, options.calc().confidence(), options.calc().esConfidence(), options.parametric());
    }

    Table nodes(VarBook b) {
        Tables.Builder t = Tables.of(descriptor.schema("node-pvar"));
        for (VarBook.Node x : b.nodes().values()) {
            double[] v = b.aggregate(x.id());
            ParametricVar.Figures f = figures(v);
            t.row(x.id(), x.hierarchy(), (long) v.length, f.fit().mu(), f.fit().sigma(), f.z(), f.var(), f.es(), options.calc().measures(v).var(),
                    (long) f.beyond(), f.expectedBeyond());
        }
        return t.build();
    }

    Table components(VarBook b, Control control) throws EngineException {
        Tables.Builder t = Tables.of(descriptor.schema("node-pvar-components"));
        for (VarBook.Node x : b.nodes().values()) {
            if (x.children().isEmpty()) {
                continue;
            }
            control.check();
            double[] v = b.aggregate(x.id());
            ParametricVar.Figures f = figures(v);
            for (String c : new TreeSet<>(x.children())) {
                ParametricVar.Component k = ParametricVar.component(b.aggregate(c), v, f);
                t.row(x.id(), c, k.rho(), k.componentVar(), k.componentEs(), k.standaloneVar(), k.incrementalVar());
            }
        }
        return t.build();
    }

    // ------------------------------------------------------------------------------------------------------------- explain

    Explanation explain(MarketBooks.Built built, Cell cell, MarketExplain historical) throws EngineException {
        VarBook b = built.book();
        String id = cell.key().get("NODE_ID");
        if (id == null || b.node(id) == null) {
            throw new EngineException("ENG-3005", Kind.REFUSED, "the node " + id + " is not in this request's answer");
        }
        if (cell.output().equals("node-pvar-components")) {
            return component(b, cell);
        }
        double[] v = b.aggregate(id);
        ParametricVar.Figures f = figures(v);
        if ("HVAR".equals(cell.column())) {
            Explanation h = historical.explain(built, null, new Cell("node-var", "VAR", cell.key()));
            return new Explanation(cell, h.value(), h.steps());
        }
        boolean equal = options.parametric().weighting() == ParametricVar.Weighting.EQUAL;
        int n = v.length;
        double[] w = f.fit().weights();
        List<Map<String, Object>> rows = new ArrayList<>(n);
        for (int j = 0; j < n; j++) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("SCENARIO", (long) j);
            m.put("DATE", built.scenarios().get(j).toString());
            m.put("PNL", v[j]);
            if (!equal) {
                m.put("WEIGHT", w[j]);
                m.put("WPNL", w[j] * v[j]);
            }
            double d = v[j] - f.fit().mu();
            m.put(equal ? "D2" : "WD2", equal ? d * d : w[j] * d * d);
            rows.add(m);
        }
        List<Step> steps = new ArrayList<>();
        String label = "The node's " + n + " scenario P&Ls" + (equal ? ", equally weighted" : ", EWMA-weighted (lambda " + options.parametric().lambda() + ")");
        steps.add(equal ? Steps.of("mean", label + ": their mean", "sum($.rows, 'PNL') / $.n", Map.of("rows", rows, "n", (long) n), f.fit().mu())
                : Steps.of("mean", label + ": their weighted mean", "sum($.rows, 'WPNL')", Map.of("rows", rows), f.fit().mu()));
        double variance = f.fit().sigma() * f.fit().sigma();
        steps.add(equal ? Steps.of("variance", "The sample variance: the squared deviations over N - 1",
                n > 1 ? "sum($.rows, 'D2') / ($.n - 1)" : "0", Map.of("rows", rows, "n", (long) n), variance)
                : Steps.of("variance", "The weighted variance", "sum($.rows, 'WD2')", Map.of("rows", rows), variance));
        steps.add(Steps.of("sigma", "The standard deviation", "sqrt(step.variance)", Map.of(), f.fit().sigma()));
        switch (cell.column()) {
            case "SIGMA" -> {
                return new Explanation(cell, f.fit().sigma(), steps);
            }
            case "PVAR" -> {
                steps.add(Steps.of("pvar", "PVaR = z sigma - mean, z the normal quantile at " + f.confidence(), "$.z * step.sigma - step.mean",
                        Map.of("z", f.z()), f.var()));
                return new Explanation(cell, f.var(), steps);
            }
            case "PES" -> {
                steps.add(Steps.of("pes", "PES = sigma phi(z_ES)/(1 - c_ES) - mean, the factor at " + f.esConfidence(), "step.sigma * $.factor - step.mean",
                        Map.of("factor", f.esFactor()), f.es()));
                return new Explanation(cell, f.es(), steps);
            }
            default -> throw new EngineException("ENG-3005", Kind.REFUSED, cell.column() + " is not a measure of node-pvar");
        }
    }

    private Explanation component(VarBook b, Cell cell) throws EngineException {
        String node = cell.key().get("NODE_ID");
        String child = cell.key().get("CHILD");
        if (child == null || b.node(child) == null || !b.node(node).children().contains(child)) {
            throw new EngineException("ENG-3005", Kind.REFUSED, child + " is not a child of " + node);
        }
        double[] v = b.aggregate(node);
        ParametricVar.Figures f = figures(v);
        ParametricVar.Component k = ParametricVar.component(b.aggregate(child), v, f);
        double value = switch (cell.column()) {
            case "COMPONENT_PVAR" -> k.componentVar();
            case "COMPONENT_PES" -> k.componentEs();
            case "STANDALONE_PVAR" -> k.standaloneVar();
            case "INCREMENTAL_PVAR" -> k.incrementalVar();
            default -> throw new EngineException("ENG-3005", Kind.REFUSED, cell.column() + " is not a measure of node-pvar-components");
        };
        List<Step> steps = new ArrayList<>();
        switch (cell.column()) {
            case "COMPONENT_PVAR" -> steps.add(Steps.of("component", "z cov(child, node) / sigma(node) - mean(child)",
                    f.fit().sigma() > 0 ? "$.z * $.cov / $.sigma - $.mu" : "0 - $.mu",
                    Map.of("z", f.z(), "cov", k.cov(), "sigma", f.fit().sigma(), "mu", k.mu()), value));
            case "COMPONENT_PES" -> steps.add(Steps.of("component", "phi(z_ES)/(1 - c_ES) cov(child, node) / sigma(node) - mean(child)",
                    f.fit().sigma() > 0 ? "$.factor * $.cov / $.sigma - $.mu" : "0 - $.mu",
                    Map.of("factor", f.esFactor(), "cov", k.cov(), "sigma", f.fit().sigma(), "mu", k.mu()), value));
            case "STANDALONE_PVAR" -> steps.add(Steps.of("standalone", "z sigma(child) - mean(child)", "$.z * $.sigma - $.mu",
                    Map.of("z", f.z(), "sigma", k.sigma(), "mu", k.mu()), value));
            default -> steps.add(Steps.opaque("incremental", "PVaR(node) - PVaR(node without " + child + ")",
                    "the node's PVaR less the PVaR of its P&L with the child's taken out, scenario by scenario; recomputed in the workbook",
                    "Main!" + child, value));
        }
        return new Explanation(cell, value, steps);
    }

    // ------------------------------------------------------------------------------------------------------------- workbook

    /** The workbook part: the nodes' vectors (and the EWMA weights) as values; every figure as a formula over them. */
    AnswerBook.Part part(MarketBooks.Built built, boolean components) {
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
                double[] w = ParametricVar.weights(n, options.parametric());
                Sheet h = data.row(at).text(0, "SCENARIO", "header").text(1, "WEIGHT", "header");
                for (int c = 0; c < nodes.size(); c++) {
                    h.text(c + 2, nodes.get(c).id(), "header");
                }
                for (int j = 0; j < n; j++) {
                    Sheet r = data.row(at + 1 + j).number(0, j, "int").number(1, w[j], "decimal");
                    for (int c = 0; c < nodes.size(); c++) {
                        r.number(c + 2, vectors.get(c)[j], "decimal");
                    }
                }
                return at + n;
            }

            private String mean(String v, String w, boolean equal) {
                return equal ? "AVERAGE(" + v + ")" : "SUMPRODUCT(" + w + "," + v + ")";
            }

            private String sigma(String v, String w, boolean equal) {
                return equal ? (n > 1 ? "STDEV(" + v + ")" : "0") : "SQRT(SUMPRODUCT(" + w + ",(" + v + "-SUMPRODUCT(" + w + "," + v + "))^2))";
            }

            @Override
            public int main(Workbook wb, Sheet main, int at) {
                boolean equal = options.parametric().weighting() == ParametricVar.Weighting.EQUAL;
                String w = AnswerBook.range(1, first + 1, first + n);
                double c = options.calc().confidence();
                double ce = options.calc().esConfidence();
                int zRow = at;
                int fRow = at + 1;
                main.row(zRow).text(0, "z", "bold").formula(1, "NORMSINV(" + c + ")", ParametricVar.inverse(c), "decimal");
                main.row(fRow).text(0, "ES factor", "bold").formula(1, "NORMDIST(NORMSINV(" + ce + "),0,1,FALSE)/(1-" + ce + ")",
                        ParametricVar.esFactor(ce), "decimal");
                int row = at + 3;
                main.row(row).text(0, components ? "NODE_ID / CHILD" : "NODE_ID", "header").text(1, "MU", "header").text(2, "SIGMA", "header")
                        .text(3, "PVAR", "header").text(4, "PES", "header");
                int firstRow = row + 1;
                for (int i = 0; i < nodes.size(); i++) {
                    row++;
                    String v = AnswerBook.range(i + 2, first + 1, first + n);
                    ParametricVar.Figures f = figures(vectors.get(i));
                    main.row(row).text(0, nodes.get(i).id(), "normal").formula(1, mean(v, w, equal), f.fit().mu(), "decimal")
                            .formula(2, sigma(v, w, equal), f.fit().sigma(), "decimal")
                            .formula(3, "$B$" + zRow + "*C" + row + "-B" + row, f.var(), "decimal")
                            .formula(4, "C" + row + "*$B$" + fRow + "-B" + row, f.es(), "decimal");
                }
                if (!nodes.isEmpty()) {
                    wb.name(AnswerBook.name("node-pvar", "SIGMA"), main.name(), "$C$" + firstRow);
                    wb.name(AnswerBook.name("node-pvar", "PVAR"), main.name(), "$D$" + firstRow);
                    wb.name(AnswerBook.name("node-pvar", "PES"), main.name(), "$E$" + firstRow);
                }
                if (!components) {
                    return row;
                }
                row += 2;
                main.row(row).text(0, "NODE_ID", "header").text(1, "CHILD", "header").text(2, "COMPONENT_PVAR", "header")
                        .text(3, "COMPONENT_PES", "header").text(4, "STANDALONE_PVAR", "header").text(5, "INCREMENTAL_PVAR", "header");
                boolean named = false;
                for (int i = 0; i < nodes.size(); i++) {
                    VarBook.Node x = nodes.get(i);
                    if (x.children().isEmpty()) {
                        continue;
                    }
                    String v = AnswerBook.range(i + 2, first + 1, first + n);
                    ParametricVar.Figures f = figures(vectors.get(i));
                    for (String ch : new TreeSet<>(x.children())) {
                        int ci = index(ch);
                        String p = AnswerBook.range(ci + 2, first + 1, first + n);
                        ParametricVar.Component k = ParametricVar.component(vectors.get(ci), vectors.get(i), f);
                        String cov = equal ? (n > 1 ? "COVAR(" + p + "," + v + ")*" + n + "/" + (n - 1) : "0")
                                : "SUMPRODUCT(" + w + ",(" + p + "-SUMPRODUCT(" + w + "," + p + "))*(" + v + "-SUMPRODUCT(" + w + "," + v + ")))";
                        String sg = sigma(v, w, equal);
                        String mp = mean(p, w, equal);
                        String rest = v + "-" + p;
                        String restSigma = equal ? (n > 1 ? "SQRT((SUMPRODUCT((" + rest + ")^2)-(SUM(" + v + ")-SUM(" + p + "))^2/" + n + ")/" + (n - 1) + ")" : "0")
                                : "SQRT(SUMPRODUCT(" + w + ",(" + rest + "-SUMPRODUCT(" + w + "," + rest + "))^2))";
                        String restMean = equal ? "(AVERAGE(" + v + ")-AVERAGE(" + p + "))" : "SUMPRODUCT(" + w + "," + rest + ")";
                        row++;
                        main.row(row).text(0, x.id(), "normal").text(1, ch, "normal")
                                .formula(2, "IF(" + sg + ">0,$B$" + zRow + "*" + cov + "/" + sg + ",0)-" + mp, k.componentVar(), "decimal")
                                .formula(3, "IF(" + sg + ">0,$B$" + fRow + "*" + cov + "/" + sg + ",0)-" + mp, k.componentEs(), "decimal")
                                .formula(4, "$B$" + zRow + "*" + sigma(p, w, equal) + "-" + mp, k.standaloneVar(), "decimal")
                                .formula(5, "($B$" + zRow + "*" + sg + "-" + mean(v, w, equal) + ")-($B$" + zRow + "*" + restSigma + "-" + restMean + ")",
                                        k.incrementalVar(), "decimal");
                        if (!named) {
                            named = true;
                            for (String[] nc : new String[][] {{"COMPONENT_PVAR", "C"}, {"COMPONENT_PES", "D"}, {"STANDALONE_PVAR", "E"},
                                {"INCREMENTAL_PVAR", "F"}}) {
                                wb.name(AnswerBook.name("node-pvar-components", nc[0]), main.name(), "$" + nc[1] + "$" + row);
                            }
                        }
                    }
                }
                return row;
            }

            private int index(String id) {
                for (int i = 0; i < nodes.size(); i++) {
                    if (nodes.get(i).id().equals(id)) {
                        return i;
                    }
                }
                throw new IllegalStateException("no node " + id);
            }
        };
    }
}
