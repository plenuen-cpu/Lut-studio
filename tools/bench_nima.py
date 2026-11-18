#!/usr/bin/env python3
"""Real-model cross-check for the Lumen 2.3 benchmark.

The Gradle benchmark (`EngineBenchmarkTest.v23BeatsV225OnTheSyntheticSet`) ranks candidates with a
proxy aesthetic score because an Android TFLite interpreter cannot run inside a JVM unit test. This
script closes that gap from the outside:

  1. reads the recipes the two engines chose (`app/build/engine-benchmark/benchmark-v23.json`),
  2. re-renders each frame with a NumPy copy of the same shader blocks the app uses,
  3. scores the original and both results with the **real** `nima_aesthetic.tflite` model that ships
     in the APK,
  4. writes `benchmark-nima.json` next to the other artefacts.

It is a check on the direction the engines moved in, not a second search: the recipes were already
chosen, so nothing here can make v2.3 look better or worse than it is. Rendering is a NumPy
approximation of the GLSL (tone, colour, region, local contrast) — labelled as such in the report.

Usage:
    python3 tools/bench_nima.py [--dir app/build/engine-benchmark]
"""

from __future__ import annotations

import argparse
import json
import math
from pathlib import Path

import numpy as np

SIZE = 224

# Param defaults, mirroring core/Params.kt (only what the benchmark recipes can contain).
DEFAULTS = {
    "exposure": 0.0, "contrast": 0.0, "highlights": 0.0, "shadows": 0.0,
    "whites": 0.0, "blacks": 0.0, "fade": 0.0,
    "temperature": 0.0, "tint": 0.0, "vibrance": 0.0, "saturation": 0.0,
    "colorHarmony": 0.0, "crossProcess": 0.0, "splitTone": 0.0, "splitBalance": 0.0,
    "skinToneClean": 0.0, "monochrome": 0.0,
    "clarity": 0.0, "texture": 0.0, "dehaze": 0.0, "sharpness": 0.0, "glow": 0.0,
    "halation": 0.0, "grain": 0.0, "vignette": 0.0, "subjectFocus": 0.0,
    "maskOn": 1.0,
    "subjectExposure": 0.0, "subjectSaturation": 0.0, "subjectWarmth": 0.0,
    "subjectClarity": 0.0, "subjectContrast": 0.0, "subjectStructure": 0.0,
    "subjectSkinProtect": 0.0,
    "backgroundExposure": 0.0, "backgroundSaturation": 0.0, "backgroundWarmth": 0.0,
    "backgroundClarity": 0.0, "backgroundSoft": 0.0, "backgroundHighlights": 0.0,
    "skyExposure": 0.0, "skySaturation": 0.0, "skyWarmth": 0.0, "skyBlue": 0.0, "skyDetail": 0.0,
    "toneShadowCurve": 0.0, "toneMidCurve": 0.0, "toneHighCurve": 0.0,
}


def read_ppm(path: Path) -> np.ndarray:
    with path.open("rb") as handle:
        magic = handle.readline().strip()
        if magic != b"P6":
            raise ValueError(f"{path}: not a binary PPM")
        line = handle.readline()
        while line.startswith(b"#"):
            line = handle.readline()
        width, height = (int(v) for v in line.split())
        maxval = int(handle.readline().strip())
        if maxval != 255:
            raise ValueError(f"{path}: unsupported max value {maxval}")
        data = np.frombuffer(handle.read(width * height * 3), dtype=np.uint8)
    return data.reshape(height, width, 3).astype(np.float32) / 255.0


def srgb_to_linear(x: np.ndarray) -> np.ndarray:
    return np.where(x <= 0.04045, x / 12.92, ((x + 0.055) / 1.055) ** 2.4)


def linear_to_srgb(x: np.ndarray) -> np.ndarray:
    x = np.maximum(x, 0.0)
    return np.where(x <= 0.0031308, x * 12.92, 1.055 * (x ** (1.0 / 2.4)) - 0.055)


def luma(rgb: np.ndarray) -> np.ndarray:
    return rgb[..., 0] * 0.2126 + rgb[..., 1] * 0.7152 + rgb[..., 2] * 0.0722


def smoothstep(edge0: float, edge1: float, x: np.ndarray) -> np.ndarray:
    t = np.clip((x - edge0) / (edge1 - edge0), 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)


