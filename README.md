---
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved. Drishti Risk. Proprietary: see LICENSE.
name: Drishti Risk
summary: >-
  Market risk and counterparty credit risk as engines: historical and parametric VaR, ES, backtesting, stress windows, intraday, what-if,
  FRTB-SA, SIMM, exposure (EE, EPE, PFE), pre-deal checks and limits, each figure explained and with its formula workbook. Delivered to a
  host as one JAR that registers the engines market-risk and ccr on the engine API; usable by an embedder through its own facade.
status: 0.1.0-SNAPSHOT; the repository and the risk-api skeleton (design step K1, cut against engine-api 0.1); the code moves at K3
coordinates: {groupId: com.ash.risk, package: com.ash.risk (+ .spi, .contract, .table, .explain, .workbook), bom: com.ash.risk:risk-bom}
java: 21
modules: [risk-api, risk-bom, risk-guards]
depends-on: [risk-api the engine API (com.ash.engine:engine-api) only]
commands: {build: "cd java && ./mvnw clean verify"}
consumers: [Drishti (the JAR in lib/engines, never a Maven dependency), embedders and risk-cli (the facade)]
design: docs/plans/DRISHTI_RISK_DESIGN.md and ENGINE_INTEGRATION_DESIGN.md section 9 in the Drishti repository
licence: proprietary (LICENSE)
---

# Drishti Risk

**Market and counterparty risk as engines, one JAR for any host.**

Author: Ashutosh Sinha. Proprietary. All rights reserved (see [LICENSE](LICENSE)).

The product is built and proven without any host: it compiles against the engine API (`com.ash.engine:engine-api`, JDK only) and, as
provided platform libraries, drishti-tools' `tools-xlsx`, Lake's contract model and Rachana core. A host receives one file,
`drishti-risk-<version>.jar`, copied into its engine lib folder; the JAR registers two engines (`market-risk`, `ccr`) that share one
risk kernel. The engine descriptors name the product id `drishti-risk`, which a host's licence must grant.

## Modules

| Module | Package | What it holds | Depends on |
|---|---|---|---|
| `risk-api` | `com.ash.risk`, `.spi`, `.contract`, `.table`, `.explain`, `.workbook` | the embedder's facade (`RiskRuntime`, `RiskDay` as an engine `Session` over `{ date }`, `MeasureQuery`, `Ask`, `Scope`, `Lens`, `RiskOptions`), the module SPI (`RiskModule`, `Measure`, `Book`, `Explainer`, `Playground`, `LimitRule`, `ReportDefinition`), the contract names, `RiskException` (`RSK-` codes, carried as the engine API's exception in a host). Answers, explanations, tables and partials are the engine API's types: there is no second set | `engine-api` |
| `risk-bom` | | one version for every module | |
| `risk-guards` | | the repository's rules, as tests | |

Coming, by the design's steps: `risk-kernel`, `risk-contracts`, `risk-market`, `risk-ccr` (K3 to K8), `risk-engine-market`,
`risk-engine-ccr` and the merged `risk-engine-jar` (E9, E15), `risk-cli` (K10), `risk-tck` (the engine kit plus Risk's cases and the
recorder comparison, K9).

## The rules (risk-guards)

| Guard | Rule |
|---|---|
| `NamesNoHostTest` | no source names Drishti's code, a `DRS-` code, a `drishti.` setting, Spring, or a host word (pack, mnemonic, Sutra); risk-api depends on engine-api only and imports the JDK, the engine API and its own packages |
| `LicenceHeaderAndLineCapTest` | the copyright header on every source; 2,500 lines at most |
| `LaunchersTest` | the IDE launchers follow the platform launcher standard and the workspace mirrors them |

The reactor bans Drishti artifacts, Spring and Jackson in any scope (enforcer).

## Install and build

The family installs in this order: **Rachana (tag) -> Drishti Tools (tag) -> Drishti Lake (tag) -> Drishti Engine (tag) -> Drishti Risk**.

```text
cd java && ./mvnw clean verify                          # JDK 21 or later
git checkout vX.Y.Z && cd java && ./mvnw clean install -DskipTests     # install a release (tag only)
```

## Build and run in the IDE

Launchers live in `run-configs/` (IntelliJ: open the repository root and link `java/pom.xml`; VS Code: open `drishti-risk.code-workspace`):
`Risk: build all`, `Risk: test`, `Risk: check guards`, `Risk: install release (tag only)`.
