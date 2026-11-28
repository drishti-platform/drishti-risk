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
import com.ash.engine.Descriptor;
import com.ash.engine.EngineException;
import com.ash.engine.EngineException.Kind;
import com.ash.engine.Filter;
import com.ash.engine.Request;
import com.ash.engine.Table;
import com.ash.engine.Tables;
import com.ash.risk.kernel.vector.OrdinalSet;
import com.ash.risk.kernel.vector.ParallelSums;
import com.ash.risk.market.calc.Backtest;
import com.ash.risk.market.calc.Columns;
import com.ash.risk.market.calc.VarBook;
import com.ash.risk.market.calc.VarCalc;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The computation contract's scope (ENGINE_INTEGRATION_DESIGN 18.1, EN-68): the host resolves every scope kind (a node, a system basket, a
 * scope expression, an ad-hoc or personal basket) to a row set and the engine never learns the kind. Two halves:
 *
 * <ul>
 *   <li><b>The index</b> ({@code scope-index}, {@code scope-positions}, {@code scope-position-values}, {@code scope-nodes},
 *       {@code scope-words}, {@code scope-limits}): what the engine derives from the rows and the host resolves over: each row's ordinal in
 *       the date's book (the canonical order of 18.4), its trade and position (the position rules are the engine's), the hierarchies'
 *       nodes as runs of ordinals, the positions' columns for rules, the hierarchy words and the system baskets' limits. Over the book of
 *       every row: a caller's visibility is applied to the row set, never by another book (EN-63).</li>
 *   <li><b>The measure</b> ({@code scope-var}): the VaR and ES of the rows the host names ({@code rows}, ordinals of the index), seen through
 *       the caller's visibility; a set that is a node whole ({@code node}) takes the node's stored aggregate, anything else the planner's
 *       sum, on the book converted to the reporting currency first: exactly how Drishti's in-tree route sums a scope, so the bits are its bits.</li>
 * </ul>
 */
final class MarketScopes {

    static final Set<String> OUTPUTS = Set.of("scope-index", "scope-positions", "scope-position-values", "scope-nodes", "scope-words",
            "scope-limits", "scope-var", "scope-correlation", "scope-path", "scope-scenarios",
            "scope-contributions", "scope-backtest", "scope-pmatrix", "scope-pcomponents");

    private final Descriptor descriptor;
    private final MarketOptions options;
    private final ParallelSums sums;
    private final MarketSession session;

    MarketScopes(Descriptor descriptor, MarketOptions options, ParallelSums sums, MarketSession session) {
        this.descriptor = descriptor;
        this.options = options;
        this.sums = sums;
        this.session = session;
    }

    /** One output; null when the book cannot be built (no FX for the reporting currency). */
    Table table(Request request, Control control, String out) throws EngineException {
        if (out.equals("scope-words")) {
            Tables.Builder t = Tables.of(descriptor.schema(out));
            options.words().forEach((w, h) -> t.row(w, h));
            return t.build();
        }
        if (out.equals("scope-limits")) {                               // in the reporting currency, as the nodes' limits (USD: as given)
            Object c = request.param("currency");
            String currency = c == null ? "USD" : String.valueOf(c);
            MarketBooks.Built usd = "USD".equalsIgnoreCase(currency) || currency.isBlank() ? null : session.allBook(request, control, "USD");
            Tables.Builder t = Tables.of(descriptor.schema(out));
            for (Map.Entry<String, Double> e : session.day().basketLimits.entrySet()) {
                t.row(e.getKey(), usd == null ? e.getValue() : MarketBooks.limitIn(usd, currency, e.getValue()));
            }
            return t.build();
        }
        MarketBooks.Built usd = session.allBook(request, control, "USD");
        if (usd == null) {
            return null;
        }
        VarBook b = usd.book();
        return switch (out) {
            case "scope-index" -> index(b);
            case "scope-positions" -> positions(b);
            case "scope-position-values" -> positionValues(inCurrency(request, control, usd).book());   // a rule's numbers in the reporting currency
            case "scope-nodes" -> nodes(b);
            case "scope-correlation" -> scopeCorrelation(request, control, usd);
            case "scope-path" -> scopePath(request, control, usd);
            case "scope-scenarios" -> scenarios(usd);
            case "scope-contributions" -> scopeContributions(request, control, usd);
            case "scope-backtest" -> scopeBacktest(request, control, usd);
            case "scope-pmatrix" -> scopeMatrix(request, control, usd);
            case "scope-pcomponents" -> scopeComponents(request, control, usd);
            default -> scopeVar(request, control, usd);
        };
    }

