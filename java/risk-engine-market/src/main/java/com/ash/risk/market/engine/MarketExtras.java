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

import com.ash.engine.Control;
import com.ash.engine.EngineException;
import com.ash.engine.Inputs;
import com.ash.engine.Missing;
import com.ash.engine.Snapshot;
import com.ash.risk.kernel.io.InputRows;
import com.ash.risk.kernel.io.InputRows.Spec;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The inputs of the market-risk engine beyond the scenario vectors, read once when the session opens and kept in the canonical order
 * (each sorted by its key): the official records of earlier dates and the desk P&amp;L (backtesting, period statistics, the reports), the
 * long P&amp;L histories (the stress window selection), the sensitivity elements with their factor dictionary, the DRC and RRAO rows and
 * the FX mids (FRTB-SA, the ladders and the sums), and the report rows. Every one is optional; an output that needs one that is absent
 * answers NOT_FOUND naming it.
 */
final class MarketExtras {

    static final String HISTORY = "var-history";
    static final String DESK_PNL = "desk-pnl";
    static final String PNL_HISTORY = "pnl-history";
    static final String SENSITIVITY = "sensitivity-element";
    static final String FACTOR = "risk-factor";
    static final String DEFAULT_RISK = "default-risk";
    static final String RESIDUAL_RISK = "residual-risk";
    static final String FX_SPOT = "fx-spot";
    static final String REPORT_ROW = "report-row";

    static final Map<String, Spec> SPECS = Map.of(
            HISTORY, new Spec(HISTORY, "risk/var-official@1", List.of("BUSINESS_DATE", "NODE_ID", "RESULT_VERSION"),
                    List.of("BUSINESS_DATE", "NODE_ID", "VAR"), Set.of("RESULT_VERSION", "VAR", "ES", "CONFIDENCE", "SVAR", "SES", "HYPO_PNL")),
            DESK_PNL, new Spec(DESK_PNL, "risk/desk-pnl@1", List.of("DESK_PNL_ID"), List.of("BUSINESS_DATE", "DESK_ID"),
                    Set.of("ACTUAL_PNL", "HYPOTHETICAL_PNL")),
            PNL_HISTORY, new Spec(PNL_HISTORY, "risk/pnl-history@1", List.of("HISTORY_ID"), List.of("HISTORY_ID", "NODE_ID", "HISTORY_DATES", "PNL"),
                    Set.of()),
            SENSITIVITY, new Spec(SENSITIVITY, "risk/sensitivity-element@1", List.of("SENSITIVITY_ID", "ELEMENT"),
                    List.of("SENSITIVITY_ID", "RISK_FACTOR", "MEASURE", "VALUE"), Set.of("ELEMENT", "VALUE", "SHOCK_SIZE", "IMPLIED_VOL", "NOTIONAL")),
            FACTOR, new Spec(FACTOR, "risk/risk-factor@1", List.of("RISK_FACTOR"), List.of("RISK_FACTOR", "RISK_CLASS"), Set.of()),
            DEFAULT_RISK, new Spec(DEFAULT_RISK, "risk/default-risk@1", List.of("DEFAULT_RISK_ID"), List.of("OBLIGOR", "NOTIONAL"),
                    Set.of("NOTIONAL", "MARKET_VALUE", "CASH_EQUITY_MATURITY")),
            RESIDUAL_RISK, new Spec(RESIDUAL_RISK, "risk/residual-risk@1", List.of("RESIDUAL_RISK_ID"), List.of("TRADE_ID", "GROSS_NOTIONAL"),
                    Set.of("GROSS_NOTIONAL")),
            FX_SPOT, new Spec(FX_SPOT, "market/fx-spot@1", List.of("PAIR"), List.of("PAIR", "MID"), Set.of("MID")),
            REPORT_ROW, new Spec(REPORT_ROW, "risk/report-definition@1", List.of("REPORT_ID", "ROW_ORDER"), List.of("REPORT_ID", "ROW_ORDER"),
                    Set.of("ROW_ORDER")));

    /** One official record of a node on a date (its latest result version), as the backtest and the statistics read it. */
    record Official(LocalDate date, String hierarchy, String node, String name, String parent, String level, double var, double es,
            double confidence, double svar, double ses, double hypo, double version, String capture) {}

    /** One desk's P&amp;L of a date. */
    record DeskPnl(LocalDate date, String desk, String legalEntity, double actual, double hypothetical, Map<String, Object> row) {}

    /** One node's long P&amp;L history. */
    record History(String id, String hierarchy, String node, String name, String level, String[] dates, double[] pnl, boolean synthetic,
            Map<String, Object> row) {}

    final List<Official> officials;
    final List<DeskPnl> deskPnl;
    final List<History> histories;
    final List<Map<String, Object>> sensitivities;
    final List<Map<String, Object>> factors;
    final List<Map<String, Object>> defaultRisk;
    final List<Map<String, Object>> residualRisk;
    final Map<String, Double> usdPer;
    final List<Map<String, Object>> reportRows;
    /** Which optional inputs were delivered (an absent DRC or RRAO input is missing, never 0). */
    final Set<String> present;

