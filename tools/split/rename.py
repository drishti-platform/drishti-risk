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

# The package rename of the move, in one commit: com.ash.drishti.* -> com.ash.risk.* (kernel, market, ccr; host types under hostcompat).
# Files move to the directory of their new package inside their module; imports and fully qualified names are rewritten; a class that
# leaves its old package's neighbours gets an explicit import in each neighbour that names it.
import os, re, sys
REPO = sys.argv[1]
CLASS = {
    **{f"com.ash.drishti.banking.compute.var.{c}": f"com.ash.risk.kernel.scope.{c}" for c in ["HierarchySpec", "IdIndex", "PositionRule", "PositionRules", "ScopeExpr"]},
    "com.ash.drishti.banking.compute.MethodChoices": "com.ash.risk.kernel.method.MethodChoices",
    "com.ash.drishti.banking.RiskLimits": "com.ash.risk.kernel.limits.RiskLimits",
    "com.ash.drishti.banking.RiskLimitsTest": "com.ash.risk.kernel.limits.RiskLimitsTest",
    "com.ash.drishti.banking.compute.var.IdIndexTest": "com.ash.risk.kernel.scope.IdIndexTest",
    "com.ash.drishti.banking.PlaygroundSheets": "com.ash.risk.kernel.workbook.PlaygroundSheets",
    "com.ash.drishti.server.nativelib.OpenBlasEngine": "com.ash.risk.kernel.linalg.OpenBlasEngine",
    "org.springframework.boot.context.properties.ConfigurationProperties": "com.ash.risk.hostcompat.spring.ConfigurationProperties",
    "org.springframework.beans.factory.annotation.Autowired": "com.ash.risk.hostcompat.spring.Autowired",
    "org.springframework.core.annotation.Order": "com.ash.risk.hostcompat.spring.Order",
}
PKG = [
    ("com.ash.drishti.compute.vector", "com.ash.risk.kernel.vector"),
    ("com.ash.drishti.compute.linalg", "com.ash.risk.kernel.linalg"),
    ("com.ash.drishti.server.playground", "com.ash.risk.kernel.workbook"),
    ("com.ash.drishti.banking.compute.var", "com.ash.risk.market.calc"),
    ("com.ash.drishti.banking.compute.frtb", "com.ash.risk.market.frtb.calc"),
    ("com.ash.drishti.banking.compute.analytics", "com.ash.risk.market.analytics"),
    ("com.ash.drishti.banking.compute.ccr", "com.ash.risk.ccr.calc"),
    ("com.ash.drishti.banking.compute.simm", "com.ash.risk.ccr.simm.calc"),
    ("com.ash.drishti.banking.var", "com.ash.risk.market.var"),
    ("com.ash.drishti.banking.frtb", "com.ash.risk.market.frtb"),
    ("com.ash.drishti.banking.exposure", "com.ash.risk.ccr.exposure"),
    ("com.ash.drishti.banking.simm", "com.ash.risk.ccr.simm"),
    ("com.ash.drishti", "com.ash.risk.hostcompat"),
    ("org.springframework", "com.ash.risk.hostcompat.spring"),
]
def new_fqn(fqn):
    if fqn in CLASS:
        return CLASS[fqn]
    for a, b in PKG:
        if fqn == a or fqn.startswith(a + "."):
            return b + fqn[len(a):]
    return fqn
NAME = re.compile(r"\b((?:com\.ash\.drishti|org\.springframework)(?:\.[A-Za-z_]\w*)+)")
def rewrite(text):
    def sub(m):
        s = m.group(1)
        parts = s.split(".")
        for i in range(len(parts), 2, -1):               # the longest prefix that names a mapped class, else a package
            head = ".".join(parts[:i])
            if head in CLASS:
                return CLASS[head] + s[len(head):]
        return new_fqn(s)
    return NAME.sub(sub, text)
files = []
for dp, dn, fn in os.walk(os.path.join(REPO, "java")):
    for f in fn:
        if f.endswith(".java"):
            files.append(os.path.join(dp, f))
info = {}
for p in files:
    src = open(p, encoding="utf-8").read()
    pkg = re.search(r"^package ([\w.]+);", src, re.M).group(1)
    name = os.path.basename(p)[:-5]
    info[p] = (pkg, name, new_fqn(pkg + "." + name), src)
moved_from = {}                                            # old package -> {simple name: new fqn} for classes that left it
for p, (pkg, name, nf, _) in info.items():
    if nf.rsplit(".", 1)[0] != new_fqn(pkg):
        moved_from.setdefault(pkg, {})[name] = nf
for p, (pkg, name, nf, src) in info.items():
    out = rewrite(src)
    npkg = nf.rsplit(".", 1)[0]
    out = re.sub(r"^package [\w.]+;", f"package {npkg};", out, count=1, flags=re.M)
    code = re.sub(r"(?s)/\*.*?\*/|//[^\n]*", " ", src)
    adds = set()
    for simple, target in moved_from.get(pkg, {}).items():
        if simple != name and re.search(r"\b" + simple + r"\b", code) and target.rsplit(".", 1)[0] != npkg:
            adds.add(target)
    for simple, target in {**moved_from.get(pkg, {})}.items():
        pass
    # a class that moved away from its old neighbours names them by import now
    if nf.rsplit(".", 1)[0] != new_fqn(pkg):
        for other_p, (opkg, oname, onf, _) in info.items():
            if opkg == pkg and oname != name and re.search(r"\b" + oname + r"\b", code) and onf.rsplit(".", 1)[0] != npkg:
                adds.add(onf)
    if adds:
        imp = "".join(f"import {a};\n" for a in sorted(adds))
        out = re.sub(r"^(package [\w.]+;\n)", r"\1\n" + imp.replace("\\", "\\\\"), out, count=1, flags=re.M)
    module_root = p.split("/src/")[0] + "/src/" + p.split("/src/")[1].split("/java/")[0] + "/java/"
    dst = module_root + nf.replace(".", "/") + ".java"
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    if dst != p:
        os.rename(p, dst)
    open(dst, "w", encoding="utf-8").write(out)
# resources and docs that name a package
for dp, dn, fn in os.walk(os.path.join(REPO, "java")):
    for f in fn:
        if not f.endswith(".java") and f.endswith((".yaml", ".yml", ".properties", ".txt", ".xml", ".json")):
            p = os.path.join(dp, f)
            try:
                s = open(p, encoding="utf-8").read()
            except UnicodeDecodeError:
                continue
            r = rewrite(s)
            if r != s:
                open(p, "w", encoding="utf-8").write(r)
# empty directories left behind
for dp, dn, fn in sorted(os.walk(os.path.join(REPO, "java")), key=lambda x: -len(x[0])):
    if not os.listdir(dp):
        os.rmdir(dp)
print(len(files), "java files renamed")
