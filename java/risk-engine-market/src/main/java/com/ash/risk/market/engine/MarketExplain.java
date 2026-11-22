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

import com.ash.engine.Cell;
import com.ash.engine.EngineException;
import com.ash.engine.EngineException.Kind;
import com.ash.engine.Explanation;
import com.ash.engine.Step;
import com.ash.engine.Steps;
import com.ash.risk.kernel.vector.ParallelSums;
import com.ash.risk.market.calc.ScopeResolver;
import com.ash.risk.market.calc.VarBook;
import com.ash.risk.market.calc.VarCalc;
import com.ash.risk.market.calc.VarMath;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Why a VaR or ES cell is what it is: the scope's aggregate P&amp;L ordered worst first, the rank k = ceil(N (1 - c)), the k-th worst
 * scenario (VaR) or the mean of the k worst (ES), each step a Rachana-EL formula over the values it is given. Component, standalone
 * and incremental VaR are opaque here and recomputed in the workbook.
 */
final class MarketExplain {

    private final MarketOptions options;
    private final ParallelSums sums;

    MarketExplain(MarketOptions options, ParallelSums sums) {
        this.options = options;
        this.sums = sums;
    }

    Explanation explain(MarketBooks.Built built, MarketDay day, Cell cell) throws EngineException {
        VarBook b = built.book();
        double[] v = vector(b, day, cell);
        VarCalc calc = options.calc();
        String col = cell.column();
        if (cell.output().equals("node-components")) {
            return opaque(b, cell, calc);
        }
        if ("VAR".equals(col) || "ES".equals(col)) {
            return ofVector(cell, v, built.scenarios(), calc, col);
        }
        throw new EngineException("ENG-3005", Kind.REFUSED, cell.output() + "." + col + " is not a measure this engine explains");
    }

    /** VaR or ES of one vector explained: the scenario at rank k worst first (VaR), the mean of the k worst (ES). */
    static Explanation ofVector(Cell cell, double[] v, List<java.time.LocalDate> scenarios, VarCalc calc, String column) throws EngineException {
        VarCalc.Measures ms = calc.measures(v);
        int n = v.length;
        if ("VAR".equals(column)) {
            List<Map<String, Object>> rows = new ArrayList<>();
            double value;
            if (calc.rule() == VarMath.RankRule.INTERPOLATED) {
                double pos = n * (1.0 - calc.confidence());
                int lo = Math.max(1, (int) Math.floor(pos));
                int hi = Math.min(n, lo + 1);
                double w = Math.min(1.0, Math.max(0.0, pos - lo));
                rows.add(row(lo, ms.order()[lo - 1], scenarios, (1 - w) * v[ms.order()[lo - 1]]));
                rows.add(row(hi, ms.order()[hi - 1], scenarios, w * v[ms.order()[hi - 1]]));
                value = (1 - w) * v[ms.order()[lo - 1]] + w * v[ms.order()[hi - 1]];
            } else {
                int k = VarMath.rank(n, calc.confidence());
                rows.add(row(k, ms.order()[k - 1], scenarios, v[ms.order()[k - 1]]));
                value = v[ms.order()[k - 1]];
            }
            List<Step> steps = List.of(
                    Steps.of("worst", "The scope's P&L at rank k of " + n + " scenarios, worst first (k = ceil(N (1 - " + calc.confidence() + ")))",
                            "sum($.rows, 'PNL')", Map.of("rows", rows), value),
                    Steps.of("var", "VaR is the loss there", "0 - step.worst", Map.of(), ms.var()));
            return new Explanation(cell, ms.var(), steps);
        }
        if ("ES".equals(column)) {
            int k = VarMath.rank(n, calc.esConfidence());
            List<Map<String, Object>> rows = new ArrayList<>();
            double s = 0;
            for (int i = 0; i < k; i++) {
                rows.add(row(i + 1, ms.order()[i], scenarios, v[ms.order()[i]]));
                s += v[ms.order()[i]];
            }
            List<Step> steps = List.of(
                    Steps.of("tail", "The sum of the " + k + " worst scenario P&Ls (k = ceil(N (1 - " + calc.esConfidence() + ")))",
                            "sum($.rows, 'PNL')", Map.of("rows", rows), s),
                    Steps.of("es", "ES is minus their mean", "0 - step.tail / $.k", Map.of("k", (long) k), ms.es()));
            return new Explanation(cell, ms.es(), steps);
        }
        throw new EngineException("ENG-3005", Kind.REFUSED, column + " is not VAR or ES");
    }

