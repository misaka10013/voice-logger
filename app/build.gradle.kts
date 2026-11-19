// ============================================================================
// app 模块构建脚本
// ----------------------------------------------------------------------------
// 目标设备：Redmi K60 至尊版 / Android 16 (API 36) / arm64-v8a
// 编译环境：SDK 35（本机已装最高版本），可正常运行在 API 36 上
// ============================================================================

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
