// ============================================================================
// voice-logger 路 工程设置
// ----------------------------------------------------------------------------
// 独立工程，与作者的另一个 Android 项目平级、互不影响。
// 仓库顺序：google 官方源在前（国内实测可直连），阿里云镜像兜底防止构建卡死。
// ============================================================================

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
    }
}

rootProject.name = "VoiceLogger"
include(":app")
