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

"""Harvest the expected values Drishti's goldens hold into kit case files, now, without running Drishti.

What it reads (moved with history, unchanged): ``java/risk-market/src/test/resources/var/golden-matrix.json.gz`` (VarGoldenMatrixTest:
3 dates x 2 windows x 2 currencies, every hierarchy node and every system basket), ``hierarchy-golden-2026-09-30.json``
(HierarchyEquivalenceTest's HierarchyGolden: every node with its aggregate's digest) and ``java/risk-ccr/src/test/resources/ccr/reference.json``
(ExposureParityTest's outside oracle: every netting set and scope). What it writes: ``tck/recorded/<engine>/*.yaml``.

Two kinds of value, kept apart:

* **exact** (compared as they are): trees, members, whole-unit VaR and ES (``VAR_UNITS``, ``ES_UNITS``), limits. These do not move with
  the summation order, so they hold for the engine's canonical order as for Drishti's file order.
* **raw bits** of VaR and ES as Drishti wrote them in its file order: kept as ``drishti-bits`` for the recorder (Recorder.java) to show how
  far the canonical order moves them (the golden itself compares to 1e-6). The kit's bit-exact cases come from the recorder, not from here.

The CCR reference is an outside oracle on float32 paths: its values are kept with the bound ExposureParityTest applies (two units in the
last place of a float32 of the largest value), never as bits.

Usage: python3 -I tools/split/recorder/harvest_goldens.py <absolute path of the drishti-risk checkout>
"""
import gzip
import json
import os
import struct
import sys

HEADER = "# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved. Drishti Risk. Proprietary: see LICENSE.\n"


def bits(x):
    return None if x is None else "0x%016x" % struct.unpack(">Q", struct.pack(">d", float(x)))[0]


def q(s):
    return "null" if s is None else json.dumps(s)


def units(x):
    # Java's Math.round: floor(x + 0.5)
    import math
    return None if x is None else int(math.floor(float(x) + 0.5))


def market(root):
    res = os.path.join(root, "java/risk-market/src/test/resources/var")
    with gzip.open(os.path.join(res, "golden-matrix.json.gz"), "rt", encoding="utf-8") as f:
        matrix = json.load(f)
    out_dir = os.path.join(root, "tck/recorded/market-risk")
    os.makedirs(out_dir, exist_ok=True)
    written = []
    by_date = {}
    for case, body in matrix.items():
        by_date.setdefault(case.split("/")[0], []).append((case, body))
    for date, cases in sorted(by_date.items()):
        lines = [HEADER, f"# Harvested from Drishti's VarGoldenMatrixTest golden (3813b8735) for {date}: exact columns and Drishti's file-order bits.\n",
                 f"suite: market-risk/drishti-goldens-{date}\n", "engine: market-risk\n", "since: \"0.1\"\n",
                 "source: \"golden-matrix.json.gz of VarGoldenMatrixTest (VaR proof of a patch), Drishti 3813b8735\"\n",
                 f"given: {{ snapshot: {{ date: \"{date}\" }}, inputs: recorder }}\n", "cases:\n"]
        for case, body in sorted(cases):
            _, window, cur = case.split("/")
            lines.append(f"  - id: {date}-{window}-{cur}\n")
            lines.append(f"    request: {{ outputs: [ node-var, basket-var ], params: {{ scenario-set: \"\", window: {window}, currency: {cur} }}, visibility: all }}\n")
            lines.append("    node-var:\n      columns: [ NODE_ID, HIERARCHY, PARENT, LEVEL, MEMBERS, VAR_UNITS, ES_UNITS, LIMIT, VAR_SCENARIO_WORST10, drishti-bits VAR, drishti-bits ES ]\n      rows:\n")
            for nid in sorted(body["nodes"]):
                n = body["nodes"][nid]
                lines.append("        - [ %s, %s, %s, %s, %d, %s, %s, %s, %s, %s, %s ]\n" % (
                    q(nid), q(n["hierarchy"]), q(n["parent"]), q(n["level"]), n["members"], units(n["var"]), units(n["es"]),
                    q(bits(n["limit"])) if n.get("limit") is not None else "null", json.dumps(n["worst"]), q(bits(n["var"])), q(bits(n["es"]))))
            lines.append("    node-components (drishti-bits, file order):\n      rows:\n")
            for nid in sorted(body["nodes"]):
                for child, v in sorted((body["nodes"][nid].get("components") or {}).items()):
                    lines.append("        - [ %s, %s, %s ]\n" % (q(nid), q(child), q(bits(v))))
            lines.append("    basket-var:\n      columns: [ BASKET_ID, MEMBERS, UNKNOWN, VAR_UNITS, ES_UNITS, LIMIT, drishti-bits VAR, drishti-bits ES ]\n      rows:\n")
            for bid in sorted(body["baskets"]):
                b = body["baskets"][bid]
                lines.append("        - [ %s, %d, %s, %s, %s, %s, %s, %s ]\n" % (
                    q(bid), b["members"], q(",".join(b["unknown"]) or None), units(b["var"]), units(b["es"]),
                    q(bits(b["limit"])) if b.get("limit") is not None else "null", q(bits(b["var"])), q(bits(b["es"]))))
        p = os.path.join(out_dir, f"drishti-goldens-{date}.yaml")
        open(p, "w", encoding="utf-8").write("".join(lines))
        written.append(p)
    hg = json.load(open(os.path.join(res, "hierarchy-golden-2026-09-30.json"), encoding="utf-8"))
    lines = [HEADER, "# Harvested from Drishti's HierarchyGolden (HierarchyEquivalenceTest), 2026-09-30, 509 scenarios: bit-exact in its file order.\n",
             "suite: market-risk/drishti-hierarchy-golden-2026-09-30\n", "engine: market-risk\n", "since: \"0.1\"\n",
             "source: \"hierarchy-golden-2026-09-30.json, Drishti 3813b8735\"\n", "cases:\n"]
    for cur in ("USD", "EUR"):
        if cur not in hg:
            continue
        lines.append(f"  - id: hierarchy-{cur}\n    columns: [ NODE_ID, MEMBERS, VAR_UNITS, ES_UNITS, AGGREGATE_DIGEST, drishti-bits VAR, drishti-bits ES ]\n    rows:\n")
        for nid in sorted(hg[cur]):
            n = hg[cur][nid]
            lines.append("      - [ %s, %d, %s, %s, %s, %s, %s ]\n" % (q(nid), n["members"], units(n["var"]), units(n["es"]),
                                                                    q(n.get("aggregateDigest")), q(bits(n["var"])), q(bits(n["es"]))))
    p = os.path.join(out_dir, "drishti-hierarchy-golden-2026-09-30.yaml")
    open(p, "w", encoding="utf-8").write("".join(lines))
    written.append(p)
    return written


