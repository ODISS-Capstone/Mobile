plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 서버 주소/토큰은 Gradle property로 주입한다. 우선순위:
//   -P 인자 > gradle.properties / ~/.gradle/gradle.properties > 아래 기본값(운영 도메인)
// 에뮬레이터 테스트는 `-PodissHttpBaseUrl=http://10.0.2.2:8000` 처럼 덮어쓴다.
fun odissProp(name: String, fallback: String): String =
    (project.findProperty(name) as String?)?.trim()?.ifEmpty { fallback } ?: fallback

val odissHttpBaseUrl = odissProp("odissHttpBaseUrl", "https://www.odiss.p-e.kr")
val odissWsBaseUrl = odissProp("odissWsBaseUrl", "wss://www.odiss.p-e.kr/ws/chat")
val odissSpeakerId = odissProp("odissSpeakerId", "android_default")
val odissWsToken = odissProp("odissWsToken", "")

android {
    namespace = "com.odiss.assistant"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.odiss.assistant"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "ODISS_HTTP_BASE_URL", "\"$odissHttpBaseUrl\"")
        buildConfigField("String", "ODISS_WS_BASE_URL", "\"$odissWsBaseUrl\"")
        buildConfigField("String", "ODISS_SPEAKER_ID", "\"$odissSpeakerId\"")
        buildConfigField("String", "ODISS_WS_TOKEN", "\"$odissWsToken\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            isDebuggable = true
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
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(bom)
    androidTestImplementation(bom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.navigation:navigation-compose:2.8.0")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.squareup.okio:okio:3.9.0")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.1")
    implementation("com.squareup.moshi:moshi-adapters:1.15.1")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-moshi:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation("androidx.camera:camera-core:1.4.0")
    implementation("androidx.camera:camera-camera2:1.4.0")
    implementation("androidx.camera:camera-lifecycle:1.4.0")
    implementation("androidx.camera:camera-view:1.4.0")

    implementation("com.google.mlkit:text-recognition-korean:16.0.1")
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.1")
    implementation("com.google.firebase:firebase-messaging-ktx:24.1.0")

    implementation("androidx.work:work-runtime-ktx:2.9.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