def box_blur(image: np.ndarray, radius: int) -> np.ndarray:
    """Separable box blur via an integral image (same window as the Kotlin pipeline)."""
    if radius <= 0:
        return image.copy()
    window = 2 * radius + 1
    pad = np.pad(image, ((radius, radius), (radius, radius), (0, 0)), mode="edge")
    integral = np.zeros((pad.shape[0] + 1, pad.shape[1] + 1, 3), dtype=np.float64)
    integral[1:, 1:, :] = pad.astype(np.float64).cumsum(axis=0).cumsum(axis=1)
    ys = np.arange(SIZE)
    xs = np.arange(SIZE)
    y1 = ys + window
    x1 = xs + window
    block = (
        integral[np.ix_(y1, x1)]
        - integral[np.ix_(ys, x1)]
        - integral[np.ix_(y1, xs)]
        + integral[np.ix_(ys, xs)]
    )
    return (block / (window * window)).astype(np.float32)


def rgb_to_hsl(rgb: np.ndarray):
    maxc = rgb.max(axis=2)
    minc = rgb.min(axis=2)
    l = (maxc + minc) * 0.5
    d = maxc - minc
    with np.errstate(divide="ignore", invalid="ignore"):
        s = np.where(l > 0.5, d / np.maximum(2.0 - maxc - minc, 1e-6), d / np.maximum(maxc + minc, 1e-6))
        h = np.where(
            maxc == rgb[..., 0],
            ((rgb[..., 1] - rgb[..., 2]) / np.maximum(d, 1e-6) + np.where(rgb[..., 1] < rgb[..., 2], 6.0, 0.0)) / 6.0,
            np.where(
                maxc == rgb[..., 1],
                ((rgb[..., 2] - rgb[..., 0]) / np.maximum(d, 1e-6) + 2.0) / 6.0,
                ((rgb[..., 0] - rgb[..., 1]) / np.maximum(d, 1e-6) + 4.0) / 6.0,
            ),
        )
    h = np.where(d <= 1e-6, 0.0, h)
    return h, np.clip(s, 0.0, 1.0), l


def skin_weight(rgb: np.ndarray) -> np.ndarray:
    h, s, _ = rgb_to_hsl(np.clip(rgb, 0.0, 1.0))
    hue = h * 360.0
    w = smoothstep(8.0, 22.0, hue) * (1.0 - smoothstep(42.0, 62.0, hue))
    w = w * smoothstep(0.06, 0.16, s) * (1.0 - smoothstep(0.55, 0.85, s))
    return np.clip(w, 0.0, 1.0)


