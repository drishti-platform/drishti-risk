<!-- Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved. Drishti Risk. Proprietary: see LICENSE. -->
# Changelog

## 0.1.0 (unreleased)

- `market-risk` (R2 lane G, part 4): `official-history` and `official-backtest` over `var-history`: a node's official records to the
  session's date (the latest result version of each date at the engine's confidence, the newest `days`), each record's VaR, ES and
  hypothetical P&L as stored, and their backtest (each day's hypothetical P&L against the record before: days, exceptions, the scaled
  Basel light, Kupiec's and Christoffersen's tests within relative 1e-12): Drishti's history of a node with official records. The
  inputs' needs are a `Map.ofEntries` (more than ten outputs need extras). `official-statistics`: a node's period statistics with what a
  host shows beside them (each point's date, result version and capture id: `var-history` now reads `CAPTURE_ID`; the weekdays without a
  record as dates, a weekday without desk P&L a holiday; the VaR and stressed VaR averages with the records they take, summed in the
  host's order): Drishti's `GET /statistics` on the engine path, every number to the bit. `node-pvar-components` also gives the moments
  each component is made of (`SIGMA`, `MU`, `COV`): Drishti's contributions' parametric columns on the engine path. `node-pvar` takes
  `stressed`, `horizon` and `ten-day-method`: each node's fit through that lens (the lens's own vectors on the normal or the stress set;
  `HVAR` and `K` the lens's), Drishti's parametric block through a horizon or the stress set; its cells through a lens are not explained
  and have no workbook part yet (refused in words).

- `market-risk` (R2 lane G, part 3): `scope-backtest`, the replayed history of a row set (the newest `days` scenario P&Ls, summed as
  `scope-var` sums the rows, against the set's VaR of the date, each day tested against the VaR before it by `Backtest`: days,
  exceptions, the zone with Basel's limits scaled under 250 days, Kupiec's and Christoffersen's tests; in-sample): Drishti's history of
  a scope without official records, to the in-tree route's figures (likelihood ratios within relative 1e-12).
- `market-risk` (R2 lane G, part 3): `node-pvar` carries what a host's parametric block shows: the fit's shape and the ES quantile (`SKEW`,
  `EXCESS_KURTOSIS`, `Z_ES`, `ES_FACTOR`), the historical rank `K`, the `SCENARIO_SET` and its `LAST_DAY` (the hand case extended by hand). Convert first (Drishti owner decision 2026-11-27): `scope-var`
  and every row-set output sum the book converted to the reporting currency (no per-currency totals), so a node whole is `node-var`'s
  figure to the bit in every currency.
- `market-risk` (R2 lane G, part 2): `scope-contributions` (the parts of a row set, each a row set summed as `scope-var` sums it, with
  standalone VaR and ES, component VaR and ES, share and incremental VaR: Drishti's tree contributions and headline parts to the bit in
  USD), and `what-if` takes `rows` (any scope the host resolved: the before is the row set's summed P&L, not only a node's aggregate).
- `market-risk` (R2 lane G): `scope-path` (a row set's P&L path: the summed scenario P&L, the scenarios worst first in the
  measures' own order, the VaR rank, the ES count and a histogram of `bins` equal bins; VaR and ES are `scope-var`'s to the bit) and
  `scope-scenarios` (the window's scenario dates, what a PNL index means). The descriptor parses again: a flow-mapping label of
  `scope-correlation` held an apostrophe, so `market-risk` was "not installed" in a host (the JAR of `264b8ff` never started).
- `market-risk`: `scope-limits` and `basket-var` take `currency` (the USD limit over the currency's base rate, as a converted book holds
  its nodes' limits and as Drishti's headline route converts it: Drishti owner decision 2026-11-27, convert first; the six EUR cases of
  `tck/recorded/market-risk` re-recorded in `basket-var`'s LIMIT and LIMIT_USE only, 336 cells; every other recorded bit unchanged), and
  `scope-position-values` gives the positions' amounts in the reporting currency (a rule over an amount selects as Drishti's in-tree
  route does in that currency).
- The engines' remaining outputs (R1, branch `r1/outputs`), each with its descriptor entries, explanation and workbook part:
  `market-risk` adds `node-pvar` and `node-pvar-components` (parametric VaR, equal or EWMA), `node-var-horizon` (stressed set, 10-day by
  the square root of 10 or overlapping sums), `stress-window` (from `pnl-history`), `backtest` and `backtest-days` (official records of
  earlier dates against `desk-pnl`: zone, multiplier, Kupiec, Christoffersen), `period-stats` and `regulatory-report` (from
  `var-history` and `report-row`), `sensitivity-sum` and `sensitivity-ladder` (the MR cube's sums and ladder), `frtb-sa` and
  `frtb-contributions` (SBM by class, measure and bucket in three scenarios, DRC, RRAO, with the Basel parameter files now in the JAR)
  and `what-if` (supplied vectors in a `table` parameter); `ccr` adds `netting-set-collateral`, `pre-deal` and `pre-deal-buckets` (the exact
  method against `credit-limit` and its tenor buckets: approve, refer, decline; EAD = alpha x EEPE) and `simm` and `simm-total` (ISDA SIMM
  under the bank's parameter file delivered as `simm-parameters`). An output that lacks an input it needs is not found by name while
  the others are answered. The kernel gains `InputRows` (one way to read an input into rows) and `AnswerBook` (one workbook made of a part
  per output). Shared pieces moved out of the services into the calc packages: `Periods`, `FrtbScope`, `TenorBucket`, `LimitMeasure`.
- Kit cases: `tck/cases/market-risk/outputs-by-hand.yaml` (11 cases) and `tck/cases/ccr/pre-deal-by-hand.yaml` (6 cases), worked by
  hand in IEEE doubles in the moved classes' order of operations; `tck/recorded/market-risk/drishti-frtb-hand-built.yaml` (5) and
  `tck/recorded/ccr/drishti-simm-hand-built.yaml` (4, with the toy SIMM file) harvested from FrtbHandBuiltCasesTest and
  SimmHandBuiltCasesTest, to the cent.
- Host types removed (K4, K6 where nothing needed them): `DrishtiException` and `ErrorCode` (now the kernel's `RiskFailure` with a
  `RiskCode`), `Principal` (the kernel's `Caller`), `ModuleHomes`, Spring's four stand-ins, `EngineWide`, `EngineOffException`, `Work`,
  `BusyRefusal`, `JvmTempDir`, `ModuleLoaders`; `SPLIT_EXEMPTIONS.md` shrinks accordingly.
- The repository (design step K1): reactor, BOM, guards, launchers, README, LICENSE.
- `risk-api` skeleton against engine-api 0.1 (design 4.1, 4.2 as amended by ENGINE_INTEGRATION_DESIGN 9.2): the facade, the module SPI,
  the contract names, `RiskException`. Answers, explanations, tables and partials are the engine API's types.
- The move (design K3, milestone R1): market risk and CCR from Drishti's frozen G4 head `3813b8735`, with history (308 commits) by
  `git filter-repo` over `tools/split/paths.txt`; 294 files, 52,473 main and 13,227 test lines of Java; packages renamed in one commit
  to `com.ash.risk.kernel`, `com.ash.risk.market`, `com.ash.risk.ccr`; 56 host types parked in `com.ash.risk.hostcompat`; every
  exemption, and the step that removes it, in `java/SPLIT_EXEMPTIONS.md`.
- Lines of business isolated (owner, 2026-11-22): `risk-market` + `risk-engine-market`, `risk-ccr` + `risk-engine-ccr`, placeholders for
  liquidity and climate; `LineOfBusinessIsolationTest`.
- The engines: `market-risk` (product `drishti-risk-market`) and `ccr` (product `drishti-risk-ccr`), each its own merged JAR, with the
  five lifecycle methods, `health()` and `stats()`.
- The reference recorder (`tools/split/recorder`): `harvest_goldens.py` keeps Drishti's goldens as expected values now
  (`tck/recorded`); `RiskRecorder.java` runs Drishti's own classes over its test data to write the bit-exact kit cases and the inputs.
