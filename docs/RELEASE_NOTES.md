# Lumen 2.2.2 · 로컬 LLM이 문장을 읽다가 앱이 꺼지던 문제 (긴급 핫픽스)

v2.2.1에서 "로컬 LLM이 문장을 읽는 중…" 상태로 앱이 즉사하던 문제를 **원인까지 확인해서** 고쳤습니다.
2.2.1의 방어(코루틴 예외 경계, R8 유지 규칙)로는 잡히지 않는 **네이티브(NDK) 크래시**였고, 범인은
번들 라이브러리 충돌도, 메모리 부족도 아닌 **프롬프트 길이**였습니다.

## 무엇이 문제였나

로컬 LLM(MediaPipe LLM Inference, `tasks-genai` 0.10.24)에 우리가 준 설정은 이랬습니다.

```kotlin
.setMaxTokens(512)
```

MediaPipe 문서에서 `maxTokens`는 **출력 길이 제한이 아니라 "입력 + 출력" 합계 상한**입니다. 그런데
2.0.2·2.1.0에서 규칙과 예시를 늘리면서 프롬프트는 이렇게 커져 있었습니다.

| 항목 | 값 |
|---|---|
| v2.2.1 프롬프트 | 2,951자 |
| Qwen2.5 토크나이저 실측 | **1,607토큰**(채팅 템플릿 포함 1,626) |
| 엔진 상한 `maxTokens` | **512** |

즉 **첫 호출부터 상한의 3.1배**를 보낸 것입니다. 설치된 AAR의 `libllm_inference_engine_jni.so`에서
해당 엔진이 이 상황에 쓰는 문자열을 직접 확인했습니다.

> `Input is too long for the model to process: current_step($0) + input_size($1) was not less than maxTokens($2). 'maxTokens' is the maximum number of tokens (input tokens + output tokens) the model handles, which can be set in the task options.`

같은 계열 엔진의 공개 소스(MediaPipe 0.10.24 `llm_inference_engine_cpu.cc`)는 프리필을
`ABSL_CHECK_OK(llm->AddInputTokens(...))`, `ABSL_CHECK_EQ(prefill_runner->Invoke(), kTfLiteOk)`로
감싸고 있어, 이 실패는 **Java 예외가 아니라 `SIGABRT`(프로세스 abort)** 로 끝납니다. 그래서
`safeLaunch`의 `try/catch`, `CoroutineExceptionHandler`, `Thread.setDefaultUncaughtExceptionHandler`
어느 것도 볼 수 없었고, 화면에는 아무 흔적 없이 앱이 사라졌습니다.

`maxTokens`는 2.0.0부터 512로 고정이었고, 로컬 LLM을 실제로 내려받아 문장을 보낸 순간이 이번이
처음이었기 때문에 지금 드러났습니다. **2.2.1까지 로컬 LLM 해석은 한 번도 성공한 적이 없습니다.**

### 확인했지만 원인이 아니었던 것들

- **`.so` 심볼 충돌**: 아닙니다. APK 안에는 `libllm_inference_engine_jni.so`(11.4MB),
  `libmediapipe_tasks_vision_jni.so`(12.4MB), `libtensorflowlite_jni.so`(3.2MB)가 **서로 다른 이름으로
  한 벌씩** 들어 있고 경로 중복이 없습니다(`tasks-genai`/`tasks-vision`/`tensorflow-lite` AAR 실측).
  별도 `org.tensorflow:tensorflow-lite`는 NIMA 점수판이 `Interpreter`를 직접 쓰기 때문에 필요합니다.
- **메모리 부족(LMK)**: `largeHeap`은 이미 켜져 있고, 0.5B 모델은 파일 매핑이라 힙을 먹지 않습니다.
  다만 여유를 더 두는 조치를 함께 넣었습니다(아래).

## 어떻게 고쳤나

1. **엔진 상한을 번들 크기에 맞췄습니다.** `maxTokens = 1152`. 두 모델 번들
   (`Qwen2.5-*_ekv1280.task`)의 KV 캐시가 1,280토큰이라 그보다 조금 아래로 잡았습니다.
2. **프롬프트를 예산 안으로 줄였습니다.** 2,951자/1,607토큰 → **1,660자/878토큰**(가장 긴 요청 기준).
   규칙은 그대로 두고 설명을 줄였고, 키 목록은 영어 키만 나열합니다(한국어 라벨을 같이 쓰면 토큰이
   두 배). 색 대역 24개는 나열하는 대신 "redSaturation처럼 색+역할" 규칙과 예시로 가르칩니다.
   예시는 모양·구어체/부정·영역/색 세 개만 남겼습니다.
3. **보내기 전에 실제 토큰 수를 셉니다.** `LlmInference.sizeInTokens()`(네이티브 토크나이저, 그래프를
   돌리지 않음)로 재고, 예산(992토큰)을 넘으면 예시를 뺀 짧은 프롬프트로 한 번 더, 그래도 넘으면
   **엔진을 호출하지 않습니다**. 추정이 필요한 경우(토크나이저 호출 실패)에는 한국어 1자=1토큰으로
   일부러 크게 잡습니다 — 한 걸음 일찍 거절하는 쪽이 프로세스를 잃는 것보다 쌉니다.
4. **동시 호출을 막았습니다.** `interpret()`에 락이 없어 문장을 연달아 보내면 같은 `LlmInference`에
   `generateResponse`가 겹칠 수 있었습니다(MediaPipe가 금지하는 사용). 이제 직렬화됩니다.
5. **어디까지 갔는지 남깁니다.** 네이티브 호출 전후로 `llm/load/start`, `llm/prompt/…`,
   `llm/generate/start`, `llm/generate/done` 같은 흔적을 파일에 **fsync로 즉시 기록**합니다. 다음에
   또 죽더라도 설정(⚙️)의 "최근 오류 기록"에서 정확히 어느 호출이었는지 보입니다.
6. **모델을 열기 전에 NIMA 평가기를 닫습니다.** EGL 컨텍스트·텍스처·인터프리터를 놓아 주고, 다음 점수
   측정 때 다시 만듭니다. 547MB~1.6GB 모델을 매핑하는 순간의 여유를 조금이라도 넓힙니다.
7. **모델이 없거나 실패해도 빈손으로 돌려보내지 않습니다.** 규칙도 모델도 읽지 못한 문장은
   "몽환·쓸쓸·청량·빈티지·세련·포근·드라마틱·따뜻함" 정도의 **느낌 낱말 표**로 작게 보정하고,
   카드에 "느낌 낱말만으로 해석했어요"라고 밝힙니다(되돌리기 한 번이면 취소).

## 검증

- 단위 테스트 **148개 전부 통과**. 새 테스트 `LlmPromptBudgetTest`가 예산·프롬프트 크기·요청 상한·
  키 노출을, `FeelFallbackTest`가 느낌 보정의 크기와 색 대역 디코딩을 고정합니다.
  (프롬프트 토큰 수는 Qwen2.5 토크나이저로 별도 실측: 878 < 992.)
- `versionCode 222` / `versionName 2.2.2`, `apksigner verify` → **APK Signature Scheme v2 통과**.

## 남은 확인

실기기에서 547MB 모델을 내려받아 사전에 없는 문장(예: "저녁 골목의 공기처럼 쓸쓸하면서도 포근하게")을
보내 주세요. 이제 "로컬 LLM이 문장을 읽는 중…" 다음에 **보정 카드**가 떠야 합니다. 그래도 문제가
생기면 설정(⚙️) → "최근 오류 기록"의 마지막 줄(`llm/...`)을 그대로 복사해 보내 주시면 그 지점부터 봅니다.
