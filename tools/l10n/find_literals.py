#!/usr/bin/env python3
"""Lists hardcoded user-facing English literals of the Compose shell (app/src/main/java/com/limelight/shell/**/*.kt).

Usage:
  python3 tools/l10n/find_literals.py                 # table: literals per file, total
  python3 tools/l10n/find_literals.py --list          # every literal as path:line: text
  python3 tools/l10n/find_literals.py --list FILE...  # only the given files (path suffix match)
  python3 tools/l10n/find_literals.py --markdown      # the table as Markdown (for docs/LOCALIZATION.md)

A line can opt out with a trailing `// l10n-ignore` comment (a literal that is not user-facing but looks like text).
The heuristic is deliberately pragmatic: a literal counts when its text (string templates reduced to `{}`)
starts with an uppercase letter or a template and has a space or more than 3 characters, and it is not a log
message, key, identifier, regex, format key or a debug-only preview. It over- and under-counts a little; it
exists to track progress per file, not to be exact. Python 3.9 compatible, standard library only.
"""
import os
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
SHELL = os.path.join(ROOT, "app", "src", "main", "java", "com", "limelight", "shell")

# Lines whose literals are never user-facing text.
SKIP_LINE = re.compile(
    r"\b(Log\.[a-z]\(|DiagLog\.|println\(|const val |Regex\(|toRegex|require\(|check\(|error\(|throw |"
    r"TAG\b|@Preview|@SerialName|@JvmName|@Suppress|import |package |assert|trace\(|"
    r"getString\(\"|getBoolean\(\"|getInt\(\"|putString\(\"|putBoolean\(\"|putInt\(\"|"
    r"values\[\"|prefs\.|SharedPreferences|System\.getProperty|String\.format\(Locale|"
    r"Intent\(|setAction|putExtra\(|getStringExtra\(|MIME|\.mime|Uri\.parse|URL\()"
)
CAMEL_OR_IDENT = re.compile(r"^[A-Za-z0-9_.:/\-]+$")
HAS_LOWER_UPPER = re.compile(r"[a-z][A-Z]")


def literals(source):
    """Yields (line number, raw literal body) for every "..." string outside comments."""
    i, n, line = 0, len(source), 1
    while i < n:
        c = source[i]
        if c == "\n":
            line += 1
            i += 1
        elif source.startswith("//", i):
            while i < n and source[i] != "\n":
                i += 1
        elif source.startswith("/*", i):
            end = source.find("*/", i + 2)
            end = n if end < 0 else end + 2
            line += source.count("\n", i, end)
            i = end
        elif source.startswith('"""', i):
            end = source.find('"""', i + 3)
            end = n if end < 0 else end + 3
            body = source[i + 3:end - 3]
            yield line, body
            line += source.count("\n", i, end)
            i = end
        elif c == "'":
            # char literal, e.g. '"' or '\n'
            j = i + 1
            if j < n and source[j] == "\\":
                j += 1
            i = min(n, j + 2)
        elif c == '"':
            j, depth, start = i + 1, 0, i + 1
            while j < n:
                d = source[j]
                if d == "\\" and depth == 0:
                    j += 2
                    continue
                if source.startswith("${", j):
                    depth += 1
                    j += 2
                    continue
                if d == "}" and depth:
                    depth -= 1
                elif d == '"' and depth == 0:
                    break
                elif d == '"':
                    # nested string inside a template: skip it
                    k = j + 1
                    while k < n and source[k] != '"':
                        k += 2 if source[k] == "\\" else 1
                    j = k
                elif d == "\n" and depth == 0:
                    break
                j += 1
            yield line, source[start:j]
            i = j + 1
        else:
            i += 1


def reduce_template(body):
    text = re.sub(r"\$\{[^{}]*(\{[^{}]*\}[^{}]*)*\}", "{}", body)
    text = re.sub(r"\$[A-Za-z_][A-Za-z0-9_]*", "{}", text)
    return text.replace("\\n", " ").replace('\\"', '"').strip()


def user_facing(body):
    text = reduce_template(body)
    if not text or "{}" == text:
        return False
    first = text[0]
    if not (first.isupper() or text.startswith("{}") and " " in text):
        return False
    if " " not in text and len(text) <= 3:
        return False
    if " " not in text and (HAS_LOWER_UPPER.search(text) or not text.replace("{}", "").isalpha()):
        # CamelCase identifiers, snake/kebab keys, paths, numbers: not words
        return False
    if CAMEL_OR_IDENT.match(text) and " " not in text and text.upper() == text:
        return False
    if text.startswith(("http", "#", "/", ".")) or "%" in text and " " not in text:
        return False
    return re.search(r"[A-Za-z]", text.replace("{}", "")) is not None


def scan(path):
    with open(path, encoding="utf-8") as f:
        source = f.read()
    lines = source.split("\n")
    found = []
    for number, body in literals(source):
        row = lines[number - 1] if number <= len(lines) else ""
        if "l10n-ignore" in row or SKIP_LINE.search(row):
            continue
        if user_facing(body):
            found.append((number, reduce_template(body)))
    return found


def shell_files():
    for base, _, names in os.walk(SHELL):
        for name in sorted(names):
            if name.endswith(".kt"):
                yield os.path.join(base, name)


def main(argv):
    args = [a for a in argv if not a.startswith("--")]
    listing = "--list" in argv
    markdown = "--markdown" in argv
    counts = []
    for path in sorted(shell_files()):
        rel = os.path.relpath(path, SHELL)
        if args and not any(rel.endswith(a) or path.endswith(a) for a in args):
            continue
        found = scan(path)
        if listing:
            for number, text in found:
                print("%s:%d: %s" % (os.path.relpath(path, ROOT), number, text))
        if found:
            counts.append((rel, len(found)))
    if listing:
        return 0
    counts.sort(key=lambda r: (-r[1], r[0]))
    total = sum(c for _, c in counts)
    if markdown:
        print("| File (under `shell/`) | Literals |\n|---|---:|")
        for rel, count in counts:
            print("| `%s` | %d |" % (rel, count))
        print("| **Total** | **%d** |" % total)
    else:
        width = max([len(r) for r, _ in counts] + [5])
        for rel, count in counts:
            print("%-*s %5d" % (width, rel, count))
        print("%-*s %5d" % (width, "TOTAL", total))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
