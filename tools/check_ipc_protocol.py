#!/usr/bin/env python3
"""Verify the hand-written Parcel protocol in IPrivTasks.java.

``IPrivTasks`` is not an AIDL file: the transaction codes are allocated purely by the
declaration order of

    private static final int TRANSACTION_xxx = createTransactionCode();

where ``createTransactionCode()`` returns ``FIRST_CALL_TRANSACTION + CODE++``.  Inserting a
new constant somewhere in the middle therefore renumbers every following transaction, and an
old daemon talking to a new app (or the other way round) then parses the arguments of interface
A as the arguments of interface B - usually a crash, sometimes silent corruption.  This script
enforces the append-only rule on the source tree, without a build.

It checks that:

  a) the number of ``TRANSACTION_*`` constants equals the number of ``code == TRANSACTION_*``
     branches in ``Stub.onTransact()`` and that the names match one-to-one;
  b) every constant that is not in the embedded baseline is appended *after* the baseline,
     and the baseline itself kept its exact order (so no code moved);
  c) the interface method list and the ``Proxy`` implementations match, the interface method
     count equals the transaction count, and each ``onTransact`` branch calls a Stub helper
     that actually exists;
  d) a new constant declared in the middle is reported explicitly as the failure it is.

Exit status is 0 when everything matches and 1 otherwise, so it can gate CI:

    python3 tools/check_ipc_protocol.py
    python3 tools/check_ipc_protocol.py --file path/to/IPrivTasks.java
"""

import argparse
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.dirname(HERE)
DEFAULT_FILE = os.path.join(
    REPO_ROOT,
    "priv_library",
    "src",
    "main",
    "java",
    "com",
    "mirfatif",
    "privtasks",
    "iface",
    "IPrivTasks.java",
)

# The transaction names already on the wire, in declaration order (which is the code order:
# the Nth name is FIRST_CALL_TRANSACTION + N - 1).  This is the baseline the check compares
# against.  It is append-only for the same reason the protocol is: reordering or dropping a
# name here would hide a renumbering from the check.  When a transaction is appended for
# real, append its name (only its name) here in the same commit.
BASELINE_TRANSACTIONS = [
    "sendStdErr",
    "hello",
    "setExitOnAppDeath",
    "getPrivsStatus",
    "setDebug",
    "dumpHeap",
    "grantPrivileges",
    "stopDaemon",
    "getAppOpsLists",
    "getPermFixedFlags",
    "getPackagesForUid",
    "getOpsForPackage",
    "getPermFlags",
    "setPermState",
    "setAppOpMode",
    "resetAppOps",
    "setPkgState",
    "openAppInfo",
    # Batch IPC: one app<->daemon round trip instead of one per package / per permission.
    # Appended in the same change that added them, so a later reorder is caught here.
    "getOpsForPackages",
    "getPermFlagsBatch",
]

# Transaction names whose interface method is spelled differently on purpose.  The mapping is
# not part of the protocol (the code number is), so it is only used to keep check (c) quiet.
METHOD_ALIASES = {
    "grantPrivileges": "grantAppPrivileges",
}

# Methods that Stub/Proxy implement for android.os.IInterface without IPrivTasks redeclaring them.
INHERITED_METHODS = {
    "asBinder",
}

TRANSACTION_RE = re.compile(
    r"(?:private|public|protected)\s+static\s+final\s+int\s+TRANSACTION_(\w+)\s*=\s*([^;]+);"
)
BRANCH_RE = re.compile(r"code\s*==\s*TRANSACTION_(\w+)")
DISPATCH_RE = re.compile(
    r"code\s*==\s*TRANSACTION_(\w+)\s*\)\s*\{\s*\n\s*(\w+)\s*\(\s*data\s*,\s*reply\s*\)\s*;"
)
STUB_IMPL_RE = re.compile(
    r"(?m)^ {4}(?:public|private|protected)\s+(?:static\s+)?[\w<>\[\]\.]+\s+(\w+)"
    r"\s*\(\s*Parcel\s+\w+\s*,\s*Parcel\s+\w+\s*\)"
)
INTERFACE_METHOD_RE = re.compile(
    r"(?m)^ {2}([A-Za-z_][\w<>\[\]\.]*)\s+(\w+)\s*\(([^;{]*?)\)\s*"
    r"(?:throws\s+[\w\s,\.]+)?;"
)
PROXY_METHOD_RE = re.compile(
    r"(?m)^ {6}(?:public|private|protected)\s+[\w<>\[\]\.]+\s+(\w+)\s*\("
)


