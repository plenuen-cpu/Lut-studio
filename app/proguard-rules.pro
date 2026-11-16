# Lumen release shrinker rules.
#
# The release build is the one the user installs, so R8 runs to keep the APK inside the size the
# release host accepts. Everything that is reached from native code or from a name string has to
# survive shrinking, so MediaPipe, TensorFlow Lite and protobuf are kept whole; our own code is
# ordinary Kotlin/Compose and shrinks normally.

# ── MediaPipe (mask segmentation, LLM inference) ──────────────────────────
-keep class com.google.mediapipe.** { *; }
-keep interface com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**

# ── TensorFlow Lite (NIMA score) ──────────────────────────────────────────
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**

# ── protobuf / guava, used by MediaPipe Tasks ─────────────────────────────
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.**
-keep class com.google.common.** { *; }
-dontwarn com.google.common.**

# ── Native entry points and JNI callbacks ─────────────────────────────────
-keepclasseswithmembers class * {
    native <methods>;
}
-keep class * implements com.google.mediapipe.tasks.core.TaskRunner { *; }

# 토크나이저·모델 JSON은 이름으로 읽는 자원이 아니라 assets에서 직접 읽으므로 추가 규칙이 없다.

# 의존성 AAR이 함께 넣은 애노테이션 프로세서(AutoValue)는 컴파일 타임 전용이라 런타임에 없다.
-dontwarn javax.annotation.processing.**
-dontwarn javax.lang.model.**
-dontwarn autovalue.**
-dontwarn javax.annotation.**
