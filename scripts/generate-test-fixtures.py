#!/usr/bin/env python3
"""Regenerates the small binary test fixtures checked into
src/test/resources/fixtures/ that aren't practical to hand-author as text
(JPEG with EXIF GPS data, SVG with an embedded <script>).

Larger/derived fixtures (multi-MB payloads, folder trees) are intentionally
NOT produced here -- they're generated at test time by
src/test/java/.../support/FixtureFiles.java so we don't commit multi-MB
binaries to git. This script only touches the handful of small, deterministic
fixtures that are cheap to keep as real files.

Usage: python scripts/generate-test-fixtures.py
"""
import pathlib
import struct

FIXTURES_DIR = pathlib.Path(__file__).resolve().parent.parent / "src" / "test" / "resources" / "fixtures"

# A minimal valid baseline JPEG (1x1 white pixel), used as the base image we
# splice an EXIF/GPS APP1 segment into.
_MINIMAL_JPEG_B64 = (
    "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAMCAgICAgMCAgIDAwMDBAYEBAQEBAgGBgUGCQgKCgkICQkKDA8MCgsOCwkJDRENDg8QEBEQCgwSExIQEw8QEBD/2wBDAQMDAwQDBAgEBAgQCwkLEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBD/wAARCAABAAEDASIAAhEBAxEB/8QAFQABAQAAAAAAAAAAAAAAAAAAAAj/xAAUEAEAAAAAAAAAAAAAAAAAAAAA/8QAFQEBAQAAAAAAAAAAAAAAAAAAAAX/xAAUEQEAAAAAAAAAAAAAAAAAAAAA/9oADAMBAAIRAxEAPwCdABmX/9k="
)


def _rational(numerator: int, denominator: int) -> bytes:
    return struct.pack("<II", numerator, denominator)


def build_gps_exif_segment() -> bytes:
    """Builds an APP1 EXIF segment (little-endian TIFF) whose IFD0 points at a
    GPS IFD encoding a fixed lat/long (Sofia, Bulgaria: 42.6977 N, 23.3219 E).
    Layout mirrors the real EXIF/TIFF spec closely enough for any EXIF
    reader (and QuickDrop's metadata-stripping check) to parse it.
    """
    tiff_header = b"II" + struct.pack("<H", 0x2A) + struct.pack("<I", 8)

    # IFD0: one entry -> GPSInfo IFD pointer (tag 0x8825, type LONG=4, count 1)
    ifd0_offset = 8
    gps_ifd_offset = ifd0_offset + 2 + 12 + 4  # after IFD0's 1 entry + next-IFD offset
    ifd0 = struct.pack("<H", 1)
    ifd0 += struct.pack("<HHI", 0x8825, 4, 1) + struct.pack("<I", gps_ifd_offset)
    ifd0 += struct.pack("<I", 0)  # no next IFD

    # GPS IFD: LatRef, Lat (3 rationals), LongRef, Long (3 rationals), next=0
    gps_entry_count = 4
    gps_ifd_header_size = 2 + gps_entry_count * 12 + 4
    data_area_offset = gps_ifd_offset + gps_ifd_header_size

    lat_ref = b"N\x00\x00\x00"
    lon_ref = b"E\x00\x00\x00"
    lat_deg, lat_min, lat_sec = 42, 41, 5172  # 42 deg 41' 51.72" N (sec scaled x100)
    lon_deg, lon_min, lon_sec = 23, 19, 1884  # 23 deg 19' 18.84" E

    lat_rationals = _rational(lat_deg, 1) + _rational(lat_min, 1) + _rational(lat_sec, 100)
    lon_rationals = _rational(lon_deg, 1) + _rational(lon_min, 1) + _rational(lon_sec, 100)
    lat_offset = data_area_offset
    lon_offset = lat_offset + len(lat_rationals)

    gps_ifd = struct.pack("<H", gps_entry_count)
    gps_ifd += struct.pack("<HHI", 0x0001, 2, 4) + lat_ref          # GPSLatitudeRef (ASCII)
    gps_ifd += struct.pack("<HHI", 0x0002, 5, 3) + struct.pack("<I", lat_offset)  # GPSLatitude
    gps_ifd += struct.pack("<HHI", 0x0003, 2, 4) + lon_ref          # GPSLongitudeRef (ASCII)
    gps_ifd += struct.pack("<HHI", 0x0004, 5, 3) + struct.pack("<I", lon_offset)  # GPSLongitude
    gps_ifd += struct.pack("<I", 0)  # no next IFD

    exif_payload = tiff_header + ifd0 + gps_ifd + lat_rationals + lon_rationals
    app1_body = b"Exif\x00\x00" + exif_payload
    app1_segment = b"\xff\xe1" + struct.pack(">H", len(app1_body) + 2) + app1_body
    return app1_segment


def write_gps_jpeg():
    import base64
    jpeg_bytes = base64.b64decode(_MINIMAL_JPEG_B64)
    assert jpeg_bytes[:2] == b"\xff\xd8", "base fixture is not a valid JPEG (missing SOI)"

    app1 = build_gps_exif_segment()
    # Insert the APP1/EXIF segment immediately after the SOI marker (FFD8),
    # before the existing APP0/JFIF segment -- this is where real cameras place it.
    out = jpeg_bytes[:2] + app1 + jpeg_bytes[2:]

    out_path = FIXTURES_DIR / "photo-with-gps-exif.jpg"
    out_path.write_bytes(out)
    print(f"wrote {out_path} ({len(out)} bytes)")


def write_xss_svg():
    svg = (
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        '<svg xmlns="http://www.w3.org/2000/svg" width="100" height="100">\n'
        '  <script>alert(document.domain)</script>\n'
        '  <rect width="100" height="100" fill="red" onload="alert(1)"/>\n'
        '</svg>\n'
    )
    out_path = FIXTURES_DIR / "xss-payload.svg"
    out_path.write_text(svg, encoding="utf-8")
    print(f"wrote {out_path} ({out_path.stat().st_size} bytes)")


if __name__ == "__main__":
    FIXTURES_DIR.mkdir(parents=True, exist_ok=True)
    write_gps_jpeg()
    write_xss_svg()
