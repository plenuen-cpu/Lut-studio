plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.plenuen.lumen"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.plenuen.lumen"
        minSdk = 29
        targetSdk = 35
        versionCode = 222
        versionName = "2.2.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // 대상 단말은 arm64 안드로이드 폰이다. 32비트·x86 네이티브 라이브러리를 빼서 APK를 절반으로 줄인다.
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            // 릴리스 APK는 배포 호스트가 받는 크기(66MB) 안에 들어가야 한다. R8로 dex를 줄인다.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android.txt"),
                "proguard-rules.pro",
            )
            // 취미용 자체 배포 앱: 디버그 키로 서명해 바로 설치할 수 있게 한다.
            // (README의 '설치' 항목에 키가 바뀌면 재설치가 필요하다고 적어 둔다.)
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        jniLibs {
            // arm64 단말이 대상이다. x86 계열은 에뮬레이터 확인용으로만 남긴다.
            useLegacyPackaging = false
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = false
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // 온디바이스 경량 LLM (LLM Inference API). 네이티브 라이브러리가 AAR에 들어 있어 NDK가 필요 없다.
    implementation("com.google.mediapipe:tasks-genai:0.10.24")
    // 온디바이스 세그멘테이션(인물·피사체 마스크). 모델 파일은 assets/models에 번들한다.
    implementation("com.google.mediapipe:tasks-vision:0.10.21")
    // 온디바이스 NIMA 미적 평가 추론(자동 보정의 점수판). 모델은 assets/models에 번들한다.
    implementation("org.tensorflow:tensorflow-lite:2.16.1")

    testImplementation("junit:junit:4.13.2")
}
