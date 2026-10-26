#!/usr/bin/env python3
"""Check that release APKs package a real OpenVPN 3 Core ELF for each ABI."""

from __future__ import annotations

import argparse
import struct
import sys
import zipfile
from pathlib import Path


ABI_LAYOUT = {
    "arm64-v8a": (2, 183),
    "armeabi-v7a": (1, 40),
    "x86_64": (2, 62),
    "x86": (1, 3),
}
LIBRARY_NAME = "libovpn3.so"


def validate_elf(data: bytes, abi: str, apk_path: Path) -> None:
    if len(data) < 20 or data[:4] != b"\x7fELF":
        raise ValueError(f"{apk_path}: {abi}/{LIBRARY_NAME} is not an ELF library")
    elf_class, byte_order = data[4], data[5]
    if byte_order != 1:
        raise ValueError(f"{apk_path}: {abi}/{LIBRARY_NAME} is not little-endian")
    elf_type, machine = struct.unpack_from("<HH", data, 16)
    expected_class, expected_machine = ABI_LAYOUT[abi]
    if elf_class != expected_class or machine != expected_machine or elf_type != 3:
        raise ValueError(
            f"{apk_path}: wrong ELF type/ABI for {abi}: "
            f"class={elf_class}, type={elf_type}, machine={machine}"
        )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("apk_dir", type=Path, help="Directory containing built APK files")
    args = parser.parse_args()

    apks = sorted(args.apk_dir.glob("*.apk"))
    if not apks:
        print(f"No APK files found in {args.apk_dir}", file=sys.stderr)
        return 1

    packaged_abis: set[str] = set()
    for apk_path in apks:
        with zipfile.ZipFile(apk_path) as apk:
            found_in_apk = 0
            for abi in ABI_LAYOUT:
                entry = f"lib/{abi}/{LIBRARY_NAME}"
                try:
                    payload = apk.read(entry)
                except KeyError:
                    continue
                validate_elf(payload, abi, apk_path)
                found_in_apk += 1
                packaged_abis.add(abi)
            if found_in_apk == 0:
                raise ValueError(f"{apk_path}: OpenVPN 3 Core library is missing")

    missing = sorted(set(ABI_LAYOUT) - packaged_abis)
    if missing:
        print(f"Missing OpenVPN 3 Core for ABI(s): {', '.join(missing)}", file=sys.stderr)
        return 1

    print(f"Verified {LIBRARY_NAME} in APKs for: {', '.join(sorted(packaged_abis))}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
