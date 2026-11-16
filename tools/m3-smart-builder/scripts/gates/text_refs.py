# SPDX-License-Identifier: Apache-2.0 OR EPL-2.0
# Copyright (c) 2026 Synexia, Hitesh Soliwal (hsoliwal@gmail.com) and others
"""text_refs.py <partial-clone> <rev> <out.tsv> <name>... [--areas a,b,...] [--max-bytes N]

TEXT_REFS v1.1 (2026-11-16; v1.1 never asks git for blob sizes: in a partial clone that downloads every blob
one by one). CPU only. Finds every NON-Java text file of a revision that names one of the given
identifiers (whole word), without a checkout: workflows, make files, build descriptors, scripts and test lists (the files a build executes; ledgers and prose are
not scanned). A rename recipe
changes Java sources; this is the check that nothing else (a workflow that compiles a file by path, a test list, a
script) still uses an old name. It lists the candidate blobs from the tree (areas, default: every top-level folder
except src and test, plus test/jdk; text extensions; at most --max-bytes, default 2 MiB), fetches the missing ones in
ONE batch, and scans them through `git cat-file --batch`. Output rows: name, path, first matching line.
Exit status: 0 no reference, 1 at least one reference (they are printed), 2 usage or git error."""
import re
import subprocess
import sys

args = sys.argv[1:]


def option(name, default):
    if name in args:
        value = args[args.index(name) + 1]
        del args[args.index(name):args.index(name) + 2]
        return value
    return default


areas = option("--areas", "")
limit = int(option("--max-bytes", str(2 << 20)))
if len(args) < 4:
    print(__doc__)
    sys.exit(2)
clone, rev, out, names = args[0], args[1], args[2], args[3:]
NL, TAB = chr(10), chr(9)
TEXT = re.compile(r"\.(yml|yaml|sh|bash|py|gmk|mk|m4|xml|properties|ps1|bat|cmd|gradle|kts|toml|cfg|list|groups|awk|sed|"
                  r"jsh)$|(^|/)(Makefile|TEST\.groups|TEST\.ROOT|ProblemList[^/]*)$")
WORD = re.compile(rb"(?<![A-Za-z0-9_])(" + b"|".join(re.escape(n.encode()) for n in names) + rb")(?![A-Za-z0-9_])")


def git(*command, data=None):
    done = subprocess.run(["git", "-C", clone, *command], capture_output=True, input=data)
    if done.returncode != 0:
        print(done.stderr.decode("utf-8", "replace")[:300])
        sys.exit(2)
    return done.stdout


wanted = [a for a in areas.split(",") if a]
candidates, skipped = {}, 0
for line in git("ls-tree", "-r", rev).decode("utf-8", "replace").splitlines():
    meta, path = line.split(TAB, 1)
    _mode, kind, oid = meta.split()
    if kind != "blob" or path.endswith(".java") or not TEXT.search(path):
        continue
    top = path.split("/")[0]
    inside = any(path.startswith(a + "/") or path == a for a in wanted) if wanted else (
        top not in ("src", "test") or path.startswith("test/jdk/"))
    if not inside:
        continue
    candidates.setdefault(oid, []).append(path)
have = set()
for line in git("cat-file", "--batch-check=%(objectname) %(objecttype)", "--batch-all-objects").decode().splitlines():
    oid, kind = line.split()
    if kind == "blob":
        have.add(oid)
missing = [oid for oid in candidates if oid not in have]
if missing:
    git("-c", "fetch.negotiationAlgorithm=noop", "fetch", "-q", "origin", "--no-tags", "--no-write-fetch-head",
        "--recurse-submodules=no", "--filter=blob:none", "--stdin", data=(NL.join(missing) + NL).encode())
stream = subprocess.Popen(["git", "-C", clone, "cat-file", "--batch"], stdin=subprocess.PIPE, stdout=subprocess.PIPE)
rows = []
for oid, paths in candidates.items():
    stream.stdin.write((oid + NL).encode())
    stream.stdin.flush()
    header = stream.stdout.readline().split()
    if len(header) < 3:
        continue
    data = stream.stdout.read(int(header[2]))
    stream.stdout.read(1)
    if len(data) > limit:
        skipped += 1
        continue
    found = WORD.search(data)
    if found:
        start = data.rfind(b"\n", 0, found.start()) + 1
        end = data.find(b"\n", found.end())
        text = data[start:end if end >= 0 else len(data)].decode("utf-8", "replace").strip()[:160]
        rows += [[found.group(1).decode(), path, text.replace(TAB, " ")] for path in paths]
stream.stdin.close()
with open(out, "w", encoding="utf-8", newline=NL) as w:
    w.write(TAB.join(["name", "path", "line"]) + NL + "".join(TAB.join(r) + NL for r in sorted(rows)))
print("text files scanned %d (fetched %d, larger than the limit and skipped %d), references %d" % (
    sum(len(p) for p in candidates.values()), len(missing), skipped, len(rows)))
for row in sorted(rows)[:25]:
    print(TAB.join(row))
sys.exit(1 if rows else 0)
