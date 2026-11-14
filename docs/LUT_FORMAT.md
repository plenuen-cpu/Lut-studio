# LUT format

Tonefinder 2.0.1 ships 301 built-in 3D LUTs inside one HTML file:

```
LUT_LORENZO_B64  ->  base64  ->  zlib  ->  4,436,439 bytes
                                            301 LUTs * (17*17*17*3) bytes
```

Each LUT is 17x17x17 RGB with three channel planes:

```
plane(c) = c * 17^3
residual offset of node (r, g, b) in plane c = c*17^3 + (b*17 + g)*17 + r
```

The residual is added to a 3D Lorenzo predictor built from already reconstructed neighbours
(r first, then g, then b), exactly like `getLutBytes()` in the original:

```js
pred = v_r + v_g + v_b - v_rg - v_rb - v_gb + v_rgb   // interior
```

The decoded byte layout is red-fastest:

```
index = ((b*17 + g)*17 + r)*3 + channel
```

which is also the layout GL wants for a 3D texture (x = r, y = g, z = b).

FilmCam keeps presets 0..56 (46 films + 11 camera looks), re-compresses their residuals with zlib
into `assets/luts/lut_residuals.bin` (206,238 bytes), and decodes a LUT the first time it is chosen.

Application (both CPU and GPU) is trilinear interpolation over the 0..255 domain, mixed with the
untouched pixel by the intensity slider — identical to `processImageBuffer()` in the original.
