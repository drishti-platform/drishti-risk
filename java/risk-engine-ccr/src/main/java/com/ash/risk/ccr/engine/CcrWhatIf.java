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

import com.ash.engine.Descriptor;
import com.ash.engine.EngineException;
import com.ash.engine.EngineException.Kind;
import com.ash.engine.Request;
import com.ash.engine.Table;
import com.ash.engine.Tables;
import com.ash.risk.ccr.calc.ExposureBook;
import com.ash.risk.ccr.calc.ExposureCalc;
import com.ash.risk.ccr.calc.ExposureGrid;
import com.ash.risk.ccr.calc.ExposureMath;
import com.ash.risk.ccr.calc.ExposureWhatIfMath;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The exposure what-if (EXPOSURE_GUIDE.md section 18.7) as Drishti's exposure route computes it. Method {@code paths} (exact): each
 * candidate's MtM on every path of the run ({@code COPY_OF} a booked trade scaled by {@code FACTOR}, or the supplied {@code MTM}) added to
 * its netting set path by path by the moved {@link ExposureWhatIfMath}; a candidate joins a netting set the scope covers whole, or none.
 * Method {@code addon} (the conservative proxy): each candidate a netting set of its own, uncollateralised, {@code max(MTM, 0) + z_q SF d
 * delta sqrt(min(t, M))} on the scope's own profile (SA-CCR's supervisory factors, CRE52). The engine prices nothing.
 */
final class CcrWhatIf {

    /** SA-CCR's supervisory factors by asset class (CRE52; single-name credit at an A rating). */
    static final Map<String, Double> SF = Map.of("rates", 0.005, "fx", 0.04, "credit", 0.0054, "equity", 0.32, "commodity", 0.18);

    /** The most netting sets one what-if's candidates join (each read whole). */
    static final int MAX_SETS = 50;

    /** One what-if, computed: the figures before and after, the profile, each candidate's figures (paths only). */
    record Done(String method, String canonical, String runId, double q, double[] before, double[] after, String tenorBefore, String tenorAfter,
            double[] eeBefore, double[] eeAfter, double[] pfeBefore, double[] pfeAfter, double[] added, List<ExposureWhatIfMath.Item> items) {}

    /** The scope evaluator of the session (the request's book, groups and measure parameters). */
    interface Scopes {
        ExposureCalc.Result evaluate(String scope) throws EngineException;

        String canonical(String scope);

        Map<String, double[]> paths(java.util.Collection<String> trades);
    }

    private final Descriptor descriptor;
    private final CcrOptions options;

    CcrWhatIf(Descriptor descriptor, CcrOptions options) {
        this.descriptor = descriptor;
        this.options = options;
    }

    Done compute(Request request, ExposureBook b, ExposureCalc.Params prm, Scopes scopes) throws EngineException {
        String scope = String.valueOf(request.param("scope")).strip();
        scope = scope.isEmpty() ? "BANK" : scope;
        String method = String.valueOf(request.param("method")).strip().toLowerCase(Locale.ROOT);
        if (!(request.param("candidates") instanceof Table t) || t.rows() == 0) {
            throw new EngineException("RSK-3160", Kind.REFUSED, "a what-if has candidates: [{ID, ...}]");
        }
        for (int i = 0; i < t.rows(); i++) {
            String run = text(t, "RUN_ID", i);
            if (run != null && !run.isBlank() && !b.runId().equals(run)) {
                throw new EngineException("RSK-3161", Kind.REFUSED, "the candidate's paths are not of the run " + b.runId() + " of " + b.businessDate()
                        + ": they are of " + run);
            }
        }
        ExposureCalc.Result base = scopes.evaluate(scope);
        String canonical = scopes.canonical(scope);
        return method.equals("addon") ? addon(b, base, t, prm.q(), canonical) : paths(b, base, t, prm, canonical, scopes);
    }

