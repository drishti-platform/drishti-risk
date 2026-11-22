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
package com.ash.risk.market.engine;

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
import com.ash.risk.kernel.io.InputRows;
import com.ash.risk.kernel.workbook.AnswerBook;
import com.ash.risk.market.frtb.calc.Drc;
import com.ash.risk.market.frtb.calc.FactorDictionary;
import com.ash.risk.market.frtb.calc.FrtbChoices;
import com.ash.risk.market.frtb.calc.FrtbInput;
import com.ash.risk.market.frtb.calc.FrtbSa;
import com.ash.risk.market.frtb.calc.FrtbScope;
import com.ash.risk.market.frtb.calc.Rrao;
import com.ash.risk.market.frtb.calc.Sbm;
import com.ash.tools.xlsx.Sheet;
import com.ash.tools.xlsx.Workbook;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The outputs over the position sensitivities (FRTB_SA_GUIDE.md; the MR cube's ladders and heat maps): the elements of a scope in the
 * reporting currency summed by factor parts, the delta ladder against its limits, and FRTB-SA (SBM by class, measure and bucket in the
 * three correlation scenarios, the DRC, the RRAO, the total and the contributions) by the moved {@link FrtbSa} with the Basel parameter
 * files carried in the JAR. The sensitivity records are the ones the request sees; a DRC or RRAO row counts when its position or trade is
 * one of them, so an answer under a visibility equals the answer over the rows it allows.
 */
final class MarketSensitivities {

    /** One element in the reporting currency, with its record. */
    record Element(Record record, FactorDictionary.Factor factor, String factorName, String measure, double value, double delivered,
            String currency, double shockSize, double impliedVol) {}

    /** One sensitivity record (a position or a trade) of the scope; compared by identity (its elements point back to it). */
    static final class Record {
        private final String id;
        private final String positionKey;
        private final String tradeId;
        private final Map<String, String> attributes;
        private final double notional;

        Record(String id, String positionKey, String tradeId, Map<String, String> attributes, double notional) {
            this.id = id;
            this.positionKey = positionKey;
            this.tradeId = tradeId;
            this.attributes = attributes;
            this.notional = notional;
        }

        String id() {
            return id;
        }

        String positionKey() {
            return positionKey;
        }

        String tradeId() {
            return tradeId;
        }

        Map<String, String> attributes() {
            return attributes;
        }

        double notional() {
            return notional;
        }
    }

    /** The scope's records and elements, and the elements refused (no FX for their currency). */
    record Scoped(List<Record> records, List<Element> elements, int noFx, String reporting) {}

    private static final List<String> ATTRIBUTES = List.of("LEGAL_ENTITY", "DIVISION", "DESK", "TT_BOOK", "PRODUCT_GROUP", "PRODUCT_TYPE",
            "CURRENCY", "LIMIT_LETTER", "FRTB_DESK");

    private final Descriptor descriptor;
    private final MarketOptions options;
    private final MarketDay day;

    MarketSensitivities(Descriptor descriptor, MarketOptions options, MarketDay day) {
        this.descriptor = descriptor;
        this.options = options;
        this.day = day;
    }

    private FactorDictionary dictionary() throws EngineException {
        List<FactorDictionary.Factor> fs = new ArrayList<>();
        for (Map<String, Object> r : day.extras.factors) {
            fs.add(new FactorDictionary.Factor(InputRows.text(r, "RISK_FACTOR"), InputRows.text(r, "RISK_CLASS"), InputRows.text(r, "FACTOR_TYPE"),
                    InputRows.text(r, "QUALIFIER"), InputRows.text(r, "CURVE"), InputRows.text(r, "UNDERLYING"), InputRows.text(r, "TENOR"),
                    InputRows.text(r, "UNDERLYING_TENOR"), InputRows.text(r, "FRTB_BUCKET"), InputRows.text(r, "SIMM_BUCKET"), List.of()));
        }
        try {
            return new FactorDictionary(fs, "risk-factor@" + day.date);
        } catch (IllegalArgumentException e) {
            throw new EngineException("RSK-3140", Kind.REFUSED, e.getMessage());
        }
    }

    /** The records the request sees and its scope names, their elements in the reporting currency. */
    Scoped scoped(Request request, Filter see) throws EngineException {
        String scope = request.params().containsKey("scope") ? String.valueOf(request.param("scope")) : "BANK";
        String reporting = String.valueOf(request.param("currency")).toUpperCase(Locale.ROOT);
        Double rep = day.extras.usdPer.get(reporting);
        if (rep == null) {
            throw new EngineException("RSK-3141", Kind.REFUSED, "no FX mid for the reporting currency " + reporting + " on " + day.date + " (fx-spot)");
        }
        FactorDictionary dict = dictionary();
        Map<String, List<Map<String, Object>>> byRecord = new TreeMap<>();
        for (Map<String, Object> r : day.extras.sensitivities) {
            if (InputRows.visible(see, r)) {
                byRecord.computeIfAbsent(InputRows.text(r, "SENSITIVITY_ID"), x -> new ArrayList<>()).add(r);
            }
        }
        List<Record> records = new ArrayList<>();
        List<Element> elements = new ArrayList<>();
        int noFx = 0;
        for (Map.Entry<String, List<Map<String, Object>>> e : byRecord.entrySet()) {
            Map<String, Object> first = e.getValue().get(0);
            String pos = InputRows.text(first, "POSITION_ID");
            String trade = InputRows.text(first, "TRADE_ID");
            String key = pos != null ? pos : trade;
            Map<String, String> attrs = new LinkedHashMap<>();
            for (String a : ATTRIBUTES) {
                if (InputRows.text(first, a) != null) {
                    attrs.put(a, InputRows.text(first, a));
                }
            }
            try {
                if (!FrtbScope.matches(scope, key, trade, trade == null ? List.of() : List.of(trade), attrs::get)) {
                    continue;
                }
            } catch (IllegalArgumentException x) {
                throw new EngineException("RSK-3142", Kind.REFUSED, x.getMessage());
            }
            double notional = Double.NaN;
            for (Map<String, Object> r : e.getValue()) {
                if (Double.isFinite(InputRows.number(r, "NOTIONAL"))) {
                    notional = InputRows.number(r, "NOTIONAL");
                    break;
                }
            }
            Record rec = new Record(e.getKey(), key, trade, Map.copyOf(attrs), notional);
            records.add(rec);
            for (Map<String, Object> r : e.getValue()) {
                String ccy = InputRows.text(r, "CURRENCY") == null ? reporting : InputRows.text(r, "CURRENCY").toUpperCase(Locale.ROOT);
                Double per = day.extras.usdPer.get(ccy);
                if (per == null) {
                    noFx++;
                    continue;
                }
                double delivered = InputRows.number(r, "VALUE");
                String name = InputRows.text(r, "RISK_FACTOR");
                elements.add(new Element(rec, dict.resolve(name), name, String.valueOf(InputRows.text(r, "MEASURE")).toUpperCase(Locale.ROOT),
                        delivered * per / rep, delivered, ccy, InputRows.number(r, "SHOCK_SIZE"), InputRows.number(r, "IMPLIED_VOL")));
            }
        }
        return new Scoped(List.copyOf(records), List.copyOf(elements), noFx, reporting);
    }

    // ------------------------------------------------------------------------------------------------------------- sums and the ladder

    /** A factor part of an element ({@code RISK_FACTOR}, {@code RISK_CLASS}, {@code MEASURE}, {@code BUCKET}, {@code QUALIFIER}, {@code TENOR}) or a record column. */
    static String part(String key, Element e) {
        FactorDictionary.Factor f = e.factor();
        String v = switch (key) {
            case "RISK_FACTOR" -> f == null ? e.factorName() : f.name();
            case "RISK_CLASS" -> f == null ? null : f.riskClass();
            case "MEASURE" -> e.measure();
            case "BUCKET" -> f == null ? null : f.frtbBucket();
            case "QUALIFIER" -> f == null ? null : f.qualifier();
            case "TENOR" -> f == null ? null : f.tenor();
            case "CURVE" -> f == null ? null : f.curve();
            case "POSITION" -> e.record().positionKey();
            default -> e.record().attributes().get(key);
        };
        return v == null ? "(none)" : v;
    }

    /** One cell of a sum: its elements. */
    record SumCell(String row, String column, List<Element> elements, double net, double gross, int positions) {}

    List<SumCell> sums(Scoped s, String by) throws EngineException {
        List<String> keys = new ArrayList<>();
        for (String k : by.split("\\s*,\\s*")) {
            if (!k.isBlank()) {
                keys.add(k.strip().toUpperCase(Locale.ROOT));
            }
        }
        if (keys.isEmpty() || keys.size() > 2) {
            throw new EngineException("RSK-3143", Kind.REFUSED, "by names one or two factor parts, rows then columns: by=RISK_CLASS,BUCKET");
        }
        Map<String, Map<String, List<Element>>> cells = new TreeMap<>();
        for (Element e : s.elements()) {
            String r = part(keys.get(0), e);
            String c = keys.size() == 2 ? part(keys.get(1), e) : "*";
            cells.computeIfAbsent(r, x -> new TreeMap<>()).computeIfAbsent(c, x -> new ArrayList<>()).add(e);
        }
        List<SumCell> out = new ArrayList<>();
        cells.forEach((r, byCol) -> byCol.forEach((c, els) -> out.add(cell(r, c, els))));
        return out;
    }

    private static SumCell cell(String r, String c, List<Element> els) {
        double net = 0;
        double gross = 0;
        Set<String> pos = new LinkedHashSet<>();
        for (Element e : els) {
            net += e.value();
            gross += Math.abs(e.value());
            pos.add(e.record().positionKey());
        }
        return new SumCell(r, c, List.copyOf(els), net, gross, pos.size());
    }

    Table sumTable(List<SumCell> cells) {
        Tables.Builder t = Tables.of(descriptor.schema("sensitivity-sum"));
        cells.forEach(c -> t.row(c.row(), c.column(), c.net(), c.gross(), (long) c.positions()));
        return t.build();
    }

    /** The delta measure of a class when none is asked: DV01 for rates, CS01 for credit spreads, DELTA otherwise. */
    static String deltaWord(String cls) {
        String c = cls.toUpperCase(Locale.ROOT);
        return c.startsWith("GIRR") ? "DV01" : c.startsWith("CSR") ? "CS01" : "DELTA";
    }

    List<SumCell> ladder(Scoped s, Request request) {
        String cls = String.valueOf(request.param("risk-class")).strip().toUpperCase(Locale.ROOT);
        String asked = String.valueOf(request.param("sensitivity-measure")).strip().toUpperCase(Locale.ROOT);
        String meas = asked.isEmpty() ? deltaWord(cls) : asked;
        Map<String, double[]> grid = options.sensitivities().grid();
        Map<String, Map<String, List<Element>>> cells = new TreeMap<>();
        for (Element e : s.elements()) {
            if (e.factor() == null || e.factor().riskClass() == null || !e.factor().riskClass().toUpperCase(Locale.ROOT).startsWith(cls)
                    || !meas.equals(e.measure())) {
                continue;
            }
            double y = FactorDictionary.years(e.factor().tenor());
            String bucket = null;
            for (Map.Entry<String, double[]> g : grid.entrySet()) {
                if (y >= g.getValue()[0] && y < g.getValue()[1]) {
                    bucket = g.getKey();
                    break;
                }
            }
            if (bucket == null) {
                continue;                                                  // no tenor, or beyond the grid: not on the ladder
            }
            String q = e.factor().qualifier() == null ? "(none)" : e.factor().qualifier();
            cells.computeIfAbsent(q, x -> new LinkedHashMap<>()).computeIfAbsent(bucket, x -> new ArrayList<>()).add(e);
        }
        List<SumCell> out = new ArrayList<>();
        cells.forEach((q, byBucket) -> {
            for (String b : grid.keySet()) {
                if (byBucket.containsKey(b)) {
                    out.add(cell(q, b, byBucket.get(b)));
                }
            }
        });
        return out;
    }

    Table ladderTable(List<SumCell> cells) {
        Tables.Builder t = Tables.of(descriptor.schema("sensitivity-ladder"));
        for (SumCell c : cells) {
            Double lim = options.sensitivities().limits().get((c.row() + " " + c.column()).toUpperCase(Locale.ROOT));
            Double use = lim == null || !(lim > 0) ? null : Math.max(c.net() / lim, -c.net() / lim);
            t.row(c.row(), c.column(), c.net(), c.gross(), (long) c.positions(), lim, use, use == null ? null : options.limits().status(use));
        }
        return t.build();
    }

    Explanation explainSum(List<SumCell> cells, Cell cell, String rowKey, String colKey) throws EngineException {
        for (SumCell c : cells) {
            if (!c.row().equals(cell.key().get(rowKey)) || !c.column().equals(cell.key().get(colKey))) {
                continue;
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Element e : c.elements()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("SENSITIVITY_ID", e.record().id());
                m.put("RISK_FACTOR", e.factorName());
                m.put("MEASURE", e.measure());
                m.put("DELIVERED", e.delivered());
                m.put("CURRENCY", e.currency());
                m.put("VALUE", e.value());
                m.put("ABS", Math.abs(e.value()));
                rows.add(m);
            }
            return switch (cell.column()) {
                case "NET" -> new Explanation(cell, c.net(), List.of(Steps.of("net", "The sum of the cell's " + rows.size()
                        + " elements in the reporting currency", "sum($.rows, 'VALUE')", Map.of("rows", rows), c.net())));
                case "GROSS" -> new Explanation(cell, c.gross(), List.of(Steps.of("gross", "The sum of their absolute values", "sum($.rows, 'ABS')",
                        Map.of("rows", rows), c.gross())));
                default -> throw new EngineException("ENG-3005", Kind.REFUSED, cell.column() + " is not a measure of " + cell.output());
            };
        }
        throw new EngineException("ENG-3005", Kind.REFUSED, "the cell is not in this request's answer");
    }

    AnswerBook.Part sumPart(List<SumCell> cells, String output) {
        return new AnswerBook.Part() {
            private final List<int[]> blocks = new ArrayList<>();

            @Override
            public int data(Sheet data, int at) {
                data.row(at).text(0, "ROW", "header").text(1, "COLUMN", "header").text(2, "SENSITIVITY_ID", "header").text(3, "RISK_FACTOR", "header")
                        .text(4, "VALUE", "header");
                int r = at;
                for (SumCell c : cells) {
                    int from = r + 1;
                    for (Element e : c.elements()) {
                        r++;
                        data.row(r).text(0, c.row(), "normal").text(1, c.column(), "normal").text(2, e.record().id(), "normal")
                                .text(3, e.factorName(), "normal").number(4, e.value(), "decimal");
                    }
                    blocks.add(new int[] {from, r});
                }
                return r;
            }

            @Override
            public int main(Workbook wb, Sheet main, int at) {
                main.row(at).text(0, "ROW", "header").text(1, "COLUMN", "header").text(2, "NET", "header").text(3, "GROSS", "header");
                int row = at;
                for (int i = 0; i < cells.size(); i++) {
                    SumCell c = cells.get(i);
                    int[] b = blocks.get(i);
                    String v = AnswerBook.range(4, b[0], b[1]);
                    row++;
                    main.row(row).text(0, c.row(), "normal").text(1, c.column(), "normal").formula(2, "SUM(" + v + ")", c.net(), "decimal")
                            .formula(3, "SUMPRODUCT(ABS(" + v + "))", c.gross(), "decimal");
                    if (i == 0) {
                        wb.name(AnswerBook.name(output, "NET"), main.name(), "$C$" + row);
                        wb.name(AnswerBook.name(output, "GROSS"), main.name(), "$D$" + row);
                    }
                }
                return row;
            }
        };
    }

    // ------------------------------------------------------------------------------------------------------------- FRTB-SA

    FrtbChoices choices(Request request) {
        Map<String, String> sw = new LinkedHashMap<>();
        for (String k : List.of("scenario", "girr-correlation", "curvature-source")) {
            if (request.params().containsKey(k)) {
                sw.put(k, String.valueOf(request.param(k)));
            }
        }
        return FrtbChoices.of(sw, Map.of());
    }

    FrtbSa.Result frtb(Scoped s, Request request) {
        List<FrtbInput.Position> positions = new ArrayList<>();
        List<FrtbInput.Element> elements = new ArrayList<>();
        Map<String, Integer> byTrade = new HashMap<>();
        Map<String, Integer> byPosition = new HashMap<>();
        Map<Record, Integer> index = new HashMap<>();
        for (Record r : s.records()) {
            int idx = positions.size();
            index.put(r, idx);
            positions.add(new FrtbInput.Position(r.positionKey(), r.attributes().get("PRODUCT_TYPE"), r.attributes()));
            if (r.tradeId() != null) {
                byTrade.put(r.tradeId(), idx);
            }
            byPosition.putIfAbsent(r.positionKey(), idx);
        }
        for (Element e : s.elements()) {
            elements.add(new FrtbInput.Element(index.get(e.record()), e.factor(), e.measure(), e.value(), e.shockSize(), e.impliedVol()));
        }
        Double rep = day.extras.usdPer.get(s.reporting());
        List<FrtbInput.DrcPosition> drc = null;
        if (day.extras.present.contains(MarketExtras.DEFAULT_RISK)) {
            drc = new ArrayList<>();
            for (Map<String, Object> n : day.extras.defaultRisk) {
                String trade = InputRows.text(n, "TRADE_ID");
                Integer idx = trade == null ? null : byTrade.get(trade);
                if (idx == null && InputRows.text(n, "POSITION_ID") != null) {
                    idx = byPosition.get(InputRows.text(n, "POSITION_ID"));
                }
                Double per = day.extras.usdPer.get(InputRows.text(n, "CURRENCY") == null ? "USD" : InputRows.text(n, "CURRENCY").toUpperCase(Locale.ROOT));
                if (idx == null || per == null) {
                    continue;                                              // not in the scope, or no FX for its currency
                }
                double fx = per / rep;
                String sen = InputRows.text(n, "SENIORITY");
                boolean cash = "EQUITY".equalsIgnoreCase(sen) && InputRows.text(n, "MATURITY_DATE") == null;
                String id = InputRows.text(n, "DEFAULT_RISK_ID");
                drc.add(new FrtbInput.DrcPosition(id != null ? id : trade + "~" + InputRows.text(n, "OBLIGOR") + "~" + sen, idx, InputRows.text(n, "OBLIGOR"),
                        drcBucket(InputRows.text(n, "DRC_BUCKET")), sen, InputRows.text(n, "CREDIT_QUALITY"), InputRows.number(n, "NOTIONAL") * fx,
                        InputRows.number(n, "MARKET_VALUE") * fx, cash ? InputRows.number(n, "CASH_EQUITY_MATURITY") : years(InputRows.text(n, "MATURITY_DATE")),
                        InputRows.flag(n, "RECOVERY_INDEPENDENT") ? options.frtb().drcRecoveryIndependentLgd : Double.NaN, cash));
            }
        }
        List<FrtbInput.RraoItem> rrao = null;
        if (day.extras.present.contains(MarketExtras.RESIDUAL_RISK)) {
            rrao = new ArrayList<>();
            for (Map<String, Object> n : day.extras.residualRisk) {
                String trade = InputRows.text(n, "TRADE_ID");
                Integer idx = trade == null ? null : byTrade.get(trade);
                Double per = day.extras.usdPer.get(InputRows.text(n, "CURRENCY") == null ? "USD" : InputRows.text(n, "CURRENCY").toUpperCase(Locale.ROOT));
                if (idx != null && per != null) {
                    rrao.add(new FrtbInput.RraoItem(trade, idx, positions.get(idx).productType(), InputRows.text(n, "RRAO_CLASS"),
                            InputRows.number(n, "GROSS_NOTIONAL") * per / rep, InputRows.text(n, "BACK_TO_BACK_OF") != null,
                            InputRows.flag(n, "LISTED_OR_CLEARABLE")));
                }
            }
        } else if (options.rraoFromPositions()) {
            rrao = new ArrayList<>();
            for (Record r : s.records()) {
                String type = r.attributes().getOrDefault("PRODUCT_TYPE", "").toUpperCase(Locale.ROOT);
                if (options.frtb().rraoTypes.containsKey(type) && Double.isFinite(r.notional())) {
                    rrao.add(new FrtbInput.RraoItem(r.positionKey(), index.get(r), type, null, Math.abs(r.notional()) / rep, false, false));
                }
            }
        }
        return FrtbSa.run(new FrtbInput(positions, elements, s.reporting(), drc, rrao), options.frtb(), choices(request));
    }

    private static String drcBucket(String b) {
        if (b == null) {
            return "CORPORATE";
        }
        String u = b.strip().toUpperCase(Locale.ROOT);
        return u.startsWith("SOV") ? "SOVEREIGN" : u.startsWith("LOCAL") ? "LOCAL_GOVERNMENT_MUNICIPALITY" : u;
    }

    private double years(String maturity) {
        if (maturity == null) {
            return 1;
        }
        try {
            return Math.max(0, (java.time.LocalDate.parse(maturity.substring(0, 10)).toEpochDay() - day.date.toEpochDay()) / 365.25);
        } catch (RuntimeException e) {
            return 1;
        }
    }

    private static Double nn(double d) {
        return Double.isFinite(d) ? d : null;
    }

    Table frtbTable(FrtbSa.Result r) {
        Tables.Builder t = Tables.of(descriptor.schema("frtb-sa"));
        Sbm.Result sbm = r.sbm();
        int rep = sbm.reported;
        for (Sbm.ClassCharge cc : sbm.charges) {
            String alt = cc.alternative[rep] ? "the alternative specification applied (MAR21.4(5)(b))" : null;
            t.row("SBM", cc.cls, cc.measure, "*", nn(cc.charge[rep]), nn(cc.charge[0]), nn(cc.charge[1]), nn(cc.charge[2]), alt);
            for (Sbm.Bucket b : cc.buckets) {
                t.row("SBM-BUCKET", cc.cls, cc.measure, b.bucket, nn(b.k[rep]), nn(b.k[0]), nn(b.k[1]), nn(b.k[2]), null);
            }
        }
        t.row("SBM", "*", "*", "*", nn(sbm.sbm()), nn(sbm.total[0]), nn(sbm.total[1]), nn(sbm.total[2]),
                "reported: " + Sbm.SCENARIOS[rep] + "; binding: " + Sbm.SCENARIOS[sbm.binding]);
        Drc.Result d = r.drc();
        if (d.delivered()) {
            for (Drc.Bucket b : d.buckets()) {
                t.row("DRC-BUCKET", "DRC", "DEFAULT", b.bucket(), nn(b.charge()), null, null, null, "HBR " + b.hbr());
            }
            t.row("DRC", "*", "*", "*", nn(d.total()), null, null, null, d.refusals().isEmpty() ? null : String.join("; ", d.refusals()));
        } else {
            t.row("DRC", "*", "*", "*", null, null, null, null, "missing: no default-risk input on the date (never 0)");
        }
        Rrao.Result q = r.rrao();
        if (q.delivered()) {
            t.row("RRAO", "EXOTIC", "*", "*", nn(q.exotic()), null, null, null, null);
            t.row("RRAO", "OTHER", "*", "*", nn(q.other()), null, null, null, null);
            t.row("RRAO", "*", "*", "*", nn(q.total()), null, null, null, null);
        } else {
            t.row("RRAO", "*", "*", "*", null, null, null, null, "missing: no residual-risk input on the date (never 0)");
        }
        t.row("TOTAL", "*", "*", "*", nn(r.total()), null, null, null, r.complete() ? null : "SBM" + (d.delivered() ? " + DRC" : "")
                + (q.delivered() ? " + RRAO" : "") + " only: the total lacks what is missing");
        return t.build();
    }

    Table contributionTable(FrtbSa.Result r, String attribute) {
        Tables.Builder t = Tables.of(descriptor.schema("frtb-contributions"));
        new TreeMap<>(r.contributionsBy(attribute.strip().toUpperCase(Locale.ROOT))).forEach((k, v) -> t.row(k, v));
        return t.build();
    }

    Explanation explainFrtb(FrtbSa.Result r, Cell cell, String attribute) throws EngineException {
        int rep = r.sbm().reported;
        if (cell.output().equals("frtb-contributions")) {
            Double v = r.contributionsBy(attribute.strip().toUpperCase(Locale.ROOT)).get(cell.key().get("VALUE"));
            if (v == null) {
                throw new EngineException("ENG-3005", Kind.REFUSED, "the value is not in this request's answer");
            }
            return new Explanation(cell, v, List.of(Steps.opaque("contribution", "The SBM by Euler in the reported scenario, the DRC by obligor share and"
                    + " the RRAO as is, summed over the records whose " + attribute + " is " + cell.key().get("VALUE"),
                    "the Euler gradient of the SBM through every bucket and class; the parts add up to the total in the workbook", "Main!TOTAL", v)));
        }
        if (!"CHARGE".equals(cell.column())) {
            throw new EngineException("ENG-3005", Kind.REFUSED, cell.column() + " is not a measure of frtb-sa");
        }
        String comp = cell.key().get("COMPONENT");
        String cls = cell.key().get("RISK_CLASS");
        List<Step> steps = new ArrayList<>();
        switch (comp) {
            case "TOTAL" -> {
                Map<String, Object> g = new LinkedHashMap<>();
                g.put("sbm", r.sbm().sbm());
                g.put("drc", r.drc().delivered() ? r.drc().total() : 0.0);
                g.put("rrao", r.rrao().delivered() ? r.rrao().total() : 0.0);
                steps.add(Steps.of("total", "SBM + DRC + RRAO (a part not delivered adds nothing and is named missing)", "$.sbm + $.drc + $.rrao", g, r.total()));
            }
            case "SBM" -> {
                if ("*".equals(cls)) {
                    List<Map<String, Object>> rows = new ArrayList<>();
                    for (Sbm.ClassCharge cc : r.sbm().charges) {
                        rows.add(Map.<String, Object>of("RISK_CLASS", cc.cls, "MEASURE", cc.measure, "CHARGE", cc.charge[rep]));
                    }
                    steps.add(Steps.of("sbm", "The class and measure charges of the " + Sbm.SCENARIOS[rep] + " scenario added (MAR21.6)",
                            "sum($.rows, 'CHARGE')", Map.of("rows", rows), r.sbm().sbm()));
                } else {
                    Sbm.ClassCharge cc = r.sbm().classCharge(cls, cell.key().get("MEASURE"));
                    if (cc == null) {
                        throw new EngineException("ENG-3005", Kind.REFUSED, "no " + cls + " charge in this answer");
                    }
                    steps.add(Steps.opaque("class", cls + " " + cc.measure + " across its buckets (MAR21.4)",
                            "sqrt(sum K_b^2 + sum gamma S_b S_c) with the bucket figures and the scenario's gamma; recomputed in the workbook",
                            "Main!" + cls + "-" + cc.measure, cc.charge[rep]));
                }
            }
            case "SBM-BUCKET" -> {
                Sbm.ClassCharge cc = r.sbm().classCharge(cls, cell.key().get("MEASURE"));
                Sbm.Bucket b = cc == null ? null : cc.buckets.stream().filter(x -> x.bucket.equals(cell.key().get("BUCKET"))).findFirst().orElse(null);
                if (b == null) {
                    throw new EngineException("ENG-3005", Kind.REFUSED, "the bucket is not in this answer");
                }
                steps.add(Steps.opaque("bucket", "K_b of " + cls + " " + cc.measure + " bucket " + b.bucket,
                        "sqrt(max(0, sum WS_k^2 + sum rho WS_k WS_l)) over the bucket's weighted sensitivities (MAR21.4(4)); the figures are the engine's",
                        "Data!" + cls + "-" + b.bucket, b.k[rep]));
            }
            case "DRC" -> {
                List<Map<String, Object>> rows = new ArrayList<>();
                r.drc().buckets().forEach(b -> rows.add(Map.<String, Object>of("BUCKET", b.bucket(), "CHARGE", b.charge())));
                steps.add(Steps.of("drc", "The DRC buckets' charges added (MAR22.26)", "sum($.rows, 'CHARGE')", Map.of("rows", rows), r.drc().total()));
            }
            case "DRC-BUCKET" -> {
                Drc.Bucket b = r.drc().buckets().stream().filter(x -> x.bucket().equals(cell.key().get("BUCKET"))).findFirst()
                        .orElseThrow(() -> new EngineException("ENG-3005", Kind.REFUSED, "the bucket is not in this answer"));
                steps.add(Steps.of("bucket", "max(0, sum RW x long - HBR x sum RW x |short|) (MAR22.24)", "max(0, $.long - $.hbr * $.short)",
                        Map.of("long", b.weightedLong(), "hbr", b.hbr(), "short", Math.abs(b.weightedShort())), b.charge()));
            }
            case "RRAO" -> {
                if ("*".equals(cls)) {
                    steps.add(Steps.of("rrao", "The exotic and the other residual add-ons", "$.exotic + $.other",
                            Map.of("exotic", r.rrao().exotic(), "other", r.rrao().other()), r.rrao().total()));
                } else {
                    List<Map<String, Object>> rows = new ArrayList<>();
                    for (Rrao.Line l : r.rrao().lines()) {
                        if (cls.equals(l.rraoClass()) && l.excluded() == null) {
                            rows.add(Map.<String, Object>of("ID", l.in().id(), "NOTIONAL", l.in().grossNotional(), "WEIGHT", l.weight(), "ADD_ON", l.addOn()));
                        }
                    }
                    steps.add(Steps.of("rrao", "The " + cls + " lines' add-ons, gross notional times the weight (MAR23.8)", "sum($.rows, 'ADD_ON')",
                            Map.of("rows", rows), "EXOTIC".equals(cls) ? r.rrao().exotic() : r.rrao().other()));
                }
            }
            default -> throw new EngineException("ENG-3005", Kind.REFUSED, "no component " + comp);
        }
        Object v = steps.get(steps.size() - 1).value();
        return new Explanation(cell, v, steps);
    }

    /** The FRTB-SA part: the class charges per scenario, the DRC buckets and the RRAO lines as values; SBM, DRC, RRAO and TOTAL as formulas. */
    AnswerBook.Part frtbPart(FrtbSa.Result r) {
        return new AnswerBook.Part() {
            private int sbmFrom;
            private int sbmTo;
            private int drcFrom;
            private int drcTo;
            private int rraoFrom;
            private int rraoTo;

            @Override
            public int data(Sheet data, int at) {
                data.row(at).text(0, "COMPONENT", "header").text(1, "RISK_CLASS", "header").text(2, "MEASURE", "header").text(3, "LOW", "header")
                        .text(4, "MEDIUM", "header").text(5, "HIGH", "header");
                int row = at;
                sbmFrom = row + 1;
                for (Sbm.ClassCharge cc : r.sbm().charges) {
                    row++;
                    data.row(row).text(0, "SBM", "normal").text(1, cc.cls, "normal").text(2, cc.measure, "normal").number(3, cc.charge[0], "decimal")
                            .number(4, cc.charge[1], "decimal").number(5, cc.charge[2], "decimal");
                }
                sbmTo = row;
                drcFrom = row + 1;
                for (Drc.Bucket b : r.drc().buckets()) {
                    row++;
                    data.row(row).text(0, "DRC", "normal").text(1, b.bucket(), "normal").text(2, "DEFAULT", "normal").number(3, b.charge(), "decimal");
                }
                drcTo = row;
                rraoFrom = row + 1;
                for (Rrao.Line l : r.rrao().lines()) {
                    row++;
                    data.row(row).text(0, "RRAO", "normal").text(1, String.valueOf(l.rraoClass()), "normal").text(2, l.in().id(), "normal")
                            .number(3, l.addOn(), "decimal");
                }
                rraoTo = row;
                return row;
            }

            private String sum(int col, int from, int to) {
                return to < from ? "0" : "SUM(" + AnswerBook.range(col, from, to) + ")";
            }

            @Override
            public int main(Workbook wb, Sheet main, int at) {
                Sbm.Result s = r.sbm();
                main.row(at).text(0, "SBM low", "bold").formula(1, sum(3, sbmFrom, sbmTo), s.total[0], "decimal");
                main.row(at + 1).text(0, "SBM medium", "bold").formula(1, sum(4, sbmFrom, sbmTo), s.total[1], "decimal");
                main.row(at + 2).text(0, "SBM high", "bold").formula(1, sum(5, sbmFrom, sbmTo), s.total[2], "decimal");
                String reported = r.choices().scenario().equals("binding") ? "MAX(B" + at + ":B" + (at + 2) + ")" : "B" + (at + s.reported);
                main.row(at + 3).text(0, "SBM (" + r.choices().scenario() + ")", "bold").formula(1, reported, s.sbm(), "decimal");
                double drc = r.drc().delivered() ? r.drc().total() : 0;
                double rrao = r.rrao().delivered() ? r.rrao().total() : 0;
                main.row(at + 4).text(0, "DRC", "bold").formula(1, sum(3, drcFrom, drcTo), drc, "decimal");
                main.row(at + 5).text(0, "RRAO", "bold").formula(1, sum(3, rraoFrom, rraoTo), rrao, "decimal");
                main.row(at + 6).text(0, "TOTAL", "bold").formula(1, "B" + (at + 3) + "+B" + (at + 4) + "+B" + (at + 5), r.total(), "decimal");
                wb.name(AnswerBook.name("frtb-sa", "CHARGE"), main.name(), "$B$" + (at + 6));
                return at + 6;
            }
        };
    }
}
