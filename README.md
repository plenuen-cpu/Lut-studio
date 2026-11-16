# Lumen 2.0 — 자연어 비파괴 사진 보정 (Android)

사진 한 장을 고르고 **"조금 더 따뜻하게, 부드럽게"** 처럼 말하면 그대로 보정되는 안드로이드 앱입니다.
원본 파일은 절대 건드리지 않고, 모든 보정은 `EditRecipe`라는 JSON 상태와 히스토리로만 관리합니다.
프리뷰와 원본 해상도 저장이 **같은 GLES 3.0 셰이더**를 쓰기 때문에 화면에서 본 그대로 파일이 나옵니다.

- 이전 저장소의 `FilmCam`(Lut-studio) 코드를 대체한 2.0 버전입니다.
- 화면에서 쓰던 **필름·카메라 3D LUT 57종**(Kodak 24, Fujifilm 14, Agfa 7, Ilford 1, Leica 6, Ricoh GR 5)은
  그대로 추출해 새 앱에 통합했습니다. LUT 바이트는 이전 빌드와 SHA-256 단위로 동일합니다(테스트로 고정).

## 받기

최신 APK: <https://github.com/plenuen-cpu/Lut-studio/releases/latest>

- `Lumen-2.0.0.apk` — release 빌드(디버그 키로 서명, 바로 설치 가능)
- `Lumen-2.0.0-debug.apk` — debug 빌드

설치 후 갤러리에서 사진을 고르면 됩니다. 서명 키가 빌드마다 같지 않을 수 있으므로,
이미 설치된 Lumen이 있으면 먼저 삭제하고 설치하세요. (패키지 이름이 `com.plenuen.filmcam`인
옛 FilmCam과는 별개 앱이라 함께 설치해도 됩니다.)

## 무엇이 들어 있나

### 1. 비파괴 편집
- 원본은 읽기 전용. `EditRecipe`(파라미터 30여 개) + 히스토리 스택만 바뀝니다.
- 되돌리기/다시 실행, 초기화, **화면을 길게 눌러 원본과 비교**, 슬라이더 수동 미세조정.
- 저장은 항상 **원본 해상도의 새 파일** → `Pictures/Lumen/Lumen_yyyyMMdd_HHmmss.jpg`.

### 2. GLES 3.0 실시간 파이프라인 (프리뷰·내보내기 공용)
한 개의 프래그먼트 셰이더(`gl/ShaderSource.kt`)가 아래를 모두 실제로 계산합니다.

| 묶음 | 파라미터 |
| --- | --- |
| 기하 | 수평 맞춤(±15°), 수직 원근, 자동 내접 크롭 |
| 기본 톤 | 노출, 대비, 하이라이트, 섀도우, 화이트, 블랙, 페이드 |
| 색감 | 색온도, 틴트, 활기, 채도, 색 정돈, 색 교차, 색 분리(섀도우/하이라이트 색상·균형), 피부톤 정리, 흑백 |
| 디테일·질감 | 명료도, 텍스처, 디헤이즈, 샤프닝, 글로우, 할레이션, 그레인, 비네팅 |
| 강조 | 피사체 강조(중심 분리 + 주변 누르기) |
| 룩 | 3D LUT 57종 + 룩 강도 |

### 3. 자동 수평·수직
- **구조 텐서(structure tensor) 투표**로 기울기를 추정합니다: 640px 이하로 줄인 휘도 → 3×3 가우시안 →
  중심 차분 기울기 → 12px 블록마다 기울기 2차 모멘트 → ±45°로 접은 뒤 0.1° 히스토그램 → 피크 보간.
- 수평(0°)과 수직(90°)이 같은 축에 투표하므로 **수평·수직이 한 번에** 맞습니다.
- 회전 각도에 맞춰 `cropScale = max(cosθ + sinθ/a, cosθ + a·sinθ)`만큼 화면을 확대해
  **검은 테두리(빈 코너)가 생기지 않습니다**. 프리뷰와 원본 해상도 저장 모두 같은 UV 변환을 씁니다.
- 원터치 **[자동 수평·수직]** 버튼, 수동 각도 슬라이더(0.1° 단위), "수직 원근" 슬라이더 제공.

### 4. 자연어 엔진 (하이브리드)
1. **즉각 파서(규칙)** — 사전 기반이라 0.01초 안에 응답합니다.
   - 17개 정밀 매핑: 부드럽게 · 선명하게 · 묵직하게 · 화사하게 · 고급스럽게 · 자연스럽게 · 감성적으로 ·
     청량하게 · 따스하게 · 쓸쓸하게 · 영화처럼 · 아련하게 · 생동감 있게 · 정갈하게 · 입체적으로 ·
     투명하게 · 필름처럼
   - 대표 룩: 필름 · 시네마틱 · 틸앤오렌지 · 화보 · 빈티지 · 세련되게 · 드라마틱하게 · 일본 스냅 ·
     흑백/라이카 모노 · 야경/네온 · 인물/뽀얗게
   - 시간·계절(새벽, 황혼, 여름, 겨울, 가을 오후), 공기(안개 낀 듯, 습하게, 공기가 맑게),
     기억(옛날 사진, 꿈속처럼), 매체(잡지, 광고, 다큐멘터리)
   - 미적 평가(예쁘게, 작품처럼, 있어 보이게, 전문가처럼, SNS 감성)는 **사진 히스토그램을 분석해**
     노출·섀도우·채도·색온도·클리핑 보정량을 그 사진에 맞게 정합니다.
   - 강도 부사: 아주 살짝 / 조금 · 살짝 / 적당히 / 진하게 / 확 · 세게 …
   - 연속 대화: "더 따뜻하게", "덜", "절반만", "취소/되돌리기", "다시 실행", "초기화"
   - 수평·수직: "수평 맞춰줘", "수직 수평 맞춰줘", "기울기 잡아줘", "반듯하게", "똑바로", "왼쪽으로 3도"
   - 룩 이름: "포트라 400", "벨비아", "라이카 모노" … → 57종 라이브러리에서 찾아 적용