    /** The book of every row in the request's reporting currency (the same trades, positions and order as the USD book's). */
    private MarketBooks.Built inCurrency(Request request, Control control, MarketBooks.Built usd) throws EngineException {
        Object c = request.param("currency");
        String currency = c == null ? "" : String.valueOf(c);
        if (currency.isBlank() || "USD".equalsIgnoreCase(currency)) {
            return usd;
        }
        MarketBooks.Built b = session.allBook(request, control, currency.toUpperCase(java.util.Locale.ROOT));
        if (b == null) {
            throw new EngineException("ENG-3005", Kind.REFUSED, "the date's scenario set has no FX for " + currency);
        }
        return b;
    }

    private Table index(VarBook b) {
        Tables.Builder t = Tables.of(descriptor.schema("scope-index"));
        Columns tc = b.trades();
        for (int o = 0; o < b.tradeCount(); o++) {
            t.row((long) o, b.tradeId(o), (long) b.positionOf(o), b.supplied(o), b.cancelled(o), text(tc, "COUNTERPARTY", o),
                    text(tc, "LEGAL_ENTITY", o));
        }
        return t.build();
    }

    private Table positions(VarBook b) {
        Tables.Builder t = Tables.of(descriptor.schema("scope-positions"));
        for (int p = 0; p < b.positionCount(); p++) {
            List<Integer> runs = new ArrayList<>();
            b.forEachPositionRun(p, (from, to) -> {
                runs.add(from);
                runs.add(to);
            });
            t.row((long) p, b.positionId(p), toDoubles(runs));
        }
        return t.build();
    }

    /** The positions' columns, long form (a rule names any of them): texts and numbers, nulls left out. */
    private Table positionValues(VarBook b) {
        Tables.Builder t = Tables.of(descriptor.schema("scope-position-values"));
        Columns pc = b.positions();
        if (pc == null) {
            return t.build();
        }
        for (int p = 0; p < pc.rows(); p++) {
            for (String c : pc.textColumns()) {
                String v = pc.text(c, p);
                if (v != null) {
                    t.row((long) p, c, v, null);
                }
            }
            for (String c : pc.numberColumns()) {
                double v = pc.number(c, p);
                if (!Double.isNaN(v)) {
                    t.row((long) p, c, null, v);
                }
            }
        }
        return t.build();
    }

    private Table nodes(VarBook b) {
        Tables.Builder t = Tables.of(descriptor.schema("scope-nodes"));
        for (VarBook.Node n : b.nodes().values()) {
            List<Integer> runs = new ArrayList<>();
            n.members().forEachRun((from, to) -> {
                runs.add(from);
                runs.add(to);
            });
            t.row(n.id(), n.hierarchy(), n.parent(), n.level(), n.name(), Double.isNaN(n.limit()) ? null : n.limit(), toDoubles(runs));
        }
        return t.build();
    }

