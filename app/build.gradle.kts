plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Single source of truth for the app version — keep in sync with the GitHub release tag (vX.Y.Z).
// versionCode is derived so it always increases: major*10000 + minor*100 + patch (minor/patch < 100).
val appVersionName = "0.2.8"
val appVersionCode = appVersionName.split(".").map { it.toInt() }
    .let { (major, minor, patch) -> major * 10000 + minor * 100 + patch }

// Live-test API keys. Read from an untracked .env at the repo root (see .env.example) and
// handed to the on-device instrumentation runner as arguments (`am instrument -e KEY VALUE`),
// so they are never compiled into any APK. Exported environment variables are the fallback.
val liveTestKeyNames = listOf(
    "OPENAI_API_KEY", "DEEPGRAM_API_KEY", "GROQ_API_KEY", "ELEVENLABS_API_KEY", "ASSEMBLYAI_API_KEY", "SONIOX_API_KEY",
)
val dotEnv: Map<String, String> = rootProject.file(".env").takeIf { it.isFile }?.readLines()
    ?.mapNotNull { line ->
        val t = line.trim()
        if (t.isEmpty() || t.startsWith("#") || '=' !in t) return@mapNotNull null
        val (k, rawValue) = t.split("=", limit = 2)
        // Drop a trailing "  # comment" (as in .env.example; also when the value is empty)
        // unless the value is quoted. A comment starts at the first '#' preceded by whitespace.
        val trimmed = rawValue.trim()
        val v = if (trimmed.startsWith("\"") || trimmed.startsWith("'")) trimmed
            else rawValue.replace(Regex("""(^|\s)#.*$"""), "").trim()
        k.trim().removePrefix("export ").trim() to v.removeSurrounding("\"").removeSurrounding("'")
    }?.toMap() ?: emptyMap()

android {
    namespace = "com.example.smartnotetaker"
    compileSdk = 34
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "com.example.smartnotetaker"
        minSdk = 26
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        liveTestKeyNames.forEach { name ->
            val value = dotEnv[name] ?: System.getenv(name) ?: ""
            if (value.isNotBlank()) testInstrumentationRunnerArguments[name] = value
        }

        vectorDrawables {
            useSupportLibrary = true
        }
        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "x86_64"))
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-Xskip-metadata-version-check")
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.4"
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
    testOptions {
        // JVM unit tests (app/src/test) run the stream clients under Robolectric against a
        // MockWebServer; LiveProvidersJvmTest also hits the real APIs when .env holds keys.
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
        unitTests.all { test ->
            liveTestKeyNames.forEach { name ->
                val value = dotEnv[name] ?: System.getenv(name) ?: ""
                if (value.isNotBlank()) test.systemProperty(name, value)
            }
            test.testLogging {
                events("passed", "skipped", "failed")
                showStandardStreams = false
            }
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.activity:activity-compose:1.8.1")
    implementation(platform("androidx.compose:compose-bom:2023.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    
    // OkHttp for networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    
    // Security and Icons
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.compose.material:material-icons-extended")

    // Remove WhisperKit, we use compiled whisper.cpp now
    
    // LiteRT for Local LLM
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.13.1")

    // On-device live/integration tests (app/src/androidTest); coroutines-test matches the
    // 1.9.0 that the runtime classpath already resolves to.
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:core:1.5.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("junit:junit:4.13.2")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")

    // JVM unit tests (app/src/test): Robolectric for Handler/Looper/Base64, MockWebServer for
    // fake provider WebSockets.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.11.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
