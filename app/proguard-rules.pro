# Lumen release shrinker rules.
#
# The release build is the one the user installs, so R8 runs to keep the APK inside the size the
# release host accepts (66MB). v2.2.0 was the first release to run R8 at all, and one hour later the
# app closed as soon as a prompt was submitted. The prompt path itself turned out to be sound (it is
# plain Kotlin, and it is now covered by unit tests that push 4000 sentences through it), so the
# exposure was everywhere a name is not a reference: native callbacks, generated/auto-value classes,
# enum and metadata lookups, coroutine service loading, attributes a stack trace needs.
#
# The rule from v2.2.1 on is the one the brief asked for: shrinking is allowed to touch *third-party*
# code that nothing names, and everything the app itself, MediaPipe, TFLite and the Kotlin runtime
# rely on by name is kept whole. Keeping our own (small) code costs a few hundred KB of dex; the
# 59.7MB APK is native libraries and models, so the size budget is unaffected.

# ── 우리 코드: 이름으로 불릴 수 있는 곳이 없도록 통째로 남긴다 ────────────────
# 앱 코드는 dex의 3% 남짓이라 용량 영향이 없고, 남겨 두면 난독화된 프레임 대신 읽을 수 있는
# 스택 트레이스가 남는다(현장 보고를 그대로 고칠 수 있는 유일한 단서다).
-keep class com.plenuen.lumen.** { *; }
-keep interface com.plenuen.lumen.** { *; }
-keep enum com.plenuen.lumen.** { *; }
-dontwarn com.plenuen.lumen.**

# ── MediaPipe (mask segmentation, LLM inference) ──────────────────────────
# JNI가 이름으로 콜백을 찾고, 그래프 옵션은 AutoValue 생성 클래스로 만든다.
-keep class com.google.mediapipe.** { *; }
-keep interface com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**

# ── TensorFlow Lite (NIMA score) ──────────────────────────────────────────
-keep class org.tensorflow.lite.** { *; }
-keep interface org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**

# ── protobuf / guava / gson, used by MediaPipe Tasks ──────────────────────
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.**
-keep class com.google.common.** { *; }
-dontwarn com.google.common.**
-keep class com.google.gson.** { *; }
-dontwarn com.google.gson.**
-keep class org.json.** { *; }
-dontwarn org.json.**

# AutoValue/FreeBuilder가 만든 클래스는 리플렉션 대신 이름으로 만들어지므로 함께 남긴다.
-keep class **.AutoValue_* { *; }
-keep class **.AutoValue_*$Builder { *; }

# ── Kotlin runtime: 메타데이터·코루틴·enum ─────────────────────────────────
-keep class kotlin.Metadata { *; }
-keep class kotlin.enums.** { *; }
-keep class kotlin.reflect.** { *; }
-dontwarn kotlin.reflect.**
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    public static ** getEntries();
}
-keepclassmembers class kotlin.coroutines.** { *; }
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**
-keepclassmembers class * implements kotlinx.coroutines.CoroutineDispatcher { *; }

# ── Native entry points and JNI callbacks ─────────────────────────────────
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class * implements com.google.mediapipe.tasks.core.TaskRunner { *; }

# ── 스택 트레이스를 읽을 수 있게 남기는 속성 ────────────────────────────────
# LineNumberTable이 없으면 현장 로그가 "H1.a.a(Unknown Source)"로만 남는다.
-keepattributes SourceFile,LineNumberTable,Signature,InnerClasses,EnclosingMethod
-renamesourcefileattribute SourceFile

# 토크나이저·모델 JSON은 이름으로 읽는 자원이 아니라 assets에서 직접 읽으므로 추가 규칙이 없다.

# 의존성 AAR이 함께 넣은 애노테이션 프로세서(AutoValue)는 컴파일 타임 전용이라 런타임에 없다.
-dontwarn javax.annotation.processing.**
-dontwarn javax.lang.model.**
-dontwarn autovalue.**
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**
-dontwarn com.google.errorprone.annotations.**