    /**
     * The rows' VaR and ES. {@code rows}: ordinals of {@code scope-index}; the caller's visibility selects from them. {@code node}: the node
     * the rows are (its stored aggregate and per-currency totals are taken when the visible rows are its members whole).
     */
    private Table scopeVar(Request request, Control control, MarketBooks.Built usd) throws EngineException {
        Summed a = summed(request, control, usd, request.param("rows"), request.param("node"));
        if (a == null) {
            return null;                                                // the reporting currency has no FX on the set: named as missing
        }
        VarCalc.Measures ms = a.measures(options.calc());
        Tables.Builder t = Tables.of(descriptor.schema("scope-var"));
        t.row((long) a.members(), (long) a.hidden(), ms.var(), ms.es(), ms.varScenario() < 0 ? null : (long) ms.varScenario(), a.v().clone());
        return t.build();
    }

    /**
     * The rows' P&amp;L path (EN-70: a path is the aggregate's vector, ranked): the summed scenario P&amp;L, the scenarios worst first in the
     * measures' own order (the order the VaR and ES are read from, so a ranked table and the VaR agree), the VaR rank and ES count, and a
     * histogram of {@code bins} equal bins. The VaR and ES are {@link #scopeVar}'s to the bit (the same sum and measures).
     */
    private Table scopePath(Request request, Control control, MarketBooks.Built usd) throws EngineException {
        Summed a = summed(request, control, usd, request.param("rows"), request.param("node"));
        if (a == null) {
            return null;
        }
        VarCalc.Measures ms = a.measures(options.calc());
        Object bp = request.param("bins");
        int bins = bp instanceof Number n ? n.intValue() : 40;
        if (bins < 1 || bins > 10_000) {
            throw new EngineException("ENG-3001", Kind.REFUSED, "bins is 1 to 10000, not " + bins);
        }
        VarCalc.Histogram h = VarCalc.histogram(a.v(), bins);
        double[] order = new double[ms.order().length];
        for (int i = 0; i < order.length; i++) {
            order[i] = ms.order()[i];
        }
        double[] counts = new double[h.counts().length];
        for (int i = 0; i < counts.length; i++) {
            counts[i] = h.counts()[i];
        }
        Tables.Builder t = Tables.of(descriptor.schema("scope-path"));
        t.row((long) a.members(), (long) a.hidden(), ms.var(), ms.es(), ms.varScenario() < 0 ? null : (long) ms.varScenario(), (long) ms.k(),
                (long) ms.esCount(), a.v().clone(), order, h.edges().clone(), counts);
        return t.build();
    }

    /**
     * The replayed history of a row set (Drishti's history of a scope without official records): the newest {@code days} scenario P&amp;Ls
     * of the set, summed as {@link #scopeVar} sums it, against the set's own VaR of the date, tested by {@link Backtest} (each day against
     * the VaR before it); in-sample, never a regulatory backtest.
     */
    private Table scopeBacktest(Request request, Control control, MarketBooks.Built usd) throws EngineException {
        Summed a = summed(request, control, usd, request.param("rows"), request.param("node"));
        if (a == null) {
            return null;
        }
        Object dp = request.param("days");
        int days = dp instanceof Number d ? d.intValue() : 250;
        if (days < 2) {
            throw new EngineException("ENG-3001", Kind.REFUSED, "days is 2 or more, not " + days);
        }
        double[] v = a.v();
        VarCalc.Measures ms = options.calc().measures(v);
        int n = Math.min(days, v.length);
        double[] pnl = java.util.Arrays.copyOfRange(v, v.length - n, v.length);
        double[] var = new double[n];
        java.util.Arrays.fill(var, ms.var());
        Backtest bt = Backtest.of(pnl, var, options.calc().confidence());
        double[] flags = new double[n];
        for (int i = 0; i < n; i++) {
            flags[i] = bt.exceptionFlags()[i] ? 1 : 0;
        }
        Tables.Builder t = Tables.of(descriptor.schema("scope-backtest"));
        t.row((long) a.members(), (long) (v.length - n), ms.var(), ms.es(), (long) bt.days(), (long) bt.exceptions(), bt.light(), bt.kupiecLr(),
                bt.kupiecPValue(), bt.christoffersenLr(), bt.christoffersenPValue(), pnl, flags);
        return t.build();
    }

