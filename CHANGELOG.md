<!-- Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved. Drishti Risk. Proprietary: see LICENSE. -->
# Changelog

## 0.1.0 (unreleased)

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