def render(src: np.ndarray, recipe: dict, subject: np.ndarray | None, sky: np.ndarray | None) -> np.ndarray:
    p = {**DEFAULTS, **recipe}
    rgb = src.copy()

    rgb = linear_to_srgb(srgb_to_linear(rgb) * (2.0 ** p["exposure"]))
    rgb = (rgb - 0.5) * (1.0 + p["contrast"] * 1.15) + 0.5
    l = luma(rgb)
    high = smoothstep(0.45, 1.0, l)[..., None]
    low = (1.0 - smoothstep(0.0, 0.55, l))[..., None]
    rgb = rgb + p["highlights"] * 0.38 * high * (1.0 - rgb) + p["shadows"] * 0.42 * low * rgb
    rgb = rgb + p["whites"] * 0.20 * smoothstep(0.62, 1.0, l)[..., None]
    rgb = rgb + p["blacks"] * 0.20 * (1.0 - smoothstep(0.0, 0.38, l))[..., None]
    faded = rgb * 0.80 + 0.10
    rgb = rgb + (faded - rgb) * p["fade"]

    if p["toneShadowCurve"] or p["toneMidCurve"] or p["toneHighCurve"]:
        tl = luma(rgb)
        w_shadow = 1.0 - smoothstep(0.0, 0.58, tl)
        w_mid = smoothstep(0.12, 0.50, tl) * (1.0 - smoothstep(0.50, 0.88, tl))
        w_high = smoothstep(0.52, 1.0, tl)
        curve = p["toneShadowCurve"] * w_shadow + p["toneMidCurve"] * w_mid + p["toneHighCurve"] * w_high
        rgb = rgb * (1.0 + curve * 0.28 * (1.0 - 0.45 * tl))[..., None]

    rgb[..., 0] *= 1.0 + p["temperature"] * 0.20
    rgb[..., 2] *= 1.0 - p["temperature"] * 0.20
    rgb[..., 1] *= 1.0 - p["tint"] * 0.14
    rgb[..., 0] *= 1.0 + p["tint"] * 0.05
    rgb[..., 2] *= 1.0 + p["tint"] * 0.05

    base = luma(rgb)[..., None]
    rgb = base + (rgb - base) * (1.0 + p["saturation"])
    chroma = rgb.max(axis=2) - rgb.min(axis=2)
    vib = (1.0 - smoothstep(0.0, 0.65, chroma))[..., None]
    base2 = luma(rgb)[..., None]
    rgb = base2 + (rgb - base2) * (1.0 + p["vibrance"] * vib)

    if p["clarity"] or p["texture"] or p["dehaze"]:
        wide = box_blur(rgb, 7)
        mid = box_blur(rgb, 2)
        clarity_mask = np.clip(1.0 - np.abs(luma(rgb) - 0.5) * 1.3, 0.0, 1.0)[..., None]
        rgb = rgb + (src - wide) * p["clarity"] * 0.85 * clarity_mask
        rgb = rgb + (src - mid) * p["texture"] * 0.90
        if p["dehaze"]:
            veil = src.min(axis=2)[..., None]
            rgb = (rgb - veil * p["dehaze"] * 0.30) / max(1.0 - p["dehaze"] * 0.18, 0.35)

    if p["vignette"]:
        ys, xs = np.mgrid[0:SIZE, 0:SIZE]
        radius = np.sqrt(((xs / SIZE) - 0.5) ** 2 + ((ys / SIZE) - 0.5) ** 2) * 2.0
        rgb = rgb * (1.0 - p["vignette"] * 0.60 * smoothstep(0.40, 1.15, radius))[..., None]

    if p["maskOn"] >= 0.5 and subject is not None:
        s_sub = np.clip(subject, 0.0, 1.0)
        s_sky = np.clip(sky, 0.0, 1.0) if sky is not None else np.zeros_like(s_sub)
        m_sky = s_sky * (1.0 - s_sub)
        m_bkg = np.clip(1.0 - s_sub - s_sky, 0.0, 1.0)
        skin_hold = skin_weight(rgb) * p["subjectSkinProtect"]
        sub_w = s_sub * (1.0 - skin_hold * 0.80)

        ev = p["subjectExposure"] * sub_w + p["backgroundExposure"] * m_bkg + p["skyExposure"] * m_sky
        rgb = linear_to_srgb(srgb_to_linear(np.clip(rgb, 0.0, 1.0)) * (2.0 ** (ev * 0.9))[..., None])
        warm = p["subjectWarmth"] * sub_w + p["backgroundWarmth"] * m_bkg + p["skyWarmth"] * m_sky
        rgb[..., 0] *= 1.0 + warm * 0.20
        rgb[..., 2] *= 1.0 - warm * 0.20
        sat = p["subjectSaturation"] * sub_w + p["backgroundSaturation"] * m_bkg + p["skySaturation"] * m_sky
        l2 = luma(rgb)[..., None]
        rgb = l2 + (rgb - l2) * (1.0 + sat)[..., None]

        if p["subjectContrast"]:
            k = 1.0 + p["subjectContrast"] * 0.55
            contrast_rgb = (rgb - 0.5) * k + 0.5
            rgb = rgb + (contrast_rgb - rgb) * sub_w[..., None]

        mid = box_blur(rgb, 2)
        wide = box_blur(rgb, 7)
        if p["subjectStructure"]:
            rgb = rgb + (src - mid) * (p["subjectStructure"] * 0.65 * sub_w)[..., None]
        clarity = p["subjectClarity"] * sub_w + p["backgroundClarity"] * m_bkg
        if np.any(clarity):
            rgb = rgb + (src - mid) * (clarity * 0.85)[..., None]
        if p["backgroundSoft"]:
            rgb = rgb - (src - wide) * (p["backgroundSoft"] * 1.35 * m_bkg)[..., None]
        if p["backgroundHighlights"]:
            bl = luma(rgb)
            bright = smoothstep(0.58, 1.0, bl) * m_bkg
            if p["backgroundHighlights"] > 0:
                drop = (p["backgroundHighlights"] * 0.42 * bright * bl)[..., None]
                rgb = rgb - drop
            else:
                lift = (-p["backgroundHighlights"] * 0.22 * bright)[..., None]
                rgb = rgb + (1.0 - rgb) * lift
        if p["skyDetail"]:
            rgb = rgb + (src - mid) * (p["skyDetail"] * 0.85 * m_sky)[..., None]
            rgb = rgb + (src - wide) * (p["skyDetail"] * 0.30 * m_sky)[..., None]
        if p["subjectSkinProtect"]:
            h, s, l3 = rgb_to_hsl(np.clip(rgb, 0.0, 1.0))
            target = np.clip(s, 0.14, 0.44)
            mixed = s + (target - s) * (skin_hold * 0.55)
            # HSL → RGB for the protected skin only (keep it simple: scale chroma around luma)
            luma_rgb = luma(rgb)[..., None]
            scale = np.where(s > 1e-6, mixed / np.maximum(s, 1e-6), 1.0)
            protected = luma_rgb + (rgb - luma_rgb) * scale[..., None]
            k = (skin_hold * 0.55)[..., None]
            rgb = rgb + (protected - rgb) * k

    return np.clip(rgb, 0.0, 1.0)


