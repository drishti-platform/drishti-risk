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

import com.ash.engine.Answer;
import com.ash.engine.Answers;
import com.ash.engine.Cell;
import com.ash.engine.Control;
import com.ash.engine.EngineException;
import com.ash.engine.EngineException.Kind;
import com.ash.engine.Explaining;
import com.ash.engine.Explanation;
import com.ash.engine.Filter;
import com.ash.engine.MemoryBudget;
import com.ash.engine.Missing;
import com.ash.engine.Request;
import com.ash.engine.Session;
import com.ash.engine.Snapshot;
import com.ash.engine.Steps;
import com.ash.engine.Table;
import com.ash.engine.Tables;
import com.ash.risk.ccr.calc.ExposureBook;
import com.ash.risk.ccr.calc.ExposureCalc;
import com.ash.risk.ccr.calc.ExposureGrid;
import com.ash.risk.ccr.calc.ExposureMath;
import com.ash.risk.ccr.calc.ExposureScopes;
import com.ash.tools.xlsx.Sheet;
import com.ash.tools.xlsx.Workbook;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A CCR session: one business date's paths and terms, and the exposure book built from them per visibility (a viewer's book holds only
 * the trades that viewer may see). A scope is resolved by Drishti's scope words ({@link ExposureScopes}) and evaluated by
 * {@link ExposureCalc#evaluate}: netting by set, collateral under each set's CSA, the quantile rule; nothing is re-derived here.
 */
final class CcrSession implements Session, Explaining {

    private final CcrEngine engine;
    private final Snapshot snapshot;
    private final CcrDay day;
    private final Map<String, ExposureBook> books = new ConcurrentHashMap<>();
    private final List<MemoryBudget.Reservation> holds = new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile boolean closed;

    CcrSession(CcrEngine engine, Snapshot snapshot, CcrDay day) {
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
        return closed ? 0 : day.bytes() * (1 + books.size());
    }

    @Override
    public Answer ask(Request request, Control control) throws EngineException {
        engine.running();
        return engine.meter().measure(() -> answer(request, control), day.paths.size(), 0);
    }

    private Answer answer(Request request, Control control) throws EngineException {
        List<Missing> missing = new ArrayList<>();
        for (String name : List.of(CcrDay.RUN, CcrDay.PATH)) {
            Missing m = day.missing.get(name);
            if (m != null) {
                missing.add(new Missing(m.input(), m.contract(), m.snapshot(), m.columns(), request.outputs()));
            }
        }
        if (!missing.isEmpty() || day.run == null) {
            if (missing.isEmpty()) {
                missing.add(new Missing(CcrDay.RUN, CcrDay.CONTRACT.get(CcrDay.RUN), snapshot, List.of(), request.outputs()));
            }
            return Answers.notFound(engine.context(), this, request, missing);
        }
        ExposureBook b = book(request, control);
        List<Table> tables = new ArrayList<>();
        for (String out : request.outputs()) {
            control.check();
            tables.add(switch (out) {
                case "exposure-summary" -> summary(b, request);
                case "exposure-profile" -> profile(b, request);
                case "netting-set-summary" -> sets(b, control);
                default -> throw new EngineException("ENG-3003", Kind.REFUSED, "ccr has no output " + out);
            });
        }
        return Answers.complete(engine.context(), this, request, tables);
    }

    private static Filter visibility(Request request) throws EngineException {
        if (request.visibility() == null) {
            throw new EngineException("ENG-4001", Kind.NOT_PERMITTED, "the request has no visibility");
        }
        Filter f = request.visibility().rows().get(CcrDay.PATH);
        if (f == null) {
            throw new EngineException("ENG-4002", Kind.NOT_PERMITTED, "the request names no rows of exposure-path it may read");
        }
        return f;
    }

    private ExposureBook book(Request request, Control control) throws EngineException {
        Filter see = visibility(request);
        String key = request.visibility().signature();
        ExposureBook have = books.get(key);
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
            MemoryBudget.Reservation hold = engine.context().memory().reserve(16L * 1024 * 1024 + 2 * day.bytes(), "ccr book");
            try {
                ExposureBook b = day.book(see, engine.options(), control);
                holds.add(hold);
                books.put(key, b);
                return b;
            } catch (EngineException | RuntimeException e) {
                hold.close();
                throw e;
            }
        }
    }

    /** The scope's evaluation, by Drishti's scope words and calculator. */
    private ExposureCalc.Result evaluate(ExposureBook b, String scope, boolean standalone) throws EngineException {
        ExposureScopes.Resolution r = new ExposureScopes(b, g -> Optional.empty()).resolve(scope == null || scope.isBlank() ? "BANK" : scope);
        if (!r.unknown().isEmpty() && r.trades().size() == 0) {
            throw new EngineException("RSK-3101", Kind.REFUSED, "the scope names what the date does not hold: " + String.join(", ", r.unknown()));
        }
        return ExposureCalc.evaluate(b, r.trades(), standalone || r.standalone(), engine.params(), this::paths);
    }

    private Evaluated evaluate(ExposureBook b, Request request) throws EngineException {
        String scope = String.valueOf(request.param("scope"));
        return new Evaluated(scope, evaluate(b, scope, Boolean.TRUE.equals(request.param("standalone"))));
    }

    private record Evaluated(String scope, ExposureCalc.Result result) {}

    /** The trades' full paths (grid points × paths), for pieces that are part of a netting set. */
    private Map<String, double[]> paths(java.util.Collection<String> ids) {
        ExposureGrid g;
        try {
            g = day.grid();
        } catch (RuntimeException e) {
            return Map.of();
        }
        int p = (int) CcrDay.num(day.run, "PATHS");
        java.util.Set<String> want = new java.util.HashSet<>(ids);
        Map<String, double[]> out = new HashMap<>();
        for (CcrDay.PathRow r : day.paths) {
            if (want.contains(r.trade()) && r.mtm() != null) {
                double[] tp = out.computeIfAbsent(r.trade(), x -> new double[g.size() * p]);
                System.arraycopy(r.mtm(), 0, tp, r.k() * p, p);
            }
        }
        return out;
    }

    private Table summary(ExposureBook b, Request request) throws EngineException {
        Evaluated e = evaluate(b, request);
        ExposureMath.Stats s = e.result().stats;
        int q = s.at(engine.params().q());
        ExposureGrid g = b.grid();
        String peakDate = g.date(g.primaryAt(s.peakAt[q])).toString();
        return Tables.of(engine.descriptor().schema("exposure-summary"))
                .row(e.scope(), (long) e.result().trades.size(), s.ce, s.epe, s.eepe, s.peakEe, s.peakPfe[q], peakDate).build();
    }

    private Table profile(ExposureBook b, Request request) throws EngineException {
        Evaluated e = evaluate(b, request);
        ExposureMath.Stats s = e.result().stats;
        int q = s.at(engine.params().q());
        ExposureGrid g = b.grid();
        Tables.Builder t = Tables.of(engine.descriptor().schema("exposure-profile"));
        for (int i = 0; i < g.primaryCount(); i++) {
            int k = g.primaryAt(i);
            t.row((long) i, g.date(k).toString(), g.tenor(k), s.ee[i], s.ene[i], s.eee[i], s.pfe[q][i], s.eeUncoll[i]);
        }
        return t.build();
    }

    private Table sets(ExposureBook b, Control control) throws EngineException {
        Tables.Builder t = Tables.of(engine.descriptor().schema("netting-set-summary"));
        for (ExposureBook.SetInfo si : b.sets()) {
            control.check();
            ExposureCalc.Result r = evaluate(b, "NSET:" + si.id(), false);
            ExposureMath.Stats s = r.stats;
            int q = s.at(engine.params().q());
            t.row(si.id(), si.counterparty(), (long) r.trades.size(), s.ce, s.epe, s.eepe, s.peakPfe[q]);
        }
        return t.build();
    }

    @Override
    public Explanation explain(Request request, Cell cell, Control control) throws EngineException {
        engine.running();
        Answer a = ask(request, control);
        Table t = a.table(cell.output());
        if (t == null) {
            throw new EngineException("ENG-3005", Kind.REFUSED, "the cell is not in this request's answer");
        }
        String keyCol = t.schema().key().get(0);
        for (int r = 0; r < t.rows(); r++) {
            if (String.valueOf(t.value(keyCol, r)).equals(cell.key().get(keyCol))) {
                Object v = t.value(cell.column(), r);
                if (!(v instanceof Double)) {
                    throw new EngineException("ENG-3005", Kind.REFUSED, cell.column() + " is not a measure");
                }
                return new Explanation(cell, v, List.of(Steps.opaque(cell.column().toLowerCase(java.util.Locale.ROOT),
                        cell.column() + " of " + cell.key(),
                        "from the netted, collateralised exposure on every path and grid point (Drishti's exposure calculator); recomputed in the workbook",
                        "Main!" + cell.column(), v)));
            }
        }
        throw new EngineException("ENG-3005", Kind.REFUSED, "the cell is not in this request's answer");
    }

    @Override
    public void workbook(Request request, OutputStream out, Control control) throws EngineException, IOException {
        engine.running();
        Answer a = ask(request, control);
        try (Workbook wb = Workbook.to(out)) {
            Sheet data = wb.sheet("Data");
            if (a.tables().isEmpty()) {
                data.row(1).text(0, "No data", "header");
                wb.sheet("Main").row(1).text(0, String.join("; ", a.missing().stream().map(Missing::words).toList()), "normal");
                return;
            }
            Table p = profile(book(request, control), request);
            data.row(1).text(0, "POINT", "header").text(1, "DATE", "header").text(2, "EE", "header").text(3, "PFE", "header");
            for (int i = 0; i < p.rows(); i++) {
                data.next().number(0, (long) i, "int").text(1, p.texts("DATE")[i], "normal").number(2, p.numbers("EE")[i], "decimal")
                        .number(3, p.numbers("PFE")[i], "decimal");
            }
            int last = Math.max(2, p.rows() + 1);
            Table s = summary(book(request, control), request);
            Sheet main = wb.sheet("Main");
            main.row(1).text(0, "Peak EE", "bold").formula(1, "MAX(Data!$C$2:$C$" + last + ")", s.numbers("PEAK_EE")[0], "decimal");
            main.row(2).text(0, "Peak PFE", "bold").formula(1, "MAX(Data!$D$2:$D$" + last + ")", s.numbers("PEAK_PFE")[0], "decimal");
            main.row(3).text(0, "EPE", "bold").number(1, s.numbers("EPE")[0], "decimal");
            main.row(4).text(0, "Effective EPE", "bold").number(1, s.numbers("EEPE")[0], "decimal");
            main.row(5).text(0, "Current exposure", "bold").number(1, s.numbers("CE")[0], "decimal");
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        books.clear();
        holds.forEach(MemoryBudget.Reservation::close);
        holds.clear();
        engine.meter().sessionClosed();
    }
}