def ccr(root):
    ref = json.load(open(os.path.join(root, "java/risk-ccr/src/test/resources/ccr/reference.json"), encoding="utf-8"))
    out_dir = os.path.join(root, "tck/recorded/ccr")
    os.makedirs(out_dir, exist_ok=True)
    lines = [HEADER, "# Harvested from Drishti's ExposureParityTest reference (an outside oracle on float32 paths), run " + ref["runId"] + ".\n",
             "# Compared within 2 x 2^-24 x max(1, PEAK_PFE_99, max EE_UNCOLL) (+1e-6; +1e-5 for the profile), as the parity test does; never as bits.\n",
             "suite: ccr/drishti-reference-" + ref["businessDate"] + "\n", "engine: ccr\n", "since: \"0.1\"\n",
             "source: \"reference.json of ExposureParityTest, Drishti 3813b8735\"\n",
             f"given: {{ snapshot: {{ date: \"{ref['businessDate']}\" }}, inputs: recorder }}\n", "cases:\n"]
    scopes = [("NSET:" + k, v) for k, v in sorted(ref["sets"].items())] + sorted(ref.get("scopes", {}).items())
    for scope, s in scopes:
        lines.append(f"  - id: {json.dumps(scope)}\n")
        lines.append(f"    request: {{ outputs: [ exposure-summary, exposure-profile ], params: {{ scope: {json.dumps(scope)}, standalone: false }}, visibility: all }}\n")
        lines.append("    exposure-summary: { CE: %r, EPE: %r, EEPE: %r, PEAK_EE: %r, PEAK_PFE_95: %r, PEAK_PFE_95_DATE: %s, PEAK_PFE_99: %r }\n" % (
            s["CE"], s["EPE"], s["EEPE"], s["PEAK_EE"], s["PEAK_PFE_95"], q(s.get("PEAK_PFE_95_DATE")), s["PEAK_PFE_99"]))
        lines.append("    exposure-profile:\n      columns: [ TENOR, DATE, EE, ENE, EEE, PFE_95, PFE_99, EE_UNCOLL ]\n      rows:\n")
        for r in s["PROFILE"]:
            lines.append("        - [ %s, %s, %r, %r, %r, %r, %r, %r ]\n" % (q(r["TENOR"]), q(r["DATE"]), r["EE"], r["ENE"], r["EEE"], r["PFE_95"],
                                                                         r["PFE_99"], r["EE_UNCOLL"]))
    p = os.path.join(out_dir, "drishti-reference-" + ref["businessDate"] + ".yaml")
    open(p, "w", encoding="utf-8").write("".join(lines))
    return [p]


def main():
    root = os.path.abspath(sys.argv[1])
    for p in market(root) + ccr(root):
        print(p, sum(1 for _ in open(p, encoding="utf-8")), "lines")


if __name__ == "__main__":
    main()
