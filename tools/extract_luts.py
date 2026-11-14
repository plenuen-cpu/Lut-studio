#!/usr/bin/env python3
"""Extract the 57 film/camera 3D LUTs used by FilmCam from the Tonefinder 2.0.1 build.

The Tonefinder HTML embeds all 301 built-in presets as a single base64 blob:
  LUT_LORENZO_B64 -> zlib -> raw "Lorenzo residual" planes for 301 LUTs.
Each LUT is 17*17*17*3 bytes, stored as three channel planes
(plane = c*17^3), and the inverse-Lorenzo predictor reproduces the byte values
in the layout  index = ((b * 17 + g) * 17 + r) * 3 + c   (red fastest, then green, then blue).

FilmCam only ships the first 57 presets (idx 0..56):
  Kodak 24, Fujifilm 14, Agfa 7, Ilford 1  (46 films)
  Leica 6, Ricoh GR 5                      (11 camera look presets)
The VSCO (216) and phone-brand (28) banks are dropped.

Outputs
  app/src/main/assets/luts/presets.json       preset catalog + canister art colors
  app/src/main/assets/luts/lut_residuals.bin  zlib(residuals of idx 0..56)
  app/src/test/resources/lut_golden.json      per-LUT sha256 + sample triples (JVM test fixture)
"""
import argparse
import hashlib
import json
import os
import re
import sys
import zlib

SIZE = 17
PLANE = SIZE * SIZE * SIZE
LUT_LEN = PLANE * 3
KEEP_BRANDS = ["Kodak", "Fujifilm", "Agfa", "Ilford", "Leica", "Ricoh GR"]
KEEP_TOTAL = 57

# Short code printed vertically on the film canister, per preset code.
LABELS = {
    "KA1": "E100G", "KA3": "E100VS", "KA3X": "EC100", "KC25": "CP200",
    "KCP2": "P200", "KE1": "E100", "KG1": "G100", "KG2": "G200",
    "KK1": "K64", "KK2": "K200", "KP1": "160NC", "KP2": "160VC",
    "KP3": "400NC", "KP4": "400VC", "KP5": "400UC", "KP6": "P800",
    "KP7": "P160", "KP8": "P400", "KP9": "800P", "KT32": "T3200",
    "KU1": "U400", "KU4": "RG400", "KU8": "U800", "KX4": "TX400",
    "FA1": "A100F", "FF5": "F50", "FN16": "N1600", "FP1": "FP100C",
    "FP2": "160C", "FP4": "400H", "FP8": "800Z", "FR4": "R100",
    "FR4X": "R100X", "FS1": "S100", "FS4": "S400", "FS16": "S1600",
    "FT6": "T64", "FV5": "V50",
    "AGA1": "O100", "AGA2": "O200", "AGA3": "XPS160", "AU1": "U100",
    "AU5": "U50", "AV4": "V400", "AV8": "V800",
    "IH5": "HP5",
    "L1": "AUTH", "L2": "VIB", "L3": "BRASS", "L4": "CONT",
    "LM1": "MONO", "LM2": "MONO+",
    "GR1": "POSI", "GR2": "NEGA", "GR3": "BLEACH", "GR4": "CROSS", "GR5": "HICON",
}

