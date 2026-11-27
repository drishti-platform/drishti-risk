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
import com.ash.risk.market.calc.Columns;
import com.ash.risk.market.calc.CurrencyTotals;
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
 *       sum, in USD or through the per-currency totals: exactly how Drishti's in-tree route sums a scope, so the bits are its bits.</li>
 * </ul>
 */
final class MarketScopes {

    static final Set<String> OUTPUTS = Set.of("scope-index", "scope-positions", "scope-position-values", "scope-nodes", "scope-words",
            "scope-limits", "scope-var", "scope-correlation", "scope-path", "scope-scenarios");

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
        if (out.equals("scope-limits")) {
            Tables.Builder t = Tables.of(descriptor.schema(out));
            session.day().basketLimits.forEach((id, l) -> t.row(id, l));
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
            case "scope-position-values" -> positionValues(b);
            case "scope-nodes" -> nodes(b);
            case "scope-correlation" -> scopeCorrelation(request, control, usd);
            case "scope-path" -> scopePath(request, control, usd);
            case "scope-scenarios" -> scenarios(usd);
            default -> scopeVar(request, control, usd);
        };
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
        VarCalc.Measures ms = options.calc().measures(a.v());
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
        VarCalc.Measures ms = options.calc().measures(a.v());
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

    /** A row set's summed scenario P&amp;L as the caller sees it, with its counts; null when the currency has no FX. */
    private record Summed(double[] v, int members, int hidden) {}

    private Summed summed(Request request, Control control, MarketBooks.Built usd, Object rowsParam, Object nodeParam) throws EngineException {
        VarBook b = usd.book();
        OrdinalSet asked = rows(rowsParam, b.tradeCount());
        OrdinalSet set = asked.and(visible(request, b));
        String node = nodeParam == null ? "" : String.valueOf(nodeParam);
        VarBook.Node n = node.isBlank() ? null : b.node(node);
        boolean whole = n != null && set.equals(n.members());
        String currency = String.valueOf(request.param("currency")).toUpperCase(java.util.Locale.ROOT);
        control.check();
        double[] v;
        if ("USD".equals(currency) || currency.isBlank()) {
            v = whole ? b.aggregate(n.id()) : b.vector(set, sums);
        } else {
            CurrencyTotals t = b.currencyTotals();
            CurrencyTotals.Sums s = whole && t != null && t.get(n.id()) != null ? t.get(n.id()) : CurrencyTotals.of(b, set, usd.fx(), sums);
            v = s.in(usd.fx(), currency);
            if (v == null) {
                return null;
            }
        }
        return new Summed(v, set.size(), asked.size() - set.size());
    }

    /** The book's rows the request's visibility lets the caller read (the in-tree view's {@code visible()}). */
    OrdinalSet visible(Request request, VarBook b) throws EngineException {
        Filter[] see = MarketSession.visibility(request);
        if (see[0] == Filter.ALL && see[1] == Filter.ALL) {
            return OrdinalSet.range(0, b.tradeCount());
        }
        Map<String, MarketDay.Row> pv = new HashMap<>();
        Map<String, MarketDay.Row> pnl = new HashMap<>();
        for (MarketDay.Row r : session.day().pv) {
            pv.put(MarketBooks.tradeKey(r), r);
        }
        for (MarketDay.Row r : session.day().pnl) {
            pnl.put(MarketBooks.tradeKey(r), r);
        }
        OrdinalSet.Builder out = new OrdinalSet.Builder();
        for (int o = 0; o < b.tradeCount(); o++) {
            String id = b.tradeId(o);
            MarketDay.Row r = pv.get(id);
            Filter f = see[0];
            if (r == null) {
                r = pnl.get(id);
                f = see[1];
            }
            if (r != null && (f == Filter.ALL || f.test(r::text))) {
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
