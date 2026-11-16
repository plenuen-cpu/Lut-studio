package com.plenuen.lumen.gl

/**
 * The single GLES 3.0 program used by the preview and by the full-resolution export.
 *
 * One shader keeps the two paths honest: what the user sees is exactly what is written to the
 * exported file, only at a different size. Every recipe parameter is applied here, in the order a
 * raw developer would apply it (exposure → tone → colour → detail → LUT → vignette/grain), on the
 * read-only source texture.
 */
object ShaderSource {

    const val VERTEX = """#version 300 es
layout(location = 0) in vec2 aPos;
layout(location = 1) in vec2 aUv;
out vec2 vUv;
void main() {
    vUv = aUv;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
"""

    /**
     * Uniform names are bound by [PhotoProgram]; the geometry block mirrors
     * [com.plenuen.lumen.geom.GeometryMath.sourceUv] line for line.
     */
    const val FRAGMENT = """#version 300 es
precision highp float;
precision highp sampler3D;

in vec2 vUv;
out vec4 fragColor;

uniform sampler2D uSrc;
uniform sampler3D uLut;

uniform vec2 uTexel;          // 1 / source size
uniform float uAspect;        // source width / height
uniform float uStraighten;    // radians, positive corrects a clockwise tilt
uniform float uCropScale;     // >= 1, keeps the corners inside the source
uniform float uVerticalTilt;  // -1..1 keystone

uniform float uExposure;
uniform float uContrast;
uniform float uHighlights;
uniform float uShadows;
uniform float uWhites;
uniform float uBlacks;
uniform float uFade;

uniform float uTemperature;
uniform float uTint;
uniform float uVibrance;
uniform float uSaturation;
uniform float uHarmony;
uniform float uCross;
uniform float uSplitTone;
uniform float uSplitBalance;
uniform float uShadowHue;     // 0..1
uniform float uHighlightHue;  // 0..1
uniform float uSkin;
uniform float uMono;

uniform float uClarity;
uniform float uTextureAmt;
uniform float uDehaze;
uniform float uSharpnessAmt;
uniform float uGlow;
uniform float uHalation;
uniform float uGrain;
uniform float uVignette;
uniform float uFocus;

uniform float uLutIntensity;
uniform float uLutEnabled;
uniform float uCompare;

// 세로 비교: vUv.x = 0 이 사진의 왼쪽 열이므로 그보다 왼쪽은 원본, 오른쪽은 보정 결과를 보여 준다.
// GL 뷰포트가 사진이 들어앉는 사각형과 정확히 같으므로 vUv는 사진 좌표계와 1:1이다(오버레이와 같은 기준).
uniform float uSplitEnabled;   // 0 = 끔, 1 = 세로 비교
uniform float uSplitX;         // 0..1, 사진 왼쪽에서부터의 분할 위치

// ───── 영역 보정: R = 피사체, G = 하늘 (B는 예약) ─────────────────
uniform sampler2D uMask;
uniform float uMaskOn;        // 0 = 영역 보정 끔, 1 = 켬
uniform float uMaskPreview;   // 1 = 마스크를 색으로 보여 준다
uniform float uSubExposure;   // 피사체
uniform float uSubSaturation;
uniform float uSubWarmth;
uniform float uSubClarity;
uniform float uBkgExposure;   // 배경
uniform float uBkgSaturation;
uniform float uBkgWarmth;
uniform float uBkgClarity;
uniform float uBkgSoft;
uniform float uSkyExposure;   // 하늘
uniform float uSkySaturation;
uniform float uSkyWarmth;
uniform float uSkyBlue;

// ───── 8색 HSL: vec3(색조, 채도, 밝기) × 8 대역 ─────────────────
uniform float uHslActive;     // 0 = 8색 손대지 않음
uniform vec3 uBandHSL[8];

float luma(vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

vec3 srgbToLinear(vec3 c) {
    vec3 lo = c / 12.92;
    vec3 hi = pow((max(c, vec3(0.0)) + 0.055) / 1.055, vec3(2.4));
    return mix(lo, hi, step(vec3(0.04045), c));
}

vec3 linearToSrgb(vec3 c) {
    c = max(c, vec3(0.0));
    vec3 lo = c * 12.92;
    vec3 hi = 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055;
    return mix(lo, hi, step(vec3(0.0031308), c));
}

vec3 rgb2hsv(vec3 c) {
    vec4 k = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
    vec4 p = mix(vec4(c.bg, k.wz), vec4(c.gb, k.xy), step(c.b, c.g));
    vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));
    float d = q.x - min(q.w, q.y);
    float e = 1.0e-10;
    return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);
}

vec3 hsv2rgb(vec3 c) {
    vec4 k = vec4(1.0, 2.0 / 3.0, 1.0 / 3.0, 3.0);
    vec3 p = abs(fract(c.xxx + k.xyz) * 6.0 - k.www);
    return c.z * mix(k.xxx, clamp(p - k.xxx, 0.0, 1.0), c.y);
}

vec3 hueColor(float hue) {
    return hsv2rgb(vec3(fract(hue), 1.0, 1.0));
}

// ── HSL (8색 대역용). RGB↔HSL은 Kotlin의 HslBands와 같은 식이다. ──
vec3 rgb2hsl(vec3 c) {
    float maxc = max(max(c.r, c.g), c.b);
    float minc = min(min(c.r, c.g), c.b);
    float l = (maxc + minc) * 0.5;
    float d = maxc - minc;
    if (d <= 1.0e-6) return vec3(0.0, 0.0, l);
    float s = l > 0.5 ? d / max(2.0 - maxc - minc, 1.0e-6) : d / max(maxc + minc, 1.0e-6);
    float h;
    if (maxc == c.r) {
        h = (c.g - c.b) / d + (c.g < c.b ? 6.0 : 0.0);
    } else if (maxc == c.g) {
        h = (c.b - c.r) / d + 2.0;
    } else {
        h = (c.r - c.g) / d + 4.0;
    }
    return vec3(fract(h / 6.0), clamp(s, 0.0, 1.0), l);
}

float hue2rgb(float p, float q, float t) {
    float v = t;
    if (v < 0.0) v += 1.0;
    if (v > 1.0) v -= 1.0;
    if (v < 1.0 / 6.0) return p + (q - p) * 6.0 * v;
    if (v < 0.5) return q;
    if (v < 2.0 / 3.0) return p + (q - p) * (2.0 / 3.0 - v) * 6.0;
    return p;
}

vec3 hsl2rgb(vec3 hsl) {
    if (hsl.y <= 1.0e-6) return vec3(hsl.z);
    float q = hsl.z < 0.5 ? hsl.z * (1.0 + hsl.y) : hsl.z + hsl.y - hsl.z * hsl.y;
    float p = 2.0 * hsl.z - q;
    return vec3(
        hue2rgb(p, q, hsl.x + 1.0 / 3.0),
        hue2rgb(p, q, hsl.x),
        hue2rgb(p, q, hsl.x - 1.0 / 3.0)
    );
}

const float BAND_CENTERS[8] = float[8](0.0, 30.0, 60.0, 120.0, 180.0, 240.0, 285.0, 330.0);

float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

float hueDelta(float a, float b) {
    float d = b - a;
    if (d > 0.5) d -= 1.0;
    if (d < -0.5) d += 1.0;
    return d;
}

// 코사인 창: 대역 중심에서 1, 이웃 중심에서 0. 이웃끼리 보완이라 경계가 끊기지 않는다.
float bandWeight(float hueDeg, float center, float prevGap, float nextGap) {
    float d = hueDelta(center / 360.0, hueDeg / 360.0) * 360.0;
    float gap = d < 0.0 ? prevGap : nextGap;
    if (gap <= 1.0e-3 || abs(d) >= gap) return 0.0;
    return 0.5 * (1.0 + cos(3.14159265 * abs(d) / gap));
}

float nearestAnchor(float h, out float distanceOut) {
    float best = 0.03;
    float bd = abs(hueDelta(h, 0.03));
    float d2 = abs(hueDelta(h, 0.12));
    if (d2 < bd) { bd = d2; best = 0.12; }
    float d3 = abs(hueDelta(h, 0.55));
    if (d3 < bd) { bd = d3; best = 0.55; }
    float d4 = abs(hueDelta(h, 0.72));
    if (d4 < bd) { bd = d4; best = 0.72; }
    distanceOut = bd;
    return best;
}

vec2 sourceUv(vec2 uv) {
    vec2 p = uv - 0.5;
    p.x *= uAspect;
    p /= max(uCropScale, 1.0);
    float c = cos(uStraighten);
    float s = sin(uStraighten);
    vec2 r = vec2(c * p.x - s * p.y, s * p.x + c * p.y);
    r.x *= 1.0 + uVerticalTilt * r.y * 0.45;
    return clamp(vec2(r.x / uAspect, r.y) + 0.5, vec2(0.0), vec2(1.0));
}

vec3 tap(vec2 uv, vec2 offset) {
    return texture(uSrc, clamp(uv + offset, vec2(0.0), vec2(1.0))).rgb;
}

// eight taps; radius is in 1/1000 of the frame, so preview and export look the same
vec3 blur(vec2 uv, float radius) {
    vec2 o = vec2(radius / 1000.0);
    vec3 sum = tap(uv, vec2(-o.x, 0.0));
    sum += tap(uv, vec2(o.x, 0.0));
    sum += tap(uv, vec2(0.0, -o.y));
    sum += tap(uv, vec2(0.0, o.y));
    sum += tap(uv, vec2(-o.x, -o.y) * 0.7071);
    sum += tap(uv, vec2(o.x, -o.y) * 0.7071);
    sum += tap(uv, vec2(-o.x, o.y) * 0.7071);
    sum += tap(uv, vec2(o.x, o.y) * 0.7071);
    return sum / 8.0;
}

// blurred bright pass, used for glow and halation
vec3 brightBlur(vec2 uv, float radius, float threshold) {
    float scale = 1.0 / max(1.0 - threshold, 1.0e-3);
    vec2 o = vec2(radius / 1000.0);
    vec3 sum = vec3(0.0);
    sum += max(tap(uv, vec2(-o.x, 0.0)) - vec3(threshold), vec3(0.0));
    sum += max(tap(uv, vec2(o.x, 0.0)) - vec3(threshold), vec3(0.0));
    sum += max(tap(uv, vec2(0.0, -o.y)) - vec3(threshold), vec3(0.0));
    sum += max(tap(uv, vec2(0.0, o.y)) - vec3(threshold), vec3(0.0));
    sum += max(tap(uv, vec2(-o.x, -o.y) * 0.7071) - vec3(threshold), vec3(0.0));
    sum += max(tap(uv, vec2(o.x, -o.y) * 0.7071) - vec3(threshold), vec3(0.0));
    sum += max(tap(uv, vec2(-o.x, o.y) * 0.7071) - vec3(threshold), vec3(0.0));
    sum += max(tap(uv, vec2(o.x, o.y) * 0.7071) - vec3(threshold), vec3(0.0));
    return sum * (scale / 8.0);
}

void main() {
    vec2 uv = sourceUv(vUv);
    vec3 original = texture(uSrc, uv).rgb;

    if (uCompare > 0.5) {
        fragColor = vec4(original, 1.0);
        return;
    }

    // ── 1. 노출 (linear light) ────────────────────────────────────────
    vec3 lin = srgbToLinear(original);
    lin *= exp2(uExposure);
    vec3 c = linearToSrgb(lin);

    // ── 2. 기본 톤 ────────────────────────────────────────────────────
    c = (c - 0.5) * (1.0 + uContrast * 1.15) + 0.5;
    float l = luma(c);
    float highMask = smoothstep(0.45, 1.0, l);
    float lowMask = 1.0 - smoothstep(0.0, 0.55, l);
    c += uHighlights * 0.38 * highMask * (1.0 - c);
    c += uShadows * 0.42 * lowMask * c;
    c += uWhites * 0.20 * smoothstep(0.62, 1.0, l);
    c += uBlacks * 0.20 * (1.0 - smoothstep(0.0, 0.38, l));
    c = mix(c, c * 0.80 + 0.10, uFade);

    // ── 3. 색온도·틴트 ────────────────────────────────────────────────
    c.r *= 1.0 + uTemperature * 0.20;
    c.b *= 1.0 - uTemperature * 0.20;
    c.g *= 1.0 - uTint * 0.14;
    c.r *= 1.0 + uTint * 0.05;
    c.b *= 1.0 + uTint * 0.05;

    // ── 4. 채도·활기 ──────────────────────────────────────────────────
    float baseLuma = luma(c);
    c = mix(vec3(baseLuma), c, 1.0 + uSaturation);
    float chroma = max(max(c.r, c.g), c.b) - min(min(c.r, c.g), c.b);
    float vibWeight = 1.0 - smoothstep(0.0, 0.65, chroma);
    c = mix(vec3(luma(c)), c, 1.0 + uVibrance * vibWeight);

    // ── 5. 색 정돈 (색상 수 감소) ─────────────────────────────────────
    if (abs(uHarmony) > 0.001) {
        vec3 hsv = rgb2hsv(clamp(c, 0.0, 1.0));
        float distOut = 0.0;
        float anchor = nearestAnchor(hsv.x, distOut);
        float pull = uHarmony * 0.45 * smoothstep(0.0, 0.10, distOut);
        hsv.x = fract(hsv.x + hueDelta(hsv.x, anchor) * pull);
        hsv.y *= 1.0 - uHarmony * 0.10 * smoothstep(0.0, 0.10, distOut);
        c = hsv2rgb(hsv);
    }

    // ── 6. 색 교차 (cross process) ────────────────────────────────────
    float xl = luma(c);
    c.g += uCross * 0.060 * (1.0 - xl);
    c.b += uCross * 0.050 * (1.0 - xl);
    c.r += uCross * 0.055 * xl;
    c.g += uCross * 0.020 * xl;

    // ── 7. 섀도우·하이라이트 색 분리 ───────────────────────────────────
    if (uSplitTone > 0.001) {
        float sl = luma(c);
        float shadowW = 1.0 - smoothstep(0.0, 0.55 + uSplitBalance * 0.20, sl);
        float highW = smoothstep(0.45 - uSplitBalance * 0.20, 1.0, sl);
        vec3 shadowHue = hueColor(uShadowHue);
        vec3 highlightHue = hueColor(uHighlightHue);
        c += (shadowHue - vec3(luma(shadowHue))) * uSplitTone * 0.32 * shadowW;
        c += (highlightHue - vec3(luma(highlightHue))) * uSplitTone * 0.26 * highW;
    }

    // ── 8. 피부톤 정리 ────────────────────────────────────────────────
    if (uSkin > 0.001) {
        vec3 skinHsv = rgb2hsv(clamp(c, 0.0, 1.0));
        float hueDeg = skinHsv.x * 360.0;
        float skinMask = smoothstep(8.0, 22.0, hueDeg) * (1.0 - smoothstep(42.0, 62.0, hueDeg));
        skinMask *= smoothstep(0.06, 0.16, skinHsv.y) * (1.0 - smoothstep(0.55, 0.85, skinHsv.y));
        vec3 fineBlur = blur(uv, 1.6);
        c -= (original - fineBlur) * 0.55 * uSkin * skinMask;
        c = mix(c, vec3(luma(c)), 0.06 * uSkin * skinMask);
    }

    // ── 9. 디테일·질감 ────────────────────────────────────────────────
    vec3 wide = blur(uv, 7.0);
    vec3 mid = blur(uv, 2.2);
    vec3 fine = blur(uv, 1.0);
    float clarityMask = clamp(1.0 - abs(luma(c) - 0.5) * 1.3, 0.0, 1.0);
    c += (original - wide) * uClarity * 0.85 * clarityMask;
    c += (original - mid) * uTextureAmt * 0.90;
    c += (original - fine) * uSharpnessAmt * 1.30;

    // 안개 제거: 가장 어두운 채널이 덮고 있는 막을 걷어낸다
    if (abs(uDehaze) > 0.001) {
        float veil = min(min(original.r, original.g), original.b);
        c = (c - veil * uDehaze * 0.30) / max(1.0 - uDehaze * 0.18, 0.35);
    }

    // ── 10. 글로우·할레이션 ───────────────────────────────────────────
    if (uGlow > 0.001) {
        c += brightBlur(uv, 9.0, 0.70) * uGlow * 0.55;
    }
    if (uHalation > 0.001) {
        vec3 halo = brightBlur(uv, 13.0, 0.72);
        float haloEnergy = luma(halo);
        c += vec3(1.0, 0.40, 0.22) * haloEnergy * uHalation * 0.55;
    }

    // ── 11. 흑백 ─────────────────────────────────────────────────────
    if (uMono > 0.001) {
        float mono = dot(c, vec3(0.32, 0.60, 0.08));
        c = mix(c, vec3(mono), uMono);
    }

    // ── 12. 피사체 강조 ───────────────────────────────────────────────
    if (uFocus > 0.001) {
        vec2 d = (vUv - 0.5) * vec2(uAspect, 1.0);
        float radius = length(d) / max(uAspect, 1.0) * 2.0;
        float inside = 1.0 - smoothstep(0.45, 0.95, radius);
        c += (original - mid) * uFocus * 0.55 * inside;
        c *= 1.0 - uFocus * 0.22 * smoothstep(0.65, 1.20, radius);
    }

    // ── 13. 3D LUT ───────────────────────────────────────────────────
    if (uLutEnabled > 0.5 && uLutIntensity > 0.001) {
        vec3 pos = clamp(c, 0.0, 1.0) * (16.0 / 17.0) + (0.5 / 17.0);
        vec3 lutColor = texture(uLut, pos).rgb;
        c = mix(c, lutColor, uLutIntensity);
    }

    // ── 13.5 8색 HSL 핀셋 ───────────────────────────────────────────
    //   대역마다 색조 ±30°, 채도 배율, 밝기 이동. 회색은 건드리지 않는다.
    if (uHslActive > 0.5) {
        vec3 hsl = rgb2hsl(clamp(c, 0.0, 1.0));
        float hueDeg = hsl.x * 360.0;
        float grey = smoothstep(0.03, 0.16, hsl.y);
        float dh = 0.0;
        float ds = 0.0;
        float dl = 0.0;
        for (int i = 0; i < 8; i++) {
            float center = BAND_CENTERS[i];
            float prevCenter = BAND_CENTERS[(i + 7) % 8];
            float nextCenter = BAND_CENTERS[(i + 1) % 8];
            float prevGap = fract((center - prevCenter) / 360.0) * 360.0;
            float nextGap = fract((nextCenter - center) / 360.0) * 360.0;
            float w = bandWeight(hueDeg, center, prevGap, nextGap);
            if (w <= 0.0) continue;
            vec3 band = uBandHSL[i];
            dh += w * band.x;
            ds += w * band.y;
            dl += w * band.z;
        }
        if (abs(dh) + abs(ds) + abs(dl) > 0.001) {
            hsl.x = fract(hsl.x + dh * (30.0 / 360.0) * grey);
            hsl.y = clamp(hsl.y * (1.0 + ds * 0.85 * grey), 0.0, 1.0);
            float mid = 1.0 - abs(2.0 * hsl.z - 1.0) * 0.5;
            hsl.z = clamp(hsl.z + dl * 0.16 * grey * mid, 0.0, 1.0);
            c = hsl2rgb(hsl);
        }
    }

    // ── 14. 영역별 보정 (피사체 · 배경 · 하늘) ─────────────────────────
    if (uMaskOn > 0.5) {
        vec3 maskSample = texture(uMask, uv).rgb;
        float mSub = clamp(maskSample.r, 0.0, 1.0);
        float mSky = clamp(maskSample.g, 0.0, 1.0) * (1.0 - mSub);
        float mBkg = clamp(1.0 - mSub - mSky, 0.0, 1.0);

        float ev = uSubExposure * mSub + uBkgExposure * mBkg + uSkyExposure * mSky;
        if (abs(ev) > 0.001) {
            c = linearToSrgb(srgbToLinear(clamp(c, 0.0, 1.0)) * exp2(ev * 0.9));
        }
        float warm = uSubWarmth * mSub + uBkgWarmth * mBkg + uSkyWarmth * mSky;
        if (abs(warm) > 0.001) {
            c.r *= 1.0 + warm * 0.20;
            c.b *= 1.0 - warm * 0.20;
        }
        float satAmt = uSubSaturation * mSub + uBkgSaturation * mBkg + uSkySaturation * mSky;
        if (abs(satAmt) > 0.001) {
            c = mix(vec3(luma(c)), c, 1.0 + satAmt);
        }
        float clarity = uSubClarity * mSub + uBkgClarity * mBkg;
        if (abs(clarity) > 0.001) {
            c += (original - mid) * clarity * 0.85;
            c += (original - fine) * max(clarity, 0.0) * 0.45;
        }
        if (uBkgSoft > 0.001) {
            // 배경 디테일을 덜어 내 초점이 피사체에 남게 한다.
            c -= (original - wide) * uBkgSoft * 1.35 * mBkg;
        }
        if (abs(uSkyBlue) > 0.001) {
            vec3 hsvSky = rgb2hsv(clamp(c, 0.0, 1.0));
            float pull = clamp(uSkyBlue, 0.0, 1.0) * 0.75;
            hsvSky.x = fract(hsvSky.x + hueDelta(hsvSky.x, 0.595) * pull);
            hsvSky.y = clamp(hsvSky.y * (1.0 + max(uSkyBlue, 0.0) * 0.45), 0.0, 1.0);
            c = mix(c, hsv2rgb(hsvSky), mSky);
        }
    }

    // ── 15. 비네팅 ───────────────────────────────────────────────────
    if (abs(uVignette) > 0.001) {
        vec2 vd = (vUv - 0.5) * vec2(uAspect, 1.0);
        float vdist = length(vd) / max(uAspect, 1.0) * 2.0;
        float vig = smoothstep(0.40, 1.15, vdist);
        c *= 1.0 - uVignette * 0.60 * vig;
    }

    // ── 15. 그레인 ───────────────────────────────────────────────────
    if (uGrain > 0.001) {
        float n = hash12(floor(vUv * 720.0));
        float grainWeight = 1.0 - abs(luma(c) - 0.5) * 1.1;
        c += (n - 0.5) * uGrain * 0.18 * max(grainWeight, 0.0);
    }

    // ── 17. 세로 비교 (왼쪽 = 원본, 오른쪽 = 보정 결과) ────────────────
    if (uSplitEnabled > 0.5) {
        c = mix(original, c, step(uSplitX, vUv.x));
    }

    // ── 18. 마스크 미리보기 (확인용, 저장에는 절대 쓰지 않는다) ────────
    if (uMaskOn > 0.5 && uMaskPreview > 0.5) {
        vec3 maskSample = texture(uMask, uv).rgb;
        float mSub = clamp(maskSample.r, 0.0, 1.0);
        float mSky = clamp(maskSample.g, 0.0, 1.0) * (1.0 - mSub);
        vec3 tint = mix(vec3(0.10, 0.10, 0.12), vec3(1.0, 0.62, 0.10), mSub);
        tint = mix(tint, vec3(0.15, 0.55, 1.0), mSky);
        float cover = clamp(mSub + mSky + 0.32, 0.0, 1.0);
        c = mix(c, tint, cover * 0.55);
    }

    fragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
"""
}