# Canister body colours.  body/body2 = vertical gradient, band = label band,
# ink = text colour, cap = spool cap.
CANISTER = {
    # Kodak slide / Ektachrome: orange body, red band
    "KA1": ("#F2A33C", "#DE7A26", "#B01B23", "#FFFFFF"),
    "KA3": ("#F0A03A", "#D9731F", "#B01B23", "#FFFFFF"),
    "KA3X": ("#EDE7DA", "#D8CDB8", "#0B4EA2", "#12305C"),
    # Kodak consumer: Kodak yellow
    "KC25": ("#FFC72C", "#F0A400", "#C8102E", "#151515"),
    "KG1": ("#FFC72C", "#F2A400", "#C8102E", "#151515"),
    "KG2": ("#FFC72C", "#F2A400", "#C8102E", "#151515"),
    "KU1": ("#FFC72C", "#EFA000", "#1B1B1B", "#151515"),
    "KU4": ("#F7B733", "#DC8B12", "#1B1B1B", "#151515"),
    "KU8": ("#F5B02E", "#D9880F", "#1B1B1B", "#151515"),
    # Portra: pastel
    "KCP2": ("#F3D9BC", "#E0B98F", "#8B6A48", "#3B2A1D"),
    "KP1": ("#F1D6B8", "#DDB489", "#8B6A48", "#3B2A1D"),
    "KP2": ("#F5DCC2", "#E3BC93", "#8B6A48", "#3B2A1D"),
    "KP3": ("#F2D3B1", "#DCAF84", "#8B6A48", "#3B2A1D"),
    "KP4": ("#F6DCC0", "#E4BB92", "#8B6A48", "#3B2A1D"),
    "KP5": ("#F3D8BA", "#E1B78D", "#8B6A48", "#3B2A1D"),
    "KP6": ("#EBCDAA", "#D8AC80", "#7A5A3C", "#3B2A1D"),
    "KP7": ("#F1D5B6", "#DCB287", "#8B6A48", "#3B2A1D"),
    "KP8": ("#F4DABE", "#E0B78D", "#8B6A48", "#3B2A1D"),
    "KP9": ("#E9C9A4", "#D3A579", "#7A5A3C", "#3B2A1D"),
    # Ektar: saturated red-orange
    "KE1": ("#E8642C", "#C4401A", "#F5C518", "#FFFFFF"),
    # Kodachrome: yellow + red
    "KK1": ("#F5C518", "#E0A800", "#B01B23", "#1A1A1A"),
    "KK2": ("#F5C518", "#E0A800", "#B01B23", "#1A1A1A"),
    # Black & white Kodak
    "KT32": ("#232323", "#101010", "#E8E8E8", "#F2F2F2"),
    "KX4": ("#1C1C1C", "#0C0C0C", "#E8E8E8", "#F2F2F2"),
    # Fujifilm: Fuji green
    "FA1": ("#0F7A45", "#0A5A32", "#F2F2F2", "#FFFFFF"),
    "FF5": ("#0B6B3A", "#084E2A", "#F5C518", "#FFFFFF"),
    "FN16": ("#1B1B1B", "#0B0B0B", "#E8E8E8", "#F2F2F2"),
    "FP1": ("#117A4C", "#0B5734", "#F2F2F2", "#FFFFFF"),
    "FP2": ("#12794A", "#0C5936", "#F2F2F2", "#FFFFFF"),
    "FP4": ("#12805A", "#0B5A3E", "#F2F2F2", "#FFFFFF"),
    "FP8": ("#0F7350", "#0A5238", "#F2F2F2", "#FFFFFF"),
    "FR4": ("#0E7C4A", "#095733", "#F5C518", "#FFFFFF"),
    "FR4X": ("#0C6B4C", "#084A34", "#F5C518", "#FFFFFF"),
    "FS1": ("#118A4E", "#0B6135", "#F2F2F2", "#FFFFFF"),
    "FS4": ("#0F7C46", "#0A5730", "#F2F2F2", "#FFFFFF"),
    "FS16": ("#0D6E3E", "#094D2A", "#F2F2F2", "#FFFFFF"),
    "FT6": ("#12305C", "#0A1E3C", "#F2F2F2", "#FFFFFF"),
    "FV5": ("#0A3B2A", "#06281C", "#C9A227", "#FFFFFF"),
    # Agfa: red
    "AGA1": ("#D6001C", "#A80015", "#F2F2F2", "#FFFFFF"),
    "AGA2": ("#D6001C", "#A80015", "#F2F2F2", "#FFFFFF"),
    "AGA3": ("#C4001A", "#960013", "#F2F2F2", "#FFFFFF"),
    "AU1": ("#E00020", "#B00018", "#F5C518", "#FFFFFF"),
    "AU5": ("#E00020", "#B00018", "#F5C518", "#FFFFFF"),
    "AV4": ("#D6001C", "#A80015", "#F2F2F2", "#FFFFFF"),
    "AV8": ("#C4001A", "#960013", "#F2F2F2", "#FFFFFF"),
    # Ilford: black & white
    "IH5": ("#1A1A1A", "#0A0A0A", "#F2F2F2", "#F5F5F5"),
}

