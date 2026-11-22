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

import com.ash.engine.Answer;
import com.ash.engine.Answers;
import com.ash.engine.Cell;
import com.ash.engine.Control;
import com.ash.engine.EngineException;
import com.ash.engine.EngineException.Kind;
import com.ash.engine.EngineMeter;
import com.ash.engine.Explaining;
import com.ash.engine.Explanation;
import com.ash.engine.Filter;
import com.ash.engine.MemoryBudget;
import com.ash.engine.Missing;
import com.ash.engine.Request;
import com.ash.engine.Session;
import com.ash.engine.Snapshot;
import com.ash.engine.Table;
import com.ash.engine.Visibility;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A market-risk session: one business date's inputs and the books built from them, one per (scenario set, window, currency,
 * visibility). A book is built at its first ask and kept for the session; a viewer's book is built from the rows that viewer may see,
 * so an answer never depends on rows outside the visibility (filter equivalence holds by construction).
 */
final class MarketSession implements Session, Explaining {

    private final MarketRiskEngine engine;
    private final Snapshot snapshot;
    private final MarketDay day;
    private final Map<MarketBooks.Key, MarketBooks.Built> books = new ConcurrentHashMap<>();
    private final List<MemoryBudget.Reservation> holds = new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile boolean closed;

    MarketSession(MarketRiskEngine engine, Snapshot snapshot, MarketDay day) {
        this.engine = engine;
        this.snapshot = snapshot;
        this.day = day;
    }

    @Override
    public Snapshot snapshot() {
        return snapshot;
    }

    @Override
    public long memoryBytes() {
        if (closed) {
            return 0;
        }
        long b = day.bytes();
        for (MarketBooks.Built x : books.values()) {
            b += x.book().heapBytes();
        }
        return b;
    }

    @Override
    public Answer ask(Request request, Control control) throws EngineException {
        engine.running();
        return engine.meter().measure(() -> answer(request, control), day.pv.size() + day.pnl.size(), 0);
    }

    private Answer answer(Request request, Control control) throws EngineException {
        List<Missing> missing = missing(request);
        if (!missing.isEmpty()) {
            return Answers.notFound(engine.context(), this, request, missing);
        }
        MarketBooks.Built built = book(request, control);
        if (built == null) {
            String cur = String.valueOf(request.param("currency"));
            return Answers.notFound(engine.context(), this, request, List.of(new Missing(MarketDay.FX, MarketDay.CONTRACT.get(MarketDay.FX),
                    snapshot, List.of("RATES of " + cur), request.outputs())));
        }
        List<Table> tables = new ArrayList<>();
        MarketTables t = new MarketTables(engine.descriptor(), engine.options(), engine.sums());
        for (String out : request.outputs()) {
            control.check();
            tables.add(switch (out) {
                case "node-var" -> t.nodeVar(built.book());
                case "node-components" -> t.nodeComponents(built.book(), control);
                case "basket-var" -> t.basketVar(built.book(), day);
                case "headline" -> t.headline(built.book());
                default -> throw new EngineException("ENG-3003", Kind.REFUSED, "market-risk has no output " + out);
            });
        }
        return Answers.complete(engine.context(), this, request, tables);
    }

