# 구조

```
photo ──▶ EditRecipe(불변 상태) ──▶ PhotoProgram(GLES 3.0) ──▶ 프리뷰 / 원본 해상도 파일
              ▲                    ▲
              │                    └── LUT 17³ 3D 텍스처
   자연어 파서 ─ 로컬 LLM ─ 사진 분석(히스토그램) ─ 기울기 추정
```

## 1. 상태

- `core/Param.kt` — 파이프라인이 아는 모든 값의 이름·범위·기본값·표시 형식.
- `core/EditRecipe.kt` — 값을 `Map<Param, Float>`로 들고 있는 불변 레시피. `with`, `plus`, `apply`,
  `toJson/fromJson`. 기본값과 같은 항목은 JSON에 쓰지 않습니다.
- `core/RecipeDelta.kt` — 상태가 아니라 **변경 설명**. `Add`(상대)와 `Set`(목표) 두 가지.
  `Set`은 현재 값에서 목표로 강도만큼 보간하므로 "절반만 흑백" 같은 표현이 자연스럽습니다.
- `core/RecipeSession.kt` — 레시피 스택. undo/redo/reset, 직전 단계 반복(`repeatLast`),
  강도 절반 재계산(`halveLast`). 최대 200단계.

원본 `Bitmap`은 어디에서도 수정되지 않습니다. 프리뷰용 축소본과 저장용 원본을 따로 디코드하고,
둘 다 같은 레시피를 셰이더에 넘깁니다.

## 2. 렌더링

`gl/ShaderSource.kt`의 프래그먼트 셰이더 하나가 전부입니다.

```
uv → 기하(회전·내접 크롭·수직 원근) → 노출(linear) → 톤 → 색온도/틴트
   → 채도/활기 → 색 정돈(색상 수 감소) → 색 교차 → 섀도우/하이라이트 색 분리
   → 피부톤 정리 → 명료도/텍스처/샤프닝 → 디헤이즈 → 글로우/할레이션
   → 흑백 → 피사체 강조 → 3D LUT → 비네팅 → 그레인
```

- 지역 연산(명료도·텍스처·샤프닝·글로우·할레이션·피부톤)은 8탭 샘플로 계산하며,
  반경은 이미지 크기의 1/1000 단위라 **프리뷰와 원본 해상도 저장이 같은 세기**로 보입니다.
- `PhotoRenderer`는 화면 크기에 맞춰 letterbox 뷰포트를 잡고 `RENDERMODE_WHEN_DIRTY`로 필요할 때만 그립니다.
- `OffscreenExporter`는 별도 EGL 컨텍스트 + FBO에 원본 크기로 그리고 `glReadPixels`로 읽어
  행을 뒤집어 `Bitmap`을 만듭니다. GL 최대 텍스처 크기를 넘는 사진은 자동 축소하고 그 사실을 알립니다.

## 3. 기울기

`geom/StraightenEstimator.kt`

1. 640px 이하로 박스 다운샘플 → 휘도
2. 3×3 이항 블러 → 중심 차분 기울기
3. 12px 블록(6px 간격)마다 `Σgx², Σgy², Σgxgy`
4. 주 방향 `φ = ½·atan2(2Σgxgy, Σgx²−Σgy²)`, 일관성 `coherence`
5. `φ mod 90`을 ±45°로 접어 0.1° 히스토그램에 `√energy × coherence` 가중 투표
6. 1.1빈 가우시안 스무딩 → 피크 → 포물선 보간

`geom/GeometryMath.kt`가 같은 변환을 Kotlin으로 구현해 테스트에서 소프트웨어로 되돌려 봅니다.

```
frame = (uv − 0.5) · (aspect, 1) / cropScale
src   = R(straighten) · frame           (y 아래 방향 좌표)
src.x *= 1 + verticalTilt · src.y · 0.45
uv'   = (src.x / aspect, src.y) + 0.5
```

## 4. 자연어

`nl/NaturalLanguageParser.kt`

- 정규화(소문자·구두점 제거) 후 **긴 구절부터** 소비하며 매칭. 이미 소비된 구간은 다시 쓰지 않습니다.
- 순서: 대화 제어 → 수평·수직/각도 → 룩 이름 → 룩 의도 → 남은 문장.
- 강도는 의도 바로 앞 12자 안의 수식어로 결정합니다.
- 남은 문장(`unmatched`)이 2자 이상이면 로컬 LLM이 그 부분만 추가 해석합니다.

`nl/SceneAnalyzer.kt`는 밝기 분포·채도·색 편향·클리핑·디테일을 측정하고,
`nl/AestheticPlanner.kt`가 "예쁘게/작품처럼/…" 요청에 그 사진에 맞는 양을 더합니다.

## 5. 로컬 LLM

- `llm/LlmModels.kt` — Hugging Face의 공개 `.task` 번들(Qwen2.5 0.5B/1.5B q8). 계정·키 불필요.
- `llm/LlmModelStore.kt` — `.part` 파일로 내려받고 크기 검증 후 이름 변경, 진행률 콜백.
- `llm/LocalLlmEngine.kt` — MediaPipe `LlmInference`(maxTokens 512, greedy). 로딩은 Mutex로 한 번만.
- `llm/LlmRecipeDecoder.kt` — 코드펜스·잡설이 섞인 답에서 첫 `{…}`를 균형 있게 뽑아 파싱,
  허용 키만 남기고 `LlmScale`로 슬라이더 단위로 환산, 범위는 레시피에서 다시 클램프.

## 6. LUT

`data/`의 Lorenzo 디코더와 카탈로그는 이전 FilmCam 구현을 그대로 가져왔습니다.
`lut_residuals.bin`(206KB)을 풀어 17³×3 바이트 LUT 57개를 만들고, 선택한 것만 3D 텍스처로 올립니다.