# Leica / Ricoh GR: brand badge instead of a canister.
BADGE = {
    "Leica": {"body": "#171717", "body2": "#0B0B0B", "band": "#E2001A", "ink": "#F5F5F5"},
    "Ricoh GR": {"body": "#141414", "body2": "#0A0A0A", "band": "#E4002B", "ink": "#F5F5F5"},
}


def load_html(path):
    with open(path, "r", encoding="utf-8") as fh:
        return fh.read()


def parse_meta(html):
    start = html.index("LUT_META = {")
    brace = html.index("{", start)
    depth = 0
    for i in range(brace, len(html)):
        ch = html[i]
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                return json.loads(html[brace:i + 1])
    raise RuntimeError("LUT_META not terminated")


def parse_residuals(html):
    import base64
    m = re.search(r'const LUT_LORENZO_B64 = "([^"]+)"', html)
    if not m:
        raise RuntimeError("LUT_LORENZO_B64 not found")
    raw = zlib.decompress(base64.b64decode(m.group(1)))
    return raw


def decode_lut(residuals, idx):
    base = idx * LUT_LEN
    out = bytearray(LUT_LEN)
    for c in range(3):
        c_off = base + c * PLANE
        for b in range(SIZE):
            for g in range(SIZE):
                for r in range(SIZE):
                    p = b * 289 + g * 17 + r
                    res = residuals[c_off + p]
                    v_r = out[((b * 289 + g * 17 + (r - 1)) * 3) + c] if r > 0 else 0
                    v_g = out[((b * 289 + (g - 1) * 17 + r) * 3) + c] if g > 0 else 0
                    v_b = out[(((b - 1) * 289 + g * 17 + r) * 3) + c] if b > 0 else 0
                    v_rg = out[((b * 289 + (g - 1) * 17 + (r - 1)) * 3) + c] if r > 0 and g > 0 else 0
                    v_rb = out[(((b - 1) * 289 + g * 17 + (r - 1)) * 3) + c] if r > 0 and b > 0 else 0
                    v_gb = out[(((b - 1) * 289 + (g - 1) * 17 + r) * 3) + c] if g > 0 and b > 0 else 0
                    v_rgb = out[(((b - 1) * 289 + (g - 1) * 17 + (r - 1)) * 3) + c] if r > 0 and g > 0 and b > 0 else 0
                    if r > 0 and g > 0 and b > 0:
                        pred = v_r + v_g + v_b - v_rg - v_rb - v_gb + v_rgb
                    elif r > 0 and g > 0:
                        pred = v_r + v_g - v_rg
                    elif r > 0 and b > 0:
                        pred = v_r + v_b - v_rb
                    elif g > 0 and b > 0:
                        pred = v_g + v_b - v_gb
                    elif r > 0:
                        pred = v_r
                    elif g > 0:
                        pred = v_g
                    elif b > 0:
                        pred = v_b
                    else:
                        pred = 0
                    out[p * 3 + c] = (res + pred) & 0xFF
    return bytes(out)


def display_name(item):
    name = item["name"]
    if " · " in name:
        name = name.split(" · ", 1)[1]
    name = name.replace("Fuji ", "Fujifilm ")
    return name