2. **로컬 LLM** — 파서가 이해하지 못한 문장(또는 남은 부분)만 온디바이스 모델이 JSON으로 해석합니다.
   - 모델: **Qwen2.5-0.5B-Instruct (q8, 547MB)** 기본 / Qwen2.5-1.5B-Instruct (q8, 1.6GB) 선택
   - 런타임: MediaPipe **LLM Inference API**(`com.google.mediapipe:tasks-genai`, NDK 빌드 불필요)
   - 배포: APK에 넣지 않고 **첫 사용 시 진행률과 함께 Hugging Face에서 다운로드** → 앱 전용 저장소
   - 디코딩은 greedy(topK=1)로 고정해 같은 문장이 같은 결과를 내도록 했습니다.
   - 출력은 `{"label","summary","adjust":{...}}` 형태로 강제하고, 앱은 허용된 키만 받아
     범위를 다시 클램프합니다. 기하(회전)와 LUT 선택은 모델이 건드릴 수 없습니다.
   - 모델이 없거나 실패하면 규칙 파서 결과만으로 조용히 동작합니다.

### 5. 57종 3D LUT
- `assets/luts/presets.json` + `lut_residuals.bin` (206KB, Lorenzo 잔차 zlib)
- 앱 시작 시 풀고, 선택한 LUT만 `LorenzoDecoder`로 복원해 17³ 3D 텍스처로 올립니다.

## 빌드

```bash
# JDK 17, Android SDK 35, Gradle 8.9
gradle :app:testDebugUnitTest     # 단위 테스트 40개
gradle :app:assembleDebug         # app-debug.apk
gradle :app:assembleRelease       # app-release.apk (디버그 키로 서명)
```

APK 확인:

```bash
$ANDROID_HOME/build-tools/35.0.0/apksigner verify --verbose app/build/outputs/apk/release/app-release.apk
$ANDROID_HOME/build-tools/35.0.0/aapt2 dump badging app/build/outputs/apk/release/app-release.apk
```

## 테스트가 고정하는 것

- 자연어 17개 매핑 · 대표 룩 · 강도 부사 · 대화 제어 · 수평·수직 문장 · 수동 각도 · 다중 의도
- 미적 평가가 사진 밝기에 따라 다른 보정을 내는지
- 레시피 클램프·누적·Set 블렌딩·JSON 왕복·히스토리(undo/redo/reset/half)
- 기울기 추정(사인·부드러운 줄무늬 3°·5°·-4°, 수직선, 평면)과 보정 후 줄이 수평이 되는지
- 내접 크롭이 네 모서리를 원본 안에 유지하는지
- LUT 57종 SHA-256이 이전 빌드와 같은지
- 로컬 LLM JSON 파싱(코드펜스·잡설·미지의 키·범위 초과)

## 실기기에서 확인해야 하는 것

정적 빌드·단위 테스트·서명 검증까지는 이 환경에서 끝냈지만, **GL 렌더링과 로컬 LLM은 실제 GPU·NPU가
필요해 여기서 실행해 볼 수 없습니다.** 설치 후 아래를 확인하세요.

1. 갤러리 사진이 프리뷰에 뜨고 슬라이더/문장 보정이 즉시 반영되는지 (GLES 3.0 + 3D 텍스처)
2. [자동 수평·수직]이 기운 사진을 곧게 세우고 검은 코너가 생기지 않는지
3. [저장] 후 `Pictures/Lumen`의 파일이 화면과 같은지(원본 해상도)
4. 설정에서 모델(547MB)을 내려받고, 규칙이 모르는 문장이 LLM으로 해석되는지
5. 아주 큰 사진(8000px 초과)에서 저장 시 자동 축소 안내가 뜨는지

## 파일 지도

```
app/src/main/java/com/plenuen/lumen/
  core/      Param.kt, EditRecipe.kt, RecipeDelta.kt, RecipeSession.kt   (비파괴 상태·히스토리)
  nl/        IntentCatalog.kt, NaturalLanguageParser.kt, SceneAnalyzer.kt, AestheticPlanner.kt
  geom/      StraightenEstimator.kt, GeometryMath.kt                     (기울기·내접 크롭)
  gl/        ShaderSource.kt, PhotoProgram.kt, PhotoRenderer.kt, OffscreenExporter.kt
  llm/       LlmModels.kt, LlmModelStore.kt, LocalLlmEngine.kt, LlmRecipeDecoder.kt
  image/     PhotoStore.kt                                               (읽기·새 파일 저장)
  data/      LutLibrary.kt, LorenzoDecoder.kt, PresetCatalog.kt, Json.kt (57종 LUT)
  ui/        EditorScreen.kt, EditorViewModel.kt, Theme.kt
```
