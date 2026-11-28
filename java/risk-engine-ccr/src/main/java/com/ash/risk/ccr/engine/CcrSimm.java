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
import com.ash.engine.Filter;
import com.ash.engine.Request;
import com.ash.engine.Step;
import com.ash.engine.Steps;
import com.ash.engine.Table;
import com.ash.engine.Tables;
import com.ash.risk.ccr.simm.calc.Simm;
import com.ash.risk.ccr.simm.calc.SimmChoices;
import com.ash.risk.ccr.simm.calc.SimmExamples;
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

    /** The scope of the hand-built examples (the moved {@link SimmExamples}). */
    static final String EXAMPLE = "EXAMPLE";

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

    List<Run> runs(Request request, Filter see, Control control) throws EngineException {
        CcrExtras.SimmInputs in0 = day.simm(control);
        String asked = String.valueOf(request.param("simm-version")).strip();
        Map<String, String> files = in0.simmFiles;
        String version = asked.isEmpty() ? (files.isEmpty() ? null : new TreeMap<>(files).lastKey()) : asked;
        if (version == null || !files.containsKey(version)) {
            throw new EngineException("RSK-3160", Kind.REFUSED, "no SIMM version " + (asked.isEmpty() ? "" : asked + " ") + "in simm-parameters: the versions are "
                    + files.keySet());
        }
        SimmParams params;
        try {
            params = in0.parameters(version);                           // parsed once a session
        } catch (RuntimeException e) {
            throw new EngineException("RSK-3161", Kind.REFUSED, "the SIMM file " + version + " is refused: " + e.getMessage());
        }
        Map<String, String> overrides = pairs(request.param("simm-overrides"), "simm-overrides");
        if (!overrides.isEmpty()) {
            try {
                params = SimmParams.parse(files.get(version).getBytes(StandardCharsets.UTF_8), overrides);   // what-if parameters: never the regulatory figure
            } catch (IllegalArgumentException e) {
                throw new EngineException("RSK-3164", Kind.REFUSED, e.getMessage());
            }
        }
        SimmChoices choices = new SimmChoices(String.valueOf(request.param("concentration")), String.valueOf(request.param("hvr")), overrides);
        boolean post = "post".equals(String.valueOf(request.param("direction")));
        String calc = String.valueOf(request.param("calculation-currency")).strip().toUpperCase(Locale.ROOT);
        Double rep = in0.usdPer.get(calc);
        if (rep == null) {
            throw new EngineException("RSK-3162", Kind.REFUSED, "no FX mid for the calculation currency " + calc + " (fx-spot)");
        }
        Double fileCcy = in0.usdPer.get(params.calculationCurrency);
        Map<String, SimmInput.Factor> factors = in0.factors;
        String scope = String.valueOf(request.param("simm-scope")).strip();
        if (scope.equalsIgnoreCase(EXAMPLE)) {                          // the hand-built examples (SIMM.md section 15): no data read
            SimmInput in = SimmExamples.together();
            return List.of(new Run(EXAMPLE, version, post ? "post" : "collect", in, run(in, params, choices, post)));
        }
        SimmInput.AddOns addOns = addOns(request, calc, rep, in0);
        Map<String, List<Map<String, Object>>> byScope = new TreeMap<>();
        // scopes the host resolves (its netting sets' records, an analytic scope's records): SCOPE, SENSITIVITY_ID; each scope's elements in
        // the input's row order (read through the session's index of the elements by sensitivity), one trade per record (as the in-tree run
        // builds it), simm-scope not read
        Map<String, java.util.Set<String>> members = members(request);
        if (members != null) {
            for (Map.Entry<String, java.util.Set<String>> m : members.entrySet()) {
                List<Map<String, Object>> rows = new ArrayList<>();
                for (int i : in0.rowsOf(m.getValue())) {
                    Map<String, Object> r = in0.sensitivities.get(i);
                    if (InputRows.visible(see, r)) {
                        rows.add(r);
                    }
                }
                byScope.put(m.getKey(), rows);
            }
        }
        for (Map<String, Object> r : members != null ? List.<Map<String, Object>>of() : in0.sensitivities) {
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
                String id = members != null ? InputRows.text(r, "SENSITIVITY_ID")
                        : InputRows.text(r, "TRADE_ID") != null ? InputRows.text(r, "TRADE_ID") : InputRows.text(r, "POSITION_ID");
                Integer idx = index.get(id);
                if (idx == null) {
                    idx = trades.size();
                    index.put(id, idx);
                    String group = InputRows.text(r, "PRODUCT_GROUP");
                    trades.add(new SimmInput.Trade(id, options.productClass(group), group == null ? Map.of() : Map.of("PRODUCT_GROUP", group)));
                }
                String ccy = InputRows.text(r, "CURRENCY") == null ? calc : InputRows.text(r, "CURRENCY").toUpperCase(Locale.ROOT);
                Double per = in0.usdPer.get(ccy);
                if (per == null) {
                    continue;                                              // no FX for its currency: left out (the input names the rows)
                }
                els.add(new SimmInput.Element(idx, factors.get(InputRows.text(r, "RISK_FACTOR")), String.valueOf(InputRows.text(r, "MEASURE"))
                        .toUpperCase(Locale.ROOT), InputRows.number(r, "VALUE") * per / rep, InputRows.number(r, "IMPLIED_VOL")));
            }
            SimmInput in = new SimmInput(trades, els, calc, addOns, fileCcy == null ? 1 : fileCcy / rep);
            out.add(new Run(e.getKey(), version, post ? "post" : "collect", in, run(in, params, choices, post)));
        }
        return out;
    }

    /**
     * The add-ons of the CSA the host resolved for the scope (SIMM_GUIDE.md, "Add-ons"): {@code simm-addon-fixed}, the product
     * multipliers ({@code simm-addon-multipliers}, product class=MS) and notional factors ({@code simm-addon-factors}, product=factor), and
     * the notional lines ({@code simm-addon-lines}, TRADE_ID|PRODUCT|NOTIONAL|CURRENCY), each line's notional in the calculation currency by
     * the date's FX mids (a line whose currency has none is left out). None given: no add-on.
     */
    SimmInput.AddOns addOns(Request request, String calc, double rep, CcrExtras.SimmInputs in0) throws EngineException {
        Object f = request.param("simm-addon-fixed");
        double fixed = f instanceof Number n ? n.doubleValue() : f == null || f.toString().isBlank() ? 0 : Double.parseDouble(f.toString());
        Map<String, Double> multipliers = numbers(pairs(request.param("simm-addon-multipliers"), "simm-addon-multipliers"), "simm-addon-multipliers");
        Map<String, Double> factors = numbers(pairs(request.param("simm-addon-factors"), "simm-addon-factors"), "simm-addon-factors");
        List<SimmInput.Line> lines = new ArrayList<>();
        if (request.param("simm-addon-lines") instanceof List<?> l) {
            for (Object o : l) {
                String[] x = String.valueOf(o).split("\\|", -1);
                if (x.length != 4) {
                    throw new EngineException("RSK-3165", Kind.REFUSED, "an add-on line is TRADE_ID|PRODUCT|NOTIONAL|CURRENCY, not " + o);
                }
                String ccy = x[3].isBlank() ? "USD" : x[3].strip().toUpperCase(Locale.ROOT);
                Double per = in0.usdPer.get(ccy);
                if (per == null) {
                    continue;                                              // no FX for its currency: left out, as the host's run leaves it
                }
                double notional = x[2].isBlank() ? 0 : Double.parseDouble(x[2].strip());
                lines.add(new SimmInput.Line(x[0].isBlank() ? null : x[0], x[1].isBlank() ? null : x[1], notional * per / rep));
            }
        }
        if (fixed == 0 && multipliers.isEmpty() && factors.isEmpty() && lines.isEmpty()) {
            return SimmInput.AddOns.NONE;
        }
        return new SimmInput.AddOns(fixed, multipliers, factors, lines);
    }

    /** A list param of {@code key=value} texts as an ordered map (the value after the last '='). */
    static Map<String, String> pairs(Object param, String name) throws EngineException {
        Map<String, String> out = new LinkedHashMap<>();
        if (param instanceof List<?> l) {
            for (Object o : l) {
                String x = String.valueOf(o);
                int eq = x.lastIndexOf('=');
                if (eq <= 0) {
                    throw new EngineException("RSK-3164", Kind.REFUSED, name + " is key=value, not " + x);
                }
                out.put(x.substring(0, eq).strip(), x.substring(eq + 1).strip());
            }
        }
        return out;
    }

    private static Map<String, Double> numbers(Map<String, String> pairs, String name) throws EngineException {
        Map<String, Double> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : pairs.entrySet()) {
            try {
                out.put(e.getKey(), Double.parseDouble(e.getValue()));
            } catch (NumberFormatException x) {
                throw new EngineException("RSK-3165", Kind.REFUSED, name + ": " + e.getValue() + " is not a number");
            }
        }
        return out;
    }

    /** The method on one input; what it refuses (a CSA multiplier below 1) is the request's, in its words. */
    private static Simm.Result run(SimmInput in, SimmParams params, SimmChoices choices, boolean post) throws EngineException {
        try {
            return Simm.run(in, params, choices, post);
        } catch (IllegalArgumentException e) {
            throw new EngineException("RSK-3165", Kind.REFUSED, e.getMessage());
        }
    }

    /** The host's resolved scopes ({@code simm-members}: SCOPE, SENSITIVITY_ID), in the table's order of scopes; null when not given. */
    static Map<String, java.util.Set<String>> members(Request request) {
        if (!(request.param("simm-members") instanceof Table t)) {
            return null;
        }
        Map<String, java.util.Set<String>> out = new LinkedHashMap<>();
        for (int i = 0; i < t.rows(); i++) {
            Object sc = t.value("SCOPE", i);
            Object id = t.value("SENSITIVITY_ID", i);
            if (sc != null) {
                java.util.Set<String> m = out.computeIfAbsent(sc.toString(), x -> new java.util.HashSet<>());
                if (id != null) {
                    m.add(id.toString());
                }
            }
        }
        return out;
    }

    /** Each scope's SIMM by product class (SIMM_p, the risk classes aggregated by psi; the product classes are then added). */
    Table products(List<Run> runs) {
        Tables.Builder t = Tables.of(descriptor.schema("simm-product"));
        for (Run r : runs) {
            for (Simm.ProductIm p : r.result().products()) {
                t.row(r.scope(), p.productClass(), p.simm());
            }
        }
        return t.build();
    }

    /** The parameter file of each scope's run: version, effective date, SHA-256, its calculation currency and the regimes it recognises. */
    Table versions(List<Run> runs) {
        Tables.Builder t = Tables.of(descriptor.schema("simm-version"));
        for (Run r : runs) {
            SimmParams p = r.result().params();
            t.row(r.scope(), p.version, p.effectiveFrom, p.hash, p.calculationCurrency, String.join(",", p.regimes), p.describe());
        }
        return t.build();
    }

    /** Each scope's charges (SIMM_GUIDE.md, the explanation's steps): a charge per product class, risk class and measure, in the method's order. */
    Table charges(List<Run> runs) {
        Tables.Builder t = Tables.of(descriptor.schema("simm-charge"));
        for (Run r : runs) {
            List<Simm.Charge> cs = r.result().charges();
            for (int i = 0; i < cs.size(); i++) {
                Simm.Charge c = cs.get(i);
                boolean curv = c.measure.equals(Simm.CURVATURE);
                t.row(r.scope(), (long) i, c.productClass, c.riskClass, c.measure, c.margin, c.root, c.residual, curv ? c.theta : null, curv ? c.lambda : null,
                        curv ? c.sumCvr : null, curv ? c.scale : null);
            }
        }
        return t.build();
    }

    /** Each charge's buckets: K, the net and S, the bucket's concentration and its factors (the indices of simm-factor, in order). */
    Table buckets(List<Run> runs) {
        Tables.Builder t = Tables.of(descriptor.schema("simm-bucket"));
        for (Run r : runs) {
            List<Simm.Charge> cs = r.result().charges();
            for (int i = 0; i < cs.size(); i++) {
                List<Simm.Bucket> bs = cs.get(i).buckets;
                for (int j = 0; j < bs.size(); j++) {
                    Simm.Bucket b = bs.get(j);
                    t.row(r.scope(), (long) i, (long) j, b.label, b.residual, b.k, b.net, b.s, b.cr, java.util.Arrays.stream(b.members).asDoubleStream().toArray());   // a list column holds doubles
                }
            }
        }
        return t.build();
    }

    /** Each weighted sensitivity: s, the risk weight (and its key), HVR, the concentration factor, WS and the trades in it. */
    Table factors(List<Run> runs) {
        Tables.Builder t = Tables.of(descriptor.schema("simm-factor"));
        for (Run r : runs) {
            List<Simm.Risk> rs = r.result().risks();
            for (int k = 0; k < rs.size(); k++) {
                Simm.Risk x = rs.get(k);
                t.row(r.scope(), (long) k, x.productClass, x.riskClass, x.measure, x.key, x.s, x.rw, x.rwKey, x.hvr, x.cr, x.ws, (long) x.parts.size());
            }
        }
        return t.build();
    }

    /** Each concentration group: the sum, the threshold and the concentration factor. */
    Table groups(List<Run> runs) {
        Tables.Builder t = Tables.of(descriptor.schema("simm-group"));
        for (Run r : runs) {
            List<Simm.Group> gs = r.result().groups();
            for (int i = 0; i < gs.size(); i++) {
                Simm.Group g = gs.get(i);
                t.row(r.scope(), (long) i, g.productClass, g.riskClass, g.measure, g.group, g.sum, g.threshold, g.cr);
            }
        }
        return t.build();
    }

    /** Each scope's add-on, line by line (fixed amount, notional by product, multiplier by product class). */
    Table addOnLines(List<Run> runs) {
        Tables.Builder t = Tables.of(descriptor.schema("simm-addon"));
        for (Run r : runs) {
            Simm.AddOn a = r.result().addOn();
            t.row(r.scope(), "", a.fixed(), a.notional(), a.multipliers(), a.total());
            for (Map.Entry<String, Double> e : a.byProduct().entrySet()) {
                t.row(r.scope(), e.getKey(), null, null, null, e.getValue());
            }
        }
        return t.build();
    }

    /** Every version of simm-parameters on the date: installed (checked as the method reads it), or refused with why. */
    Table versionList(Control control) throws EngineException {
        CcrExtras.SimmInputs in0 = day.simm(control);
        Tables.Builder t = Tables.of(descriptor.schema("simm-versions"));
        for (Map.Entry<String, String> e : new TreeMap<>(in0.simmFiles).entrySet()) {
            try {
                SimmParams p = in0.parameters(e.getKey());              // parsed once a session
                t.row(e.getKey(), true, p.effectiveFrom, p.hash, null);
            } catch (RuntimeException x) {
                t.row(e.getKey(), false, null, null, x.getMessage());
            }
        }
        return t.build();
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
