#!/usr/bin/env python3
"""Design mock of the film strip artwork, mirroring ui/PresetArt.kt geometry (not used by the app)."""
import json
from PIL import Image, ImageDraw, ImageFont

cat = json.load(open('app/src/main/assets/luts/presets.json'))
presets = cat['presets']
S = 3
SLOT_W, SLOT_H = 76*S, 90*S
GAP = 6*S
PERF_H = 5*S
FONT_PATHS = ["/usr/share/fonts/truetype/dejavu/DejaVuSansCondensed-Bold.ttf",
              "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"]
_cache = {}

def font(size):
    key = int(size)
    if key in _cache:
        return _cache[key]
    f = None
    for p in FONT_PATHS:
        try:
            f = ImageFont.truetype(p, key)
            break
        except Exception:
            continue
    if f is None:
        f = ImageFont.load_default()
    _cache[key] = f
    return f

def h(s):
    return tuple(int(s[i:i+2], 16) for i in (1, 3, 5))

def draw_text_vertical(img, cx, cy, text, size, color):
    f = font(size)
    tw = int(img.width)
    layer = Image.new('RGBA', (tw, int(size*1.6)), (0, 0, 0, 0))
    ImageDraw.Draw(layer).text((tw/2, layer.height/2), text, font=f, fill=color, anchor='mm')
    layer = layer.rotate(90, expand=True)
    img.alpha_composite(layer, (int(cx - layer.width/2), int(cy - layer.height/2)))

def draw_canister(img, p):
    d = ImageDraw.Draw(img)
    w, hh = img.size
    bw, bh = w*0.46, hh*0.66
    left = (w-bw)/2
    top = (hh-bh)/2 + hh*0.01
    d.rounded_rectangle((left+bw*0.33, top-hh*0.05, left+bw*0.67, top+hh*0.015), radius=w*0.02, fill='#1E1E24')
    d.rounded_rectangle((left, top, left+bw, top+bh), radius=w*0.05, fill=p['colors']['body'])
    d.rectangle((left, top, left+bw, top+bh*0.08), fill='#35353C')
    d.rectangle((left, top+bh*0.92, left+bw, top+bh), fill='#35353C')
    d.rectangle((left, top+bh*0.08, left+bw, top+bh*0.145), fill=p['colors']['band'])
    code_top, code_bottom = top+bh*0.17, top+bh*0.84
    size = hh*0.17
    while size > hh*0.055 and d.textlength(p['label'], font=font(size)) > (code_bottom-code_top):
        size *= 0.93
    draw_text_vertical(img, left+bw/2, (code_top+code_bottom)/2, p['label'], size, p['colors']['ink'])
    d = ImageDraw.Draw(img)
    d.text((left+bw/2, top+bh*0.895), p['brand'].upper(), font=font(hh*0.048), fill=p['colors']['ink'], anchor='mm')

def draw_badge(img, p):
    d = ImageDraw.Draw(img)
    w, hh = img.size
    pw, ph = w*0.64, hh*0.64
    left, top = (w-pw)/2, (hh-ph)/2
    d.rounded_rectangle((left, top, left+pw, top+ph), radius=w*0.07, fill=p['colors']['body'], outline='#3A3A44', width=int(w*0.012))
    cx = left+pw/2
    if p['brand'] == 'Leica':
        r = pw*0.125
        d.ellipse((cx-r, top+ph*0.24-r, cx+r, top+ph*0.24+r), fill=p['colors']['band'])
        r2 = pw*0.032
        d.ellipse((cx-r2, top+ph*0.24-r2, cx+r2, top+ph*0.24+r2), fill='#FFFFFF')
        d.text((cx, top+ph*0.43), 'LEICA', font=font(ph*0.075), fill=p['colors']['ink'], anchor='mm')
    else:
        d.text((cx, top+ph*0.28), 'GR', font=font(ph*0.20), fill=p['colors']['band'], anchor='mm')
        d.text((cx, top+ph*0.42), 'RICOH', font=font(ph*0.065), fill=p['colors']['ink'], anchor='mm')
    size = ph*0.145
    while size > ph*0.05 and d.textlength(p['label'], font=font(size)) > pw*0.78:
        size *= 0.9
    d.text((cx, top+ph*0.74), p['label'], font=font(size), fill=p['colors']['ink'], anchor='mm')

cols = 10
rows = (len(presets)+cols-1)//cols
W = cols*(SLOT_W+GAP)+GAP
H = rows*(SLOT_H+GAP)+GAP+2*PERF_H+2*12
strip = Image.new('RGBA', (W, H), '#050507')
d = ImageDraw.Draw(strip)
x = 12
while x < W-12:
    d.rounded_rectangle((x, 8, x+9*S, 8+PERF_H), radius=int(1.5*S), fill='#232329')
    d.rounded_rectangle((x, H-8-PERF_H, x+9*S, H-8), radius=int(1.5*S), fill='#232329')
    x += (9+8)*S
for i, p in enumerate(presets):
    r, c = divmod(i, cols)
    img = Image.new('RGBA', (SLOT_W, SLOT_H), '#15151B')
    if p['art'] == 'badge':
        draw_badge(img, p)
    else:
        draw_canister(img, p)
    x0 = GAP + c*(SLOT_W+GAP)
    y0 = PERF_H + 12 + r*(SLOT_H+GAP)
    strip.alpha_composite(img, (x0, y0))
    d = ImageDraw.Draw(strip)
    d.text((x0+SLOT_W/2, y0+SLOT_H-7*S), str(p['slot']), font=font(2.7*S), fill='#8E8E96', anchor='mm')
strip.convert('RGB').save('docs/filmstrip-preview.png')
print('saved docs/filmstrip-preview.png', strip.size)