    private MarketExtras(List<Official> officials, List<DeskPnl> deskPnl, List<History> histories, List<Map<String, Object>> sensitivities,
            List<Map<String, Object>> factors, List<Map<String, Object>> defaultRisk, List<Map<String, Object>> residualRisk,
            Map<String, Double> usdPer, List<Map<String, Object>> reportRows, Set<String> present) {
        this.officials = officials;
        this.deskPnl = deskPnl;
        this.histories = histories;
        this.sensitivities = sensitivities;
        this.factors = factors;
        this.defaultRisk = defaultRisk;
        this.residualRisk = residualRisk;
        this.usdPer = usdPer;
        this.reportRows = reportRows;
        this.present = present;
    }

    static MarketExtras read(Snapshot snapshot, Inputs inputs, Map<String, Missing> missing, Control control) throws EngineException {
        Map<String, List<Map<String, Object>>> rows = new TreeMap<>();
        for (Spec s : SPECS.values()) {
            rows.put(s.name(), InputRows.read(s, inputs, snapshot, missing, control, s.name().equals(PNL_HISTORY) ? List.of("PNL") : List.of()));
        }
        // the official records: the latest result version of each node and date (rows sorted by date, node, version: the last wins)
        Map<String, Official> latest = new TreeMap<>();
        for (Map<String, Object> r : rows.get(HISTORY)) {
            String d = InputRows.text(r, "BUSINESS_DATE");
            String node = InputRows.text(r, "NODE_ID");
            if (d == null || node == null) {
                continue;
            }
            double conf = InputRows.number(r, "CONFIDENCE");
            latest.put(d.substring(0, 10) + "|" + node + "|" + conf, new Official(LocalDate.parse(d.substring(0, 10)), InputRows.text(r, "HIERARCHY"),
                    node, InputRows.text(r, "NODE_NAME"), InputRows.text(r, "PARENT_ID"), InputRows.text(r, "LEVEL"), InputRows.number(r, "VAR"),
                    InputRows.number(r, "ES"), conf, InputRows.number(r, "SVAR"), InputRows.number(r, "SES"), InputRows.number(r, "HYPO_PNL"),
                    InputRows.number(r, "RESULT_VERSION"), InputRows.text(r, "CAPTURE_ID")));
        }
        List<DeskPnl> desk = new ArrayList<>();
        for (Map<String, Object> r : rows.get(DESK_PNL)) {
            String d = InputRows.text(r, "BUSINESS_DATE");
            double a = InputRows.number(r, "ACTUAL_PNL");
            double h = InputRows.number(r, "HYPOTHETICAL_PNL");
            if (d != null && (Double.isFinite(a) || Double.isFinite(h))) {
                desk.add(new DeskPnl(LocalDate.parse(d.substring(0, 10)), InputRows.text(r, "DESK_ID"), InputRows.text(r, "LEGAL_ENTITY"), a, h, r));
            }
        }
        List<History> hist = new ArrayList<>();
        if (!missing.containsKey(PNL_HISTORY)) {
            Spec s = SPECS.get(PNL_HISTORY);
            Map<String, double[]> v = InputRows.vectors(s, "PNL", inputs, control);
            for (Map<String, Object> r : rows.get(PNL_HISTORY)) {
                double[] pnl = v.get(InputRows.key(r, s.key()));
                List<String> dates = InputRows.list(r.get("HISTORY_DATES"));
                if (pnl == null || dates.size() != pnl.length) {
                    continue;                                            // a history whose dates and values disagree is not used
                }
                hist.add(new History(InputRows.text(r, "HISTORY_ID"), InputRows.text(r, "HIERARCHY"), InputRows.text(r, "NODE_ID"),
                        InputRows.text(r, "NAME"), InputRows.text(r, "LEVEL"), dates.toArray(String[]::new), pnl, InputRows.flag(r, "SYNTHETIC"), r));
            }
        }
        Map<String, Double> usdPer = new TreeMap<>();
        usdPer.put("USD", 1.0);
        for (Map<String, Object> r : rows.get(FX_SPOT)) {
            String p = String.valueOf(InputRows.text(r, "PAIR")).replace("FX-", "").replace("/", "").toUpperCase(java.util.Locale.ROOT);
            double mid = InputRows.number(r, "MID");
            if (!(mid > 0) || p.length() != 6) {
                continue;
            }
            if (p.endsWith("USD")) {
                usdPer.put(p.substring(0, 3), mid);
            } else if (p.startsWith("USD")) {
                usdPer.put(p.substring(3), 1 / mid);
            }
        }
        Set<String> present = new java.util.TreeSet<>(SPECS.keySet());
        present.removeAll(missing.keySet());
        return new MarketExtras(List.copyOf(latest.values()), List.copyOf(desk), List.copyOf(hist), rows.get(SENSITIVITY), rows.get(FACTOR),
                rows.get(DEFAULT_RISK), rows.get(RESIDUAL_RISK), Map.copyOf(usdPer), rows.get(REPORT_ROW), Set.copyOf(present));
    }

    long bytes() {
        long b = 256L * (officials.size() + deskPnl.size() + sensitivities.size() + factors.size() + defaultRisk.size() + residualRisk.size()
                + reportRows.size());
        for (History h : histories) {
            b += 512 + 64L * h.pnl().length;
        }
        return b;
    }
}
