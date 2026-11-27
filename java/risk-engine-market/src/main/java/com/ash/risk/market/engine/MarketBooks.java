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
package com.ash.risk.market.engine;

import com.ash.engine.Filter;
import com.ash.risk.kernel.scope.PositionRules;
import com.ash.risk.kernel.vector.ParallelSums;
import com.ash.risk.market.calc.CurrencyBook;
import com.ash.risk.market.calc.CurrencyTotals;
import com.ash.risk.market.calc.ScenarioFx;
import com.ash.risk.market.calc.VarBook;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Builds one book: the date's rows of one scenario set, cut to the newest N scenarios, in USD or converted to the reporting currency,
 * seen through one visibility. The steps are Drishti's (its loader as its goldens drive it: scenario PVs to P&amp;L through the scenario
 * FX, the position rules, the hierarchies over promoted columns, then the currency conversion), on the moved classes, unchanged.
 */
final class MarketBooks {

    /** What a book is made of; equal keys give the same bits. */
    record Key(String set, int window, String currency, String visibility) {}

    /** A built book, with the scenario dates and FX of its window. */
    record Built(VarBook book, List<LocalDate> scenarios, ScenarioFx fx) {}

    private MarketBooks() {
    }

    /** Null when the set is unknown or its FX is missing; the caller answers NOT_FOUND. */
    static Built build(MarketDay day, MarketDay.ScenarioSet set, int window, String currency, Filter pvSee, Filter pnlSee, MarketOptions o,
            ParallelSums sums) {
        List<LocalDate> scen = set.dates();
        int w = window <= 0 || window > scen.size() ? scen.size() : window;
        List<ScenarioFx.Rates> rates = new ArrayList<>();
        for (MarketDay.Fx f : day.fx) {
            if (set.id().equals(f.set())) {
                rates.add(new ScenarioFx.Rates(f.currency(), f.rate(), f.rates(), "supplied"));
            }
        }
        ScenarioFx fx = new ScenarioFx(scen.size(), rates);
        int from = scen.size() - w;
        List<LocalDate> cut = List.copyOf(scen.subList(from, scen.size()));
        List<ScenarioFx.Rates> cutRates = new ArrayList<>();
        for (ScenarioFx.Rates r : rates) {
            cutRates.add(new ScenarioFx.Rates(r.currency(), r.base(), Arrays.copyOfRange(r.scenarios(), from, r.scenarios().length), "supplied"));
        }
        ScenarioFx wfx = new ScenarioFx(w, cutRates);
        String label = set.id() + (w == scen.size() ? "" : "/" + w);
        VarBook.Builder b = new VarBook.Builder(day.date, label, cut, MarketOptions.ROWS_PER_CHUNK, Long.MAX_VALUE);
        b.columnHierarchies(o.hierarchies(), day.nodeLimits);
        b.currencyRates(c -> wfx.rates(c).orElse(null));
        PositionRules rules = o.positions();
        for (MarketDay.Row n : day.pv) {
            if (!set.id().equals(n.text("SCENARIO_SET_ID")) || n.vector() == null || !rules.live(n.text("TRADE_STATUS")) || !visible(pvSee, n)) {
                continue;
            }
            ScenarioFx.Rates r = fx.rates(n.text("CURRENCY")).orElse(null);
            if (r == null) {
                continue;                                               // a currency without FX: the loader's "not found" (counted)
            }
            double[] p = n.vector();
            double[] v = new double[p.length];
            ScenarioFx.pnlFromPv(n.pv(), p, p.length, r, v);
            add(b, rules, n, tail(v, w, n), n.pv() * r.base(), n.text("CURRENCY"));
        }
        for (MarketDay.Row n : day.pnl) {
            if (!set.id().equals(n.text("SCENARIO_SET_ID")) || n.vector() == null || !rules.live(n.text("TRADE_STATUS")) || !visible(pnlSee, n)) {
                continue;
            }
            add(b, rules, n, tail(n.vector(), w, n), n.pv(), null);
        }
        VarBook usd = b.build(sums);
        if (currency == null || currency.isBlank() || "USD".equalsIgnoreCase(currency)) {
            return new Built(usd, cut, wfx);
        }
        ScenarioFx.Rates rep = wfx.rates(currency).orElse(null);
        if (rep == null) {
            usd.close();
            return null;
        }
        VarBook converted = CurrencyBook.convert(usd, rep,
                new VarBook.Builder(day.date, label, cut, MarketOptions.ROWS_PER_CHUNK, Long.MAX_VALUE), sums).book();
        usd.close();
        return new Built(converted, cut, wfx);
    }