    /**
     * The parts' parametric covariance and correlation matrix ({@code parts}: row sets ended by -1, in the host's order; {@code part-nodes}:
     * a part's node, for its stored aggregate when whole): each part summed as {@link #scopeVar} sums a row set, the matrix by
     * {@code ParametricVar.matrix} (one {@code dgemm} through the bound linear algebra, named in {@code ENGINE}). One row per pair.
     */
    private Table scopeMatrix(Request request, Control control, MarketBooks.Built usd) throws EngineException {
        if (!(request.param("parts") instanceof Collection<?> all)) {
            throw new EngineException("ENG-3001", Kind.REFUSED, "scope-pmatrix needs parts: row sets of scope-index, each ended by -1");
        }
        Object pn = request.param("part-nodes");
        String[] nodes = pn == null || String.valueOf(pn).isEmpty() ? new String[0] : String.valueOf(pn).split(",", -1);
        List<double[]> vectors = new ArrayList<>();
        List<Object> one = new ArrayList<>();
        for (Object x : all) {
            if (((Number) x).intValue() >= 0) {
                one.add(x);
                continue;
            }
            int i = vectors.size();
            Summed s = part(request, control, usd, one, i < nodes.length ? nodes[i] : null);
            if (s == null) {
                return null;
            }
            vectors.add(s.v());
            one = new ArrayList<>();
        }
        com.ash.risk.kernel.linalg.LinearAlgebra la = com.ash.risk.kernel.linalg.JavaBlas.INSTANCE;   // an engine loads no native library (8.4)
        double[][][] cm = com.ash.risk.market.calc.ParametricVar.matrix(vectors, options.parametric(), la);
        Tables.Builder t = Tables.of(descriptor.schema("scope-pmatrix"));
        for (int i = 0; i < vectors.size(); i++) {
            for (int j = 0; j < vectors.size(); j++) {
                t.row((long) i, (long) j, cm[0][i][j], cm[1][i][j], la.name());
            }
        }
        return t.build();
    }

    /**
     * Each part's figures in the rows' set ({@code parts}: row sets ended by -1; {@code part-nodes}: a part's node, for its stored aggregate
     * when whole): every part summed as {@link #scopeVar} sums a row set, measured by the calculator's {@code contributions} against the
     * set's own measures (the same sum and measures as scope-var: its VaR and ES to the bit).
     */
    private Table scopeContributions(Request request, Control control, MarketBooks.Built usd) throws EngineException {
        Summed a = summed(request, control, usd, request.param("rows"), request.param("node"));
        if (a == null) {
            return null;
        }
        if (!(request.param("parts") instanceof Collection<?> all)) {
            throw new EngineException("ENG-3001", Kind.REFUSED, "scope-contributions needs parts: row sets of scope-index, each ended by -1");
        }
        Object pn = request.param("part-nodes");
        String[] nodes = pn == null || String.valueOf(pn).isEmpty() ? new String[0] : String.valueOf(pn).split(",", -1);
        List<VarCalc.Part> parts = new ArrayList<>();
        List<Integer> members = new ArrayList<>();
        List<VarCalc.Measures> known = new ArrayList<>();
        List<Object> one = new ArrayList<>();
        for (Object x : all) {
            if (((Number) x).intValue() >= 0) {
                one.add(x);
                continue;
            }
            int i = parts.size();
            Summed s = part(request, control, usd, one, i < nodes.length ? nodes[i] : null);
            if (s == null) {
                return null;
            }
            parts.add(new VarCalc.Part(Integer.toString(i), null, null, s.v(), Double.NaN));
            known.add(s.own());
            members.add(s.members());
            one = new ArrayList<>();
        }
        VarCalc.Measures ms = a.measures(options.calc());
        List<VarCalc.Contribution> cs = options.calc().contributions(a.v(), ms, parts, known::get);
        Tables.Builder t = Tables.of(descriptor.schema("scope-contributions"));
        for (int i = 0; i < cs.size(); i++) {
            VarCalc.Contribution c = cs.get(i);
            t.row((long) i, (long) members.get(i), c.standaloneVar(), c.standaloneEs(), c.componentVar(), c.componentEs(), c.pct(),
                    c.incrementalVar());
        }
        return t.build();
    }

