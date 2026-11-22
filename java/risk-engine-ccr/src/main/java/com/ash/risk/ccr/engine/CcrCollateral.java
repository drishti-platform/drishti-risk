/*
 * Project Drishti · Any data. Any domain. One grammar.
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

import com.ash.engine.Cell;
import com.ash.engine.Control;
import com.ash.engine.Descriptor;
import com.ash.engine.EngineException;
import com.ash.engine.EngineException.Kind;
import com.ash.engine.Explanation;
import com.ash.engine.Steps;
import com.ash.engine.Table;
import com.ash.engine.Tables;
import com.ash.risk.ccr.calc.CsaTerms;
import com.ash.risk.ccr.calc.ExposureBook;
import com.ash.risk.ccr.calc.ExposureCalc;
import com.ash.risk.ccr.calc.ExposureGrid;
import com.ash.risk.ccr.calc.ExposureMath;
import com.ash.risk.kernel.workbook.AnswerBook;
import com.ash.tools.xlsx.Sheet;
import com.ash.tools.xlsx.Workbook;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Netting and collateral by netting set (EXPOSURE_GUIDE.md sections 18.3 and 18.4): each set's CSA terms and holdings as the engine
 * reads them, its MtM today, and its current exposure, EPE and peak PFE without collateral (the netted value's positive part on every path,
 * by {@link ExposureMath#positive}) and with it (the scope evaluation under the set's CSA, {@link ExposureCalc}); the collateral's benefit
 * is the EPE it takes away.
 */
final class CcrCollateral {

    /** One set's two views. */
    record View(ExposureBook.SetInfo set, int trades, ExposureMath.Stats uncollateralised, ExposureMath.Stats collateralised, int q) {}

    private final Descriptor descriptor;
    private final CcrOptions options;

    CcrCollateral(Descriptor descriptor, CcrOptions options) {
        this.descriptor = descriptor;
        this.options = options;
    }

    /** The evaluator of a netting set under the request's visibility. */
    interface Sets {
        ExposureCalc.Result evaluate(String scope) throws EngineException;
    }

    List<View> views(ExposureBook b, Sets sets, Control control) throws EngineException {
        ExposureGrid g = b.grid();
        double[] times = ExposureCalc.times(g);
        int p = b.paths();
        List<View> out = new ArrayList<>();
        for (int s = 0; s < b.sets().size(); s++) {
            control.check();
            ExposureBook.SetInfo si = b.set(s);
            ExposureCalc.Result r = sets.evaluate("NSET:" + si.id());
            double[] v = b.value(s);
            ExposureMath.Stats un = ExposureMath.statistics(ExposureMath.positive(v, g, p), ExposureMath.negative(v, g, p), null, times, p,
                    Math.min(1.0, si.lifeYears()), new double[] {options.pfeConfidence()}, Math.max(si.v0(), 0), options.rule());
            out.add(new View(si, r.trades.size(), un, r.stats, r.stats.at(options.pfeConfidence())));
        }
        return out;
    }

    Table table(List<View> views) {
        Tables.Builder t = Tables.of(descriptor.schema("netting-set-collateral"));
        for (View x : views) {
            CsaTerms c = x.set().csa() == null ? CsaTerms.NONE : x.set().csa();
            t.row(x.set().id(), x.set().counterparty(), c.csaId(), c.margined(), c.thThem(), c.thUs(), c.mtaThem(), c.ia(), (long) c.mporDays(),
                    c.heldToday(), c.imHeld(), (long) x.trades(), x.set().v0(), x.uncollateralised().ce, x.collateralised().ce, x.uncollateralised().epe,
                    x.collateralised().epe, x.uncollateralised().peakPfe[0], x.collateralised().peakPfe[x.q()],
                    x.uncollateralised().epe - x.collateralised().epe);
        }
        return t.build();
    }

