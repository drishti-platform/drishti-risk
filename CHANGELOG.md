<!-- Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved. Drishti Risk. Proprietary: see LICENSE. -->
# Changelog

## 0.1.0 (unreleased)

- `market-risk`, R2 speed (2026-11-28, Drishti lane S2; R2 engine speed design steps 2, 5, 8, 9): `descriptor()` read once per engine
  object; the planner looks only at the books a set touches; each node's and whole position's measures and each visibility's rows kept
  with the built book as session state (`MarketDerived`); a scope's parts summed as Drishti's in-tree parts (a node whole its aggregate, a
  position whole its row). The date opens with its small inputs only; the vector inputs are read in one pass straight into the book
  (`MarketRows`), nothing delivered kept; the extras read at the first output that needs them. On the mapped tier each book lives in the
  host's kept image (`MarketImage`: the hot layout of `VarHotFile` written through the host's `ImageWriter` and read back in place), so a
  restart or a second session maps it; option `zero-copy` and `HostRows` removed (the image replaces them). The open names its stage
  (`Control.progress`: "reading 62,000 of 100,000 rows") for a host that shows "preparing". Kit case `headline-mapped-by-hand`.

- `ccr`: SIMM for a host-resolved scope: new param `simm-members` (SCOPE, SENSITIVITY_ID: a netting set's records as the host's trade
  master places them, an analytic scope's records; each scope its records' elements in the input's order, one trade per record, as
  Drishti's in-tree run builds it; `simm-scope` is then not read) on `simm` and `simm-total`; new outputs `simm-product` (each scope's SIMM
  of a product class) and `simm-version` (the run's parameter file: version, effective date, SHA-256, thresholds currency, the regimes it
  recognises, its description). Drishti's `GET /api/v1/risk/simm` on the engine path reads them, every SIMM input a lake table. R2 SIMM.
- `ccr`: `exposure-tree` adds `ORDER` (the nodes in the engine's order: the netting sets in the book's order first), so a host reading
  only its reference data takes the netting sets' order from the engine (Drishti's engine-path tree without the in-tree paths). R2 exposure part 3.
