#!/usr/bin/env python3
"""Repair the base64 asset parts, then reassemble and verify index.html.

The committed assetsrc/part*.b64 files were corrupted by a text-munging
round-trip (one character substituted, four characters dropped). This script
restores the exact bytes, then decodes + gunzips them and checks the result
against a known SHA-256 so a bad asset can never silently ship again.
"""
import base64
import gzip
import hashlib
import pathlib
import sys

EXPECTED_SHA256 = "89013ad30c4ef702ab9e6a6efeeba0c91b10c9d1e41cea955d842a56eaf75bd7"

root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".")
p0 = (root / "assetsrc/part0.b64").read_text()
p2 = (root / "assetsrc/part2.b64").read_text()

# Repo-coordinate offsets, applied from the END backwards so earlier
# offsets stay valid.
p0 = p0[:15668] + "g" + p0[15668:]
p0 = p0[:7660] + "a" + p0[7660:]
p0 = p0[:2255] + "K" + p0[2256:]
p2 = p2[:10170] + "a" + p2[10170:]
p2 = p2[:8886] + "A" + p2[8886:]

(root / "assetsrc/part0.b64").write_text(p0)
(root / "assetsrc/part2.b64").write_text(p2)

b64 = p0 + (root / "assetsrc/part1.b64").read_text() + p2
html = gzip.decompress(base64.b64decode(b64))
got = hashlib.sha256(html).hexdigest()
print("html bytes:", len(html))
print("sha256:", got)
if got != EXPECTED_SHA256:
    print("MISMATCH: asset does not match the expected build")
    sys.exit(1)
print("REPAIR OK")