    /** What the outputs asked need and the date does not have: the answer names it; nothing is substituted. */
    private List<Missing> missing(Request request) {
        List<Missing> out = new ArrayList<>();
        for (String name : List.of(MarketDay.SCENARIO_SET, MarketDay.FX)) {
            Missing m = day.missing.get(name);
            if (m != null) {
                out.add(new Missing(m.input(), m.contract(), m.snapshot(), m.columns(), request.outputs()));
            }
        }
        if (day.pv.isEmpty() && day.pnl.isEmpty()) {
            Missing m = day.missing.getOrDefault(MarketDay.SCENARIO_PV,
                    new Missing(MarketDay.SCENARIO_PV, MarketDay.CONTRACT.get(MarketDay.SCENARIO_PV), snapshot, List.of(), List.of()));
            out.add(new Missing(m.input(), m.contract(), m.snapshot(), m.columns(), request.outputs()));
        }
        if (request.outputs().contains("basket-var") && day.missing.containsKey(MarketDay.BASKET)) {
            Missing m = day.missing.get(MarketDay.BASKET);
            out.add(new Missing(m.input(), m.contract(), m.snapshot(), m.columns(), List.of("basket-var")));
        }
        if (out.isEmpty() && day.set(String.valueOf(request.param("scenario-set"))) == null) {
            out.add(new Missing(MarketDay.SCENARIO_SET, MarketDay.CONTRACT.get(MarketDay.SCENARIO_SET), snapshot,
                    List.of("SCENARIO_SET_ID " + request.param("scenario-set")), request.outputs()));
        }
        return out;
    }

    /** The visibility over the two vector inputs, each its own filter (a request without them is refused, ENG-4001, ENG-4002). */
    static Filter[] visibility(Request request) throws EngineException {
        Visibility v = request.visibility();
        if (v == null) {
            throw new EngineException("ENG-4001", Kind.NOT_PERMITTED, "the request has no visibility");
        }
        Filter pv = v.rows().get(MarketDay.SCENARIO_PV);
        Filter pnl = v.rows().get(MarketDay.PNL_VECTOR);
        if (pv == null || pnl == null) {
            throw new EngineException("ENG-4002", Kind.NOT_PERMITTED, "the request names no rows of scenario-pv and pnl-vector it may read");
        }
        return new Filter[] {pv, pnl};
    }

    MarketBooks.Built book(Request request, Control control) throws EngineException {
        Filter[] see = visibility(request);
        String setName = String.valueOf(request.param("scenario-set"));
        MarketDay.ScenarioSet set = day.set(setName);
        int window = ((Number) request.param("window")).intValue();
        String currency = String.valueOf(request.param("currency")).toUpperCase(java.util.Locale.ROOT);
        MarketBooks.Key key = new MarketBooks.Key(set.id(), window, currency, request.visibility().signature());
        MarketBooks.Built have = books.get(key);
        if (have != null) {
            engine.meter().cacheHit();
            return have;
        }
        engine.meter().cacheMiss();
        synchronized (books) {
            have = books.get(key);
            if (have != null) {
                return have;
            }
            control.check();
            long need = 16L * 1024 * 1024 + 2L * 8 * (long) set.dates().size() * (day.pv.size() + day.pnl.size());
            MemoryBudget.Reservation hold = engine.context().memory().reserve(need, "market-risk book " + key);
            MarketBooks.Built b;
            try {
                b = MarketBooks.build(day, set, window, currency, see[0], see[1], engine.options(), engine.sums());
            } catch (RuntimeException e) {
                hold.close();
                throw e;
            }
            if (b == null) {
                hold.close();
                return null;
            }
            holds.add(hold);
            books.put(key, b);
            return b;
        }
    }

    @Override
    public Explanation explain(Request request, Cell cell, Control control) throws EngineException {
        engine.running();
        MarketBooks.Built built = book(request, control);
        if (built == null) {
            throw new EngineException("ENG-3005", Kind.REFUSED, "the cell is not in this request's answer: the reporting currency has no FX");
        }
        return new MarketExplain(engine.options(), engine.sums()).explain(built, day, cell);
    }

    @Override
    public void workbook(Request request, OutputStream out, Control control) throws EngineException, IOException {
        engine.running();
        Answer a = ask(request, control);
        MarketBooks.Built built = a.tables().isEmpty() ? null : book(request, control);
        MarketWorkbook.write(out, built, engine.options(), engine.sums(), a);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        books.values().forEach(b -> b.book().close());
        books.clear();
        holds.forEach(MemoryBudget.Reservation::close);
        holds.clear();
        engine.meter().sessionClosed();
    }

    EngineMeter meter() {
        return engine.meter();
    }
}
