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
package com.ash.drishti.banking.var;

import com.ash.drishti.banking.compute.ccr.CsaTerms;
import com.ash.drishti.banking.compute.ccr.ExposureBook;
import com.ash.drishti.banking.compute.ccr.ExposureCalc;
import com.ash.drishti.banking.compute.ccr.ExposureGrid;
import com.ash.drishti.banking.compute.ccr.ExposureMath;
import com.ash.drishti.banking.compute.ccr.ExposureScopes;
import com.ash.drishti.banking.compute.var.CurrencyBook;
import com.ash.drishti.banking.compute.var.CurrencyTotals;
import com.ash.drishti.banking.compute.var.HierarchySpec;
import com.ash.drishti.banking.compute.var.PositionRules;
import com.ash.drishti.banking.compute.var.ScenarioFx;
import com.ash.drishti.banking.compute.var.ScopeResolver;
import com.ash.drishti.banking.compute.var.VarBook;
import com.ash.drishti.banking.compute.var.VarCalc;
import com.ash.drishti.compute.vector.ParallelSums;
import com.ash.drishti.identity.content.FileContentStore;
import com.ash.drishti.testkit.FeedFiles;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The reference recorder (DRISHTI_RISK_DESIGN section 7, step K2): Drishti's own classes at the freeze commit, run over Drishti's committed
 * test data, write the answers the Drishti Risk engines must give, every double as its raw bits. It lives in the product's repository
 * and is never part of Drishti; it is compiled against a Drishti build of the freeze commit and runs in Drishti's package so it can reach
 * the basket store exactly as Drishti's goldens do (VarGoldenMatrixTest, SystemBasketGoldenTest).
 *
 * <p>It does three things:
 * <ol>
 * <li><b>Exports the inputs</b> under the engines' contracts: {@code tck/data/<engine>/<date>/<input>.jsonl.gz} (scenario PVs of every set,
 * the scenario sets with their dates joined by commas, the FX, the limits, the system baskets with the scope Drishti's store derives;
 * the exposure paths, the run, the netting sets, the CSAs, each balance's holdings after haircut as Drishti's loader reads them, each
 * trade's MtM today from its netting set). Gzipped and committed (the kit reads them; about 62 MB), so the cases run without Drishti.</li>
 * <li><b>Records the answers</b> as kit cases, {@code tck/recorded/<engine>/recorder-<date>.yaml}: market risk for 509 and 250 scenarios
 * in USD and EUR (headline, node-var, node-components, basket-var), CCR for the bank and every netting set (exposure-summary,
 * exposure-profile). Rows are added in the engines' canonical order (each input sorted by its key), so the engines must give these bits.</li>
 * <li><b>Proves itself stable</b> before anything is written: every answer is computed again with 1, 4 and 24 summation threads and must
 * be the same text, and every whole-unit VaR and ES must equal Drishti's own golden (golden-matrix.json.gz, in Drishti's file order).</li>
 * </ol>
 *
 * <p>Run: {@code python3 -I tools/split/recorder/record.py --drishti <absolute Drishti checkout at 3813b8735, built> --risk <absolute
 * drishti-risk checkout>}.
 */
public final class RiskRecorder {

    static final ObjectMapper JSON = new ObjectMapper();
    static final List<String> DATES = List.of("2026-09-28", "2026-09-29", "2026-09-30");
    static final int[] WINDOWS = {509, 250};
    static final int[] THREADS = {1, 4, 24};

    private final Path drishti;
    private final Path risk;
    private final Path store;