    /**
     * Each part's parametric component in the rows' set ({@code parts}: row sets ended by -1; {@code part-nodes}: a part's node, for its
     * stored aggregate when whole): every part summed as {@link #scopeVar} sums a row set, measured by {@code ParametricVar.component}
     * against the set's own normal fit (as node-pvar-components measures a node's children; Drishti's parametric columns of a scope's
     * positions). Within relative 1e-12, as node-pvar.
     */
    private Table scopeComponents(Request request, Control control, MarketBooks.Built usd) throws EngineException {
        Summed a = summed(request, control, usd, request.param("rows"), request.param("node"));
        if (a == null) {
            return null;
        }
        if (!(request.param("parts") instanceof Collection<?> all)) {
            throw new EngineException("ENG-3001", Kind.REFUSED, "scope-pcomponents needs parts: row sets of scope-index, each ended by -1");
        }
        Object pn = request.param("part-nodes");
        String[] nodes = pn == null || String.valueOf(pn).isEmpty() ? new String[0] : String.valueOf(pn).split(",", -1);
        com.ash.risk.market.calc.ParametricVar.Figures f = com.ash.risk.market.calc.ParametricVar.of(a.v(), options.calc().confidence(),
                options.calc().esConfidence(), options.parametric());
        Tables.Builder t = Tables.of(descriptor.schema("scope-pcomponents"));
        List<Object> one = new ArrayList<>();
        int i = 0;
        for (Object x : all) {
            if (((Number) x).intValue() >= 0) {
                one.add(x);
                continue;
            }
            Summed s = part(request, control, usd, one, i < nodes.length ? nodes[i] : null);
            if (s == null) {
                return null;
            }
            com.ash.risk.market.calc.ParametricVar.Component k = com.ash.risk.market.calc.ParametricVar.component(s.v(), a.v(), f);
            t.row((long) i, k.rho(), k.componentVar(), k.componentEs(), k.standaloneVar(), k.incrementalVar(), k.sigma(), k.mu(), k.cov());
            i++;
            one = new ArrayList<>();
        }
        return t.build();
    }

    /** A row set's summed USD scenario P&amp;L as scope-var sums it (the what-if's before on any scope the host resolved). */
    double[] usdRows(Request request, Control control) throws EngineException {
        MarketBooks.Built usd = session.allBook(request, control, "USD");
        if (usd == null) {
            throw new EngineException("ENG-3005", Kind.REFUSED, "the date's scenario set has no book in USD");
        }
        Summed a = summed(request, control, usd, request.param("rows"), request.param("node"));
        return a == null ? null : a.v();
    }

    /** The window's scenario dates, oldest first: what a scope's PNL index means. */
    private Table scenarios(MarketBooks.Built usd) {
        Tables.Builder t = Tables.of(descriptor.schema("scope-scenarios"));
        for (int j = 0; j < usd.scenarios().size(); j++) {
            t.row((long) j, usd.scenarios().get(j).toString());
        }
        return t.build();
    }

