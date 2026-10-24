pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    // 工具链自动供给：本地无 JDK 17 时自动下载（CI 已由 setup-java 提供，不触发）
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // memory-graph-ui 的 Compose / AndroidX 依赖线（google() 只收 Google 系
        // group —— NIA 同款纪律，防其它坐标误走 google() 拉到非预期构件）。
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}
rootProject.name = "apex-kit-memory"
include(":memory-core")
// 记忆图谱 UI（Android Compose 库）：六类记忆节点 + 六类关系边的交互式
// 可视化 —— 力导向布局 / 缩放平移拖拽 / 点击节点看内部记忆 / 过滤搜索图例。
// 零 Hilt 零导航依赖（宿主经 MemoryGraphSource 端口喂数据，任意 Compose
// 环境可接入）；纯逻辑（图谱构建 / 布局引擎 / 状态机）全部下沉到
// src/main/kotlin 的非 Android 包，testDebugUnitTest 纯 JVM 可跑。
include(":memory-graph-ui")
