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

import com.ash.engine.Control;
import com.ash.engine.EngineException;
import com.ash.engine.Filter;
import com.ash.engine.InputTable;
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

    /** A built book, with the scenario dates and FX of its window, and what the session derives from it ({@link MarketDerived}). */
    record Built(VarBook book, List<LocalDate> scenarios, ScenarioFx fx, MarketDerived derived) {

        Built(VarBook book, List<LocalDate> scenarios, ScenarioFx fx, com.ash.risk.market.calc.VarCalc calc) {
            this(book, scenarios, fx, new MarketDerived(book, calc));
        }
    }

    private MarketBooks() {
    }

    /**
     * Null when the set is unknown or its FX is missing; the caller answers NOT_FOUND. The vector inputs are read in one pass each, every
     * row straight into the builder (its texts resolved to the builder's columns once per row, its vector converted or cut into one
     * scratch row the builder copies): nothing delivered is kept after the build.
     */
    static Built build(MarketDay day, MarketDay.ScenarioSet set, int window, String currency, Filter pvSee, Filter pnlSee, MarketOptions o,
            ParallelSums sums, Images images, String visibility, Control control) throws EngineException {
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
        HeapBook heap = () -> {
            VarBook.Builder b = new VarBook.Builder(day.date, label, cut, MarketOptions.ROWS_PER_CHUNK, Long.MAX_VALUE);
            b.columnHierarchies(o.hierarchies(), day.nodeLimits);
            b.currencyRates(c -> wfx.rates(c).orElse(null));
            PositionRules rules = o.positions();
            Adder add = new Adder(b, rules, w);
            InputTable pvIn = day.vectors(MarketDay.PV_INPUT);
            if (pvIn != null) {
                MarketRows rows = MarketRows.read(pvIn, MarketDay.PV_INPUT.key(), null, control);
                double[] pnl = new double[scen.size()];
                rows.eachVector(pvIn, MarketDay.PV_INPUT.vector(), control, (n, p, len) -> {
                    if (!set.id().equals(n.text("SCENARIO_SET_ID")) || !rules.live(n.text("TRADE_STATUS")) || !visible(pvSee, n)) {
                        return;
                    }
                    ScenarioFx.Rates r = fx.rates(n.text("CURRENCY")).orElse(null);
                    if (r == null) {
                        return;                                             // a currency without FX: the loader's "not found" (counted)
                    }
                    double[] v = len <= pnl.length ? pnl : new double[len];
                    ScenarioFx.pnlFromPv(n.number("BASE_PV"), p, len, r, v);
                    add.row(n, v, len, n.number("BASE_PV") * r.base(), n.text("CURRENCY"));
                });
            }
            InputTable pnlIn = day.vectors(MarketDay.PNL_INPUT);
            if (pnlIn != null) {
                MarketRows rows = MarketRows.read(pnlIn, MarketDay.PNL_INPUT.key(), null, control);
                rows.eachVector(pnlIn, MarketDay.PNL_INPUT.vector(), control, (n, v, len) -> {
                    if (!set.id().equals(n.text("SCENARIO_SET_ID")) || !rules.live(n.text("TRADE_STATUS")) || !visible(pnlSee, n)) {
                        return;
                    }
                    add.row(n, v, len, n.number("PV"), null);
                });
            }
            control.progress(0.9, "building the book of " + label);
            return b.build(sums);
        };
        // the mapped tier: the book's rows and aggregates in the host's kept image (found, or built once from the heap book and written)
        String tag = "|" + label + "|" + visibility;
        VarBook usd = o.float32Rows() ? images.book("book|USD" + tag, heap) : heap.build();
        if (currency == null || currency.isBlank() || "USD".equalsIgnoreCase(currency)) {
            return new Built(usd, cut, wfx, o.calc());
        }
        ScenarioFx.Rates rep = wfx.rates(currency).orElse(null);
        if (rep == null) {
            usd.close();
            return null;
        }
        // converted from the USD book's float32 rows (as the host's mapped tier converts), kept as its own image
        HeapBook convert = () -> CurrencyBook.convert(usd, rep, new VarBook.Builder(day.date, label, cut, MarketOptions.ROWS_PER_CHUNK,
                Long.MAX_VALUE), sums).book();
        VarBook converted = o.float32Rows() ? images.book("book|" + currency.toUpperCase(java.util.Locale.ROOT) + tag, convert) : convert.build();
        usd.close();
        return new Built(converted, cut, wfx, o.calc());
    }

    /** Builds a book on the heap (its rows read from the inputs, or converted from another book). */
    @FunctionalInterface
    interface HeapBook {
        VarBook build() throws EngineException;
    }

    /** The session's kept books: the image of {@code name} found, or built once from {@code heap} and written; the book read from it. */
    @FunctionalInterface
    interface Images {
        VarBook book(String name, HeapBook heap) throws EngineException;
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
    static String tradeKey(MarketRows.Row n) {
        String trade = n.text("TRADE_ID");
        return trade != null ? trade : n.text("POSITION_ID");
    }

    private static boolean visible(Filter see, MarketRows.Row n) {
        return see == Filter.ALL || see.test(n::text);
    }

    /**
     * Adds rows exactly as Drishti's loader under its goldens adds them (the position rules, the record's own currency, the newest
     * {@code window} scenarios of each vector), the builder's text columns resolved to their positions once.
     */
    private static final class Adder {
        private final VarBook.Builder b;
        private final PositionRules rules;
        private final int window;
        private final List<String> cols;
        private final int currency;
        private final int position;
        private final int rule;
        private final int instrument;
        private final int security;
        private final int pvCurrency;
        private final double[] scratch;

        Adder(VarBook.Builder b, PositionRules rules, int window) {
            this.b = b;
            this.rules = rules;
            this.window = window;
            this.cols = b.textColumns();
            this.currency = cols.indexOf("CURRENCY");
            this.position = cols.indexOf("POSITION_ID");
            this.rule = cols.indexOf("POSITION_RULE");
            this.instrument = cols.indexOf("INSTRUMENT_ID");
            this.security = cols.indexOf("SECURITY_ID");
            this.pvCurrency = cols.indexOf(CurrencyTotals.COLUMN);
            this.scratch = new double[window];
        }

        /** One row: {@code v[0..length)} its vector (read now, not kept), its PV in USD and the currency its PVs came in. */
        void row(MarketRows.Row n, double[] v, int length, double pvUsd, String pvCcy) {
            if (length < window) {
                // the set's dates say n scenarios and a row's vector holds fewer: the supplier's error, named (never padded, never cut short)
                throw new IllegalArgumentException(n.key() + ": its scenario vector holds " + length + " values and the scenario set's window needs "
                        + window);
            }
            System.arraycopy(v, length - window, scratch, 0, window);  // the newest window scenarios; the builder copies the row
            String[] texts = new String[cols.size()];
            for (int c = 0; c < texts.length; c++) {
                texts[c] = n.text(cols.get(c));
            }
            String own = n.text("TRADE_CURRENCY");
            if (own != null) {
                texts[currency] = own;
            }
            String trade = n.text("TRADE_ID");
            if (trade == null) {
                trade = n.text("POSITION_ID");                          // a position sent whole: never derived
                texts[position] = trade;
                texts[rule] = VarBook.SUPPLIED;
                texts[instrument] = n.text("SECURITY_ID") != null ? n.text("SECURITY_ID") : trade;
                texts[security] = n.text("SECURITY_ID");
            } else {
                PositionRules.Derived d = rules.derive(trade, n.text("TT_BOOK"), n.text("PRODUCT_GROUP"), n.text("PRODUCT_TYPE"), n::text);
                texts[position] = d.positionId();
                texts[rule] = d.rule();
                texts[instrument] = d.instrumentId();
                texts[security] = d.securityId();
            }
            texts[pvCurrency] = pvCcy;
            b.trade(trade, scratch, window, texts, new double[] {n.number("QUANTITY"), pvUsd});
        }
    }
}