- `ccr`: new outputs `exposure-official` and `exposure-official-profile` (the figures of Drishti's official exposure record for the scopes
  the host names, param `scopes`, with `scope-members` for a scope only the host resolves, a hierarchy node's trades, never standalone:
  netting sets, partial sets, trades, CE, EPE, EEPE, peak EE, peak PFE at 95% and 99% and its date, flags,
  the profile; a scope the book cannot answer is a row with its note), `exposure-trade-ee` (every trade's EE contribution at every primary
  point, the record's audit) and `exposure-by-netting-set` (a scope's netting sets with their own figures and their part of the scope's
  peak PFE, Drishti's captures); kit cases for each. R2 exposure part 3.
- `ccr`: new outputs `exposure-what-if` and `exposure-what-if-profile` (Drishti's `POST /what-if`: method `paths`, each candidate's
  MtM on every path, a booked trade's copy scaled or supplied, added to its netting set path by path by the moved `ExposureWhatIfMath`;
  method `addon`, the conservative proxy on the scope's own profile; rows BEFORE, AFTER and ITEM:<id>; params `method`, `candidates`); new
  param `groups` (each connected group's member counterparties from the host's reference) so `GROUP:<id>` scopes resolve by Drishti's scope
  words. Kit: `tck/cases/ccr/exposure-outputs-by-hand.yaml` (tree, tree EE, run, flags, a group's resolution, summary and profile,
  distribution, histogram, fan, contributions, both what-if methods, netting-set and counterparty summaries); every answered measure has
  its workbook name (the figures part), an explanation keyed by every key column, and the pre-deal buckets' peaks as `MAX` over their
  consecutive rows (the kit's explain and workbook laws now pass for every ccr case). R2 exposure part 3.
- `ccr`: new outputs `exposure-tree` (every netting set with paths, every counterparty, the bank and every legal entity the request
  sees, each evaluated as `exposure-summary` evaluates its scope: netting sets, trades, EPE, peak EE, peak PFE and its tenor, flags; a
  note instead when an interactive answer cannot sum it), `exposure-tree-ee` (each node's EE at every primary point), `exposure-run`
  (the run's id, paths, grid and primary dates) and `exposure-flags` (a scope's netting sets and flags), for Drishti's exposure tree,
  its netting-set pages and the contributions route's audit line on the engine path (R2).
- `ccr`: new outputs `exposure-distribution` (a scope's exposure across the paths at a primary point `at`, -1 the peak PFE's: mean,
  share without exposure, paths, range), `exposure-histogram` (param `bins`) and `exposure-fan` (quantiles 5 to 99% at every primary
  point), for Drishti's `/scope/distribution` on the engine path (R2).
- `ccr`: new outputs `exposure-contributions` (a scope's parts by netting set, counterparty, legal entity, trade or asset and each part's
  contribution to EE, EPE or PFE, its share and incremental PFE, in the calculation's order `RANK`; params `by`, `figure`, `at`) and
  `scope-resolution` (a scope's canonical text, kind, id and trades), for Drishti's `/scope/contributions` on the engine path (R2).
- `ccr`: `counterparty-summary` adds `LIMIT_ID` and `LIMIT`, the counterparty's stored credit limit as the pre-deal check reads it
  (`credit-limit`, the first by id; empty without one), so a host's exposure Home joins nothing itself (R2, Drishti's role Homes).
- `ccr` (R2 MC-12, exposure): option `vector-tier` (`heap`, the default: float64 netting-set matrices; `mapped`: every value, exposure and
  negative-exposure row float32, rounded once and read into float64 sums in row order, as a host's mapped exposure tier's hot file keeps
  them; half the bytes). `ExposureParityTest.aHeapBookOfFloat32RowsEqualsTheHotFileToTheBit`: every netting set, the counterparty, the
  bank, a desk cut and a standalone trade under each collateral model, every figure and profile point to the bit.
- `market-risk` (R2 MC-12): option `vector-tier` (`heap`, the default: float64 rows as delivered; `mapped`: the rows as a host's mapped
  VaR tier keeps them). On `mapped` each book (USD, and each book converted from it) keeps every position as one float32 row, the
  float64 sum of its trades converted once, and the trades of positions of several trades as float32 rows, in the hot file's layout
  (`MappedMatrix.held`, `VarBook.withFloat32Rows`), read through the same `GroupedVectors` in the same reduction order; the node and book
  aggregates stay float64. Drishti sets it from its tier (`drishti.var.tier.mode`), so row-set figures, position parts and every other
  currency are the in-tree route's raw bits on the mapped tier too. `VarHotFileTest`: a heap book with float32 rows equals a hot file's
  book to the bit (every position, trade, scope, random set and node, 1 and 4 threads).

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
  and have no workbook part yet (refused in words). `scope-pmatrix`: the parts' parametric covariance and correlation (row sets in the
  host's order, each summed as `scope-var` sums it; one `dgemm` through the bound linear algebra, named): Drishti's contributions'
  `matrix=true` on the engine path.

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
- `ccr` (R2, Drishti's exposure routes on the engine): `exposure-summary` and `exposure-profile` take the measure parameters
  `confidence` (the PFE confidence; 0: the engine's `pfe-confidence`) and `collateral` (lagged, current or none, the uncollateralised view;
  empty: the engine's `model`), as Drishti's `q` and `collateral`; the book is the same, only the measures read it differently. A new
  output `counterparty-summary` gives every counterparty's figures (the counterparty grain of the Exposure Home: netting sets, trades, CE,
  EPE, EEPE, peak EE, peak PFE and its date), each as `exposure-summary` of `CPTY:<id>`; credit limits stay the host's to join. The
  pre-deal check reads a netting set summed whole from the book's matrices as the set alone (its exposure and negative matrices), as
  Drishti's what-if does: the kit's three pre-deal cases that failed with a null matrix give their worked answers.
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
