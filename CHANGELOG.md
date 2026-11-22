<!-- Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved. Drishti Risk. Proprietary: see LICENSE. -->
# Changelog

## 0.1.0 (unreleased)

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
