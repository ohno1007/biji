plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.biji.notes"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.biji.notes"
        minSdk = 26
        // 用 28 是为了绕开 Android 10+ 对 app-private 目录的 W^X
        // 强制 —— 高 targetSdk 下 ProcessBuilder 启动 /data/data/
        // <pkg>/files/... 里的二进制会 EACCES。Termux 同样用 28。
        targetSdk = 28
        versionCode = 1
        versionName = "1.0.0"
        vectorDrawables { useSupportLibrary = true }
        // Vosk / JNA 各带 4 个 ABI 的 .so，占了 APK 45 MB 以上。
        // 现役设备基本都是 arm64，只保留它，包体直接砍到 ~20 MB。
        ndk { abiFilters += listOf("arm64-v8a") }
        externalNativeBuild {
            cmake { cppFlags += "-std=c++17" }
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
        debug {
            // 纯 shrink（-dontobfuscate）：debug 包的 classes.dex 有 44 MB，
            // 大半是 material-icons-extended 里没用到的图标类。只删死代码、
            // 不改名，反射按名字找类的地方不受影响。
            isMinifyEnabled = true
            isShrinkResources = true
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
    kotlinOptions { jvmTarget = "17" }

    // Telegram 那套思路：算法密集、每帧都跑的东西下沉到 C++，
    // Kotlin 只留 UI 和编排。native 挂了就自动退回 Kotlin 实现。
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.navigation:navigation-compose:2.8.1")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("androidx.datastore:datastore-preferences:1.1.1")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")

    implementation("org.jsoup:jsoup:1.17.2")
    implementation("net.dankito.readability4j:readability4j:1.0.8")

    implementation("androidx.webkit:webkit:1.11.0")
    implementation("androidx.lifecycle:lifecycle-process:2.8.6")

    // Vosk offline speech recognition — on-device Kaldi-based ASR.
    // Models (40–80 MB) are downloaded into app-private storage at
    // runtime so the APK itself stays slim.
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
    implementation("net.java.dev.jna:jna:5.13.0@aar")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
