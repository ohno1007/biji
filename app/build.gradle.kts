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
        // resources.arsc 189 KB 里有 85 个 locale，其中 84 个非默认语言占
        // 83 KB 的 TYPE chunk + 74 KB 的字符串池 —— 全是 androidx 组件自带的
        // 无障碍标签（m3c_*、nav_app_bar_*）。app 自己的 strings.xml 只有 69 字节，
        // 界面本来就是中文写死的。arsc 在 APK 里是 stored，砍掉就是 1:1 减重。
        //
        // 坑：这里必须把 zh-rCN/rHK/rTW 一个个列出来。resourceConfigurations 是
        // 按资源限定符精确匹配的，光写 "zh" 匹配的是 values-zh/ —— 而 androidx
        // 根本没有那个目录，只有 values-zh-rCN 之类，结果是中文被整个删光，
        // 中文系统的用户反而看到英文的无障碍标签。英文不用列：它就在默认
        // values/ 里，en-rGB/rAU 那些区域变体删掉自动回落到默认。
        resourceConfigurations += listOf("zh", "zh-rCN", "zh-rHK", "zh-rTW")
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
        // libvosk.so 解压后 8.86 MB，而 .so 在 APK 里是 stored（不压缩），
        // 一个人就占了包体的 61.7%。离线语音本来就得先下 40 MB+ 的模型才能用，
        // .so 跟着模型一起现下（VoskNativeLib），装不上就退回系统语音识别。
        // 注意只排它一个：libjnidispatch.so 才 157 KB，是 JNA 自己的加载器，
        // 必须留在 APK 里，否则连去下载的那段代码都跑不起来。
        jniLibs { excludes += "**/libvosk.so" }
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
    // navigation-compose 删了：全项目零引用（没有 NavHost/rememberNavController），
    // 页面切换是自己的状态机。R8 早就把它的类删光了，但它的 attr（argType /
    // popUpTo / enterAnim …）还留在 resources.arsc 里。

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
    // okhttp-sse 删了：零引用。SSE 是 DeepSeekClient.readSse() 自己按行解的，
    // 从来没用过 EventSource。

    // .tar.xz 解包（zig / static-curl 只发这个格式）。这里原本挂着一份
    // 590 行手写的 LZMA2 解码器，理由是「不想动 build.gradle」——
    // 不划算：LZMA2 解错的表现是**静默产出坏字节**，坏在编译器二进制里
    // 要等到某次 zig cc 莫名崩掉才暴露。110 KB 的成熟实现换掉它，
    // 顺带白拿 CRC 校验和 BCJ/delta 过滤器支持。0BSD 许可，无传染性。
    implementation("org.tukaani:xz:1.12")

    implementation("org.jsoup:jsoup:1.17.2")
    implementation("net.dankito.readability4j:readability4j:1.0.8")

    // androidx.webkit 删了：零引用，WebViewScreen / EditorScreen 用的是框架
    // 自带的 android.webkit.WebView。留下的只有 38 个
    // org.chromium.support_lib_boundary 空接口。
    implementation("androidx.lifecycle:lifecycle-process:2.8.6")

    // Vosk offline speech recognition — on-device Kaldi-based ASR.
    // Models (40–80 MB) are downloaded into app-private storage at
    // runtime so the APK itself stays slim.
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
    implementation("net.java.dev.jna:jna:5.13.0@aar")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