    /**
     * Correlation and beta (EN-70: a measure is a function of aggregates): of the rows' summed P&amp;L against another row set's
     * ({@code against}: a desk against the firm, a position against an index's rows), over the same scenarios, in the same currency.
     * Pearson correlation; beta the covariance over the other's variance; each sum in scenario order, so the bits are fixed by the inputs.
     */
    private Table scopeCorrelation(Request request, Control control, MarketBooks.Built usd) throws EngineException {
        Summed a = summed(request, control, usd, request.param("rows"), request.param("node"));
        Summed b = summed(request, control, usd, request.param("against"), null);
        if (a == null || b == null) {
            return null;
        }
        double[] x = a.v();
        double[] y = b.v();
        int n = Math.min(x.length, y.length);
        double mx = 0;
        double my = 0;
        for (int j = 0; j < n; j++) {
            mx += x[j];
            my += y[j];
        }
        mx /= n;
        my /= n;
        double sxx = 0;
        double syy = 0;
        double sxy = 0;
        for (int j = 0; j < n; j++) {
            double dx = x[j] - mx;
            double dy = y[j] - my;
            sxx += dx * dx;
            syy += dy * dy;
            sxy += dx * dy;
        }
        Double corr = sxx == 0 || syy == 0 ? null : sxy / Math.sqrt(sxx * syy);
        Double beta = syy == 0 ? null : sxy / syy;
        Tables.Builder t = Tables.of(descriptor.schema("scope-correlation"));
        t.row((long) a.members(), (long) b.members(), (long) n, corr, beta, n < 2 ? null : Math.sqrt(sxx / (n - 1)), n < 2 ? null : Math.sqrt(syy / (n - 1)));
        return t.build();
    }

    /**
     * A row set's summed scenario P&amp;L as the caller sees it, with its counts, and its measures when the session keeps them (a node
     * whole: the node's); null when the currency has no FX.
     */
    private record Summed(double[] v, int members, int hidden, VarCalc.Measures own) {

        /** The measures of the sum: the kept ones, else measured now (the same calculator on the same vector: the same bits). */
        VarCalc.Measures measures(VarCalc calc) {
            return own != null ? own : calc.measures(v);
        }
    }

    private Summed summed(Request request, Control control, MarketBooks.Built usd, Object rowsParam, Object nodeParam) throws EngineException {
        return sum(request, control, usd, rowsParam, nodeParam, false);
    }

    /**
     * One part of a scope (a child node, a position, a trade) summed as Drishti's in-tree parts are: a node whole is its aggregate, a
     * position whole its own row ({@link VarBook#positionVector}), anything else the planner's sum of its rows.
     */
    private Summed part(Request request, Control control, MarketBooks.Built usd, Object rowsParam, Object nodeParam) throws EngineException {
        return sum(request, control, usd, rowsParam, nodeParam, true);
    }

    private Summed sum(Request request, Control control, MarketBooks.Built usd, Object rowsParam, Object nodeParam, boolean part)
            throws EngineException {
        VarBook b = usd.book();
        OrdinalSet asked = rows(rowsParam, b.tradeCount());
        OrdinalSet set = asked.and(visible(request, usd, control));
        String node = nodeParam == null ? "" : String.valueOf(nodeParam);
        VarBook.Node n = node.isBlank() ? null : b.node(node);
        boolean whole = n != null && set.equals(n.members());
        String currency = String.valueOf(request.param("currency")).toUpperCase(java.util.Locale.ROOT);
        control.check();
        // the book converted to the reporting currency first, then summed (Drishti owner decision 2026-11-27: one rule in every currency;
        // node-var reads the same converted book, so a node whole is its figure to the bit)
        MarketBooks.Built cbuilt = "USD".equals(currency) || currency.isBlank() ? usd : inCurrency(request, control, usd);
        VarBook cb = cbuilt.book();
        if (whole) {
            return new Summed(cb.aggregate(n.id()), set.size(), asked.size() - set.size(), cbuilt.derived().node(n.id()));
        }
        int pi = part && !set.isEmpty() ? cb.positionOf(set.first()) : -1;
        if (pi >= 0 && cb.positionMemberCount(pi) == set.size() && cb.positionWithin(pi, set)) {
            return new Summed(cb.positionVector(pi), set.size(), asked.size() - set.size(), cbuilt.derived().position(pi));
        }
        return new Summed(cb.vector(set, sums), set.size(), asked.size() - set.size(), null);
    }

