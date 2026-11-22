<!--
  Drishti Risk · market and counterparty risk as engines.

  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
  All rights reserved.

  PROPRIETARY AND CONFIDENTIAL.

  This file is the confidential and proprietary property of Ashutosh Sinha.
  Unauthorised copying, use, modification, distribution or disclosure of this
  file, via any medium, is strictly prohibited except with the express prior
  written permission of the copyright holder.

  See the LICENSE file in the root of this repository for the full terms.
-->
# Split exemptions

What the move (step K3 of the Drishti Risk design) left that the product's rules forbid, each with the step that removes it. **This
list only shrinks**: a step that removes an entry deletes its row; nothing is added after K3 without the owner's word.

The move: `git filter-repo` on a fresh clone of Drishti at the frozen G4 head `3813b8735` (`origin/wip/g4-rehearsal-rx`), over the
allow-list `tools/split/paths.txt` (made by `tools/split/allow.py`, each file's earlier names followed through Drishti's renames),
merged with `--allow-unrelated-histories`; then one commit renaming the packages (`tools/split/rename.py`) and parking the host types
(`tools/split/hostcompat.py`). Drishti itself was not changed.

## 1. Host types parked whole in `com.ash.risk.hostcompat` (risk-kernel)

Copied unchanged but for the package; each is removed when the step named retires the code that uses it.