    /**
     * A USD limit (var-limit) in the reporting currency, as a converted book holds its nodes' limits: the limit over the currency's base
     * rate (USD per unit); NaN stays NaN, and USD or no currency is the limit itself. Null when the currency has no rate on the set.
     */
    static Double limitIn(Built b, String currency, Double usd) {
        if (usd == null || currency == null || currency.isBlank() || "USD".equalsIgnoreCase(currency)) {
            return usd;
        }
        ScenarioFx.Rates rep = b.fx().rates(currency.toUpperCase(java.util.Locale.ROOT)).orElse(null);
        return rep == null ? null : Double.isNaN(usd) ? usd : usd / rep.base();
    }

    /** The id a row is kept under in the book: its trade, else (a position sent whole) its position. */
    static String tradeKey(MarketDay.Row n) {
        String trade = n.text("TRADE_ID");
        return trade != null ? trade : n.text("POSITION_ID");
    }

    private static boolean visible(Filter see, MarketDay.Row n) {
        return see == Filter.ALL || see.test(n::text);
    }

    private static double[] tail(double[] v, int n, MarketDay.Row row) {
        if (v.length < n) {
            // the set's dates say n scenarios and a row's vector holds fewer: the supplier's error, named (never padded, never cut short)
            throw new IllegalArgumentException(row.key() + ": its scenario vector holds " + v.length + " values and the scenario set's window needs " + n);
        }
        return n == v.length ? v.clone() : Arrays.copyOfRange(v, v.length - n, v.length);
    }

    /** A row into the book, exactly as Drishti's loader under its goldens adds it (the position rules, the record's own currency). */
    private static void add(VarBook.Builder b, PositionRules rules, MarketDay.Row n, double[] v, double pvUsd, String pvCurrency) {
        List<String> cols = b.textColumns();
        String[] texts = new String[cols.size()];
        for (int c = 0; c < texts.length; c++) {
            texts[c] = n.text(cols.get(c));
        }
        String own = n.text("TRADE_CURRENCY");
        if (own != null) {
            texts[cols.indexOf("CURRENCY")] = own;
        }
        String trade = n.text("TRADE_ID");
        if (trade == null) {
            trade = n.text("POSITION_ID");                              // a position sent whole: never derived
            texts[cols.indexOf("POSITION_ID")] = trade;
            texts[cols.indexOf("POSITION_RULE")] = VarBook.SUPPLIED;
            texts[cols.indexOf("INSTRUMENT_ID")] = n.text("SECURITY_ID") != null ? n.text("SECURITY_ID") : trade;
            texts[cols.indexOf("SECURITY_ID")] = n.text("SECURITY_ID");
        } else {
            PositionRules.Derived d = rules.derive(trade, n.text("TT_BOOK"), n.text("PRODUCT_GROUP"), n.text("PRODUCT_TYPE"), n::text);
            texts[cols.indexOf("POSITION_ID")] = d.positionId();
            texts[cols.indexOf("POSITION_RULE")] = d.rule();
            texts[cols.indexOf("INSTRUMENT_ID")] = d.instrumentId();
            texts[cols.indexOf("SECURITY_ID")] = d.securityId();
        }
        texts[cols.indexOf(CurrencyTotals.COLUMN)] = pvCurrency;
        b.trade(trade, v, v.length, texts, new double[] {n.quantity(), pvUsd});
    }
}