    /** The proxy, exactly as Drishti's in-tree addon method sums it. */
    private Done addon(ExposureBook b, ExposureCalc.Result base, Table t, double q, String canonical) {
        ExposureGrid g = b.grid();
        ExposureMath.Stats st = base.stats;
        int qa = st.at(q);
        double zq = z(q);
        double[] times = ExposureCalc.times(g);
        double[] addPfe = new double[times.length];
        double[] addEe = new double[times.length];
        double longest = 0;
        for (int c = 0; c < t.rows(); c++) {
            double mtm = num(t, "MTM0", c, 0);
            double notional = Math.abs(num(t, "NOTIONAL", c, 0));
            double delta = num(t, "DELTA", c, 1);
            String cls = factor(text(t, "ASSET_CLASS", c));
            double m = num(t, "LIFE_YEARS", c, 1);
            longest = Math.max(longest, m);
            double dAdj = cls.equals("rates") ? notional * (1 - Math.exp(-0.05 * m)) / 0.05 : notional;
            for (int i = 0; i < times.length; i++) {
                double alive = times[i] <= m ? 1 : 0;
                double spread = SF.get(cls) * dAdj * Math.abs(delta) * Math.sqrt(Math.min(times[i], m));
                addPfe[i] += alive * (Math.max(mtm, 0) + zq * spread);
                addEe[i] += alive * (Math.max(mtm, 0) + spread / Math.sqrt(2 * Math.PI));
            }
        }
        double peakWith = 0;
        double eeWithMax = 0;
        double epeWith = 0;
        double prev = 0;
        double hWith = Math.min(1.0, Math.max(st.horizon, longest));
        double[] eeAfter = new double[times.length];
        double[] pfeAfter = new double[times.length];
        double[] pfeBefore = new double[times.length];
        for (int i = 0; i < times.length; i++) {
            double pfeWith = st.pfe[qa][i] + addPfe[i];
            double eeWith = st.ee[i] + addEe[i];
            peakWith = Math.max(peakWith, pfeWith);
            eeWithMax = Math.max(eeWithMax, eeWith);
            if (hWith > 0 && prev < hWith - 1e-9) {
                epeWith += eeWith * (Math.min(times[i], hWith) - prev);
                prev = times[i];
            }
            eeAfter[i] = eeWith;
            pfeAfter[i] = pfeWith;
            pfeBefore[i] = st.pfe[qa][i];
        }
        epeWith = hWith > 0 ? epeWith / hWith : 0;
        double nan = Double.NaN;
        return new Done("addon", canonical, b.runId(), q, new double[] {nan, st.epe, nan, st.peakEe, st.peakPfe[qa]},
                new double[] {nan, epeWith, nan, eeWithMax, peakWith}, null, null, st.ee.clone(), eeAfter, pfeBefore, pfeAfter, addPfe, List.of());
    }

