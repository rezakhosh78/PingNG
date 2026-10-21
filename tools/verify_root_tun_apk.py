#!/usr/bin/env python3
"""Verify the native files needed by the packaged HEV VPN and root modes."""

from __future__ import annotations

import argparse
import sys
import zipfile
from pathlib import Path


REQUIRED_BY_ABI = {
    "libhevsockstun.so": "standalone HEV executable for Root mode",
    "libhev-socks5-tunnel.so": "HEV JNI library for VpnService mode",
}
GO_RUNTIME_LIBRARIES = {
    "libpingng_psiphon.so",
    "libpingng_snowflake.so",
    "libpingng_bepass.so",
}


def verify(apk: Path) -> list[str]:
    failures: list[str] = []
    try:
        with zipfile.ZipFile(apk) as archive:
            names = set(archive.namelist())
    except (OSError, zipfile.BadZipFile) as error:
        return [f"{apk}: cannot read APK: {error}"]

    abis = sorted(
        name.split("/", 2)[1]
        for name in names
        if name.startswith("lib/") and len(name.split("/", 2)) == 3
    )
    abis = sorted(set(abis))
    if not abis:
        return [f"{apk}: APK contains no native-library ABI directories"]

    for abi in abis:
        for library, purpose in REQUIRED_BY_ABI.items():
            path = f"lib/{abi}/{library}"
            if path not in names:
                failures.append(f"{apk}: missing {path} ({purpose})")
        if not any(f"lib/{abi}/{name}" in names for name in GO_RUNTIME_LIBRARIES):
            failures.append(f"{apk}: no isolated Go runtime library packaged for {abi}")
    return failures


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("apk_path", type=Path, help="an APK file or a directory containing APKs")
    args = parser.parse_args()
    apks = [args.apk_path] if args.apk_path.is_file() else sorted(args.apk_path.rglob("*.apk"))
    if not apks:
        print(f"No APK files found under {args.apk_path}", file=sys.stderr)
        return 1

    failures = [failure for apk in apks for failure in verify(apk)]
    if failures:
        print("\n".join(failures), file=sys.stderr)
        return 1
    for apk in apks:
        print(f"Verified HEV JNI, HEV root executable, and isolated Go runtime: {apk}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