    private static Map<String, Object> row(int rank, int scenario, List<java.time.LocalDate> scenarios, double pnl) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("RANK", (long) rank);
        m.put("SCENARIO", (long) scenario);
        m.put("DATE", scenarios.get(scenario).toString());
        m.put("PNL", pnl);
        return m;
    }

    private Explanation opaque(VarBook b, Cell cell, VarCalc calc) throws EngineException {
        String node = cell.key().get("NODE_ID");
        String child = cell.key().get("CHILD");
        if (node == null || child == null || b.node(node) == null || b.node(child) == null) {
            throw new EngineException("ENG-3005", Kind.REFUSED, "the cell is not in this request's answer");
        }
        double[] v = b.aggregate(node);
        VarCalc.Measures ms = calc.measures(v);
        List<VarCalc.Part> parts = new ArrayList<>();
        for (String c : new java.util.TreeSet<>(b.node(node).children())) {
            parts.add(new VarCalc.Part(c, c, "node", b.aggregate(c), b.node(c).members().size()));
        }
        for (VarCalc.Contribution c : calc.contributions(v, ms, parts)) {
            if (c.key().equals(child)) {
                double value = switch (cell.column()) {
                    case "COMPONENT_VAR" -> c.componentVar();
                    case "STANDALONE_VAR" -> c.standaloneVar();
                    case "INCREMENTAL_VAR" -> c.incrementalVar();
                    default -> throw new EngineException("ENG-3005", Kind.REFUSED, cell.column() + " is not a measure of node-components");
                };
                Step s = Steps.opaque(cell.column().toLowerCase(java.util.Locale.ROOT),
                        cell.column() + " of " + child + " in " + node,
                        "averaged over the ranks k ± " + calc.window() + " of the node's order and scaled to its VaR; recomputed in the workbook",
                        "Main!" + child, value);
                return new Explanation(cell, value, List.of(s));
            }
        }
        throw new EngineException("ENG-3005", Kind.REFUSED, child + " is not a child of " + node);
    }

    /** The cell's scope's aggregate vector: a node's, a hierarchy root's, or a basket's. */
    private double[] vector(VarBook b, MarketDay day, Cell cell) throws EngineException {
        switch (cell.output()) {
            case "node-var", "node-components" -> {
                String id = cell.key().get("NODE_ID");
                if (id == null || b.node(id) == null) {
                    throw new EngineException("ENG-3005", Kind.REFUSED, "the node " + id + " is not in this request's answer");
                }
                return b.aggregate(id);
            }
            case "headline" -> {
                VarBook.Node root = b.root(cell.key().get("HIERARCHY"));
                if (root == null) {
                    throw new EngineException("ENG-3005", Kind.REFUSED, "the hierarchy is not in this request's answer");
                }
                return b.aggregate(root.id());
            }
            case "basket-var" -> {
                String id = cell.key().get("BASKET_ID");
                Optional<MarketDay.Basket> pf = day.baskets.stream().filter(x -> x.id().equals(id)).findFirst();
                if (pf.isEmpty()) {
                    throw new EngineException("ENG-3005", Kind.REFUSED, "the basket " + id + " is not in this request's answer");
                }
                ScopeResolver r = new ScopeResolver(b, new ScopeResolver.Definitions() {
                    @Override
                    public Optional<String> portfolio(String x) {
                        return x.equals(id) ? Optional.of(pf.get().scope()) : Optional.empty();
                    }

                    @Override
                    public Optional<String> basket(String name) {
                        return Optional.empty();
                    }
                }, options.words());
                return b.vector(r.resolve("PORT:" + id).set(), sums);
            }
            default -> throw new EngineException("ENG-3005", Kind.REFUSED, "market-risk has no output " + cell.output());
        }
    }
}