    /** The exact method, as Drishti's in-tree paths method reads the scope, its netting sets and the candidates. */
    private Done paths(ExposureBook b, ExposureCalc.Result base, Table t, ExposureCalc.Params prm, String canonical, Scopes scopes) throws EngineException {
        ExposureGrid g = b.grid();
        int len = g.size() * b.paths();
        List<ExposureWhatIfMath.Candidate> items = new ArrayList<>();
        Map<String, ExposureWhatIfMath.NettingSet> sets = new LinkedHashMap<>();
        for (int c = 0; c < t.rows(); c++) {
            String id = text(t, "ID", c);
            id = id == null || id.isBlank() ? "CANDIDATE-" + (c + 1) : id;
            String copy = text(t, "COPY_OF", c);
            double[] m;
            double life;
            double today = num(t, "MTM0", c, Double.NaN);
            if (copy != null && !copy.isBlank()) {
                int tr = b.tradeOrdinal(copy.strip());
                if (tr < 0) {
                    throw new EngineException("RSK-3162", Kind.REFUSED, "no exposure paths for the trade " + copy.strip() + " on " + b.businessDate() + " (run "
                            + b.runId() + ") that the request sees");
                }
                double f = num(t, "FACTOR", c, 1.0);
                ExposureBook.TradeInfo ti = b.trade(tr);
                double[] src = scopes.paths(List.of(ti.id())).get(ti.id());
                if (src == null) {
                    throw new EngineException("RSK-3162", Kind.REFUSED, "no exposure paths for the trade " + copy.strip() + " on " + b.businessDate());
                }
                m = new double[src.length];
                for (int i = 0; i < m.length; i++) {
                    m[i] = f * src[i];
                }
                life = ti.lifeYears();
                if (!has(t, "MTM0") || t.value("MTM0", c) == null) {
                    today = f * ti.mtm0();
                }
            } else {
                Object v = has(t, "MTM") ? t.value("MTM", c) : null;
                if (!(v instanceof double[] d)) {
                    throw new EngineException("RSK-3163", Kind.REFUSED, "method paths needs each candidate's paths on the run " + b.runId() + ": COPY_OF "
                            + "and FACTOR, or MTM (" + g.size() + " x " + b.paths() + " values); " + id + " has none");
                }
                m = d;
                life = num(t, "LIFE_YEARS", c, 1);
            }
            if (m.length != len) {
                throw new EngineException("RSK-3161", Kind.REFUSED, "the candidate " + id + "'s paths are not of the run " + b.runId() + ": " + m.length
                        + " values, the run has " + g.size() + " grid dates x " + b.paths() + " paths = " + len);
            }
            String set = text(t, "NETTING_SET", c);
            set = set == null ? "" : set.strip();
            String label = text(t, "LABEL", c);
            items.add(new ExposureWhatIfMath.Candidate(id, label == null ? id : label, set, m, Double.isFinite(today) ? today : Double.NaN, life));
            if (!set.isEmpty() && !sets.containsKey(set)) {
                if (sets.size() >= MAX_SETS) {
                    throw new EngineException("RSK-3164", Kind.REFUSED, "a what-if's candidates join at most " + MAX_SETS + " netting sets");
                }
                sets.put(set, set(b, base, set, canonical, scopes));
            }
        }
        double life = 0;
        for (ExposureCalc.Piece pc : base.pieces) {
            for (int tr : pc.trades) {
                life = Math.max(life, b.trade(tr).lifeYears());
            }
        }
        double[] times = ExposureCalc.times(g);
        ExposureWhatIfMath.Result w;
        try {
            w = ExposureWhatIfMath.of(new ExposureWhatIfMath.Scope(base.e, base.n, base.stats.ce, life), sets, items, g, b.paths(), times, prm.q(),
                    prm.model(), prm.rule(), canonical);
        } catch (IllegalArgumentException e) {
            throw new EngineException("RSK-3165", Kind.REFUSED, e.getMessage());
        }
        ExposureMath.Stats s0 = w.before();
        ExposureMath.Stats s1 = w.after();
        return new Done("paths", canonical, b.runId(), prm.q(), new double[] {s0.ce, s0.epe, s0.eepe, s0.peakEe, s0.peakPfe[0]},
                new double[] {s1.ce, s1.epe, s1.eepe, s1.peakEe, s1.peakPfe[0]}, tenor(g, s0.peakAt[0]), tenor(g, s1.peakAt[0]), s0.ee, s1.ee, s0.pfe[0],
                s1.pfe[0], null, w.items());
    }

    /** A netting set a candidate joins: one the scope covers whole, read whole as its own scope. */
    private static ExposureWhatIfMath.NettingSet set(ExposureBook b, ExposureCalc.Result base, String id, String canonical, Scopes scopes) throws EngineException {
        boolean whole = base.pieces.stream().anyMatch(pc -> pc.whole && b.set(pc.set).id().equals(id));
        if (!whole) {
            throw new EngineException("RSK-3166", Kind.REFUSED, "the netting set " + id + " is not one the scope " + canonical + " covers whole: a "
                    + "candidate joins a netting set of the scope (or none: a netting set of its own)");
        }
        ExposureCalc.Result s = scopes.evaluate("NSET:" + id);
        if (s.pieces.size() != 1 || !s.pieces.get(0).whole) {
            throw new EngineException("RSK-3166", Kind.REFUSED, "the netting set " + id + " is not seen whole: a candidate joins a netting set the "
                    + "request sees whole (or none: a netting set of its own)");
        }
        ExposureCalc.Piece pc = s.pieces.get(0);
        ExposureBook.SetInfo si = b.set(pc.set);
        return new ExposureWhatIfMath.NettingSet(si.id(), b.value(pc.set), s.e, s.n, si.csa(), si.v0(), pc.ce);
    }

