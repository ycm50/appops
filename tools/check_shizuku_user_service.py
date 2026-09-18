#!/usr/bin/env python3
"""Check that the Shizuku user service survived R8 in a built APK.

Shizuku starts a process of its own, loads ``com.mirfatif.permissionmanagerx.privs.PmxUserService``
out of our APK by name and instantiates it with reflection; it then calls it over AIDL from that
other process. R8 cannot see any of that, so in release builds (``isMinifyEnabled`` and
``isShrinkResources`` are only set for release) it deletes the whole implementation - leaving an
empty abstract class that only keeps the name - and deletes the AIDL ``Stub`` too. The user service
then never starts, every ``bindUserService()`` runs into Shizuku's start timeout, and the app
reports "Shizuku is not usable" although the permission is granted.

``app/proguard-rules.pro`` keeps those classes. This script checks that a built APK really matches,
so the same trap cannot come back silently. Run it on the release APK before shipping it:

    python3 tools/check_shizuku_user_service.py app/build/outputs/apk/release/app-release.apk
"""

import struct
import sys
import zipfile

SERVICE = "Lcom/mirfatif/permissionmanagerx/privs/PmxUserService;"
STUB = "Lcom/mirfatif/permissionmanagerx/privs/IPmxUserService$Stub;"
CONTEXT = "Landroid/content/Context;"

ACC_PUBLIC = 0x1
ACC_ABSTRACT = 0x400


def read_uleb(data, pos):
    result = 0
    shift = 0
    while True:
        byte = data[pos]
        pos += 1
        result |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return result, pos
        shift += 7


def read_string(data, pos):
    _, pos = read_uleb(data, pos)  # utf16 length, not needed
    end = data.index(b"\x00", pos)
    return data[pos:end].decode("utf-8", "replace")


class Dex:
    """Just enough of the DEX format to list classes and their declared methods."""

    def __init__(self, data):
        if data[:4] != b"dex\n":
            raise ValueError("not a dex file")
        self.data = data

        def u4(off):
            return struct.unpack_from("<I", data, off)[0]

        strings_size, strings_off = u4(56), u4(60)
        types_size, types_off = u4(64), u4(68)
        protos_size, protos_off = u4(72), u4(76)
        methods_size, methods_off = u4(88), u4(92)
        class_defs_size, class_defs_off = u4(96), u4(100)

        strings = [read_string(data, u4(strings_off + 4 * i)) for i in range(strings_size)]
        self.types = [strings[u4(types_off + 4 * i)] for i in range(types_size)]

        proto_return = [u4(protos_off + 12 * i + 4) for i in range(protos_size)]
        proto_params = []
        for i in range(protos_size):
            params_off = u4(protos_off + 12 * i + 8)
            if params_off == 0:
                proto_params.append("")
                continue
            count = u4(params_off)
            names = [
                self.types[struct.unpack_from("<H", data, params_off + 4 + 2 * k)[0]]
                for k in range(count)
            ]
            proto_params.append("".join(names))

        method_name = [strings[u4(methods_off + 8 * i + 4)] for i in range(methods_size)]
        method_proto = [struct.unpack_from("<H", data, methods_off + 8 * i + 2)[0] for i in range(methods_size)]
        self.methods = [
            (method_name[i], proto_params[method_proto[i]], self.types[proto_return[method_proto[i]]])
            for i in range(methods_size)
        ]

        self.classes = {}
        for c in range(class_defs_size):
            off = class_defs_off + 32 * c
            class_idx = u4(off)
            access = u4(off + 4)
            class_data_off = u4(off + 24)
            entry = {"access": access, "methods": []}

            if class_data_off:
                pos = class_data_off
                static_fields, pos = read_uleb(data, pos)
                instance_fields, pos = read_uleb(data, pos)
                direct_methods, pos = read_uleb(data, pos)
                virtual_methods, pos = read_uleb(data, pos)
                for _ in range(static_fields + instance_fields):
                    _, pos = read_uleb(data, pos)
                    _, pos = read_uleb(data, pos)
                # direct and virtual methods are two separate lists; in each one the index is a diff
                # against the previous method, and the first entry holds it directly.
                for count in (direct_methods, virtual_methods):
                    method_idx = 0
                    for _ in range(count):
                        diff, pos = read_uleb(data, pos)
                        method_access, pos = read_uleb(data, pos)
                        code_off, pos = read_uleb(data, pos)
                        method_idx += diff
                        entry["methods"].append((method_idx, method_access, code_off))

            self.classes[self.types[class_idx]] = entry


def load_dex_files(apk_path):
    with zipfile.ZipFile(apk_path) as apk:
        names = sorted(n for n in apk.namelist() if n.endswith(".dex"))
        if not names:
            raise ValueError("no dex file inside %s" % apk_path)
        return [(name, Dex(apk.read(name))) for name in names]


def find_class(dex_files, descriptor):
    for _, dex in dex_files:
        if descriptor in dex.classes:
            return dex, dex.classes[descriptor]
    return None, None


def method_names(dex, entry):
    names = []
    for idx, access, _ in entry["methods"]:
        name, params, _ = dex.methods[idx]
        names.append(("%s(%s)" % (name, params), access))
    return names


def check_apk(apk_path):
    problems = []
    dex_files = load_dex_files(apk_path)

    dex, service = find_class(dex_files, SERVICE)
    if service is None:
        problems.append("%s is missing from the APK" % SERVICE)
    else:
        if service["access"] & ACC_ABSTRACT:
            problems.append("%s is abstract (nothing left to instantiate)" % SERVICE)
        if not service["access"] & ACC_PUBLIC:
            problems.append("%s is not public (Shizuku reflects on it)" % SERVICE)

        methods = dict(method_names(dex, service))
        constructors = [m for m in methods if m.startswith("<init>(")]
        if not any(m == "<init>()" for m in constructors) and not any(
            m == "<init>(%s)" % CONTEXT for m in constructors
        ):
            problems.append(
                "%s has no public no-arg / Context constructor (found: %s)"
                % (SERVICE, ", ".join(constructors) or "none")
            )
        for expected in ("destroy()", "runCommand(%s)" % "Ljava/lang/String;",
                         "startDaemon(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)"):
            if expected not in methods:
                problems.append("%s has no %s" % (SERVICE, expected))
            elif not methods[expected] & ACC_PUBLIC:
                problems.append("%s.%s is not public" % (SERVICE, expected))

    dex, stub = find_class(dex_files, STUB)
    if stub is None:
        problems.append("%s is missing (the AIDL dispatch Shizuku needs)" % STUB)
    else:
        methods = dict(method_names(dex, stub))
        if "onTransact(IILandroid/os/Parcel;Landroid/os/Parcel;I)" not in methods and not any(
            m.startswith("onTransact(") for m in methods
        ):
            problems.append("%s has no onTransact (Shizuku cannot call the service)" % STUB)
        if not any(m.startswith("<init>(") for m in methods):
            problems.append("%s has no constructor" % STUB)

    return problems


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2

    failed = False
    for apk_path in argv[1:]:
        try:
            problems = check_apk(apk_path)
        except Exception as exc:  # noqa: BLE001 - report anything as a check failure
            print("FAIL %s: %s" % (apk_path, exc))
            failed = True
            continue

        if problems:
            failed = True
            print("FAIL %s" % apk_path)
            for problem in problems:
                print("     - %s" % problem)
            print(
                "     R8 removed the Shizuku user service. Check the -keep rules for"
                " com.mirfatif.permissionmanagerx.privs in app/proguard-rules.pro."
            )
        else:
            print("ok   %s: Shizuku user service is intact" % apk_path)

    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
