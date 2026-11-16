# SPDX-License-Identifier: Apache-2.0 OR EPL-2.0
# Copyright (c) 2026 Synexia, Hitesh Soliwal (hsoliwal@gmail.com) and others
"""interface_additions.py <repo-root> <base-ref> [<out.tsv>]

INTERFACE_ADDITIONS v2 (2026-11-16). CPU only. Enforces the backward-compatibility rule for extension points:
a method added to an existing interface is a default method, and a method added to an existing abstract class has a
body (new variants are overloads that delegate), so existing implementors and subclasses keep compiling.
For every Java file modified since <base-ref> it compares the interfaces and abstract classes that exist on both
sides and reports, per type:
  ABSTRACT_ADDED    a new abstract method (violation, exit 1)
  DEFAULT_ADDED     a new default method on an interface (allowed)
  CONCRETE_ADDED    a new non-private method with a body on an abstract class (allowed)
  ABSTRACT_REMOVED  an abstract method that is gone or now has a body (information)
Types that are new in the head, annotation types, concrete classes and static or private methods are ignored.
Comments, strings, character literals and text blocks are masked before scanning, so text inside them never counts.
A signature is the method name plus its parameter types (annotations, `final` and parameter names removed)."""
import re
import subprocess
import sys

MASK = re.compile(r'"""(?:\\.|[^\\])*?"""|"(?:\\.|[^"\\\n])*"|\'(?:\\.|[^\'\\\n])*\'|//[^\n]*|/\*.*?\*/', re.S)
DECL = re.compile(r"(?<![@\w])(interface|class)\s+([A-Za-z_$][\w$]*)")
TYPE_WORD = re.compile(r"\b(class|interface|enum|record)\b")


def mask(text):
    return MASK.sub(lambda m: re.sub(r"[^\n]", " ", m.group(0)), text)


def close(text, start):
    depth = 0
    for i in range(start, len(text)):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                return i
    return len(text) - 1


def signature(chunk):
    head = chunk[:chunk.index("(")]
    name = re.findall(r"[A-Za-z_$][\w$]*", head)[-1]
    depth, end = 0, len(chunk)
    for i in range(chunk.index("("), len(chunk)):
        depth += chunk[i] == "("
        depth -= chunk[i] == ")"
        if depth == 0:
            end = i
            break
    params, part, angle = [], "", 0
    for ch in chunk[chunk.index("(") + 1:end] + ",":
        angle += ch == "<"
        angle -= ch == ">"
        if ch == "," and angle == 0:
            p = re.sub(r"@[\w.]+(\([^)]*\))?", " ", part)
            p = re.sub(r"\bfinal\b", " ", p).strip()
            if p:
                p = re.sub(r"\s*[A-Za-z_$][\w$]*\s*$", "", p) if re.search(r"[\s>\].]\s*[A-Za-z_$][\w$]*\s*$", p) else p
                params.append(re.sub(r"\s+", "", p))
            part = ""
        else:
            part += ch
    return name + "(" + ",".join(params) + ")"


def members(body):
    """Yields (chunk, has_body) for each member declared directly in a type body."""
    depth, start, i = 0, 0, 0
    while i < len(body):
        ch = body[i]
        if ch in "({":
            if ch == "{" and depth == 0:
                end = close(body, i)
                yield body[start:i], True
                start = i = end + 1
                continue
            depth += 1
        elif ch in ")}":
            depth -= 1
        elif ch == ";" and depth == 0:
            yield body[start:i], False
            start = i + 1
        i += 1


def extension_points(text):
    """Maps nested type path -> (kind, abstract signatures, bodied signatures) for interfaces and abstract classes."""
    code = mask(text)
    spans = []
    for m in DECL.finditer(code):
        brace = code.find("{", m.end())
        if brace < 0:
            continue
        modifiers = code[max(code.rfind(";", 0, m.start()), code.rfind("{", 0, m.start()), code.rfind("}", 0, m.start())) + 1:m.start()]
        kind = "interface" if m.group(1) == "interface" else ("abstract class" if re.search(r"\babstract\b", modifiers) else "class")
        spans.append((m.start(), close(code, brace), brace, m.group(2), kind))
    out = {}
    for start, end, brace, name, kind in spans:
        if kind == "class":
            continue
        path = ".".join(n for s, e, _, n, _ in spans if s <= start and end <= e)
        abstract, bodied = set(), set()
        for chunk, has_body in members(code[brace + 1:end]):
            if "(" not in chunk or TYPE_WORD.search(chunk.split("(")[0]):
                continue
            head = chunk.split("(")[0]
            if "=" in head or re.search(r"\b(static|private)\b", head) or not re.search(r"[\w>\]]\s+[A-Za-z_$][\w$]*\s*$", head):
                continue
            if kind == "interface":
                if has_body and re.search(r"\bdefault\b", head):
                    bodied.add(signature(chunk))
                elif not has_body:
                    abstract.add(signature(chunk))
            elif has_body:
                bodied.add(signature(chunk))
            elif re.search(r"\babstract\b", head):
                abstract.add(signature(chunk))
        out[path] = (kind, abstract, bodied)
    return out


def git(root, *args):
    return subprocess.run(["git", "-C", root, *args], capture_output=True, check=True).stdout.decode("utf-8", "replace")


def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    root, base = sys.argv[1:3]
    rows = []
    for path in filter(None, git(root, "diff", "--name-only", "--diff-filter=M", "-z", base, "--", "*.java").split("\0")):
        before = extension_points(git(root, "show", f"{base}:{path}"))
        after = extension_points(open(f"{root}/{path}", encoding="utf-8", errors="replace").read())
        for name in sorted(set(before) & set(after)):
            kind, new_abstract, new_bodied = after[name]
            _, old_abstract, old_bodied = before[name]
            added = "DEFAULT_ADDED" if kind == "interface" else "CONCRETE_ADDED"
            rows += [(path, kind, name, s, "ABSTRACT_ADDED") for s in sorted(new_abstract - old_abstract - old_bodied)]
            rows += [(path, kind, name, s, added) for s in sorted(new_bodied - old_bodied - old_abstract)]
            rows += [(path, kind, name, s, "ABSTRACT_REMOVED") for s in sorted(old_abstract - new_abstract)]
    text = "path\ttype_kind\ttype\tsignature\tkind\n" + "".join("\t".join(r) + "\n" for r in rows)
    if len(sys.argv) > 3:
        open(sys.argv[3], "w", encoding="utf-8", newline="\n").write(text)
    else:
        sys.stdout.write(text)
    bad = sum(r[4] == "ABSTRACT_ADDED" for r in rows)
    print(f"extension points: {bad} abstract added (violations), "
          f"{sum(r[4] in ('DEFAULT_ADDED', 'CONCRETE_ADDED') for r in rows)} with a body added, "
          f"{sum(r[4] == 'ABSTRACT_REMOVED' for r in rows)} abstract removed", file=sys.stderr)
    sys.exit(1 if bad else 0)


main()
