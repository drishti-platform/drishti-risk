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

import com.ash.engine.Control;
import com.ash.engine.Descriptor;
import com.ash.engine.EngineException;
import com.ash.engine.Request;
import com.ash.engine.Table;
import com.ash.engine.Tables;
import com.ash.risk.ccr.calc.ExposureBook;
import com.ash.risk.ccr.calc.ExposureCalc;
import com.ash.risk.ccr.calc.ExposureGrid;
import com.ash.risk.ccr.calc.ExposureMath;
import java.util.ArrayList;
import java.util.List;

/**
 * The figures of Drishti's official exposure record (EXPOSURE_GUIDE.md section 20) for the scopes the host names: each scope evaluated as
 * {@code exposure-summary} evaluates it (no interactive limit: the batch's), its figures at the configured confidences 95% and 99% and its
 * peak date at the request's confidence, its profile at every primary point; and each trade's EE contribution at every primary point from
 * its netting set's exposure (the record's audit of who drove the exposure), as Drishti's capture computes them. A scope the book cannot
 * answer is a row with its note, never a partial figure. The record itself (its versions, store, published tables) is the host's.
 */
final class CcrOfficial {

    /** One scope evaluated, or its note. */
    record Scope(String scope, ExposureCalc.Result result, String note) {}

    /** How the session evaluates a scope (the request's book, groups and measure parameters). */
    interface Scopes {
        ExposureCalc.Result evaluate(String scope) throws EngineException;
    }

    private final Descriptor descriptor;

    CcrOfficial(Descriptor descriptor) {
        this.descriptor = descriptor;
    }

    /** The scopes of {@code scopes} (a list), in order; each evaluated, or noted when the book cannot answer it. */
    List<Scope> evaluate(Request request, Scopes s, Control control) throws EngineException {
        List<Scope> out = new ArrayList<>();
        Object asked = request.param("scopes");
        if (!(asked instanceof List<?> l)) {
            return out;
        }
        for (Object o : l) {
            control.check();
            String scope = String.valueOf(o).strip();
            try {
                out.add(new Scope(scope, s.evaluate(scope), null));
            } catch (EngineException | IllegalArgumentException | IllegalStateException e) {
                out.add(new Scope(scope, null, e.getMessage()));
            }
        }
        return out;
    }

    Table table(ExposureBook b, List<Scope> scopes, double q) {
        ExposureGrid g = b.grid();
        Tables.Builder t = Tables.of(descriptor.schema("exposure-official"));
        for (Scope s : scopes) {
            if (s.result() == null) {
                t.row(s.scope(), null, null, null, null, null, null, null, null, null, null, "", s.note());
                continue;
            }
            ExposureMath.Stats st = s.result().stats;
            int q95 = st.at(0.95);
            int q99 = st.at(0.99);
            int qa = st.at(q);
            String peak = qa < 0 || st.peakAt[qa] < 0 ? null : g.date(g.primaryAt(st.peakAt[qa])).toString();
            t.row(s.scope(), (long) s.result().pieces.size(), (long) s.result().partialSets(), (long) s.result().trades.size(), st.ce, st.epe, st.eepe,
                    st.peakEe, q95 < 0 ? null : st.peakPfe[q95], q99 < 0 ? null : st.peakPfe[q99], peak, String.join(",", s.result().flags), null);
        }
        return t.build();
    }

    Table profile(ExposureBook b, List<Scope> scopes) {
        ExposureGrid g = b.grid();
        Tables.Builder t = Tables.of(descriptor.schema("exposure-official-profile"));
        for (Scope s : scopes) {
            if (s.result() == null) {
                continue;
            }
            ExposureMath.Stats st = s.result().stats;
            int q95 = st.at(0.95);
            int q99 = st.at(0.99);
            for (int i = 0; i < g.primaryCount(); i++) {
                int k = g.primaryAt(i);
                t.row(s.scope(), (long) i, g.date(k).toString(), g.tenor(k), st.ee[i], st.ene[i], st.eee[i], q95 < 0 ? null : st.pfe[q95][i],
                        q99 < 0 ? null : st.pfe[q99][i]);
            }
        }
        return t.build();
    }

    /**
     * Every trade's EE contribution at each primary point (its part of its netting set's exposure, the mean over the paths), every netting
     * set with paths in the book's order, each set's trades in the calculation's order ({@code ORDER}).
     */
    Table tradeEe(ExposureBook b, Scopes s, ExposureBook.TradePaths paths, Control control) throws EngineException {
        Tables.Builder t = Tables.of(descriptor.schema("exposure-trade-ee"));
        int p = b.paths();
        long order = 0;
        for (ExposureBook.SetInfo si : b.sets()) {
            control.check();
            ExposureCalc.Result r = s.evaluate("NSET:" + si.id());
            for (ExposureCalc.Part pt : ExposureCalc.parts(r, "trade", paths)) {
                for (int i = 0; i < b.grid().primaryCount(); i++) {
                    double sum = 0;
                    for (int j = 0; j < p; j++) {
                        sum += pt.x()[i * p + j];
                    }
                    t.row(pt.key(), si.id(), (long) i, order, sum / p);
                }
                order++;
            }
        }
        return t.build();
    }
}
