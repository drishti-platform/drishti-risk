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

# Which host types the moved code names directly can be parked whole (their own references close inside the parked set and the allowed
# libraries); prints the plan, or with --write <dir> copies them under com.ash.risk.hostcompat.<their old sub-package>.
import os, re, sys
L = os.environ.get("RISK_SPLIT_LANE", os.path.expanduser("~/.cache/drishti-lanes/risk-r1"))
ROOT = L + "/freeze"
paths = [l.split("==>")[0] for l in open(L + "/split/paths.txt").read().split("\n") if l]
now = [p for p in paths if os.path.exists(os.path.join(ROOT, p)) and p.endswith(".java")]
moved = set()
for p in now:
    m = re.search(r"/src/(?:main|test)/java/(.*)\.java$", p); moved.add(m.group(1).replace("/", "."))
index = {}
for dp, dn, fn in os.walk(ROOT):
    if "/src/main/java/com/ash/drishti" not in dp: continue
    for f in fn:
        if f.endswith(".java"):
            full = os.path.join(dp, f); index.setdefault(full.split("/src/main/java/")[1][:-5].replace("/", "."), full)
ALLOWED = ("java.", "javax.annotation", "org.apache.arrow.", "com.ash.tools.codec.", "com.ash.tools.files.", "com.fasterxml.jackson.", "org.slf4j.")
def refs(full):
    src = re.sub(r"(?s)/\*.*?\*/", " ", open(full).read()); src = re.sub(r"//[^\n]*", " ", src); host, other = set(), set()
    for m in re.finditer(r"^import (?:static )?([\w.]+?)(?:\.\*)?;", src, re.M):
        n = m.group(1)
        if n.startswith("com.ash.drishti."):
            while n not in index and "." in n: n = n.rsplit(".", 1)[0]
            if n in index: host.add(n)
        elif not n.startswith(ALLOWED): other.add(n)
    pkg = re.search(r"^package ([\w.]+);", src, re.M).group(1)
    for w in set(re.findall(r"\b([A-Z]\w+)\b", src)):
        if pkg + "." + w in index: host.add(pkg + "." + w)
    for m in re.finditer(r"\b(com\.ash\.drishti\.[a-z.]+\.[A-Z]\w*)", src):
        if m.group(1) in index: host.add(m.group(1))
    for m in re.finditer(r"\b(org\.springframework\.[\w.]+|jakarta\.[\w.]+)", src): other.add(m.group(1))
    return host - {re.search(r"/java/(.*)\.java", full).group(1).replace("/", ".")}, other
direct = set()
for p in now:
    h, _ = refs(os.path.join(ROOT, p)); direct |= {t for t in h if t not in moved and not t.startswith("com.ash.drishti.banking.")}
# grow: a parked type may need others; park those too while they close
cand = set(direct); changed = True
while changed:
    changed = False
    for t in list(cand):
        h, o = refs(index[t])
        need = {x for x in h if x not in moved and not x.startswith("com.ash.drishti.banking.")}
        if o:
            continue
        for x in need - cand:
            cand.add(x); changed = True
ok = set(cand); changed = True
while changed:
    changed = False
    for t in list(ok):
        h, o = refs(index[t])
        need = {x for x in h if x not in moved and not x.startswith("com.ash.drishti.banking.")}
        if o or not need <= ok:
            ok.discard(t); changed = True
parked = sorted(ok); open_ = sorted(direct - ok)
lines = sum(sum(1 for _ in open(index[t])) for t in parked)
print("PARKED", len(parked), lines); [print("  ", t) for t in parked]
print("OPEN (named by moved code, not parkable whole)"); [print("  ", t, sorted(refs(index[t])[1])[:3]) for t in open_]
if len(sys.argv) > 2 and sys.argv[1] == "--write":
    out = sys.argv[2]
    for t in parked:
        sub = t[len("com.ash.drishti."):]
        dst = os.path.join(out, "com/ash/risk/hostcompat", *sub.split(".")) + ".java"
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        open(dst, "w").write(open(index[t]).read())
    open(os.path.join(L, "split/parked.txt"), "w").write("\n".join(parked) + "\n")
    open(os.path.join(L, "split/open.txt"), "w").write("\n".join(open_) + "\n")
