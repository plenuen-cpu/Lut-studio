# FilmCam

Ricoh GR 커스텀 펌웨어(GRLife)의 스타일을 따른 **실시간 LUT 필름 카메라** 안드로이드 앱입니다.
Tonefinder 2.0.1에 들어 있는 3D LUT 중 필름 46종 + 카메라 룩 11종, 총 **57종**을 그대로 옮겨
뷰파인더에 30/60fps로 입히고, 촬영한 사진도 같은 색감으로 저장합니다.

## 룩 57종

| 브랜드 | 수 | 예 |
| --- | --- | --- |
| Kodak | 24 | Ektar 100, Portra 400, Gold 200, Tri-X 400, T-Max 3200 |
| Fujifilm | 14 | Pro 400H, Velvia 50, Superia 400, Reala 100, FP-100c |
| Agfa | 7 | Optima 100 II, Ultra 50, Vista 400 |
| Ilford | 1 | HP5 Plus 400 |
| Leica | 6 | Leica Authentic, Vibrant, Classic Brass, Contemporary, Monochrom |
| Ricoh GR | 5 | GR Positive / Negative / Bleach Bypass / Cross Process / Hi-Contrast B&W |

VSCO 216종과 Honor·Xiaomi·Vivo·OnePlus·Huawei&Nothing 28종은 이 앱에서 제외했습니다.

## 화면과 기능

- **실시간 뷰파인더**: CameraX 프리뷰를 GLES3로 받아 17×17×17 3D 텍스처 LUT을 셰이더에서
  트라이리니어 보간으로 적용 (Tonefinder의 CPU 연산과 같은 수식).
- **Films in use 필름스트립**: 상·하단 퍼포레이션(구멍)이 있는 어두운 필름스트립, 선택된 슬롯에
  노란 포커스 테두리, 아래에 `1  Kodak Ektachrome E100G`처럼 번호+이름 표시.
  - 필름(Kodak·Fujifilm·Agfa·Ilford)은 캐니스터 아트 위에 필름 코드(E100, G200, P400, 400H…)를
    세로로 인쇄 — 코닥 노랑/빨강, 포트라 파스텔, 후지 그린, 아그파 레드, 일포드 흑백.
  - Leica·Ricoh GR은 브랜드 배지(라이카 레드닷, GR 로고)로 표시.
- **브랜드 필터 칩**: All / Kodak / Fujifilm / Agfa / Ilford / Leica / Ricoh GR.
- **LUT 강도 슬라이더** 0~100% (원본과 LUT 결과를 mix).
- **전/후면 카메라 전환**, **비율 3:2·4:3·16:9·1:1**.
- **저장**: `Pictures/FilmCam` (MediaStore) 로 JPEG 저장, 최근 사진 썸네일 → 전체화면 보기.
- 촬영 후 "현상 중…" 처리 동안 LUT 적용 + 크롭 + 회전 보정을 백그라운드에서 수행.

## LUT 데이터

`app/src/main/assets/luts/`

- `presets.json` — 57종 메타데이터(브랜드, 코드, 표시 이름, 캐니스터 색, 슬롯 번호)
- `lut_residuals.bin` — Tonefinder의 `LUT_LORENZO_B64`(base64 → zlib → Lorenzo 잔차)에서
  0~56번 LUT만 잘라 다시 zlib으로 압축한 206KB 블롭

앱은 시작할 때 블롭을 풀고, 선택한 LUT을 `LorenzoDecoder`로 복원해 3D 텍스처로 올립니다.
값 배치는 `index = ((b*17 + g)*17 + r)*3 + c` (R이 가장 빠름)로 원본과 동일합니다.

`tools/extract_luts.py` 가 원본 HTML에서 위 두 파일과 테스트 골든(`app/src/test/resources/lut_golden.json`)을
다시 만들어 냅니다:

```bash
python3 tools/extract_luts.py path/to/Tonefinder.html
```

## 빌드

GitHub Actions(`.github/workflows/android.yml`)가 `main` 푸시마다

1. `:app:testDebugUnitTest` — LUT 디코더가 원본과 바이트 단위로 같은지(sha256 + 샘플 노드),
   57종 매핑/브랜드 개수, 트라이리니어 보간, 회전·크롭 변환 행렬을 검증
2. `:app:assembleDebug` 로 `app-debug.apk` 생성
3. `v1.0.{빌드번호}` 릴리스에 APK 게시

로컬 빌드: Android SDK 35 + JDK 17 + Gradle 8.9, `gradle :app:assembleDebug`.

## CI 활성화 (GitHub Actions)

`.github/workflows/android.yml` 로 두면 `main` 푸시마다 테스트 → APK 빌드 → 릴리스가 자동으로 돕니다.
워크플로 정의는 이 저장소의 `ci/android-workflow.yml` 에 들어 있습니다. 푸시에 쓰는 GitHub App에
`workflows` 권한이 없어 `.github/workflows/` 경로에는 직접 쓸 수 없으므로, 다음 중 하나로 활성화하세요.

1. GitHub → Settings → Applications → Pickle → Configure → Repository permissions 에서
   **Workflows: Read and write** 로 변경한 뒤 `ci/android-workflow.yml` 을 `.github/workflows/android.yml` 로 이동
2. 또는 GitHub 웹 편집기에서 `.github/workflows/android.yml` 을 만들고 `ci/android-workflow.yml` 내용을 붙여넣기

## 설치

릴리스에서 `FilmCam-debug.apk`를 받아 설치하세요. CI가 매번 새 디버그 키로 서명하므로
**이미 설치된 FilmCam이 있으면 먼저 삭제한 뒤 설치**해야 합니다.

## 참고

- 사진은 기기 안에서만 처리되며 어디로도 전송되지 않습니다.
- 룩 데이터는 사용자가 만든 Tonefinder 2.0.1의 내장 LUT에서 추출했습니다.