    Explanation explain(List<View> views, Cell cell) throws EngineException {
        for (View x : views) {
            if (!x.set().id().equals(cell.key().get("NETTING_SET"))) {
                continue;
            }
            double un = x.uncollateralised().epe;
            double co = x.collateralised().epe;
            return switch (cell.column()) {
                case "CE_UNCOLLATERALISED" -> new Explanation(cell, x.uncollateralised().ce, List.of(Steps.of("ce", "The netted MtM today, floored at 0",
                        "max($.mtm, 0)", Map.of("mtm", x.set().v0()), x.uncollateralised().ce)));
                case "COLLATERAL_BENEFIT" -> new Explanation(cell, un - co, List.of(Steps.of("benefit", "EPE without collateral less EPE with it",
                        "$.uncollateralised - $.collateralised", Map.of("uncollateralised", un, "collateralised", co), un - co)));
                case "CE", "EPE_UNCOLLATERALISED", "EPE", "PEAK_PFE_UNCOLLATERALISED", "PEAK_PFE" -> {
                    double v = switch (cell.column()) {
                        case "CE" -> x.collateralised().ce;
                        case "EPE_UNCOLLATERALISED" -> un;
                        case "EPE" -> co;
                        case "PEAK_PFE_UNCOLLATERALISED" -> x.uncollateralised().peakPfe[0];
                        default -> x.collateralised().peakPfe[x.q()];
                    };
                    yield new Explanation(cell, v, List.of(Steps.opaque(cell.column().toLowerCase(java.util.Locale.ROOT), cell.column() + " of " + x.set().id(),
                            "from the netted exposure on every path and grid point (Drishti's exposure calculator); the profile is in the workbook",
                            "Main!" + x.set().id(), v)));
                }
                default -> throw new EngineException("ENG-3005", Kind.REFUSED, cell.column() + " is not a measure of netting-set-collateral");
            };
        }
        throw new EngineException("ENG-3005", Kind.REFUSED, "the netting set is not in this request's answer");
    }

    /** The workbook part: each set's EE and PFE profiles with and without collateral as values; the peaks and the benefit as formulas. */
    AnswerBook.Part part(List<View> views) {
        return new AnswerBook.Part() {
            private final List<int[]> blocks = new ArrayList<>();

            @Override
            public int data(Sheet data, int at) {
                data.row(at).text(0, "NETTING_SET", "header").text(1, "POINT", "header").text(2, "PFE_UNCOLLATERALISED", "header")
                        .text(3, "PFE", "header");
                int r = at;
                for (View x : views) {
                    int from = r + 1;
                    for (int i = 0; i < x.collateralised().ee.length; i++) {
                        r++;
                        data.row(r).text(0, x.set().id(), "normal").number(1, i, "int").number(2, x.uncollateralised().pfe[0][i], "decimal")
                                .number(3, x.collateralised().pfe[x.q()][i], "decimal");
                    }
                    blocks.add(new int[] {from, r});
                }
                return r;
            }

            @Override
            public int main(Workbook wb, Sheet main, int at) {
                main.row(at).text(0, "NETTING_SET", "header").text(1, "PEAK_PFE_UNCOLLATERALISED", "header").text(2, "PEAK_PFE", "header")
                        .text(3, "EPE_UNCOLLATERALISED", "header").text(4, "EPE", "header").text(5, "COLLATERAL_BENEFIT", "header");
                int row = at;
                for (int i = 0; i < views.size(); i++) {
                    View x = views.get(i);
                    int[] b = blocks.get(i);
                    row++;
                    main.row(row).text(0, x.set().id(), "normal")
                            .formula(1, "MAX(" + AnswerBook.range(2, b[0], b[1]) + ")", x.uncollateralised().peakPfe[0], "decimal")
                            .formula(2, "MAX(" + AnswerBook.range(3, b[0], b[1]) + ")", x.collateralised().peakPfe[x.q()], "decimal")
                            .number(3, x.uncollateralised().epe, "decimal").number(4, x.collateralised().epe, "decimal")
                            .formula(5, "D" + row + "-E" + row, x.uncollateralised().epe - x.collateralised().epe, "decimal");
                    if (i == 0) {
                        wb.name(AnswerBook.name("netting-set-collateral", "PEAK_PFE_UNCOLLATERALISED"), main.name(), "$B$" + row);
                        wb.name(AnswerBook.name("netting-set-collateral", "PEAK_PFE"), main.name(), "$C$" + row);
                        wb.name(AnswerBook.name("netting-set-collateral", "COLLATERAL_BENEFIT"), main.name(), "$F$" + row);
                    }
                }
                return row;
            }
        };
    }
}
