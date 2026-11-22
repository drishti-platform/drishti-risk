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

"""Run the reference recorder (RiskRecorder.java) against a Drishti build of the freeze commit.

The Drishti checkout must be at 3813b8735 and built (its modules' target/classes present; ``./mvnw -o install -DskipTests`` there, by
whoever owns that machine's build). This script never builds Drishti and never writes the local Maven repository: it asks Maven, offline,
for the banking module's class path, compiles the recorder beside it into a work folder, runs it, and removes the work folder.

Usage:
  python3 -I tools/split/recorder/record.py --drishti /abs/drishti-at-freeze --risk /abs/drishti-risk --work /abs/empty-folder
"""
import argparse
import os
import shutil
import subprocess
import sys

MODULES = ["modules/banking/java/banking-service", "modules/banking/java/banking-compute", "testing/drishti-testkit"]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--drishti", required=True)
    ap.add_argument("--risk", required=True)
    ap.add_argument("--work", required=True)
    a = ap.parse_args()
    for p in (a.drishti, a.risk, a.work):
        if not os.path.isabs(p):
            sys.exit("an absolute path is expected: " + p)
    os.makedirs(a.work, exist_ok=True)
    cp_file = os.path.join(a.work, "classpath.txt")
    mvnw = os.path.join(a.drishti, "mvnw")
    subprocess.run([mvnw, "-q", "-o", "-pl", ",".join(MODULES), "dependency:build-classpath", "-Dmdep.outputFile=" + cp_file,
                    "-Dmdep.includeScope=test"], cwd=a.drishti, check=True)
    parts = [os.path.join(a.drishti, m, "target/classes") for m in MODULES]
    with open(cp_file, encoding="utf-8") as f:
        parts += [x for x in f.read().strip().split(os.pathsep) if x]
    cp = os.pathsep.join(dict.fromkeys(parts))
    out = os.path.join(a.work, "classes")
    os.makedirs(out, exist_ok=True)
    src = os.path.join(a.risk, "tools/split/recorder/RiskRecorder.java")
    try:
        subprocess.run(["javac", "--release", "21", "-cp", cp, "-d", out, src], check=True)
        subprocess.run(["java", "-cp", out + os.pathsep + cp, "-Djava.io.tmpdir=" + a.work, "com.ash.drishti.banking.var.RiskRecorder",
                        a.drishti, a.risk, os.path.join(a.work, "store")], check=True)
    finally:
        shutil.rmtree(a.work, ignore_errors=True)


if __name__ == "__main__":
    main()