class Nima:
    def __init__(self, model_path: Path):
        from ai_edge_litert.interpreter import Interpreter

        self.interpreter = Interpreter(model_path=str(model_path))
        self.interpreter.allocate_tensors()
        self.input_index = self.interpreter.get_input_details()[0]["index"]
        self.output_index = self.interpreter.get_output_details()[0]["index"]

    def score(self, rgb: np.ndarray) -> float:
        x = np.clip(rgb, 0.0, 1.0) * 255.0
        # 앱의 NimaScores.toInput과 같다: 224×224, 채널별 [-1, 1].
        x = x.reshape(SIZE, SIZE, 3).astype(np.float32) / 127.5 - 1.0
        self.interpreter.set_tensor(self.input_index, x[None, ...])
        self.interpreter.invoke()
        probs = self.interpreter.get_tensor(self.output_index)[0].astype(np.float64)
        probs = np.clip(probs, 0, None)
        probs = probs / max(probs.sum(), 1e-9)
        return float((np.arange(1, 11) * probs).sum())


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dir", default="app/build/engine-benchmark")
    parser.add_argument("--model", default="app/src/main/assets/models/nima_aesthetic.tflite")
    args = parser.parse_args()

    root = Path(args.dir)
    data = json.loads((root / "benchmark-v23.json").read_text())
    nima = Nima(Path(args.model))

    rows = []
    for scene in data["scenes"]:
        name = scene["name"]
        original = read_ppm(root / "scenes" / f"{name}-original.ppm")
        mask = read_ppm(root / "scenes" / f"{name}-mask.ppm")
        subject = mask[..., 0]
        sky = mask[..., 1]
        legacy = render(original, scene["legacyRecipe"], subject, sky)
        v23 = render(original, scene["v23Recipe"], subject, sky)

        base_score = nima.score(original)
        legacy_score = nima.score(legacy)
        v23_score = nima.score(v23)

        def clip(frame: np.ndarray) -> float:
            l = luma(frame)
            return float(((l >= 250 / 255) | (frame.max(axis=2) >= 0.999)).mean())

        rows.append(
            {
                "name": name,
                "category": scene["category"],
                "nimaBase": round(base_score, 3),
                "nimaV225": round(legacy_score, 3),
                "nimaV230": round(v23_score, 3),
                "clipV225": round(clip(legacy), 4),
                "clipV230": round(clip(v23), 4),
                "deltaV225": round(legacy_score - base_score, 3),
                "deltaV230": round(v23_score - base_score, 3),
            }
        )
        print(
            f"{name:<14} NIMA {base_score:5.2f} → v2.2.5 {legacy_score:5.2f} ({legacy_score - base_score:+.2f}) "
            f"| v2.3 {v23_score:5.2f} ({v23_score - base_score:+.2f})"
        )

    def mean(values):
        return sum(values) / max(len(values), 1)

    summary = {
        "scenes": len(rows),
        "nimaBase": round(mean([r["nimaBase"] for r in rows]), 3),
        "nimaV225": round(mean([r["nimaV225"] for r in rows]), 3),
        "nimaV230": round(mean([r["nimaV230"] for r in rows]), 3),
        "deltaV225": round(mean([r["deltaV225"] for r in rows]), 3),
        "deltaV230": round(mean([r["deltaV230"] for r in rows]), 3),
        "clipV225": round(mean([r["clipV225"] for r in rows]), 4),
        "clipV230": round(mean([r["clipV230"] for r in rows]), 4),
        "v23BetterThanV225": sum(1 for r in rows if r["nimaV230"] > r["nimaV225"]),
        "v225BetterThanV23": sum(1 for r in rows if r["nimaV225"] > r["nimaV230"]),
        "bothAboveOriginal": sum(1 for r in rows if r["nimaV230"] > r["nimaBase"] and r["nimaV225"] > r["nimaBase"]),
        "model": str(args.model),
        "perScene": rows,
    }
    (root / "benchmark-nima.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2))
    print()
    print(
        f"평균 NIMA: 원본 {summary['nimaBase']} → v2.2.5 {summary['nimaV225']} "
        f"({summary['deltaV225']:+.3f}) / v2.3 {summary['nimaV230']} ({summary['deltaV230']:+.3f})"
    )
    print(
        f"v2.3가 더 높은 장면 {summary['v23BetterThanV225']}/{len(rows)}, "
        f"v2.2.5가 더 높은 장면 {summary['v225BetterThanV23']}/{len(rows)}, "
        f"평균 하이라이트 클리핑 {summary['clipV225'] * 100:.2f}% → {summary['clipV230'] * 100:.2f}%"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