    RiskRecorder(Path drishti, Path risk, Path store) {
        this.drishti = drishti;
        this.risk = risk;
        this.store = store;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException("usage: RiskRecorder <Drishti root> <drishti-risk root> <an empty work folder>; all absolute");
        }
        Path d = Path.of(args[0]);
        Path r = Path.of(args[1]);
        Path w = Path.of(args[2]);
        for (Path p : List.of(d, r, w)) {
            if (!p.isAbsolute()) {
                throw new IllegalArgumentException("an absolute path is expected: " + p);
            }
        }
        RiskRecorder rec = new RiskRecorder(d, r, w);
        rec.market();
        rec.ccr();
    }

    // ------------------------------------------------------------------------------------------------------------------ market risk

    @SuppressWarnings("unchecked")
    void market() throws Exception {
        Path data = drishti.resolve("data/testdata/market-risk");
        Map<String, Object> pack = new ObjectMapper(new YAMLFactory()).readValue(
                drishti.resolve("modules/banking/packs/market-risk/pack.yaml").toFile(), Map.class);
        List<HierarchySpec> specs = HierarchySpec.parse((Map<?, ?>) pack.get("hierarchies")).stream().map(s -> s.withRootName("Drishti Bank")).toList();
        PositionRules rules = PositionRules.of((Map<?, ?>) pack.get("positions"));
        Map<String, String> words = new LinkedHashMap<>();
        specs.forEach(s -> {
            if (s.word() != null && !s.word().isBlank()) {
                words.put(s.word(), s.id());
            }
        });
        Object seed = VarSystemBasketEdit.YAML.readValue(drishti.resolve("modules/banking/packs/market-risk/seeds/system-baskets.yaml").toFile(), Object.class);
        VarSystemBaskets baskets = new VarSystemBaskets(new FileContentStore(store.resolve("content")));
        if (baskets.seed(seed, "recorder", null) != 28) {
            throw new IllegalStateException("the demo's 28 system baskets did not seed");
        }
        JsonNode golden;
        try (var in = new java.util.zip.GZIPInputStream(Files.newInputStream(risk.resolve("java/risk-market/src/test/resources/var/golden-matrix.json.gz")))) {
            golden = JSON.readTree(in);
        }
        for (String date : DATES) {
            Map<String, List<JsonNode>> in = new TreeMap<>();
            for (String kind : List.of("scenario-pv", "pnl-vector", "scenario-set", "fx-scenario", "var-limit")) {
                in.put(kind, read(data.resolve(kind), date));
            }
            Map<String, Double> limits = new LinkedHashMap<>();
            Map<String, Double> basketLimits = new LinkedHashMap<>();
            for (JsonNode n : in.get("var-limit")) {
                ("PORTFOLIO".equalsIgnoreCase(text(n, "HIERARCHY")) ? basketLimits : limits).put(n.path("NODE_ID").asText(), n.path("LIMIT_VAR").asDouble());
            }
            Map<String, VarDate.Portfolio> defs = new TreeMap<>(baskets.portfolios(LocalDate.parse(date), basketLimits));
            exportMarket(date, in, defs);
            StringBuilder first = null;
            for (int threads : THREADS) {
                StringBuilder y = new StringBuilder();
                try (ParallelSums sums = new ParallelSums(threads, 4096)) {
                    for (int w : WINDOWS) {
                        for (String cur : List.of("USD", "EUR")) {
                            VarBook b = book(date, in, specs, rules, limits, w, cur, sums);
                            y.append(marketCase(date, w, cur, b, defs, words, sums, golden));
                            b.close();
                        }
                    }
                }
                if (first == null) {
                    first = y;
                } else if (!first.toString().equals(y.toString())) {
                    throw new IllegalStateException(date + ": the answers differ between 1 and " + threads + " summation threads; nothing is recorded");
                }
            }
            write(risk.resolve("tck/recorded/market-risk/recorder-" + date + ".yaml"), header("market-risk", date, specs, rules) + first);
        }
    }

    /** A date's book, as the market-risk engine builds it: every input sorted by its key, then Drishti's loader steps under its goldens. */
    static VarBook book(String date, Map<String, List<JsonNode>> in, List<HierarchySpec> specs, PositionRules rules, Map<String, Double> limits,
            int window, String currency, ParallelSums sums) {
        JsonNode normal = in.get("scenario-set").stream().filter(n -> !"STRESS".equalsIgnoreCase(text(n, "SET_TYPE")))
                .min(Comparator.comparing(n -> n.path("SCENARIO_SET_ID").asText())).orElseThrow();
        String set = normal.path("SCENARIO_SET_ID").asText();
        List<LocalDate> scen = new ArrayList<>();
        normal.path("SCENARIO_DATES").forEach(d -> scen.add(LocalDate.parse(d.asText())));
        List<ScenarioFx.Rates> rates = new ArrayList<>();
        for (JsonNode n : sorted(in.get("fx-scenario"), "FX_SCENARIO_ID")) {
            if (set.equals(text(n, "SCENARIO_SET_ID"))) {
                rates.add(new ScenarioFx.Rates(text(n, "CURRENCY"), n.path("RATE").asDouble(), doubles(n.get("RATES")), "supplied"));
            }
        }
        ScenarioFx fx = new ScenarioFx(scen.size(), rates);
        int w = window <= 0 || window > scen.size() ? scen.size() : window;
        int from = scen.size() - w;
        List<LocalDate> cut = List.copyOf(scen.subList(from, scen.size()));
        List<ScenarioFx.Rates> cutRates = new ArrayList<>();
        for (ScenarioFx.Rates r : rates) {
            cutRates.add(new ScenarioFx.Rates(r.currency(), r.base(), Arrays.copyOfRange(r.scenarios(), from, r.scenarios().length), "supplied"));
        }
        ScenarioFx wfx = new ScenarioFx(w, cutRates);
        String label = set + (w == scen.size() ? "" : "/" + w);
        VarBook.Builder b = new VarBook.Builder(LocalDate.parse(date), label, cut, 65_536, Long.MAX_VALUE);
        b.columnHierarchies(specs, limits);
        b.currencyRates(c -> wfx.rates(c).orElse(null));
        for (JsonNode n : sorted(in.get("scenario-pv"), "SCENARIO_PV_ID")) {
            if (!set.equals(text(n, "SCENARIO_SET_ID")) || !rules.live(text(n, "TRADE_STATUS"))) {
                continue;
            }
            ScenarioFx.Rates r = fx.rates(text(n, "CURRENCY")).orElse(null);
            if (r == null) {
                continue;
            }
            double[] p = doubles(n.get("SCENARIO_PV"));
            double[] v = new double[p.length];
            ScenarioFx.pnlFromPv(n.path("BASE_PV").asDouble(), p, p.length, r, v);
            add(b, rules, n, tail(v, w), n.path("BASE_PV").asDouble() * r.base(), text(n, "CURRENCY"));
        }
        for (JsonNode n : sorted(in.get("pnl-vector"), "VECTOR_ID")) {
            if (!set.equals(text(n, "SCENARIO_SET_ID")) || !rules.live(text(n, "TRADE_STATUS"))) {
                continue;
            }
            add(b, rules, n, tail(doubles(n.get("PNL")), w), n.path("PV").asDouble(Double.NaN), null);
        }
        VarBook usd = b.build(sums);
        if ("USD".equals(currency)) {
            return usd;
        }
        VarBook out = CurrencyBook.convert(usd, wfx.rates(currency).orElseThrow(),
                new VarBook.Builder(LocalDate.parse(date), label, cut, 65_536, Long.MAX_VALUE), sums).book();
        usd.close();
        return out;
    }

    /** TestDataBook.add of Drishti's goldens, unchanged. */
    static void add(VarBook.Builder b, PositionRules rules, JsonNode n, double[] v, double pvUsd, String pvCurrency) {
        List<String> cols = b.textColumns();
        String[] texts = new String[cols.size()];
        for (int c = 0; c < texts.length; c++) {
            texts[c] = text(n, cols.get(c));
        }
        String own = text(n, "TRADE_CURRENCY");
        if (own != null) {
            texts[cols.indexOf("CURRENCY")] = own;
        }
        String trade = text(n, "TRADE_ID");
        if (trade == null) {
            trade = text(n, "POSITION_ID");
            texts[cols.indexOf("POSITION_ID")] = trade;
            texts[cols.indexOf("POSITION_RULE")] = VarBook.SUPPLIED;
            texts[cols.indexOf("INSTRUMENT_ID")] = text(n, "SECURITY_ID") != null ? text(n, "SECURITY_ID") : trade;
            texts[cols.indexOf("SECURITY_ID")] = text(n, "SECURITY_ID");
        } else {
            PositionRules.Derived d = rules.derive(trade, text(n, "TT_BOOK"), text(n, "PRODUCT_GROUP"), text(n, "PRODUCT_TYPE"), k -> text(n, k));
            texts[cols.indexOf("POSITION_ID")] = d.positionId();
            texts[cols.indexOf("POSITION_RULE")] = d.rule();
            texts[cols.indexOf("INSTRUMENT_ID")] = d.instrumentId();
            texts[cols.indexOf("SECURITY_ID")] = d.securityId();
        }
        texts[cols.indexOf(CurrencyTotals.COLUMN)] = pvCurrency;
        b.trade(trade, v, v.length, texts, new double[] {n.path("QUANTITY").asDouble(Double.NaN), pvUsd});
    }

    /** One recorded case: the four outputs of one window and currency, every double as raw bits; units checked against the golden. */
    static String marketCase(String date, int w, String cur, VarBook b, Map<String, VarDate.Portfolio> defs, Map<String, String> words,
            ParallelSums sums, JsonNode golden) {
        VarCalc calc = VarCalc.defaults();
        JsonNode g = golden.get(date + "/" + w + "/" + cur);
        StringBuilder y = new StringBuilder();
        y.append("  - id: ").append(date).append('-').append(w).append('-').append(cur).append('\n');
        y.append("    source: \"recorder: Drishti 3813b8735 (VarBook, VarCalc, CurrencyBook, ScopeResolver), canonical order, 1/4/24 threads equal\"\n");
        y.append("    reference: ").append(w == 250 && cur.equals("USD")).append('\n');
        y.append("    laws: []                 # the recorded bits only: the laws run on the hand cases (tck/cases), not on 8,000 positions\n");
        y.append("    request: { outputs: [ headline, node-var, node-components, basket-var ], params: { scenario-set: \"\", window: ").append(w)
                .append(", currency: ").append(cur).append(" }, visibility: { scenario-pv: all, pnl-vector: all }, seed: 0 }\n");
        y.append("    expect:\n      status: complete\n      tables:\n");
        y.append("        headline:\n          columns: [ HIERARCHY, NODE_ID, VAR, ES, VAR_UNITS, ES_UNITS ]\n          rows:\n");
        for (String h : new TreeSet<>(b.hierarchies())) {
            VarBook.Node root = b.root(h);
            VarCalc.Measures ms = calc.measures(b.aggregate(root.id()));
            y.append("            - [ ").append(q(h)).append(", ").append(q(root.id())).append(", ").append(bits(ms.var())).append(", ")
                    .append(bits(ms.es())).append(", ").append(Math.round(ms.var())).append(", ").append(Math.round(ms.es())).append(" ]\n");
        }
        y.append("        node-var:\n          columns: [ NODE_ID, HIERARCHY, PARENT, LEVEL, NAME, MEMBERS, VAR, ES, VAR_UNITS, ES_UNITS, VAR_SCENARIO, LIMIT, LIMIT_USE ]\n          rows:\n");
        StringBuilder comps = new StringBuilder();
        for (String id : new TreeSet<>(b.nodes().keySet())) {
            VarBook.Node x = b.node(id);
            double[] v = b.aggregate(id);
            VarCalc.Measures ms = calc.measures(v);
            unitsAgree(g, "nodes", id, ms);
            double limit = x.limit();
            y.append("            - [ ").append(q(id)).append(", ").append(q(x.hierarchy())).append(", ").append(q(x.parent())).append(", ")
                    .append(q(x.level())).append(", ").append(q(x.name())).append(", ").append(x.members().size()).append(", ").append(bits(ms.var()))
                    .append(", ").append(bits(ms.es())).append(", ").append(Math.round(ms.var())).append(", ").append(Math.round(ms.es())).append(", ")
                    .append(ms.varScenario() < 0 ? "null" : String.valueOf(ms.varScenario())).append(", ")
                    .append(Double.isNaN(limit) ? "null" : bits(limit)).append(", ")
                    .append(Double.isNaN(limit) || limit == 0 ? "null" : bits(ms.var() / limit)).append(" ]\n");
            if (!x.children().isEmpty()) {
                List<VarCalc.Part> parts = new ArrayList<>();
                for (String c : new TreeSet<>(x.children())) {
                    parts.add(new VarCalc.Part(c, c, "node", b.aggregate(c), b.node(c).members().size()));
                }
                Map<String, VarCalc.Contribution> byChild = new TreeMap<>();
                calc.contributions(v, ms, parts).forEach(c -> byChild.put(c.key(), c));
                byChild.forEach((c, k) -> comps.append("            - [ ").append(q(id)).append(", ").append(q(c)).append(", ").append(bits(k.componentVar()))
                        .append(", ").append(bits(k.standaloneVar())).append(", ").append(bits(k.incrementalVar())).append(" ]\n"));
            }
        }
        y.append("        node-components:\n          columns: [ NODE_ID, CHILD, COMPONENT_VAR, STANDALONE_VAR, INCREMENTAL_VAR ]\n          rows:\n").append(comps);
        ScopeResolver resolver = new ScopeResolver(b, new ScopeResolver.Definitions() {
            @Override
            public Optional<String> portfolio(String id) {
                return Optional.ofNullable(defs.get(id)).map(VarDate.Portfolio::scope);
            }

            @Override
            public Optional<String> basket(String name) {
                return Optional.empty();
            }
        }, words);
        y.append("        basket-var:\n          columns: [ BASKET_ID, NAME, TYPE, OWNER, MEMBERS, UNKNOWN, VAR, ES, VAR_UNITS, ES_UNITS, LIMIT, LIMIT_USE ]\n          rows:\n");
        for (VarDate.Portfolio pf : defs.values()) {
            ScopeResolver.Resolution r = resolver.resolve("PORT:" + pf.id());
            VarCalc.Measures ms = calc.measures(b.vector(r.set(), sums));
            unitsAgree(g, "baskets", pf.id(), ms);
            double limit = pf.limit();
            y.append("            - [ ").append(q(pf.id())).append(", ").append(q(pf.name())).append(", ").append(q(pf.type())).append(", ")
                    .append(q(pf.owner())).append(", ").append(r.set().size()).append(", ").append(r.unknown().isEmpty() ? "null" : q(String.join(",", r.unknown())))
                    .append(", ").append(bits(ms.var())).append(", ").append(bits(ms.es())).append(", ").append(Math.round(ms.var())).append(", ")
                    .append(Math.round(ms.es())).append(", ").append(Double.isNaN(limit) ? "null" : bits(limit)).append(", ")
                    .append(Double.isNaN(limit) || limit == 0 ? "null" : bits(ms.var() / limit)).append(" ]\n");
        }
        return y.toString();
    }

    /** The canonical order may move the last bits, never a whole unit: each must equal Drishti's golden in its file order. */
    static void unitsAgree(JsonNode golden, String section, String id, VarCalc.Measures ms) {
        if (golden == null || golden.get(section) == null || golden.get(section).get(id) == null) {
            return;
        }
        JsonNode n = golden.get(section).get(id);
        if (Math.round(n.get("var").asDouble()) != Math.round(ms.var()) || Math.round(n.get("es").asDouble()) != Math.round(ms.es())) {
            throw new IllegalStateException(section + " " + id + ": whole units differ from Drishti's golden (VaR " + n.get("var") + " against "
                    + ms.var() + ", ES " + n.get("es") + " against " + ms.es() + "); nothing is recorded");
        }
    }

    /** The inputs as the engine reads them, one JSONL file per input. */
    void exportMarket(String date, Map<String, List<JsonNode>> in, Map<String, VarDate.Portfolio> defs) throws IOException {
        Path dir = risk.resolve("tck/data/market-risk/" + date);
        Files.createDirectories(dir);
        for (String kind : List.of("scenario-pv", "pnl-vector", "fx-scenario", "var-limit")) {
            jsonl(dir.resolve(kind + ".jsonl"), in.get(kind));
        }
        List<JsonNode> sets = new ArrayList<>();
        for (JsonNode n : in.get("scenario-set")) {
            ObjectNode o = n.deepCopy();
            List<String> d = new ArrayList<>();
            n.path("SCENARIO_DATES").forEach(x -> d.add(x.asText()));
            o.put("SCENARIO_DATES", String.join(",", d));
            sets.add(o);
        }
        jsonl(dir.resolve("scenario-set.jsonl"), sets);
        List<JsonNode> pfs = new ArrayList<>();
        for (VarDate.Portfolio p : defs.values()) {
            ObjectNode o = JSON.createObjectNode();
            o.put("PORTFOLIO_ID", p.id()).put("NAME", p.name()).put("TYPE", p.type()).put("OWNER", p.owner()).put("SCOPE", p.scope());
            pfs.add(o);
        }
        jsonl(dir.resolve("system-basket.jsonl"), pfs);
    }

    // ------------------------------------------------------------------------------------------------------------------ CCR

    void ccr() throws Exception {
        Path data = drishti.resolve("data/testdata/counterparty-risk");
        String date = "2026-09-30";
        JsonNode run = read(data.resolve("simulation-run"), date).get(0);
        List<JsonNode> paths = new ArrayList<>(read(data.resolve("exposure-path"), date));
        paths.sort(Comparator.comparing((JsonNode n) -> n.path("TRADE_ID").asText()).thenComparingInt(n -> n.path("GRID_INDEX").asInt()));
        // the reference kinds as Drishti's exposure loader reads them: each record's latest version on or before the date (a CSA is
        // loaded once, on 2026-07-08; the later daily files are empty), never the date's file alone
        Map<String, JsonNode> sets = byKey(readAsOf(data.resolve("netting-set"), date), "NETTING_SET_ID");
        Map<String, JsonNode> csas = byKey(readAsOf(data.resolve("csa"), date), "CSA_ID");
        Map<String, JsonNode> balances = byKey(readAsOf(data.resolve("collateral-balance"), date), "BALANCE_ID");
        Map<String, Double> mtm0 = new TreeMap<>();
        sets.values().forEach(s -> s.path("TRADES").forEach(t -> mtm0.put(t.path("TRADE_ID").asText(), t.path("MTM").asDouble())));
        exportCcr(date, run, paths, sets, csas, balances, mtm0);
        List<LocalDate> dates = new ArrayList<>();
        run.get("GRID_DATES").forEach(x -> dates.add(LocalDate.parse(x.asText())));
        List<String> roles = new ArrayList<>();
        run.get("GRID_ROLE").forEach(x -> roles.add(x.asText()));
        List<Integer> lb = new ArrayList<>();
        run.get("LOOKBACK_OF").forEach(x -> lb.add(x.asInt()));
        List<String> tenors = new ArrayList<>();
        run.get("GRID_TENORS").forEach(x -> tenors.add(x.isNull() ? null : x.asText()));
        ExposureGrid g = ExposureGrid.of(LocalDate.parse(run.get("BUSINESS_DATE").asText()), dates, roles, lb, tenors);
        int p = run.get("PATHS").asInt();
        ExposureBook.Builder b = new ExposureBook.Builder(g, p, run.get("RUN_ID").asText(), run.get("BATCH_ID").asText(), Map.of(), ExposureMath.Model.LAGGED, 0);
        Map<String, double[]> tradePaths = new HashMap<>();
        for (JsonNode n : paths) {
            double[] values = doubles(n.get("MTM"));
            String t = n.get("TRADE_ID").asText();
            int k = n.get("GRID_INDEX").asInt();
            b.row(t, text(n, "NETTING_SET"), new String[] {text(n, "COUNTERPARTY"), text(n, "LEGAL_ENTITY"), text(n, "DESK"), text(n, "TT_BOOK"),
                text(n, "ASSET_CLASS"), text(n, "PRODUCT"), text(n, "SOURCE_SYSTEM")}, text(n, "MATURITY_DATE"), n.path("NOTIONAL").asDouble(Double.NaN),
                    k, values, p);
            double[] tp = tradePaths.computeIfAbsent(t, x -> new double[g.size() * p]);
            System.arraycopy(values, 0, tp, k * p, p);
        }
        ExposureBook book = b.build(new ExposureBook.Reference() {
            @Override
            public CsaTerms terms(String set) {
                return RiskRecorder.terms(sets.get(set), csas, balances);
            }

            @Override
            public double mtm0(String trade) {
                Double d = mtm0.get(trade);
                return d == null ? Double.NaN : d;
            }
        }, null, "engine", Map.of());
        ExposureCalc.Params prm = new ExposureCalc.Params(0.95, ExposureMath.Model.LAGGED, ExposureMath.Rule.KTH, new double[] {0.95, 0.99}, 21, 0);
        StringBuilder y = new StringBuilder();
        List<String> scopes = new ArrayList<>(List.of("BANK"));
        book.sets().forEach(s -> scopes.add("NSET:" + s.id()));
        for (String scope : scopes) {
            ExposureScopes.Resolution r = new ExposureScopes(book, x -> Optional.empty()).resolve(scope);
            ExposureCalc.Result res = ExposureCalc.evaluate(book, r.trades(), r.standalone(), prm, ids -> {
                Map<String, double[]> out = new HashMap<>();
                ids.forEach(i -> out.put(i, tradePaths.get(i)));
                return out;
            });
            ExposureMath.Stats s = res.stats;
            int q = s.at(0.95);
            y.append("  - id: ").append(q(scope)).append('\n');
            y.append("    source: \"recorder: Drishti 3813b8735 (ExposureBook, ExposureScopes, ExposureCalc), paths in (trade, grid index) order\"\n");
            y.append("    reference: ").append(scope.equals("BANK")).append('\n');
            y.append("    laws: []                 # the recorded bits only: the laws run on the hand cases (tck/cases), not on 8,000 positions\n");
            y.append("    request: { outputs: [ exposure-summary, exposure-profile ], params: { scope: ").append(q(scope))
                    .append(", standalone: false }, visibility: { exposure-path: all }, seed: 0 }\n");
            y.append("    expect:\n      status: complete\n      tables:\n");
            y.append("        exposure-summary:\n          columns: [ SCOPE, TRADES, CE, EPE, EEPE, PEAK_EE, PEAK_PFE, PEAK_PFE_DATE ]\n          rows:\n");
            y.append("            - [ ").append(q(scope)).append(", ").append(res.trades.size()).append(", ").append(bits(s.ce)).append(", ").append(bits(s.epe))
                    .append(", ").append(bits(s.eepe)).append(", ").append(bits(s.peakEe)).append(", ").append(bits(s.peakPfe[q])).append(", ")
                    .append(q(g.date(g.primaryAt(s.peakAt[q])).toString())).append(" ]\n");
            y.append("        exposure-profile:\n          columns: [ POINT, DATE, TENOR, EE, ENE, EEE, PFE, EE_UNCOLL ]\n          rows:\n");
            for (int i = 0; i < g.primaryCount(); i++) {
                int k = g.primaryAt(i);
                y.append("            - [ ").append(i).append(", ").append(q(g.date(k).toString())).append(", ").append(q(g.tenor(k))).append(", ")
                        .append(bits(s.ee[i])).append(", ").append(bits(s.ene[i])).append(", ").append(bits(s.eee[i])).append(", ").append(bits(s.pfe[q][i]))
                        .append(", ").append(bits(s.eeUncoll[i])).append(" ]\n");
            }
        }
        String head = "# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved. Drishti Risk. Proprietary: see LICENSE.\n"
                + "# Written by tools/split/recorder/RiskRecorder.java from Drishti 3813b8735; do not edit by hand.\n"
                + "suite: ccr/recorder-" + date + "\nengine: ccr\nsince: \"0.1\"\ndescription: Drishti's exposure figures and profiles for the bank and every netting set.\n"
                + "given:\n  snapshot: { date: \"" + date + "\" }\n  inputs:\n"
                + "    simulation-run: { file: ../../data/ccr/" + date + "/simulation-run.jsonl.gz }\n"
                + "    exposure-path: { file: ../../data/ccr/" + date + "/exposure-path.jsonl.gz }\n"
                + "    netting-set: { file: ../../data/ccr/" + date + "/netting-set.jsonl.gz }\n"
                + "    csa: { file: ../../data/ccr/" + date + "/csa.jsonl.gz }\n"
                + "    collateral-balance: { file: ../../data/ccr/" + date + "/collateral-balance.jsonl.gz }\n"
                + "    trade-valuation: { file: ../../data/ccr/" + date + "/trade-valuation.jsonl.gz }\ncases:\n";
        write(risk.resolve("tck/recorded/ccr/recorder-" + date + ".yaml"), head + y);
    }

    /** ExposureLoader.terms and heldAfterHaircut of Drishti at the freeze, over the documents as JSON. */
    static CsaTerms terms(JsonNode set, Map<String, JsonNode> csas, Map<String, JsonNode> balances) {
        JsonNode bal = set == null || text(set, "COLLATERAL_BALANCE") == null ? null : balances.get(text(set, "COLLATERAL_BALANCE"));
        double held = held(bal);
        double im = bal == null ? 0 : nz(bal.path("IM_HELD").asDouble(Double.NaN));
        JsonNode csa = set == null || text(set, "CSA") == null ? null : csas.get(text(set, "CSA"));
        if (csa == null) {
            return new CsaTerms(null, 0, 0, 0, 0, 0, 0, 0, 1, false, held, im);
        }
        double mta = nz(csa.path("MTA").asDouble(Double.NaN));
        double mtaThem = csa.has("MTA_THEM") ? nz(csa.path("MTA_THEM").asDouble(Double.NaN)) : mta;
        double mtaUs = csa.has("MTA_US") ? nz(csa.path("MTA_US").asDouble(Double.NaN)) : mta;
        int mpor = csa.has("MPOR_DAYS") ? csa.path("MPOR_DAYS").asInt(10) : 10;
        int freq = csa.has("MARGIN_FREQUENCY_DAYS") ? csa.path("MARGIN_FREQUENCY_DAYS").asInt(1) : 1;
        String type = String.valueOf(text(csa, "MARGIN_TYPE"));
        return new CsaTerms(text(csa, "CSA_ID"), nz(csa.path("THRESHOLD_THEM").asDouble(Double.NaN)), nz(csa.path("THRESHOLD_US").asDouble(Double.NaN)),
                mtaThem, mtaUs, nz(csa.path("INDEPENDENT_AMOUNT").asDouble(Double.NaN)), nz(csa.path("ROUNDING").asDouble(Double.NaN)), mpor, freq,
                type.contains("VM"), held, im);
    }

    static double held(JsonNode bal) {
        if (bal == null) {
            return 0;
        }
        JsonNode pos = bal.get("POSITIONS");
        if (pos == null || pos.size() == 0) {
            return nz(bal.path("NET").asDouble(Double.NaN));
        }
        double held = 0;
        for (JsonNode p : pos) {
            double v = p.path("VALUE").asDouble(Double.NaN);
            if (Double.isNaN(v)) {
                v = p.path("MARKET_VALUE").asDouble(Double.NaN) * (1 - nz(p.path("HAIRCUT").asDouble(Double.NaN)));
            }
            held += "Posted".equalsIgnoreCase(String.valueOf(text(p, "DIRECTION"))) ? -nz(v) : nz(v);
        }
        return held;
    }

    void exportCcr(String date, JsonNode run, List<JsonNode> paths, Map<String, JsonNode> sets, Map<String, JsonNode> csas,
            Map<String, JsonNode> balances, Map<String, Double> mtm0) throws IOException {
        Path dir = risk.resolve("tck/data/ccr/" + date);
        Files.createDirectories(dir);
        ObjectNode r = run.deepCopy();
        for (String f : List.of("GRID_DATES", "GRID_ROLE", "LOOKBACK_OF", "GRID_TENORS")) {
            List<String> v = new ArrayList<>();
            run.path(f).forEach(x -> v.add(x.isNull() ? "null" : x.asText()));
            r.put(f, String.join(",", v));
        }
        jsonl(dir.resolve("simulation-run.jsonl"), List.of(r));
        jsonl(dir.resolve("exposure-path.jsonl"), paths);
        jsonl(dir.resolve("netting-set.jsonl"), new ArrayList<>(sets.values()));
        jsonl(dir.resolve("csa.jsonl"), new ArrayList<>(csas.values()));
        List<JsonNode> bal = new ArrayList<>();
        balances.values().forEach(b -> bal.add(((ObjectNode) b.deepCopy()).put("NET", held(b))));   // the engine reads NET as held after haircut
        jsonl(dir.resolve("collateral-balance.jsonl"), bal);
        List<JsonNode> tv = new ArrayList<>();
        mtm0.forEach((t, m) -> tv.add(JSON.createObjectNode().put("TRADE_ID", t).put("MTM", m)));
        jsonl(dir.resolve("trade-valuation.jsonl"), tv);
    }

    // ------------------------------------------------------------------------------------------------------------------ helpers

    String header(String engine, String date, List<HierarchySpec> specs, PositionRules rules) throws IOException {
        StringBuilder h = new StringBuilder();
        h.append("# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved. Drishti Risk. Proprietary: see LICENSE.\n");
        h.append("# Written by tools/split/recorder/RiskRecorder.java from Drishti 3813b8735; do not edit by hand.\n");
        h.append("suite: ").append(engine).append("/recorder-").append(date).append('\n');
        h.append("engine: ").append(engine).append("\nsince: \"0.1\"\n");
        h.append("description: Drishti's VaR, ES, components and baskets for every hierarchy node, 509 and 250 scenarios, USD and EUR.\n");
        h.append("given:\n  snapshot: { date: \"").append(date).append("\" }\n  options:\n    hierarchies:\n");
        for (HierarchySpec s : specs) {
            h.append("      - { id: ").append(s.id()).append(", title: ").append(q(s.title())).append(", levels: ").append(q(String.join(" > ", s.levels())))
                    .append(", level-names: ").append(q(String.join(" > ", s.levelNames()))).append(", ids: ").append(s.leafIds() ? "leaf" : "path")
                    .append(", root-id: ").append(q(s.rootId())).append(", root-name: ").append(q(s.rootName()))
                    .append(s.word() == null ? "" : ", word: " + q(s.word())).append(" }\n");
        }
        h.append("    live-statuses: ").append(JSON.writeValueAsString(new ArrayList<>(rules.liveStatuses()))).append('\n');
        h.append("    position-rules:\n");
        for (PositionRules.Rule r : rules.rules()) {
            h.append("      - { name: ").append(q(r.name())).append(", groups: ").append(JSON.writeValueAsString(r.groups())).append(", types: ")
                    .append(JSON.writeValueAsString(r.types())).append(", key: ").append(JSON.writeValueAsString(r.key())).append(" }\n");
        }
        h.append("  inputs:\n");
        for (String in : List.of("scenario-pv", "pnl-vector", "scenario-set", "fx-scenario", "var-limit", "system-basket")) {
            h.append("    ").append(in).append(": { file: ../../data/").append(engine).append('/').append(date).append('/').append(in).append(".jsonl.gz }\n");
        }
        h.append("cases:\n");
        return h.toString();
    }

    static List<JsonNode> read(Path folder, String date) throws IOException {
        List<JsonNode> out = new ArrayList<>();
        if (!Files.isDirectory(folder)) {
            return out;
        }
        for (String line : FeedFiles.lines(folder, p -> p.date().equals(date))) {
            out.add(JSON.readTree(line));
        }
        return out;
    }

    /** Every row of the files dated on or before {@code date}, oldest first (byKey then keeps each record's latest version). */
    static List<JsonNode> readAsOf(Path folder, String date) throws IOException {
        List<JsonNode> out = new ArrayList<>();
        if (!Files.isDirectory(folder)) {
            return out;
        }
        for (String line : FeedFiles.lines(folder, p -> p.date().compareTo(date) <= 0)) {
            out.add(JSON.readTree(line));
        }
        return out;
    }

    static List<JsonNode> sorted(List<JsonNode> rows, String key) {
        List<JsonNode> s = new ArrayList<>(rows);
        s.sort(Comparator.comparing(n -> n.path(key).asText()));
        return s;
    }

    static Map<String, JsonNode> byKey(List<JsonNode> rows, String key) {
        Map<String, JsonNode> m = new TreeMap<>();
        rows.forEach(r -> m.put(r.path(key).asText(), r));
        return m;
    }

    static String text(JsonNode n, String f) {
        JsonNode v = n == null ? null : n.get(f);
        return v == null || v.isNull() || v.asText().isEmpty() ? null : v.asText();
    }

    static double[] doubles(JsonNode a) {
        double[] v = new double[a.size()];
        for (int j = 0; j < v.length; j++) {
            v[j] = a.get(j).asDouble();
        }
        return v;
    }

    static double[] tail(double[] v, int n) {
        return n == v.length ? v.clone() : Arrays.copyOfRange(v, v.length - n, v.length);
    }

    static double nz(double d) {
        return Double.isNaN(d) ? 0 : d;
    }

    static String bits(double d) {
        return "\"0x" + String.format("%016x", Double.doubleToRawLongBits(d)) + "\"";
    }

    static String q(String s) {
        if (s == null) {
            return "null";
        }
        try {
            return JSON.writeValueAsString(s);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Writes {@code file}{@code .gz}: gzip with no name and no time in its header, so a second run writes the same rows. */
    static void jsonl(Path file, List<JsonNode> rows) throws IOException {
        try (Writer w = new java.io.OutputStreamWriter(new java.util.zip.GZIPOutputStream(Files.newOutputStream(
                file.resolveSibling(file.getFileName() + ".gz"))), StandardCharsets.UTF_8)) {
            for (JsonNode n : rows) {
                w.write(JSON.writeValueAsString(n));
                w.write('\n');
            }
        }
    }

    static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
        System.out.println("recorded " + file + " (" + text.lines().count() + " lines)");
    }
}
