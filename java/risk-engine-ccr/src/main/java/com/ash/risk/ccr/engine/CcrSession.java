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
    static final Set<String> PATHS = Set.of("exposure-summary", "exposure-profile", "exposure-contributions", "scope-resolution", "netting-set-summary", "counterparty-summary",
            "exposure-tree", "exposure-tree-ee", "exposure-run", "exposure-flags", "exposure-distribution", "exposure-histogram", "exposure-fan",
            "netting-set-collateral", "pre-deal", "pre-deal-buckets", "exposure-what-if", "exposure-what-if-profile", "exposure-official", "exposure-official-profile", "exposure-trade-ee", "exposure-by-netting-set");
    /** The inputs each output cannot be answered without, beyond the run and the paths. */
    static final Map<String, List<String>> NEEDS = Map.ofEntries(
            Map.entry("pre-deal", List.of(CcrExtras.LIMIT, CcrExtras.BUCKET)),
            Map.entry("pre-deal-buckets", List.of(CcrExtras.LIMIT, CcrExtras.BUCKET)),
            Map.entry("simm", List.of(CcrExtras.SENSITIVITY, CcrExtras.FACTOR, CcrExtras.SIMM)),
            Map.entry("simm-total", List.of(CcrExtras.SENSITIVITY, CcrExtras.FACTOR, CcrExtras.SIMM)),
            Map.entry("simm-product", List.of(CcrExtras.SENSITIVITY, CcrExtras.FACTOR, CcrExtras.SIMM)),
            Map.entry("simm-version", List.of(CcrExtras.SENSITIVITY, CcrExtras.FACTOR, CcrExtras.SIMM)),
            Map.entry("simm-charge", List.of(CcrExtras.SENSITIVITY, CcrExtras.FACTOR, CcrExtras.SIMM)),
            Map.entry("simm-bucket", List.of(CcrExtras.SENSITIVITY, CcrExtras.FACTOR, CcrExtras.SIMM)),
            Map.entry("simm-factor", List.of(CcrExtras.SENSITIVITY, CcrExtras.FACTOR, CcrExtras.SIMM)),
            Map.entry("simm-group", List.of(CcrExtras.SENSITIVITY, CcrExtras.FACTOR, CcrExtras.SIMM)),
            Map.entry("simm-addon", List.of(CcrExtras.SENSITIVITY, CcrExtras.FACTOR, CcrExtras.SIMM)),
            Map.entry("simm-versions", List.of(CcrExtras.SIMM)));

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
        List<TreeNode> tree;
        Evaluated evaluated;
        CcrWhatIf.Done whatIf;
        List<CcrOfficial.Scope> official;

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

        Evaluated evaluated() throws EngineException {
            if (evaluated == null) {
                evaluated = evaluate(book(), request);
            }
            return evaluated;
        }

        List<CcrOfficial.Scope> official() throws EngineException {
            if (official == null) {
                ExposureBook b = book();
                ExposureCalc.Params prm = params(request);
                ExposureScopes.Groups gs = groups(request);
                official = new CcrOfficial(engine.descriptor()).evaluate(request, scope -> CcrSession.this.evaluate(b, scope, false, prm, gs),
                        ids -> CcrSession.this.members(b, ids, prm), control);
            }
            return official;
        }

        CcrWhatIf.Done whatIf() throws EngineException {
            if (whatIf == null) {
                ExposureBook b = book();
                ExposureCalc.Params prm = params(request);
                ExposureScopes.Groups gs = groups(request);
                whatIf = new CcrWhatIf(engine.descriptor(), engine.options()).compute(request, b, prm, new CcrWhatIf.Scopes() {
                    @Override
                    public ExposureCalc.Result evaluate(String scope) throws EngineException {
                        return CcrSession.this.evaluate(b, scope, false, prm, gs);
                    }

                    @Override
                    public String canonical(String scope) {
                        return new ExposureScopes(b, gs).resolve(scope).canonical();
                    }

                    @Override
                    public Map<String, double[]> paths(java.util.Collection<String> trades) {
                        return CcrSession.this.paths(trades);
                    }
                });
            }
            return whatIf;
        }

        List<TreeNode> tree() throws EngineException {
            if (tree == null) {
                tree = CcrSession.this.tree(book(), request, control);
            }
            return tree;
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
                case "exposure-contributions" -> contributions(c.book(), request, control);
                case "scope-resolution" -> resolution(c.book(), request);
                case "netting-set-summary" -> sets(c.book(), control);
                case "counterparty-summary" -> counterparties(c.book(), request, control);
                case "exposure-tree" -> treeTable(c.book(), request, c.tree());
                case "exposure-tree-ee" -> treeEe(c.tree());
                case "exposure-run" -> run(c.book());
                case "exposure-flags" -> flags(c.book(), request);
                case "exposure-distribution" -> distribution(c.book(), request, c.evaluated());
                case "exposure-histogram" -> histogram(c.book(), request, c.evaluated());
                case "exposure-fan" -> fan(c.book(), c.evaluated(), control);
                case "exposure-by-netting-set" -> bySet(c.book(), c.evaluated());
                case "exposure-official" -> new CcrOfficial(engine.descriptor()).table(c.book(), c.official(), params(request).q());
                case "exposure-official-profile" -> new CcrOfficial(engine.descriptor()).profile(c.book(), c.official());
                case "exposure-trade-ee" -> {
                    ExposureBook b = c.book();
                    ExposureCalc.Params prm = params(request);
                    yield new CcrOfficial(engine.descriptor()).tradeEe(b, scope -> evaluate(b, scope, false, prm), this::paths, control);
                }
                case "exposure-what-if" -> new CcrWhatIf(engine.descriptor(), engine.options()).table(c.whatIf());
                case "exposure-what-if-profile" -> new CcrWhatIf(engine.descriptor(), engine.options()).profile(c.book(), c.whatIf());
                case "netting-set-collateral" -> new CcrCollateral(engine.descriptor(), engine.options()).table(c.collateral());
                case "pre-deal" -> new CcrPreDeal(engine.descriptor(), engine.options(), day).table(c.check());
                case "pre-deal-buckets" -> new CcrPreDeal(engine.descriptor(), engine.options(), day).buckets(c.check());
                case "simm" -> new CcrSimm(engine.descriptor(), engine.options(), day).classes(c.simm());
                case "simm-total" -> new CcrSimm(engine.descriptor(), engine.options(), day).totals(c.simm());
                case "simm-product" -> new CcrSimm(engine.descriptor(), engine.options(), day).products(c.simm());
                case "simm-version" -> new CcrSimm(engine.descriptor(), engine.options(), day).versions(c.simm());
                case "simm-charge" -> new CcrSimm(engine.descriptor(), engine.options(), day).charges(c.simm());
                case "simm-bucket" -> new CcrSimm(engine.descriptor(), engine.options(), day).buckets(c.simm());
                case "simm-factor" -> new CcrSimm(engine.descriptor(), engine.options(), day).factors(c.simm());
                case "simm-group" -> new CcrSimm(engine.descriptor(), engine.options(), day).groups(c.simm());
                case "simm-addon" -> new CcrSimm(engine.descriptor(), engine.options(), day).addOnLines(c.simm());
                case "simm-versions" -> new CcrSimm(engine.descriptor(), engine.options(), day).versionList();
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
        return evaluate(b, scope, standalone, engine.params());
    }

    private ExposureCalc.Result evaluate(ExposureBook b, String scope, boolean standalone, ExposureCalc.Params prm) throws EngineException {
        return evaluate(b, scope, standalone, prm, g -> Optional.empty());
    }

    /**
     * The connected groups the request names ({@code groups}: GROUP, COUNTERPARTY rows, the host's reference): a {@code GROUP:<id>} scope reads
     * its members here, in the rows' order; without them a group is not known.
     */
    static ExposureScopes.Groups groups(Request request) {
        if (!(request.param("groups") instanceof com.ash.engine.Table t) || t.rows() == 0) {
            return g -> Optional.empty();
        }
        Map<String, List<String>> m = new LinkedHashMap<>();
        for (int i = 0; i < t.rows(); i++) {
            Object g = t.value("GROUP", i);
            Object c = t.value("COUNTERPARTY", i);
            if (g != null && c != null) {
                m.computeIfAbsent(g.toString(), x -> new ArrayList<>()).add(c.toString());
            }
        }
        return g -> Optional.ofNullable(m.get(g)).map(List::copyOf);
    }

    private ExposureCalc.Result evaluate(ExposureBook b, String scope, boolean standalone, ExposureCalc.Params prm, ExposureScopes.Groups groups)
            throws EngineException {
        ExposureScopes.Resolution r = new ExposureScopes(b, groups).resolve(scope == null || scope.isBlank() ? "BANK" : scope);
        if (!r.unknown().isEmpty() && r.trades().size() == 0) {
            throw new EngineException("RSK-3101", Kind.REFUSED, "the scope names what the date does not hold: " + String.join(", ", r.unknown()));
        }
        return ExposureCalc.evaluate(b, r.trades(), standalone || r.standalone(), prm, this::paths);
    }

    private Evaluated evaluate(ExposureBook b, Request request) throws EngineException {
        String scope = String.valueOf(request.param("scope"));
        ExposureCalc.Params prm = params(request);
        return new Evaluated(scope, evaluate(b, scope, Boolean.TRUE.equals(request.param("standalone")), prm, groups(request)), prm.q());
    }

    /**
     * The request's measure parameters over the engine's: {@code confidence} (0: the engine's pfe-confidence) and {@code collateral}
     * (empty: the engine's model; none: the uncollateralised view). The book is the same; only the measures read it differently.
     */
    ExposureCalc.Params params(Request request) throws EngineException {
        ExposureCalc.Params base = engine.params();
        Object c = request.param("confidence");
        double q = c instanceof Number n && n.doubleValue() > 0 ? n.doubleValue() : base.q();
        if (!(q > 0.5 && q <= 0.9999)) {
            throw new EngineException("RSK-3102", Kind.REFUSED, "confidence is above 0.5 and at most 0.9999, not " + q);
        }
        Object m = request.param("collateral");
        ExposureMath.Model model;
        try {
            model = m == null || String.valueOf(m).isBlank() ? base.model() : ExposureMath.Model.of(String.valueOf(m));
        } catch (IllegalArgumentException e) {
            throw new EngineException("RSK-3102", Kind.REFUSED, e.getMessage());
        }
        return q == base.q() && model == base.model() ? base : new ExposureCalc.Params(q, model, base.rule(), base.confidences(), base.window(),
                base.maxSets());
    }

    private record Evaluated(String scope, ExposureCalc.Result result, double q) {}

    /** A set of trades the host resolved, evaluated as a scope that is not standalone (a hierarchy node's trades: each netting set whole or partial). */
    private ExposureCalc.Result members(ExposureBook b, List<String> ids, ExposureCalc.Params prm) throws EngineException {
        int[] ord = new int[ids.size()];
        int n = 0;
        List<String> unknown = new ArrayList<>();
        for (String id : ids) {
            int t = b.tradeOrdinal(id);
            if (t < 0) {
                unknown.add(id);
            } else {
                ord[n++] = t;
            }
        }
        if (!unknown.isEmpty()) {
            throw new EngineException("RSK-3101", Kind.REFUSED, "the scope's members name trades the date does not hold: "
                    + String.join(", ", unknown.subList(0, Math.min(5, unknown.size()))));
        }
        java.util.Arrays.sort(ord, 0, n);
        return ExposureCalc.evaluate(b, com.ash.risk.kernel.vector.OrdinalSet.of(java.util.Arrays.copyOf(ord, n)), false, prm, this::paths);
    }

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
        int q = s.at(e.q());
        ExposureGrid g = b.grid();
        String peakDate = g.date(g.primaryAt(s.peakAt[q])).toString();
        return Tables.of(engine.descriptor().schema("exposure-summary"))
                .row(e.scope(), (long) e.result().trades.size(), s.ce, s.epe, s.eepe, s.peakEe, s.peakPfe[q], peakDate).build();
    }

    private Table profile(ExposureBook b, Request request) throws EngineException {
        Evaluated e = evaluate(b, request);
        ExposureMath.Stats s = e.result().stats;
        int q = s.at(e.q());
        ExposureGrid g = b.grid();
        Tables.Builder t = Tables.of(engine.descriptor().schema("exposure-profile"));
        for (int i = 0; i < g.primaryCount(); i++) {
            int k = g.primaryAt(i);
            t.row((long) i, g.date(k).toString(), g.tenor(k), s.ee[i], s.ene[i], s.eee[i], s.pfe[q][i], s.eeUncoll[i]);
        }
        return t.build();
    }

    /**
     * A scope's contributions to one figure by its parts ({@code by}: netting-set, counterparty, legal-entity, trade or asset; {@code figure}:
     * EE, EPE or PFE; {@code at}: a primary point, -1 the figure's own point), computed as Drishti's exposure route computes them
     * ({@link ExposureCalc#parts}, {@link ExposureCalc#contributions}), every part in the calculation's order ({@code RANK}).
     */
    private Table contributions(ExposureBook b, Request request, Control control) throws EngineException {
        String by = String.valueOf(request.param("by")).strip().toLowerCase(java.util.Locale.ROOT);
        String figure = String.valueOf(request.param("figure")).strip().toUpperCase(java.util.Locale.ROOT);
        if (!List.of("netting-set", "counterparty", "legal-entity", "trade", "asset").contains(by)) {
            throw new EngineException("RSK-3103", Kind.REFUSED, "by is netting-set, counterparty, legal-entity, trade or asset, not " + by);
        }
        if (!List.of("EE", "EPE", "PFE").contains(figure)) {
            throw new EngineException("RSK-3103", Kind.REFUSED, "figure is EE, EPE or PFE, not " + figure);
        }
        int at = request.param("at") instanceof Number n ? n.intValue() : -1;
        if (at < -1 || at >= b.grid().primaryCount()) {
            throw new EngineException("RSK-3103", Kind.REFUSED, "at is a primary point from 0 to " + (b.grid().primaryCount() - 1) + ", or -1, not " + at);
        }
        Evaluated e = evaluate(b, request);
        control.check();
        List<ExposureCalc.Contribution> cs = ExposureCalc.contributions(e.result(), ExposureCalc.parts(e.result(), by, this::paths), figure, at);
        Tables.Builder t = Tables.of(engine.descriptor().schema("exposure-contributions"));
        long rank = 0;
        for (ExposureCalc.Contribution c : cs) {
            t.row(rank++, c.key(), c.scope(), c.label(), c.kind(), c.value(), c.pct(), c.incremental());
        }
        return t.build();
    }

    /** How the request's scope reads on this book: its canonical text, its kind (bank, netting-set, counterparty, ...) and its id. */
    private Table resolution(ExposureBook b, Request request) {
        String scope = String.valueOf(request.param("scope"));
        ExposureScopes.Resolution r = new ExposureScopes(b, groups(request)).resolve(scope == null || scope.isBlank() ? "BANK" : scope);
        return Tables.of(engine.descriptor().schema("scope-resolution")).row(scope, r.canonical(), r.kind(), r.id(), (long) r.trades().size()).build();
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

    /**
     * Every counterparty the book holds (its netting sets with paths the request sees), in counterparty order: each one the scope
     * {@code CPTY:<id>} evaluated as exposure-summary evaluates it (its sets netted each, then added in the book's order), under the
     * request's confidence and collateral model; beside it the counterparty's stored credit limit (the pre-deal check's).
     */
    private Table counterparties(ExposureBook b, Request request, Control control) throws EngineException {
        ExposureCalc.Params prm = params(request);
        java.util.TreeSet<String> ids = new java.util.TreeSet<>();
        for (ExposureBook.SetInfo si : b.sets()) {
            if (si.counterparty() != null) {
                ids.add(si.counterparty());
            }
        }
        ExposureGrid g = b.grid();
        Tables.Builder t = Tables.of(engine.descriptor().schema("counterparty-summary"));
        for (String cp : ids) {
            control.check();
            ExposureCalc.Result r = evaluate(b, "CPTY:" + cp, false, prm);
            ExposureMath.Stats s = r.stats;
            int q = s.at(prm.q());
            String peakDate = s.peakAt[q] < 0 ? null : g.date(g.primaryAt(s.peakAt[q])).toString();
            CcrExtras.Limit l = day.extras.limits.get(cp);
            t.row(cp, (long) r.pieces.size(), (long) r.trades.size(), s.ce, s.epe, s.eepe, s.peakEe, s.peakPfe[q], peakDate, l == null ? null : l.id(),
                    l == null ? null : l.amount());
        }
        return t.build();
    }

    /** One node of the tree: its scope, kind and id, and its evaluation (null, with the note, when an interactive answer cannot sum it). */
    record TreeNode(String scope, String kind, String id, ExposureCalc.Result result, String note) {}

    /**
     * The tree of the book the request sees: every netting set with paths (in the book's order), every counterparty (in counterparty
     * order), the bank, and every legal entity (in order), each evaluated as exposure-summary evaluates its scope, under the request's
     * confidence and collateral model.
     */
    private List<TreeNode> tree(ExposureBook b, Request request, Control control) throws EngineException {
        ExposureCalc.Params prm = params(request);
        List<String[]> scopes = new ArrayList<>();
        java.util.TreeSet<String> cps = new java.util.TreeSet<>();
        java.util.TreeSet<String> les = new java.util.TreeSet<>();
        for (ExposureBook.SetInfo si : b.sets()) {
            scopes.add(new String[] {"NSET:" + si.id(), "netting-set", si.id()});
            if (si.counterparty() != null) {
                cps.add(si.counterparty());
            }
            if (si.legalEntity() != null) {
                les.add(si.legalEntity());
            }
        }
        cps.forEach(c -> scopes.add(new String[] {"CPTY:" + c, "counterparty", c}));
        scopes.add(new String[] {"BANK", "bank", "BANK"});
        les.forEach(l -> scopes.add(new String[] {"LE:" + l, "legal-entity", l}));
        List<TreeNode> out = new ArrayList<>();
        for (String[] s : scopes) {
            control.check();
            try {
                out.add(new TreeNode(s[0], s[1], s[2], evaluate(b, s[0], false, prm), null));
            } catch (ExposureCalc.TooLarge e) {
                out.add(new TreeNode(s[0], s[1], s[2], null, e.getMessage()));
            }
        }
        return out;
    }

    private Table treeTable(ExposureBook b, Request request, List<TreeNode> nodes) throws EngineException {
        double q = params(request).q();
        ExposureGrid g = b.grid();
        Tables.Builder t = Tables.of(engine.descriptor().schema("exposure-tree"));
        long order = 0;
        for (TreeNode n : nodes) {
            if (n.result() == null) {
                t.row(n.scope(), n.kind(), n.id(), null, null, null, null, null, null, "", n.note(), order++);
                continue;
            }
            ExposureMath.Stats s = n.result().stats;
            int a = s.at(q);
            String tenor = s.peakAt[a] < 0 ? null : g.tenor(g.primaryAt(s.peakAt[a]));
            t.row(n.scope(), n.kind(), n.id(), (long) n.result().pieces.size(), (long) n.result().trades.size(), s.epe, s.peakEe, s.peakPfe[a], tenor,
                    String.join(",", n.result().flags), null, order++);
        }
        return t.build();
    }

    private Table treeEe(List<TreeNode> nodes) {
        Tables.Builder t = Tables.of(engine.descriptor().schema("exposure-tree-ee"));
        for (TreeNode n : nodes) {
            if (n.result() != null) {
                double[] ee = n.result().stats.ee;
                for (int i = 0; i < ee.length; i++) {
                    t.row(n.scope(), (long) i, ee[i]);
                }
            }
        }
        return t.build();
    }

    /** The date's run as the book holds it: its id, the number of paths, the grid's dates and primary dates. */
    private Table run(ExposureBook b) {
        return Tables.of(engine.descriptor().schema("exposure-run")).row(b.runId(), (long) b.paths(), (long) b.grid().size(),
                (long) b.grid().primaryCount()).build();
    }

    /** The scope's netting sets and flags (partial, standalone, each of its netting sets' data-quality flags), as the evaluation gives them. */
    private Table flags(ExposureBook b, Request request) throws EngineException {
        Evaluated e = evaluate(b, request);
        return Tables.of(engine.descriptor().schema("exposure-flags")).row(e.scope(), (long) e.result().pieces.size(), String.join(",", e.result().flags)).build();
    }

    /**
     * The scope's netting sets as Drishti's scope answer lists them ({@code byNettingSet}): each part by netting set with its own statistics
     * over the scope's horizon at the request's confidence and its contribution to the scope's peak PFE, in the parts' order ({@code RANK}).
     */
    private Table bySet(ExposureBook b, Evaluated e) {
        ExposureCalc.Result r = e.result();
        List<ExposureCalc.Part> parts = ExposureCalc.parts(r, "netting-set", ids -> Map.of());
        Map<String, Double> pfe = new HashMap<>();
        for (ExposureCalc.Contribution c : ExposureCalc.contributions(r, parts, "PFE", -1)) {
            pfe.put(c.key(), c.value());
        }
        double[] times = ExposureCalc.times(b.grid());
        Tables.Builder t = Tables.of(engine.descriptor().schema("exposure-by-netting-set"));
        long rank = 0;
        for (ExposureCalc.Part pt : parts) {
            ExposureCalc.Piece pc = r.pieces.stream().filter(x -> b.set(x.set).id().equals(pt.key())).findFirst().orElseThrow();
            ExposureMath.Stats one = ExposureMath.statistics(pt.x(), new double[pt.x().length], null, times, b.paths(), r.stats.horizon,
                    new double[] {e.q()}, pc.ce, engine.params().rule());
            t.row(pt.key(), rank++, b.set(pc.set).counterparty(), !pc.whole, one.epe, one.peakEe, one.peakPfe[0], pfe.getOrDefault(pt.key(), 0.0));
        }
        return t.build();
    }

    /** The quantiles of exposure-fan: the path fan's (5 to 95%) and a distribution's (50 to 99%). */
    static final double[] FAN = {0.05, 0.25, 0.5, 0.75, 0.9, 0.95, 0.975, 0.99};

    /** The primary point of a distribution: the request's {@code at}, else the peak PFE's point at the request's confidence (the first when none). */
    private static int point(ExposureBook b, Request request, Evaluated e) throws EngineException {
        int at = request.param("at") instanceof Number n ? n.intValue() : -1;
        if (at < -1 || at >= b.grid().primaryCount()) {
            throw new EngineException("RSK-3103", Kind.REFUSED, "at is a primary point from 0 to " + (b.grid().primaryCount() - 1) + ", or -1, not " + at);
        }
        ExposureMath.Stats s = e.result().stats;
        return at >= 0 ? at : Math.max(0, s.peakAt[s.at(e.q())]);
    }

    /** A scope's exposure across the paths at one primary point: the point, its mean (EE), the share of paths without exposure, the range. */
    private Table distribution(ExposureBook b, Request request, Evaluated e) throws EngineException {
        int i = point(b, request, e);
        int pp = b.paths();
        double[] sorted = ExposureMath.sortedRow(e.result().e, i, pp);
        int zero = 0;
        for (double x : sorted) {
            zero += x <= 0 ? 1 : 0;
        }
        ExposureGrid g = b.grid();
        int k = g.primaryAt(i);
        return Tables.of(engine.descriptor().schema("exposure-distribution")).row(e.scope(), (long) i, g.date(k).toString(), g.tenor(k),
                e.result().stats.ee[i], (double) zero / pp, (long) pp, sorted[0], sorted[pp - 1]).build();
    }

    /** The histogram of that distribution: {@code bins} bins of equal width from the lowest to the highest path (one of width 1 when all are equal). */
    private Table histogram(ExposureBook b, Request request, Evaluated e) throws EngineException {
        int i = point(b, request, e);
        int n = request.param("bins") instanceof Number x ? x.intValue() : 42;
        if (n < 1 || n > 500) {
            throw new EngineException("RSK-3103", Kind.REFUSED, "bins is from 1 to 500, not " + n);
        }
        int pp = b.paths();
        double[] sorted = ExposureMath.sortedRow(e.result().e, i, pp);
        double lo = sorted[0];
        double hi = sorted[pp - 1];
        double w = hi > lo ? (hi - lo) / n : 1;
        long[] counts = new long[n];
        for (double x : sorted) {
            counts[Math.min(n - 1, (int) ((x - lo) / w))]++;
        }
        Tables.Builder t = Tables.of(engine.descriptor().schema("exposure-histogram"));
        for (int k = 0; k <= n; k++) {
            t.row((long) k, lo + k * w, k < n ? counts[k] : null);
        }
        return t.build();
    }

    /** The scope's exposure quantiles at every primary point ({@link #FAN}), under the engine's quantile rule. */
    private Table fan(ExposureBook b, Evaluated e, Control control) throws EngineException {
        ExposureGrid g = b.grid();
        ExposureMath.Rule rule = engine.params().rule();
        Tables.Builder t = Tables.of(engine.descriptor().schema("exposure-fan"));
        for (int i = 0; i < g.primaryCount(); i++) {
            control.check();
            double[] sorted = ExposureMath.sortedRow(e.result().e, i, b.paths());
            int k = g.primaryAt(i);
            for (double q : FAN) {
                t.row((long) i, q, g.date(k).toString(), g.tenor(k), g.time(k), ExposureMath.quantileSorted(sorted, q, rule));
            }
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
        List<String> keys = t.schema().key();
        for (int r = 0; r < t.rows(); r++) {
            final int row = r;
            if (keys.stream().allMatch(k -> same(t.value(k, row), cell.key().get(k)))) {
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

    /** A key cell against the asked key's value (a number by value: 1 and 1.0, 0.75 and "0.75" are the same). */
    private static boolean same(Object cell, Object asked) {
        if (cell instanceof Number a && asked != null) {
            try {
                return a.doubleValue() == (asked instanceof Number b ? b.doubleValue() : Double.parseDouble(asked.toString()));
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return String.valueOf(cell).equals(String.valueOf(asked));
    }

    /** The names the dedicated workbook parts define (each output's measures the formulas recompute). */
    private static final Map<String, Set<String>> DEFINED = Map.of(
            "exposure-summary", Set.of("PEAK_EE", "PEAK_PFE"),
            "netting-set-collateral", Set.of("PEAK_PFE_UNCOLLATERALISED", "PEAK_PFE", "COLLATERAL_BENEFIT"),
            "simm", Set.of("IM"),
            "simm-total", Set.of("SIMM", "TOTAL"),
            "pre-deal", Set.of("BEFORE", "AFTER", "INCREMENT", "USE_AFTER"),
            "pre-deal-buckets", Set.of("BEFORE", "AFTER", "USE_AFTER"));

    /**
     * The answer's first-row figures that no dedicated part recomputes (every other measure of every table asked), as the engine's values
     * with their defined names ({@code OUT_<output>_<column>}), so each figure of the answer has its cell in the workbook.
     */
    private static AnswerBook.Part figuresPart(Answer a, List<String> outputs) {
        return new AnswerBook.Part() {
            @Override
            public int data(Sheet data, int at) {
                data.row(at).text(0, "OUTPUT", "header").text(1, "COLUMN", "header").text(2, "VALUE", "header");
                return at;
            }

            @Override
            public int main(Workbook wb, Sheet main, int at) {
                main.row(at).text(0, "Figures (the engine's)", "bold");
                int row = at;
                for (String name : outputs) {
                    Table t = a.table(name);
                    if (t == null || t.rows() == 0) {
                        continue;
                    }
                    for (com.ash.engine.ColumnSchema col : t.schema().columns()) {
                        if (!col.measure() || t.isNull(col.name(), 0) || DEFINED.getOrDefault(name, Set.of()).contains(col.name())
                                || !(t.value(col.name(), 0) instanceof Number v)) {
                            continue;
                        }
                        row++;
                        main.row(row).text(0, name + " " + col.name(), "normal").number(1, v.doubleValue(), "decimal");
                        wb.name(AnswerBook.name(name, col.name()), main.name(), "$B$" + row);
                    }
                }
                return row;
            }
        };
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
        if (!parts.isEmpty() || request.outputs().stream().anyMatch(n -> a.table(n) != null)) {
            parts.add(figuresPart(a, request.outputs()));
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
