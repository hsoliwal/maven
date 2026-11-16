# SPDX-License-Identifier: Apache-2.0 OR EPL-2.0
# Copyright (c) 2026 Synexia, Hitesh Soliwal (hsoliwal@gmail.com) and others
"""stamp_gate.py <repo> <base-ref> [out.tsv]

STAMP_GATE v1 (2026-11-16). CPU only. Refuses a change that introduces a product stamp. It compares the working tree
(tracked and untracked files) with <base-ref> and reports, one row each:
  NEW_PATH     an added or renamed path with a stamped segment that does not exist at the base (a folder or file named
               m3, m3-x, m3_x, .m3, M3Name)                                                      -> fails
  NEW_NAME     an added line with a stamped identifier or label (M3Name, m3-name, m3_name) that occurs nowhere else
               in the tree, so this change introduces it                                         -> fails
  CARRIED      an added line that repeats a stamp the tree already has (a reference to an existing name); listed so a
               de-stamp recipe can take it, does not fail
Package and module names that already exist (jdk.m3.x, jdk.internal.m3.x) are not identifiers of this kind and are
not reported. Exit status: 0 no new stamp, 1 at least one NEW_PATH or NEW_NAME, 2 usage or git error."""
import re
import subprocess
import sys

if len(sys.argv) < 3:
    print(__doc__)
    sys.exit(2)
repo, base = sys.argv[1], sys.argv[2]
NL, TAB = chr(10), chr(9)
SEGMENT = re.compile(r"^(\.?m3([-_.].*)?|M3[A-Z0-9].*)$")
NAME = re.compile(r"(?<![A-Za-z0-9_.])(M3[A-Z][A-Za-z0-9_]*|m3[-_][A-Za-z0-9][A-Za-z0-9_-]*)")


def git(*args, check=True):
    done = subprocess.run(["git", "-C", repo, *args], capture_output=True, text=True, encoding="utf-8",
                          errors="replace")
    if check and done.returncode != 0:
        print(done.stderr.strip())
        sys.exit(2)
    return done.stdout


def exists_at_base(path):
    return subprocess.run(["git", "-C", repo, "cat-file", "-e", base + ":" + path], capture_output=True).returncode == 0


git("add", "-A", "-N")
rows = []
for line in git("diff", "--name-status", "-M", base).splitlines():
    parts = line.split(TAB)
    if parts[0][0] not in "AR":
        continue
    path = parts[-1]
    segments = path.split("/")
    for depth, segment in enumerate(segments):
        if SEGMENT.match(segment) and not exists_at_base("/".join(segments[:depth + 1])):
            rows.append(["NEW_PATH", path, "0", segment, "/".join(segments[:depth + 1])])
            break

added, path, number = {}, None, 0
for line in git("diff", "--unified=0", "--no-color", base).splitlines():
    if line.startswith("+++ "):
        path = line[6:] if line.startswith("+++ b/") else None
    elif line.startswith("@@"):
        number = int(re.search(r"\+(\d+)", line).group(1))
    elif line.startswith("+") and path:
        for name in NAME.findall(line[1:]):
            added.setdefault(name, []).append((path, number, line[1:].strip()))
        number += 1
for name, uses in sorted(added.items()):
    counted = git("grep", "-I", "-w", "-F", "-c", "-e", name, check=False)
    total = sum(int(row.rsplit(":", 1)[1]) for row in counted.splitlines() if ":" in row)
    kind = "CARRIED" if total > len(uses) else "NEW_NAME"
    rows += [[kind, use[0], str(use[1]), name, use[2][:160]] for use in uses]

text = TAB.join(["finding", "path", "line", "stamp", "evidence"]) + NL + "".join(TAB.join(r) + NL for r in rows)
if len(sys.argv) > 3:
    with open(sys.argv[3], "w", encoding="utf-8", newline=NL) as out:
        out.write(text)
failing = [r for r in rows if r[0] != "CARRIED"]
print("stamp gate: new paths %d, new names %d, carried %d" % (
    sum(r[0] == "NEW_PATH" for r in rows), sum(r[0] == "NEW_NAME" for r in rows), sum(r[0] == "CARRIED" for r in rows)))
for row in failing + [r for r in rows if r[0] == "CARRIED"][:10]:
    print(TAB.join(row))
sys.exit(1 if failing else 0)
