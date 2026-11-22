# Drishti Risk · market and counterparty risk as engines.
#
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
# All rights reserved.
#
# PROPRIETARY AND CONFIDENTIAL.
#
# This file is the confidential and proprietary property of Ashutosh Sinha.
# Unauthorised copying, use, modification, distribution or disclosure of this
# file, via any medium, is strictly prohibited except with the express prior
# written permission of the copyright holder.
#
# See the LICENSE file in the root of this repository for the full terms.

# Builds the filter-repo paths file: the allow-list at the freeze commit, each file's earlier names (rename chains), and its target path.
import re, subprocess, sys, collections
# usage: allow.py <freeze ref> <absolute path of a Drishti clone> <out paths file> <renames file: git log -M --diff-filter=R --name-status --format=>
FREEZE = sys.argv[1]; SRC = sys.argv[2]; OUT = sys.argv[3]; RENAMES = sys.argv[4]
files = subprocess.run(["git", "-C", SRC, "ls-tree", "-r", "--name-only", FREEZE], capture_output=True, text=True, check=True).stdout.split("\n")
BC = "modules/banking/java/banking-compute/"
BS = "modules/banking/java/banking-service/"
STAY = re.compile(r"BankingAutoConfiguration|BankingOfficialResults|BankingVocabulary|ModuleBound|RiskOperations|package-info|/official/|Controller|Configuration\.java|"
    r"VarAccess|ExposureAccess|VarPivotProvider|VarPivotLens|VarPivotCodes|MrCubeExtras|VarFlightForm|VarPvStream\.|VarPvStreamPlugin|VarReadyRule|VarSaved|"
    r"VarBasketEdit|VarBasketDelete|VarSystemBasketEdit|VarReportSignoff|VarReportApprovals|StressWindowApprovals|NumberExplainer|PreDealExplainer|VarWhyPanels|"
    r"VarShardRows|Provider\.java|ResultEngine|VarCapacityFigures|VarNodeParts|VarShards\.java|VarMeasure\.java|VarMatrixLake|VarTradeLinks|ExposureTradeLinks|VarCaches|ExposureCaches")
SVC_TESTS = set("""RiskLimitsTest ExposureTierDiskCapTest SimmTestData ExposurePathsKeyTest FrtbTestData FrtbServiceTest VarPartsLargestTest ExposureTierBenchmark
FrtbPlaygroundWorkbookTest SimmServiceTest VarPlaygroundWorkbookTest ExposureInputsTest VarInputsTest VarMaturedTest VarCompareDateRollbackTest
PvarPlaygroundWorkbookTest VarTierDiskCapTest VarParametricTest VarCurrencyRetentionTest SimmPlaygroundWorkbookTest
VarDiskBudgetRefusalTest VarEvictionLruTest ExposurePreDealLimitTest VarExplainMathTest VarOfficialJsonlTest ExposureOfficialJsonlTest
TestDataBook VarGoldenMatrixTest SystemBasketGoldenTest""".split())
KERNEL_NAMES = {"HierarchySpec", "IdIndex", "PositionRule", "PositionRules", "ScopeExpr", "MethodChoices", "RiskLimits", "PlaygroundSheets", "IdIndexTest", "RiskLimitsTest"}
CCR_RES = re.compile(r"(playground/(exposure|simm|saccr|fva)/|engines/(exposure|simm)\.yaml|resources/(ccr|simm)/)")

def chosen(p):
    if p.startswith(BC + "src/"):
        return True
    if p.startswith(BS + "src/main/java/"):
        return p.endswith(".java") and re.search(r"/banking/(var|exposure|frtb|simm)/|/banking/(RiskLimits|PlaygroundSheets)\.java$", p) and not STAY.search(p)
    if p.startswith(BS + "src/main/resources/"):
        return re.search(r"resources/(playground|engines)/", p) is not None
    if p.startswith(BS + "src/test/java/"):
        return p.rsplit("/", 1)[1][:-5] in SVC_TESTS
    if p.startswith(BS + "src/test/resources/var/"):
        return True
    if re.match(r"compute/drishti-compute/src/(main|test)/java/com/ash/drishti/compute/(vector|linalg)/", p):
        return True
    if re.match(r"kernel/drishti-server/src/(main|test)/java/com/ash/drishti/server/playground/", p):
        return True
    if p == "kernel/drishti-server/src/main/java/com/ash/drishti/server/nativelib/OpenBlasEngine.java":
        return True
    return p.startswith("tools/pvgen/")

def target(p):
    if p.startswith("tools/pvgen/"):
        return "tools/samples/pvgen/" + p[len("tools/pvgen/"):]
    m = re.search(r"/src/(main|test)/(java|resources)/(.*)$", p)
    kind, lang, rest = m.groups()
    name = rest.rsplit("/", 1)[-1].split(".")[0]
    if re.search(r"compute/(vector|linalg)/|server/(playground|nativelib)/", rest) or name in KERNEL_NAMES:
        mod = "risk-kernel"
    elif re.search(r"/(ccr|simm|exposure)/", "/" + rest) or CCR_RES.search(p):
        mod = "risk-ccr"
    else:
        mod = "risk-market"
    return f"java/{mod}/src/{kind}/{lang}/{rest}"

cur = [p for p in files if p and chosen(p)]
olds = collections.defaultdict(list)
for line in open(RENAMES):
    parts = line.rstrip("\n").split("\t")
    if len(parts) == 3 and parts[0].startswith("R"):
        olds[parts[2]].append(parts[1])
lines, seen = [], set()
for p in sorted(cur):
    t = target(p)
    todo = [p]
    while todo:
        q = todo.pop()
        if q in seen:
            continue
        seen.add(q)
        lines.append(f"{q}==>{t}")
        todo.extend(olds.get(q, []))
open(OUT, "w").write("\n".join(lines) + "\n")
print(len(cur), "files now,", len(lines), "paths with history", file=sys.stderr)
for m in ("risk-kernel", "risk-market", "risk-ccr", "tools"):
    print(m, sum(1 for p in cur if target(p).startswith(("java/" + m) if m != "tools" else "tools")), file=sys.stderr)
