#!/usr/bin/env python3
"""Check actual ELF load alignment, plus uncompressed APK packaging, for Play's 16 KB rule."""
import pathlib
import struct
import sys
import zipfile

PAGE = 16384


def verify(path):
    count = 0
    with zipfile.ZipFile(path) as archive, open(path, "rb") as source:
        for entry in archive.infolist():
            if not entry.filename.endswith(".so"):
                continue
            data = archive.read(entry)
            assert data[:4] == b"\x7fELF", f"not ELF: {entry.filename}"
            assert data[5] in (1, 2), f"invalid ELF encoding: {entry.filename}"
            endian = "<" if data[5] == 1 else ">"
            if data[4] == 2:
                offset = struct.unpack_from(endian + "Q", data, 32)[0]
                size, number = struct.unpack_from(endian + "HH", data, 54)
                fmt, offset_index, address_index = "IIQQQQQQ", 2, 3
            else:
                assert data[4] == 1, f"invalid ELF class: {entry.filename}"
                offset = struct.unpack_from(endian + "I", data, 28)[0]
                size, number = struct.unpack_from(endian + "HH", data, 42)
                fmt, offset_index, address_index = "IIIIIIII", 1, 2
            assert size >= struct.calcsize(endian + fmt)
            loads = 0
            for index in range(number):
                header = struct.unpack_from(endian + fmt, data, offset + index * size)
                if header[0] != 1:  # PT_LOAD
                    continue
                loads += 1
                alignment = header[-1]
                assert alignment >= PAGE and alignment & (alignment - 1) == 0, (
                    f"ELF load alignment {alignment}, requires >= {PAGE}: {entry.filename}"
                )
                assert (header[offset_index] - header[address_index]) % PAGE == 0, (
                    f"ELF offset/address mismatch: {entry.filename}"
                )
            assert loads > 0, f"no ELF load segments: {entry.filename}"
            if pathlib.Path(path).suffix == ".apk" and entry.compress_type == zipfile.ZIP_STORED:
                source.seek(entry.header_offset)
                header = source.read(30)
                name_size, extra_size = struct.unpack_from("<HH", header, 26)
                assert (entry.header_offset + 30 + name_size + extra_size) % PAGE == 0, (
                    f"APK ZIP native payload is not 16 KB aligned: {entry.filename}"
                )
            count += 1
    assert count > 0, "no native libraries found"
    print(f"PASS {pathlib.Path(path).name}: {count} native libraries support 16 KB pages")


if __name__ == "__main__":
    assert len(sys.argv) > 1, "supply APK and/or AAB paths"
    for argument in sys.argv[1:]:
        verify(argument)
