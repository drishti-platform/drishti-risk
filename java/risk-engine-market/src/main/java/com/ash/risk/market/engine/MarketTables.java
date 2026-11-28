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
import com.ash.engine.Table;
import com.ash.engine.Tables;
import com.ash.risk.kernel.vector.ParallelSums;
import com.ash.risk.market.calc.ScopeResolver;
import com.ash.risk.market.calc.VarBook;
import com.ash.risk.market.calc.VarCalc;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The answer tables, computed by the moved classes exactly as Drishti's goldens compute them: a node's aggregate from the book, the
 * measures from {@link VarCalc}, the children's contributions from {@link VarCalc#contributions}, a basket's members from the scope
 * grammar ({@link ScopeResolver}) and its vector from the book's deterministic sum.
 */
final class MarketTables {

    private final Descriptor descriptor;
    private final MarketOptions options;
    private final ParallelSums sums;

    MarketTables(Descriptor descriptor, MarketOptions options, ParallelSums sums) {
        this.descriptor = descriptor;
        this.options = options;
        this.sums = sums;
    }

    Table nodeVar(MarketBooks.Built built) {
        VarBook b = built.book();
        Tables.Builder t = Tables.of(descriptor.schema("node-var"));
        for (VarBook.Node x : b.nodes().values()) {
            VarCalc.Measures ms = built.derived().node(x.id());
            double limit = x.limit();
            Double use = Double.isNaN(limit) || limit == 0 ? null : ms.var() / limit;
            t.row(x.id(), x.hierarchy(), x.parent(), x.level(), x.name(), (long) x.members().size(), ms.var(), ms.es(),
                    Math.round(ms.var()), Math.round(ms.es()), ms.varScenario() < 0 ? null : (long) ms.varScenario(),
                    Double.isNaN(limit) ? null : limit, use);
        }
        return t.build();
    }

    Table nodeComponents(MarketBooks.Built built, Control control) throws EngineException {
        VarBook b = built.book();
        VarCalc calc = options.calc();
        Tables.Builder t = Tables.of(descriptor.schema("node-components"));
        for (VarBook.Node x : b.nodes().values()) {
            if (x.children().isEmpty()) {
                continue;
            }
            control.check();
            double[] v = b.aggregate(x.id());
            VarCalc.Measures ms = built.derived().node(x.id());
            List<VarCalc.Part> parts = new ArrayList<>();
            for (String c : new TreeSet<>(x.children())) {
                parts.add(new VarCalc.Part(c, c, "node", b.aggregate(c), b.node(c).members().size()));
            }
            for (VarCalc.Contribution c : calc.contributions(v, ms, parts, i -> built.derived().node(parts.get(i).key()))) {
                t.row(x.id(), c.key(), c.componentVar(), c.standaloneVar(), c.incrementalVar());
            }
        }
        return t.build();
    }

    Table basketVar(MarketBooks.Built built, MarketDay day, String currency) {
        VarBook b = built.book();
        Map<String, MarketDay.Basket> defs = new TreeMap<>();
        day.baskets.forEach(x -> defs.put(x.id(), x));
        ScopeResolver resolver = new ScopeResolver(b, new ScopeResolver.Definitions() {
            @Override
            public Optional<String> portfolio(String id) {
                return Optional.ofNullable(defs.get(id)).map(MarketDay.Basket::scope);
            }

            @Override
            public Optional<String> basket(String name) {
                return Optional.empty();
            }
        }, options.words());
        VarCalc calc = options.calc();
        Tables.Builder t = Tables.of(descriptor.schema("basket-var"));
        for (MarketDay.Basket pf : defs.values()) {
            ScopeResolver.Resolution r = resolver.resolve("PORT:" + pf.id());
            double[] v = b.vector(r.set(), sums);
            VarCalc.Measures ms = calc.measures(v);
            // in the reporting currency, as the nodes' limits (Drishti owner decision 2026-11-27: convert first; the recorded case re-recorded)
            Double limit = MarketBooks.limitIn(built, currency, day.basketLimits.get(pf.id()));
            Double use = limit == null || limit == 0 ? null : ms.var() / limit;
            t.row(pf.id(), pf.name(), pf.type(), pf.owner(), (long) r.set().size(), r.unknown().isEmpty() ? null : String.join(",", r.unknown()),
                    ms.var(), ms.es(), Math.round(ms.var()), Math.round(ms.es()), limit, use);
        }
        return t.build();
    }

    Table headline(MarketBooks.Built built) {
        VarBook b = built.book();
        Tables.Builder t = Tables.of(descriptor.schema("headline"));
        for (String h : b.hierarchies()) {
            VarBook.Node root = b.root(h);
            if (root == null) {
                continue;
            }
            VarCalc.Measures ms = built.derived().node(root.id());
            t.row(h, root.id(), ms.var(), ms.es(), Math.round(ms.var()), Math.round(ms.es()));
        }
        return t.build();
    }
}
