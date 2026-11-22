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
import com.ash.engine.Descriptor;
import com.ash.engine.EngineException;
import com.ash.engine.EngineException.Kind;
import com.ash.engine.Explanation;
import com.ash.engine.Filter;
import com.ash.engine.Request;
import com.ash.engine.Step;
import com.ash.engine.Steps;
import com.ash.engine.Table;
import com.ash.engine.Tables;
import com.ash.risk.ccr.simm.calc.Simm;
import com.ash.risk.ccr.simm.calc.SimmChoices;
import com.ash.risk.ccr.simm.calc.SimmInput;
import com.ash.risk.ccr.simm.calc.SimmParams;
import com.ash.risk.kernel.io.InputRows;
import com.ash.risk.kernel.workbook.AnswerBook;
import com.ash.tools.xlsx.Sheet;
import com.ash.tools.xlsx.Workbook;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * ISDA SIMM (SIMM_GUIDE.md): the initial margin of a netting set's sensitivities by the moved {@link Simm} under the bank's licensed
 * parameter file, delivered as an input (the engine carries no ISDA value; a toy file proves the formulas), for the bank's side
 * ({@code collect}) or the counterparty's ({@code post}: every sensitivity negated), with the method's switches (concentration, the
 * historical volatility ratios). SIMM is CCR's (the design places it with the counterparty engine). A scope's records are the ones the
 * request sees.
 */
final class CcrSimm {

    /** One scope's run. */
    record Run(String scope, String version, String direction, SimmInput input, Simm.Result result) {}

    private final Descriptor descriptor;
    private final CcrOptions options;
    private final CcrDay day;

    CcrSimm(Descriptor descriptor, CcrOptions options, CcrDay day) {
        this.descriptor = descriptor;
        this.options = options;
        this.day = day;
    }