| Parked type | Drishti's type | Removed at |
|---|---|---|
| `com.ash.risk.hostcompat.api.AsOf` | `com.ash.drishti.api.AsOf` | K5 |
| `com.ash.risk.hostcompat.api.ChannelIo` | `com.ash.drishti.api.ChannelIo` | K5 |
| `com.ash.risk.hostcompat.api.ColumnSet` | `com.ash.drishti.api.ColumnSet` | K5 |
| `com.ash.risk.hostcompat.api.DataNode` | `com.ash.drishti.api.DataNode` | K5 |
| `com.ash.risk.hostcompat.api.DatesInUse` | `com.ash.drishti.api.DatesInUse` | K5 |
| `com.ash.risk.hostcompat.api.EntityDocument` | `com.ash.drishti.api.EntityDocument` | K5 |
| `com.ash.risk.hostcompat.api.EntityRef` | `com.ash.drishti.api.EntityRef` | K5 |
| `com.ash.risk.hostcompat.api.FileMaps` | `com.ash.drishti.api.FileMaps` | K5 |
| `com.ash.risk.hostcompat.api.KeyRange` | `com.ash.drishti.api.KeyRange` | K8 |
| `com.ash.risk.hostcompat.api.NodeType` | `com.ash.drishti.api.NodeType` | K5 |
| `com.ash.risk.hostcompat.api.PathWalker` | `com.ash.drishti.api.PathWalker` | K5 |
| `com.ash.risk.hostcompat.api.Provenance` | `com.ash.drishti.api.Provenance` | K5 |
| `com.ash.risk.hostcompat.api.VectorReads` | `com.ash.drishti.api.VectorReads` | K5 |
| `com.ash.risk.hostcompat.api.VectorSink` | `com.ash.drishti.api.VectorSink` | K5 |
| `com.ash.risk.hostcompat.api.compute.ComputeEngine` | `com.ash.drishti.api.compute.ComputeEngine` | K6 |
| `com.ash.risk.hostcompat.api.compute.ComputeEvent` | `com.ash.drishti.api.compute.ComputeEvent` | K6 |
| `com.ash.risk.hostcompat.api.compute.EngineException` | `com.ash.drishti.api.compute.EngineException` | K6 |
| `com.ash.risk.hostcompat.api.compute.EngineInputs` | `com.ash.drishti.api.compute.EngineInputs` | K6 |
| `com.ash.risk.hostcompat.api.compute.EngineManifest` | `com.ash.drishti.api.compute.EngineManifest` | K6 |
| `com.ash.risk.hostcompat.api.compute.EngineProgress` | `com.ash.drishti.api.compute.EngineProgress` | K6 |
| `com.ash.risk.hostcompat.api.compute.EngineRequest` | `com.ash.drishti.api.compute.EngineRequest` | K6 |
| `com.ash.risk.hostcompat.api.compute.EngineResult` | `com.ash.drishti.api.compute.EngineResult` | K6 |
| `com.ash.risk.hostcompat.api.compute.Viewer` | `com.ash.drishti.api.compute.Viewer` | K6 |
| `com.ash.risk.hostcompat.api.error.DrishtiException` | `com.ash.drishti.api.error.DrishtiException` | K4 |
| `com.ash.risk.hostcompat.api.error.ErrorCode` | `com.ash.drishti.api.error.ErrorCode` | K4 |
| `com.ash.risk.hostcompat.api.resolution.ModuleHomes` | `com.ash.drishti.api.resolution.ModuleHomes` | K4 |
| `com.ash.risk.hostcompat.common.Branding` | `com.ash.drishti.common.Branding` | K4 |
| `com.ash.risk.hostcompat.common.JvmTempDir` | `com.ash.drishti.common.JvmTempDir` | K6 |
| `com.ash.risk.hostcompat.common.ModuleLoaders` | `com.ash.drishti.common.ModuleLoaders` | K6 |
| `com.ash.risk.hostcompat.compute.arrow.ArrowIpc` | `com.ash.drishti.compute.arrow.ArrowIpc` | K7 |
| `com.ash.risk.hostcompat.compute.arrow.ArrowMemory` | `com.ash.drishti.compute.arrow.ArrowMemory` | K7 |
| `com.ash.risk.hostcompat.compute.arrow.ArrowTable` | `com.ash.drishti.compute.arrow.ArrowTable` | K7 |
| `com.ash.risk.hostcompat.compute.shard.ShardMap` | `com.ash.drishti.compute.shard.ShardMap` | K8 |
| `com.ash.risk.hostcompat.engine.source.SourceFailures` | `com.ash.drishti.engine.source.SourceFailures` | K5 |
| `com.ash.risk.hostcompat.identity.AuditLog` | `com.ash.drishti.identity.AuditLog` | K7 |
| `com.ash.risk.hostcompat.identity.content.ContentDocs` | `com.ash.drishti.identity.content.ContentDocs` | K7 |
| `com.ash.risk.hostcompat.identity.content.ContentStore` | `com.ash.drishti.identity.content.ContentStore` | K7 |
| `com.ash.risk.hostcompat.identity.content.FileContentStore` | `com.ash.drishti.identity.content.FileContentStore` | K7 |
| `com.ash.risk.hostcompat.identity.content.TxContentStore` | `com.ash.drishti.identity.content.TxContentStore` | K7 |
| `com.ash.risk.hostcompat.kernel.api.module.EngineWide` | `com.ash.drishti.kernel.api.module.EngineWide` | K6 |
| `com.ash.risk.hostcompat.kernel.api.security.Principal` | `com.ash.drishti.kernel.api.security.Principal` | K4 |
| `com.ash.risk.hostcompat.kernel.api.shard.ShardGather` | `com.ash.drishti.kernel.api.shard.ShardGather` | K8 |
| `com.ash.risk.hostcompat.kernel.api.view.ViewAugmenter` | `com.ash.drishti.kernel.api.view.ViewAugmenter` | K7 |
| `com.ash.risk.hostcompat.packs.Pack` | `com.ash.drishti.packs.Pack` | K4 |
| `com.ash.risk.hostcompat.server.admission.BusyRefusal` | `com.ash.drishti.server.admission.BusyRefusal` | K6 |
| `com.ash.risk.hostcompat.server.admission.Work` | `com.ash.drishti.server.admission.Work` | K6 |
| `com.ash.risk.hostcompat.server.caches.OpenDates` | `com.ash.drishti.server.caches.OpenDates` | K6 |
| `com.ash.risk.hostcompat.server.caches.Refusals` | `com.ash.drishti.server.caches.Refusals` | K6 |
| `com.ash.risk.hostcompat.server.caches.RefusedForNow` | `com.ash.drishti.server.caches.RefusedForNow` | K6 |
| `com.ash.risk.hostcompat.server.compute.LoadPools` | `com.ash.drishti.server.compute.LoadPools` | K6 |
| `com.ash.risk.hostcompat.server.compute.VersionWatch` | `com.ash.drishti.server.compute.VersionWatch` | K6 |
| `com.ash.risk.hostcompat.server.disk.HotFiles` | `com.ash.drishti.server.disk.HotFiles` | K6 |
| `com.ash.risk.hostcompat.server.dist.ExportDoc` | `com.ash.drishti.server.dist.ExportDoc` | K7 |
| `com.ash.risk.hostcompat.server.dist.JsonlWriter` | `com.ash.drishti.server.dist.JsonlWriter` | K7 |
| `com.ash.risk.hostcompat.server.engines.EngineOffException` | `com.ash.drishti.server.engines.EngineOffException` | K6 |
| `com.ash.risk.hostcompat.testkit.FeedFiles` | `com.ash.drishti.testkit.FeedFiles` | K9 |
| `com.ash.risk.hostcompat.spring.ConfigurationProperties`, `Autowired`, `Order`, `stereotype.Component` | Spring's annotations (source-retention stand-ins, no behaviour) | K4 |