def scan(text):
    """Collect the parts of IPrivTasks that together define the wire protocol."""
    transactions = TRANSACTION_RE.findall(text)
    names = [name for name, _ in transactions]

    stub_start = text.find("abstract class Stub")
    interface_text = text if stub_start < 0 else text[:stub_start]
    interface_methods = [m.group(2) for m in INTERFACE_METHOD_RE.finditer(interface_text)]

    proxy_start = text.find("class Proxy implements IPrivTasks")
    proxy_text = "" if proxy_start < 0 else text[proxy_start:]
    proxy_methods = [m.group(1) for m in PROXY_METHOD_RE.finditer(proxy_text)]

    return {
        "names": names,
        "initialisers": [init.strip() for _, init in transactions],
        "interface_methods": interface_methods,
        "proxy_methods": proxy_methods,
        "branches": BRANCH_RE.findall(text),
        "dispatches": dict(DISPATCH_RE.findall(text)),
        "stub_impls": STUB_IMPL_RE.findall(text),
        "has_stub": stub_start >= 0,
        "has_proxy": proxy_start >= 0,
    }


def check(path):
    """Return (problems, notes, facts); problems are fatal for the gate."""
    with open(path, "r", encoding="utf-8") as src:
        text = src.read()

    problems = []
    notes = []
    facts = scan(text)
    names = facts["names"]

    if not names:
        problems.append("no TRANSACTION_* constant found: this does not look like IPrivTasks.java")
        return problems, notes, facts

    # (a) hand-written codes must stay declaration-ordered, or the check cannot reason at all.
    for name, init in zip(names, facts["initialisers"]):
        if init != "createTransactionCode()":
            problems.append(
                "TRANSACTION_%s is initialised with '%s' instead of createTransactionCode(),"
                " so its code no longer follows the declaration order" % (name, init)
            )
    duplicates = sorted({name for name in names if names.count(name) > 1})
    for name in duplicates:
        problems.append("TRANSACTION_%s is declared more than once" % name)

    # (a) constants <-> onTransact branches, one-to-one.
    branches = facts["branches"]
    if len(branches) != len(names):
        problems.append(
            "%d transaction constants but %d code == TRANSACTION_* branches in onTransact()"
            % (len(names), len(branches))
        )
    for index, name in enumerate(names):
        if name not in branches:
            problems.append(
                "onTransact() has no branch for TRANSACTION_%s (code FIRST_CALL_TRANSACTION + %d)"
                % (name, index)
            )
        elif branches.count(name) > 1:
            problems.append("onTransact() has more than one branch for TRANSACTION_%s" % name)
    for name in branches:
        if name not in names:
            problems.append(
                "onTransact() dispatches TRANSACTION_%s, which is not declared in Stub" % name
            )

    # (b)(d) append-only against the embedded baseline.
    baseline_set = set(BASELINE_TRANSACTIONS)
    new_names = [name for name in names if name not in baseline_set]
    for expected, name in enumerate(BASELINE_TRANSACTIONS):
        if name not in names:
            problems.append(
                "baseline transaction TRANSACTION_%s (code FIRST_CALL_TRANSACTION + %d)"
                " disappeared; the wire protocol is append-only" % (name, expected)
            )

    shifted = []
    for expected, name in enumerate(BASELINE_TRANSACTIONS):
        if name not in names:
            continue
        actual = names.index(name)
        if actual != expected:
            shifted.append((name, expected, actual))
    if shifted:
        first_name, first_expected, _ = shifted[0]
        found = names[first_expected] if first_expected < len(names) else "nothing"
        problems.append(
            "baseline order is broken at code FIRST_CALL_TRANSACTION + %d: the baseline expects"
            " TRANSACTION_%s but the file declares TRANSACTION_%s there"
            % (first_expected, first_name, found)
        )
        moved = ", ".join(
            "%s +%d->+%d" % (name, expected, actual) for name, expected, actual in shifted
        )
        problems.append("baseline transaction codes moved: " + moved)

    for name in new_names:
        actual = names.index(name)
        if actual < len(BASELINE_TRANSACTIONS):
            problems.append(
                "new transaction TRANSACTION_%s (code +%d) is declared inside the baseline; it must"
                " be appended after TRANSACTION_%s (index %d)"
                % (name, actual, BASELINE_TRANSACTIONS[-1], len(BASELINE_TRANSACTIONS))
            )
        else:
            notes.append(
                "appended TRANSACTION_%s = FIRST_CALL_TRANSACTION + %d (order is safe)"
                % (name, actual)
            )

    # (c) interface / Proxy / Stub completeness.
    interface_methods = facts["interface_methods"]
    proxy_methods = facts["proxy_methods"]
    if not facts["has_stub"]:
        problems.append("no 'abstract class Stub' found")
    if not interface_methods:
        problems.append("no interface method declarations found above the Stub class")
    if not facts["has_proxy"]:
        problems.append("no 'class Proxy implements IPrivTasks' found")

    if len(names) != len(interface_methods):
        problems.append(
            "%d transaction constants but %d interface methods: each transaction needs exactly one"
            " interface method" % (len(names), len(interface_methods))
        )

    if facts["has_proxy"]:
        for method in interface_methods:
            if method not in proxy_methods:
                problems.append(
                    "interface method %s() has no Proxy implementation (proxy calls fail)" % method
                )
        for method in proxy_methods:
            if method not in interface_methods and method not in INHERITED_METHODS:
                problems.append(
                    "Proxy implements %s(), which is not declared by IPrivTasks (missing interface"
                    " method?)" % method
                )

    stub_impls = facts["stub_impls"]
    for name in names:
        target = facts["dispatches"].get(name)
        if target is None:
            notes.append(
                "could not read the dispatch call of TRANSACTION_%s; verify it by hand" % name
            )
        elif target not in stub_impls:
            problems.append(
                "onTransact() branch for TRANSACTION_%s calls %s(data, reply), but Stub has no"
                " method %s(Parcel, Parcel)" % (name, target, target)
            )

    interface_set = set(interface_methods)
    for name in names:
        if name in interface_set or METHOD_ALIASES.get(name) in interface_set:
            continue
        notes.append(
            "transaction TRANSACTION_%s has no interface method of a matching name; verify the"
            " mapping by hand" % name
        )

    return problems, notes, facts