    /**
     * The book's rows the request's visibility lets the caller read (the in-tree view's {@code visible()}), made once per book and
     * visibility signature and kept with the book (session state): a caller who sees every row reads every row.
     */
    OrdinalSet visible(Request request, MarketBooks.Built built, Control control) throws EngineException {
        Filter[] see = MarketSession.visibility(request);
        VarBook b = built.book();
        if (see[0] == Filter.ALL && see[1] == Filter.ALL) {
            return OrdinalSet.range(0, b.tradeCount());
        }
        OrdinalSet have = built.derived().visible(request.visibility().signature());
        if (have == null) {
            have = built.derived().keepVisible(request.visibility().signature(), selected(see, b, control));
        }
        return have;
    }

    /**
     * The rows of the book a visibility passes: each vector input's key, trade and position and the filter's columns read in one pass
     * (no vector), each row tested under its own input's filter; a trade delivered in both inputs is tested as its scenario-PV row.
     */
    private OrdinalSet selected(Filter[] see, VarBook b, Control control) throws EngineException {
        Map<String, Boolean> passes = new HashMap<>();
        MarketDay day = session.day();
        MarketDay.Vectors[] inputs = {MarketDay.PV_INPUT, MarketDay.PNL_INPUT};
        for (int k = 0; k < inputs.length; k++) {
            com.ash.engine.InputTable t = day.vectors(inputs[k]);
            if (t == null) {
                continue;
            }
            Filter f = see[k];
            java.util.Set<String> cols = new java.util.TreeSet<>(f.columns());
            cols.add("TRADE_ID");
            cols.add("POSITION_ID");
            MarketRows rows = MarketRows.read(t, inputs[k].key(), cols, control);
            for (int r = 0; r < rows.size(); r++) {
                int row = r;
                String trade = rows.text("TRADE_ID", row);
                String id = trade != null ? trade : rows.text("POSITION_ID", row);
                if (id != null && !passes.containsKey(id)) {
                    passes.put(id, f == Filter.ALL || f.test(c -> rows.text(c, row)));
                }
            }
        }
        OrdinalSet.Builder out = new OrdinalSet.Builder();
        for (int o = 0; o < b.tradeCount(); o++) {
            if (Boolean.TRUE.equals(passes.get(b.tradeId(o)))) {
                out.add(o);
            }
        }
        return out.build();
    }

    private static OrdinalSet rows(Object p, int size) throws EngineException {
        if (!(p instanceof Collection<?> c)) {
            throw new EngineException("ENG-3001", Kind.REFUSED, "scope-var needs rows: the ordinals of scope-index the scope resolved to");
        }
        OrdinalSet.Builder b = new OrdinalSet.Builder();
        int last = -1;
        boolean sorted = true;
        int[] all = new int[c.size()];
        int i = 0;
        for (Object x : c) {
            int o = ((Number) x).intValue();
            if (o < 0 || o >= size) {
                throw new EngineException("ENG-3001", Kind.REFUSED, "row " + o + " is not a row of this date's book (0 to " + (size - 1) + ")");
            }
            sorted &= o > last;
            last = o;
            all[i++] = o;
        }
        if (!sorted) {
            java.util.Arrays.sort(all);
        }
        int prev = -1;
        for (int o : all) {
            if (o != prev) {
                b.add(o);
            }
            prev = o;
        }
        return b.build();
    }

    private static String text(Columns c, String column, int row) {
        return c != null && c.has(column) ? c.text(column, row) : null;
    }

    private static double[] toDoubles(List<Integer> l) {
        double[] d = new double[l.size()];
        for (int i = 0; i < d.length; i++) {
            d[i] = l.get(i);
        }
        return d;
    }
}