## 2. Host types named by the moved code and NOT parked (their own reach is the host: Spring, servlets, the cluster)

The moved code that names them does not compile until the step named cuts it onto the ports. These are the K3 build's known errors.

| Host type | Named by | Removed at |
|---|---|---|
| `engine.source.SourceRouter` | `VarLoader`, `ExposureLoader`, `VarDeskPnl`, `VarAttributeGroups`, `VarMatured`, `VarService` | K5 (the intake onto `Inputs`) |
| `packs.PackRegistry` | `Hierarchies`, `VarSharding` | K4 (hierarchies are engine options) |
| `server.admission.AdmissionGate`, `server.caches.PinKeeper`, `PinnedDates` | `VarService`, `ExposureService`, `VarProperties`, `ExposureProperties` | K6 (admission and pins are the host's) |
| `server.disk.DiskBudget`, `DiskProperties` | `VarTier`, `ExposureTier`, three disk-cap tests | K6 (the engine's `MemoryBudget` and `workDir`) |
| `server.net.NetCaller` | `VarFigures`, `ExposureFigures` | K4 (the actor of the request) |
| `api.DataDir`, `api.Roots` | the settings classes, `Blas` | K4 (absolute paths from options) |
| `common.TempFiles` | `VarTier` | K6 (`workDir`) |

## 3. Drishti classes that stay in Drishti, named by moved code

| Stays in Drishti | Named by | Removed at |
|---|---|---|
| `VarAccess`, `ExposureAccess` | `VarService`, `VarViews`, `VarBasketCheck`, `VarMaturedView`, `ExposureService`, `ExposureViews`, `ExposureWhatIfPaths` | K4 (`Visibility`) |
| `VarShards`, `VarShardRows`, `VarMeasure` | `VarService`, `VarSharding` | K8 (partials are values) |
| `VarSaved` | `VarService` | K4 (saved views are the host's) |
| `VarMatrixLake` | `VarMatrix` | K5 |
| `VarReportSignoff` | `VarRegulatory` | K7 (sign-off is the host's workflow over stored outputs) |
| `OfficialSink` | `VarPublisher`, `ExposurePublisher` | K7 (the engine returns tables) |
| `MrCubeExtras` | `MrSensitivities` | K7 |
| `VarPvStreamPlugin` | `VarProperties` | K4 |
| `VarSystemBasketEdit` | `VarGoldenMatrixTest`, `SystemBasketGoldenTest` (copies of Drishti's goldens, kept as the recorder's reference) | K9 (the kit cases replace them) |

## 4. Other exemptions

| Exemption | Where | Removed at |
|---|---|---|
| Jackson (YAML and JSON reading) in the moved code; the parent POM's Jackson ban is lifted until then | 18 main files of risk-kernel, risk-market, risk-ccr | K4 (options) and K5 (inputs) |
| "Names no host": the moved code still names `drishti.` settings and pack words in text and keys | risk-kernel, risk-market, risk-ccr (the guard reads this list: the three moved modules are reported, not failed, until K4) | K4 |
| One line of business names another | `risk-ccr`: `ExposureLoader`, `SimmData`, `SimmService`, `SimmTestData` name `risk-market`'s `Hierarchies`, `VarService`, `FrtbData`, `FrtbService`, `FrtbProperties`; `risk-market`: `FrtbHandBuiltCasesTest` names `risk-ccr`'s `SimmMath`. The kernel cut (K6) moves `Hierarchies` and `MembershipLines` into risk-kernel and the shared SIMM/FRTB parameter readers into the kernel; the guard `LineOfBusinessIsolationTest` lists exactly these until then | K6 |
| Kernel candidates still in risk-market: `Cells`, `Columns`, `ScopeResolver`, `ShardAssembly`, `ShardSkeleton`, `Hierarchies`, `MembershipLines` (they name `VarBook`, `VarHotFile`, `CurrencyTotals`) | risk-market | K6 |
| Tests that read Drishti's committed test data (`data/testdata/market-risk`, `counterparty-risk`) through `TestRoots` | `TestDataBook`, `VarTestDataPrecisionTest`, `ExposureParityTest`, `FrtbTestData`, `SimmTestData`, the hand-built FRTB and SIMM cases | K10 (the sample day in `tools/samples`) |
| Static mutable state: `VarBook.positionCacheBytes`, the native-library holder | risk-market, risk-kernel | K6 |
| `hostcompat.testkit.FeedFiles` is test support parked in main | risk-kernel | K9 |