def main(argv=None):
    parser = argparse.ArgumentParser(
        description="Verify that the IPrivTasks Parcel protocol only ever grows at the end."
    )
    parser.add_argument(
        "--file",
        default=DEFAULT_FILE,
        metavar="PATH",
        help="IPrivTasks.java to check (default: %(default)s)",
    )
    args = parser.parse_args(argv)

    path = args.file
    if not os.path.isabs(path):
        if not os.path.isfile(path):
            path = os.path.join(REPO_ROOT, path)
        path = os.path.abspath(path)
    label = os.path.basename(path)
    try:
        rel = os.path.relpath(path, REPO_ROOT)
        shown = rel if not rel.startswith("..") else path
    except ValueError:  # different drive on Windows
        shown = path

    if not os.path.isfile(path):
        print("FAIL %s: not found" % path)
        return 1
    try:
        problems, notes, facts = check(path)
    except OSError as exc:
        print("FAIL %s: %s" % (path, exc))
        return 1

    names = facts.get("names", [])
    summary = (
        "%d transactions / %d onTransact branches / %d interface methods / %d Proxy methods"
        % (
            len(names),
            len(facts.get("branches", [])),
            len(facts.get("interface_methods", [])),
            len(facts.get("proxy_methods", [])),
        )
    )

    if problems:
        print("FAIL %s (%s)" % (label, shown))
        print("     " + summary)
        for problem in problems:
            print("     - %s" % problem)
        for note in notes:
            print("     * %s" % note)
        print(
            "     Transaction codes are wire protocol: append new TRANSACTION_* constants after the"
            " existing ones, keep onTransact()/Proxy in step, and append the name to"
            " BASELINE_TRANSACTIONS in this script once the append ships."
        )
        return 1

    print("ok   %s: %s" % (label, summary))
    new_names = [name for name in names if name not in set(BASELINE_TRANSACTIONS)]
    if new_names:
        print(
            "     baseline: %d names unchanged, %d appended: %s"
            % (len(BASELINE_TRANSACTIONS), len(new_names), ", ".join(new_names))
        )
    else:
        print(
            "     baseline: %d names unchanged, none appended (baseline compatible)"
            % len(BASELINE_TRANSACTIONS)
        )
        print(
            "     * nothing is appended after the baseline yet; if a new transaction is expected,"
            " it has not landed in this file"
        )
    for note in notes:
        print("     * %s" % note)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