    Table table(Done d) {
        Tables.Builder t = Tables.of(descriptor.schema("exposure-what-if"));
        t.row("BEFORE", null, null, d.canonical(), d.runId(), nn(d.before()[0]), nn(d.before()[1]), nn(d.before()[2]), nn(d.before()[3]),
                nn(d.before()[4]), d.tenorBefore(), null, null);
        t.row("AFTER", null, null, d.canonical(), d.runId(), nn(d.after()[0]), nn(d.after()[1]), nn(d.after()[2]), nn(d.after()[3]), nn(d.after()[4]),
                d.tenorAfter(), null, null);
        for (ExposureWhatIfMath.Item it : d.items()) {
            t.row("ITEM:" + it.id(), it.label(), it.set(), null, null, null, null, null, null, null, null, it.peakPfeAlone(), it.incrementalPeakPfe());
        }
        return t.build();
    }

    Table profile(ExposureBook b, Done d) {
        ExposureGrid g = b.grid();
        double[] times = ExposureCalc.times(g);
        Tables.Builder t = Tables.of(descriptor.schema("exposure-what-if-profile"));
        for (int i = 0; i < times.length; i++) {
            int k = g.primaryAt(i);
            t.row((long) i, g.date(k).toString(), g.tenor(k), times[i], d.eeBefore()[i], d.eeAfter()[i], d.pfeBefore()[i], d.pfeAfter()[i],
                    d.added() == null ? null : d.added()[i]);
        }
        return t.build();
    }

    private static Double nn(double x) {
        return Double.isFinite(x) ? x : null;
    }

    private static String tenor(ExposureGrid g, int i) {
        return i >= 0 && i < g.primaryCount() ? g.tenor(g.primaryAt(i)) : null;
    }

    /** The factor class of an asset class as the trades name it (Rates, FX, Credit, Equity, Commodity and their kin). */
    static String factor(String assetClass) {
        String a = assetClass == null ? "" : assetClass.trim().toLowerCase(Locale.ROOT);
        if (a.contains("fx") || a.contains("foreign")) {
            return "fx";
        }
        if (a.contains("credit")) {
            return "credit";
        }
        if (a.contains("equity") || a.contains("structured")) {
            return "equity";
        }
        if (a.contains("commodity")) {
            return "commodity";
        }
        return "rates";
    }

    /** The standard normal quantile (Acklam's approximation; |error| below 1.2e-9 over (0, 1)), as Drishti's addon method takes it. */
    static double z(double q) {
        double[] a = {-3.969683028665376e+01, 2.209460984245205e+02, -2.759285104469687e+02, 1.383577518672690e+02, -3.066479806614716e+01,
            2.506628277459239e+00};
        double[] bb = {-5.447609879822406e+01, 1.615858368580409e+02, -1.556989798598866e+02, 6.680131188771972e+01, -1.328068155288572e+01};
        double[] c = {-7.784894002430293e-03, -3.223964580411365e-01, -2.400758277161838e+00, -2.549732539343734e+00, 4.374664141464968e+00,
            2.938163982698783e+00};
        double[] d = {7.784695709041462e-03, 3.224671290700398e-01, 2.445134137142996e+00, 3.754408661907416e+00};
        double lo = 0.02425;
        if (q < lo) {
            double x = Math.sqrt(-2 * Math.log(q));
            return (((((c[0] * x + c[1]) * x + c[2]) * x + c[3]) * x + c[4]) * x + c[5]) / ((((d[0] * x + d[1]) * x + d[2]) * x + d[3]) * x + 1);
        }
        if (q > 1 - lo) {
            return -z(1 - q);
        }
        double x = q - 0.5;
        double r = x * x;
        return (((((a[0] * r + a[1]) * r + a[2]) * r + a[3]) * r + a[4]) * r + a[5]) * x
                / (((((bb[0] * r + bb[1]) * r + bb[2]) * r + bb[3]) * r + bb[4]) * r + 1);
    }

    private static boolean has(Table t, String col) {
        return t.schema().columns().stream().anyMatch(c -> c.name().equals(col));
    }

    private static String text(Table t, String col, int r) {
        if (!has(t, col)) {
            return null;
        }
        Object v = t.value(col, r);
        return v == null ? null : v.toString();
    }

    private static double num(Table t, String col, int r, double otherwise) {
        if (!has(t, col)) {
            return otherwise;
        }
        Object v = t.value(col, r);
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v == null || v.toString().isBlank()) {
            return otherwise;
        }
        try {
            return Double.parseDouble(v.toString());
        } catch (NumberFormatException e) {
            return otherwise;
        }
    }
}