    List<Run> runs(Request request, Filter see) throws EngineException {
        String asked = String.valueOf(request.param("simm-version")).strip();
        Map<String, String> files = day.extras.simmFiles;
        String version = asked.isEmpty() ? (files.isEmpty() ? null : new TreeMap<>(files).lastKey()) : asked;
        if (version == null || !files.containsKey(version)) {
            throw new EngineException("RSK-3160", Kind.REFUSED, "no SIMM version " + (asked.isEmpty() ? "" : asked + " ") + "in simm-parameters: the versions are "
                    + files.keySet());
        }
        SimmParams params;
        try {
            params = SimmParams.parse(files.get(version).getBytes(StandardCharsets.UTF_8), Map.of());
        } catch (RuntimeException e) {
            throw new EngineException("RSK-3161", Kind.REFUSED, "the SIMM file " + version + " is refused: " + e.getMessage());
        }
        SimmChoices choices = new SimmChoices(String.valueOf(request.param("concentration")), String.valueOf(request.param("hvr")), Map.of());
        boolean post = "post".equals(String.valueOf(request.param("direction")));
        String calc = String.valueOf(request.param("calculation-currency")).strip().toUpperCase(Locale.ROOT);
        Double rep = day.extras.usdPer.get(calc);
        if (rep == null) {
            throw new EngineException("RSK-3162", Kind.REFUSED, "no FX mid for the calculation currency " + calc + " (fx-spot)");
        }
        Double fileCcy = day.extras.usdPer.get(params.calculationCurrency);
        Map<String, SimmInput.Factor> factors = new TreeMap<>();
        for (Map<String, Object> r : day.extras.factors) {
            String name = InputRows.text(r, "RISK_FACTOR");
            factors.put(name, new SimmInput.Factor(name, InputRows.text(r, "SIMM_RISK_CLASS"), InputRows.text(r, "FACTOR_TYPE"), InputRows.text(r, "SIMM_BUCKET"),
                    InputRows.text(r, "QUALIFIER"), InputRows.text(r, "SIMM_LABEL1"), InputRows.text(r, "SIMM_LABEL2"), InputRows.text(r, "SENIORITY")));
        }
        String scope = String.valueOf(request.param("simm-scope")).strip();
        Map<String, List<Map<String, Object>>> byScope = new TreeMap<>();
        for (Map<String, Object> r : day.extras.sensitivities) {
            if (!InputRows.visible(see, r)) {
                continue;
            }
            String set = InputRows.text(r, "NETTING_SET");
            String key;
            if (scope.isEmpty()) {
                key = "NSET:" + (set == null ? "(none)" : set);
            } else if (scope.toUpperCase(Locale.ROOT).startsWith("NSET:")) {
                key = scope.substring(5).equals(set) ? scope : null;
            } else if (scope.toUpperCase(Locale.ROOT).startsWith("CPTY:")) {
                key = scope.substring(5).equals(InputRows.text(r, "COUNTERPARTY")) ? scope : null;
            } else {
                throw new EngineException("RSK-3163", Kind.REFUSED, "a SIMM scope is NSET:<netting set> or CPTY:<counterparty>, not " + scope);
            }
            if (key != null) {
                byScope.computeIfAbsent(key, x -> new ArrayList<>()).add(r);
            }
        }
        List<Run> out = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> e : byScope.entrySet()) {
            List<SimmInput.Trade> trades = new ArrayList<>();
            Map<String, Integer> index = new LinkedHashMap<>();
            List<SimmInput.Element> els = new ArrayList<>();
            for (Map<String, Object> r : e.getValue()) {
                String id = InputRows.text(r, "TRADE_ID") != null ? InputRows.text(r, "TRADE_ID") : InputRows.text(r, "POSITION_ID");
                Integer idx = index.get(id);
                if (idx == null) {
                    idx = trades.size();
                    index.put(id, idx);
                    String group = InputRows.text(r, "PRODUCT_GROUP");
                    trades.add(new SimmInput.Trade(id, options.productClass(group), group == null ? Map.of() : Map.of("PRODUCT_GROUP", group)));
                }
                String ccy = InputRows.text(r, "CURRENCY") == null ? calc : InputRows.text(r, "CURRENCY").toUpperCase(Locale.ROOT);
                Double per = day.extras.usdPer.get(ccy);
                if (per == null) {
                    continue;                                              // no FX for its currency: left out (the input names the rows)
                }
                els.add(new SimmInput.Element(idx, factors.get(InputRows.text(r, "RISK_FACTOR")), String.valueOf(InputRows.text(r, "MEASURE"))
                        .toUpperCase(Locale.ROOT), InputRows.number(r, "VALUE") * per / rep, InputRows.number(r, "IMPLIED_VOL")));
            }
            SimmInput in = new SimmInput(trades, els, calc, SimmInput.AddOns.NONE, fileCcy == null ? 1 : fileCcy / rep);
            out.add(new Run(e.getKey(), version, post ? "post" : "collect", in, Simm.run(in, params, choices, post)));
        }
        return out;
    }

    Table classes(List<Run> runs) {
        Tables.Builder t = Tables.of(descriptor.schema("simm"));
        for (Run r : runs) {
            for (Simm.ClassIm c : r.result().classes()) {
                t.row(r.scope(), c.productClass(), c.riskClass(), c.delta(), c.vega(), c.curvature(), c.baseCorr(), c.im());
            }
        }
        return t.build();
    }

    Table totals(List<Run> runs) {
        Tables.Builder t = Tables.of(descriptor.schema("simm-total"));
        for (Run r : runs) {
            Simm.Result x = r.result();
            t.row(r.scope(), r.version(), r.direction(), (long) r.input().trades().size(), x.simm(), x.addOn().total(), x.total(), x.regulatory(),
                    (long) x.refusals().size());
        }
        return t.build();
    }

    Explanation explain(List<Run> runs, Cell cell) throws EngineException {
        for (Run r : runs) {
            if (!r.scope().equals(cell.key().get("SCOPE"))) {
                continue;
            }
            Simm.Result x = r.result();
            if (cell.output().equals("simm")) {
                for (Simm.ClassIm c : x.classes()) {
                    if (c.productClass().equals(cell.key().get("PRODUCT_CLASS")) && c.riskClass().equals(cell.key().get("RISK_CLASS"))) {
                        List<Step> steps = List.of(Steps.opaque("delta", "The delta margin of " + c.riskClass() + " in " + c.productClass(),
                                "the buckets' K and the cross-bucket correlations of the SIMM version (SIMM paragraphs 7 to 9); the figures are the engine's",
                                "Data!" + c.productClass() + "-" + c.riskClass(), c.delta()),
                                Steps.of("im", "IM_r = Delta + Vega + Curvature + BaseCorr", "step.delta + $.vega + $.curvature + $.baseCorr",
                                        Map.of("vega", c.vega(), "curvature", c.curvature(), "baseCorr", c.baseCorr()), c.im()));
                        return new Explanation(cell, c.im(), steps);
                    }
                }
                break;
            }
            switch (cell.column()) {
                case "ADD_ON" -> {
                    return new Explanation(cell, x.addOn().total(), List.of(Steps.of("addon", "The fixed, notional and multiplier add-ons",
                            "$.fixed + $.notional + $.multipliers", Map.of("fixed", x.addOn().fixed(), "notional", x.addOn().notional(),
                                    "multipliers", x.addOn().multipliers()), x.addOn().total())));
                }
                case "SIMM" -> {
                    List<Map<String, Object>> rows = new ArrayList<>();
                    x.products().forEach(p -> rows.add(Map.<String, Object>of("PRODUCT_CLASS", p.productClass(), "SIMM", p.simm())));
                    return new Explanation(cell, x.simm(), List.of(Steps.of("simm", "The product classes' SIMM added (SIMM paragraph 2)",
                            "sum($.rows, 'SIMM')", Map.of("rows", rows), x.simm())));
                }
                case "TOTAL" -> {
                    return new Explanation(cell, x.total(), List.of(Steps.of("total", "SIMM plus the add-on", "$.simm + $.addOn",
                            Map.of("simm", x.simm(), "addOn", x.addOn().total()), x.total())));
                }
                default -> throw new EngineException("ENG-3005", Kind.REFUSED, cell.column() + " is not a measure of simm-total");
            }
        }
        throw new EngineException("ENG-3005", Kind.REFUSED, "the cell is not in this request's answer");
    }

    /** The workbook part: each class's four margins and each product class's IM vector as values; IM_r, SIMM_p, SIMM and TOTAL as formulas. */
    AnswerBook.Part part(List<Run> runs) {
        return new AnswerBook.Part() {
            private final List<int[]> blocks = new ArrayList<>();

            @Override
            public int data(Sheet data, int at) {
                data.row(at).text(0, "SCOPE", "header").text(1, "PRODUCT_CLASS", "header").text(2, "RISK_CLASS", "header").text(3, "DELTA", "header")
                        .text(4, "VEGA", "header").text(5, "CURVATURE", "header").text(6, "BASE_CORR", "header");
                int r = at;
                for (Run run : runs) {
                    int from = r + 1;
                    for (Simm.ClassIm c : run.result().classes()) {
                        r++;
                        data.row(r).text(0, run.scope(), "normal").text(1, c.productClass(), "normal").text(2, c.riskClass(), "normal")
                                .number(3, c.delta(), "decimal").number(4, c.vega(), "decimal").number(5, c.curvature(), "decimal")
                                .number(6, c.baseCorr(), "decimal");
                    }
                    blocks.add(new int[] {from, r});
                }
                return r;
            }

            @Override
            public int main(Workbook wb, Sheet main, int at) {
                main.row(at).text(0, "SCOPE", "header").text(1, "PRODUCT_CLASS", "header").text(2, "RISK_CLASS", "header").text(3, "IM", "header");
                int row = at;
                boolean named = false;
                for (int i = 0; i < runs.size(); i++) {
                    Run run = runs.get(i);
                    int[] b = blocks.get(i);
                    for (int k = 0; k < run.result().classes().size(); k++) {
                        Simm.ClassIm c = run.result().classes().get(k);
                        row++;
                        int d = b[0] + k;
                        main.row(row).text(0, run.scope(), "normal").text(1, c.productClass(), "normal").text(2, c.riskClass(), "normal")
                                .formula(3, "SUM(" + AnswerBook.cell(3, d) + ":" + AnswerBook.cell(6, d).replace("Data!", "") + ")", c.im(), "decimal");
                        if (!named) {
                            named = true;
                            wb.name(AnswerBook.name("simm", "IM"), main.name(), "$D$" + row);
                        }
                    }
                    Simm.Result x = run.result();
                    StringBuilder simm = new StringBuilder();
                    for (Simm.ProductIm p : x.products()) {
                        simm.append(simm.length() == 0 ? "" : "+").append(p.simm());
                    }
                    row++;
                    main.row(row).text(0, run.scope(), "bold").text(1, "SIMM (product classes added; each SIMM_p the engine's)", "normal")
                            .formula(3, simm.length() == 0 ? "0" : simm.toString(), x.simm(), "decimal");
                    row++;
                    main.row(row).text(0, run.scope(), "bold").text(1, "TOTAL", "normal").formula(3, "D" + (row - 1) + "+" + x.addOn().total(), x.total(), "decimal");
                    if (i == 0) {
                        wb.name(AnswerBook.name("simm-total", "SIMM"), main.name(), "$D$" + (row - 1));
                        wb.name(AnswerBook.name("simm-total", "TOTAL"), main.name(), "$D$" + row);
                    }
                }
                return row;
            }
        };
    }
}
