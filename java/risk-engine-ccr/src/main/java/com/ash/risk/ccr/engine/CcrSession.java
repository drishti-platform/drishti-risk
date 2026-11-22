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
import com.ash.risk.kernel.workbook.AnswerBook;
import com.ash.tools.xlsx.Sheet;
import com.ash.tools.xlsx.Workbook;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Set;
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

    /** The outputs answered from the exposure book (they need the run and the paths). */
    static final Set<String> PATHS = Set.of("exposure-summary", "exposure-profile", "netting-set-summary", "netting-set-collateral", "pre-deal",
            "pre-deal-buckets");
    /** The inputs each output cannot be answered without, beyond the run and the paths. */
    static final Map<String, List<String>> NEEDS = Map.of(
            "pre-deal", List.of(CcrExtras.LIMIT, CcrExtras.BUCKET),
            "pre-deal-buckets", List.of(CcrExtras.LIMIT, CcrExtras.BUCKET),
            "simm", List.of(CcrExtras.SENSITIVITY, CcrExtras.FACTOR, CcrExtras.SIMM),
            "simm-total", List.of(CcrExtras.SENSITIVITY, CcrExtras.FACTOR, CcrExtras.SIMM));

    /** What the outputs asked need and the date does not have, one per input, naming every output it stops. */
    private List<Missing> missing(Request request) {
        Map<String, Missing> out = new LinkedHashMap<>();
        for (String o : request.outputs()) {
            List<String> needs = new ArrayList<>();
            if (PATHS.contains(o)) {
                needs.add(CcrDay.RUN);
                needs.add(CcrDay.PATH);
            }
            needs.addAll(NEEDS.getOrDefault(o, List.of()));
            for (String input : needs) {
                Missing m = day.missing.get(input);
                if (m == null && input.equals(CcrDay.RUN) && day.run == null) {
                    m = new Missing(CcrDay.RUN, CcrDay.CONTRACT.get(CcrDay.RUN), snapshot, List.of(), List.of());
                }
                if (m == null) {
                    continue;
                }
                Missing was = out.get(input);
                List<String> outs = new ArrayList<>(was == null ? List.of() : was.outputs());
                outs.add(o);
                out.put(input, new Missing(m.input(), m.contract(), m.snapshot(), m.columns(), outs));
            }
        }
        return List.copyOf(out.values());
    }

    /** What one request computed, kept for its explanation and workbook. */
    private final class Computed {
        final Request request;
        final Control control;
        CcrPreDeal.Check check;
        List<CcrSimm.Run> simm;
        List<CcrCollateral.View> collateral;

        Computed(Request request, Control control) {
            this.request = request;
            this.control = control;
        }

        ExposureBook book() throws EngineException {
            return CcrSession.this.book(request, control);
        }

        CcrPreDeal.Check check() throws EngineException {
            if (check == null) {
                ExposureBook b = book();
                check = new CcrPreDeal(engine.descriptor(), engine.options(), day).check(request, b, new CcrPreDeal.Scopes() {
                    @Override
                    public ExposureCalc.Result evaluate(String scope) throws EngineException {
                        return CcrSession.this.evaluate(b, scope, false);
                    }

                    @Override
                    public Map<String, double[]> paths(java.util.Collection<String> trades) {
                        return CcrSession.this.paths(trades);
                    }
                });
            }
            return check;
        }

        List<CcrSimm.Run> simm() throws EngineException {
            if (simm == null) {
                simm = new CcrSimm(engine.descriptor(), engine.options(), day).runs(request, see(request, CcrExtras.SENSITIVITY));
            }
            return simm;
        }

        List<CcrCollateral.View> collateral() throws EngineException {
            if (collateral == null) {
                ExposureBook b = book();
                collateral = new CcrCollateral(engine.descriptor(), engine.options()).views(b, scope -> CcrSession.this.evaluate(b, scope, false), control);
            }
            return collateral;
        }
    }

    private Answer answer(Request request, Control control) throws EngineException {
        List<Missing> missing = missing(request);
        Computed c = new Computed(request, control);
        List<Table> tables = new ArrayList<>();
        for (String out : request.outputs()) {
            control.check();
            if (missing.stream().anyMatch(m -> m.outputs().contains(out))) {
                continue;
            }
            tables.add(switch (out) {
                case "exposure-summary" -> summary(c.book(), request);
                case "exposure-profile" -> profile(c.book(), request);
                case "netting-set-summary" -> sets(c.book(), control);
                case "netting-set-collateral" -> new CcrCollateral(engine.descriptor(), engine.options()).table(c.collateral());
                case "pre-deal" -> new CcrPreDeal(engine.descriptor(), engine.options(), day).table(c.check());
                case "pre-deal-buckets" -> new CcrPreDeal(engine.descriptor(), engine.options(), day).buckets(c.check());
                case "simm" -> new CcrSimm(engine.descriptor(), engine.options(), day).classes(c.simm());
                case "simm-total" -> new CcrSimm(engine.descriptor(), engine.options(), day).totals(c.simm());
                default -> throw new EngineException("ENG-3003", Kind.REFUSED, "ccr has no output " + out);
            });
        }
        if (tables.isEmpty()) {
            return Answers.notFound(engine.context(), this, request, missing);
        }
        return missing.isEmpty() ? Answers.complete(engine.context(), this, request, tables) : Answers.of(engine.context(), this, request, tables, missing);
    }

    /** The rows of one input the request may read (ENGINE_SPEC 8.6 rule 1). */
    static Filter see(Request request, String input) throws EngineException {
        if (request.visibility() == null) {
            throw new EngineException("ENG-4001", Kind.NOT_PERMITTED, "the request has no visibility");
        }
        Filter f = request.visibility().rows().get(input);
        if (f == null) {
            throw new EngineException("ENG-4002", Kind.NOT_PERMITTED, "the request names no rows of " + input + " it may read");
        }
        return f;
    }

    private static Filter visibility(Request request) throws EngineException {
        return see(request, CcrDay.PATH);
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
    ExposureCalc.Result evaluate(ExposureBook b, String scope, boolean standalone) throws EngineException {
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
    Map<String, double[]> paths(java.util.Collection<String> ids) {
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
        if (missing(request).stream().anyMatch(m -> m.outputs().contains(cell.output()))) {
            throw new EngineException("ENG-3005", Kind.REFUSED, "the cell is not in this request's answer: an input it needs is missing");
        }
        Computed c = new Computed(request, control);
        switch (cell.output()) {
            case "netting-set-collateral" -> {
                return new CcrCollateral(engine.descriptor(), engine.options()).explain(c.collateral(), cell);
            }
            case "pre-deal", "pre-deal-buckets" -> {
                return new CcrPreDeal(engine.descriptor(), engine.options(), day).explain(c.check(), cell);
            }
            case "simm", "simm-total" -> {
                return new CcrSimm(engine.descriptor(), engine.options(), day).explain(c.simm(), cell);
            }
            default -> {
            }
        }
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
        Computed c = new Computed(request, control);
        List<AnswerBook.Part> parts = new ArrayList<>();
        boolean exposure = false;
        boolean preDeal = false;
        boolean simm = false;
        for (String name : request.outputs()) {
            if (a.table(name) == null) {
                continue;
            }
            switch (name) {
                case "exposure-summary", "exposure-profile" -> {
                    if (!exposure) {
                        exposure = true;
                        parts.add(exposurePart(c.book(), request));
                    }
                }
                case "netting-set-collateral" -> parts.add(new CcrCollateral(engine.descriptor(), engine.options()).part(c.collateral()));
                case "pre-deal", "pre-deal-buckets" -> {
                    if (!preDeal) {
                        preDeal = true;
                        parts.add(new CcrPreDeal(engine.descriptor(), engine.options(), day).part(c.check()));
                    }
                }
                case "simm", "simm-total" -> {
                    if (!simm) {
                        simm = true;
                        parts.add(new CcrSimm(engine.descriptor(), engine.options(), day).part(c.simm()));
                    }
                }
                default -> {
                }
            }
        }
        AnswerBook.write(out, parts, String.join("; ", a.missing().stream().map(Missing::words).toList()));
    }

    /** The exposure part: the scope's EE and PFE profile as values; the peaks as MAX formulas, EPE, EEPE and CE the engine's. */
    private AnswerBook.Part exposurePart(ExposureBook b, Request request) throws EngineException {
        Table p = profile(b, request);
        Table s = summary(b, request);
        return new AnswerBook.Part() {
            private int first;
            private int last;

            @Override
            public int data(Sheet data, int at) {
                data.row(at).text(0, "POINT", "header").text(1, "DATE", "header").text(2, "EE", "header").text(3, "PFE", "header");
                for (int i = 0; i < p.rows(); i++) {
                    data.row(at + 1 + i).number(0, (long) i, "int").text(1, p.texts("DATE")[i], "normal").number(2, p.numbers("EE")[i], "decimal")
                            .number(3, p.numbers("PFE")[i], "decimal");
                }
                first = at + 1;
                last = Math.max(first, at + p.rows());
                return last;
            }

            @Override
            public int main(Workbook wb, Sheet main, int at) {
                main.row(at).text(0, "Peak EE", "bold").formula(1, "MAX(" + AnswerBook.range(2, first, last) + ")", s.numbers("PEAK_EE")[0], "decimal");
                main.row(at + 1).text(0, "Peak PFE", "bold").formula(1, "MAX(" + AnswerBook.range(3, first, last) + ")", s.numbers("PEAK_PFE")[0], "decimal");
                main.row(at + 2).text(0, "EPE", "bold").number(1, s.numbers("EPE")[0], "decimal");
                main.row(at + 3).text(0, "Effective EPE", "bold").number(1, s.numbers("EEPE")[0], "decimal");
                main.row(at + 4).text(0, "Current exposure", "bold").number(1, s.numbers("CE")[0], "decimal");
                wb.name(AnswerBook.name("exposure-summary", "PEAK_EE"), main.name(), "$B$" + at);
                wb.name(AnswerBook.name("exposure-summary", "PEAK_PFE"), main.name(), "$B$" + (at + 1));
                return at + 4;
            }
        };
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