def build_catalog(meta):
    presets = []
    slot = 1
    for brand in KEEP_BRANDS:
        for item in meta.get(brand, []):
            code = item["code"]
            entry = {
                "slot": slot,
                "idx": item["idx"],
                "brand": brand,
                "cat": item["cat"],
                "code": code,
                "name": item["name"],
                "display": display_name(item),
                "label": LABELS.get(code, code),
            }
            if brand in BADGE:
                entry["art"] = "badge"
                entry["colors"] = dict(BADGE[brand])
            else:
                if code not in CANISTER:
                    raise SystemExit("missing canister colors for " + code)
                body, body2, band, ink = CANISTER[code]
                entry["art"] = "canister"
                entry["colors"] = {"body": body, "body2": body2, "band": band, "ink": ink}
            presets.append(entry)
            slot += 1
    return presets


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("html", help="Tonefinder 2.0.1 HTML build")
    ap.add_argument("--root", default=os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                    help="FilmCam repo root")
    args = ap.parse_args()

    html = load_html(args.html)
    meta = parse_meta(html)
    residuals = parse_residuals(html)
    if len(residuals) != 301 * LUT_LEN:
        raise SystemExit("unexpected residual size %d" % len(residuals))

    presets = build_catalog(meta)
    if len(presets) != KEEP_TOTAL:
        raise SystemExit("expected %d presets, got %d" % (KEEP_TOTAL, len(presets)))
    idxs = sorted(p["idx"] for p in presets)
    if idxs != list(range(KEEP_TOTAL)):
        raise SystemExit("preset idx values are not 0..56: %s" % idxs)

    kept = bytearray()
    for idx in range(KEEP_TOTAL):
        kept += residuals[idx * LUT_LEN:(idx + 1) * LUT_LEN]
    packed = zlib.compress(bytes(kept), 9)

    assets = os.path.join(args.root, "app", "src", "main", "assets", "luts")
    tests = os.path.join(args.root, "app", "src", "test", "resources")
    os.makedirs(assets, exist_ok=True)
    os.makedirs(tests, exist_ok=True)

    catalog = {
        "version": 1,
        "lutSize": SIZE,
        "residuals": "lut_residuals.bin",
        "residualsEncoding": "zlib",
        "source": "Tonefinder 2.0.1 built-in 3D LUTs (Lorenzo residual blob), presets 1-57",
        "presets": presets,
    }
    with open(os.path.join(assets, "presets.json"), "w", encoding="utf-8") as fh:
        json.dump(catalog, fh, ensure_ascii=False, indent=1)
        fh.write("\n")
    with open(os.path.join(assets, "lut_residuals.bin"), "wb") as fh:
        fh.write(packed)

    samples = [(0, 0, 0), (8, 8, 8), (16, 16, 16), (16, 0, 0), (0, 16, 0),
               (0, 0, 16), (4, 8, 12), (12, 6, 3)]
    golden = {"lutSize": SIZE, "presets": []}
    for p in presets:
        lut = decode_lut(residuals, p["idx"])
        entry = {
            "slot": p["slot"],
            "idx": p["idx"],
            "code": p["code"],
            "sha256": hashlib.sha256(lut).hexdigest(),
            "samples": [
                {"input": list(s), "output": list(lut[((s[2] * SIZE + s[1]) * SIZE + s[0]) * 3:][:3])}
                for s in samples
            ],
        }
        golden["presets"].append(entry)
    with open(os.path.join(tests, "lut_golden.json"), "w", encoding="utf-8") as fh:
        json.dump(golden, fh, indent=1)
        fh.write("\n")

    print("presets.json      : %d presets" % len(presets))
    for brand in KEEP_BRANDS:
        print("  %-10s %d" % (brand, len(meta[brand])))
    print("lut_residuals.bin : %d bytes (raw %d)" % (len(packed), len(kept)))
    print("lut_golden.json   : %d entries" % len(golden["presets"]))
    print("first sha256 %s" % golden["presets"][0]["sha256"])


if __name__ == "__main__":
    sys.exit(main())
