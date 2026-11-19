// ============================================================================
// app 模块构建脚本
// ----------------------------------------------------------------------------
// 目标设备：Redmi K60 至尊版 / Android 16 (API 36) / arm64-v8a
// 编译环境：SDK 35（本机已装最高版本），可正常运行在 API 36 上
// ============================================================================

import java.io.File

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.misaka.voicelog"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.misaka.voicelog"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // 只保留 arm64-v8a：设备实测 abilist 首项即 arm64-v8a
        // 去掉 armeabi-v7a/x86/x86_64 可显著缩小 APK 体积
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    /* CI 上用环境变量显式指定调试签名。
       原因：AGP 查找默认 debug keystore 的目录会受 ANDROID_SDK_HOME / ANDROID_USER_HOME
       影响，本地是 ~/.android，而 CI runner 上这两个变量存在，AGP 会去别处找，
       找不到就**自己生成一把随机密钥** —— 这样出的 APK 与手机已装版本签名不一致，
       必须先卸载才能装（本地数据库会一起丢）。
       本地不设 DEBUG_KEYSTORE 时这段不生效，行为与原来完全一致。 */
    signingConfigs {
        getByName("debug") {
            val ksPath = System.getenv("DEBUG_KEYSTORE")
            if (!ksPath.isNullOrBlank() && File(ksPath).exists()) {
                storeFile = File(ksPath)
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
    }

    // sherpa-onnx 的 .so 不需要再压缩，解压安装减少首次启动耗时
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    // AAR 里的 onnxruntime 依赖需要显式下载，避免被 abiFilters 误删
    androidResources {
        noCompress += "onnx"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // sherpa-onnx 本地推理库（官方 v2.0.1 Release，arm64-v8a 内含）
    implementation(files("libs/sherpa-onnx.aar"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
