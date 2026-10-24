import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.maven.publish)
}

android {
    namespace = "com.apex.memkit.graph"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        // F2（同 browser-chrome 纪律）：资源加 memgraph_ 前缀，防宿主 R 类冲突
        resourcePrefix = "memgraph_"
    }
    testOptions {
        // 图谱构建 / 布局引擎 / 状态机均为纯 Kotlin —— 单测零 Android API，
        // returnDefaultValues 让类加载安全通过（无需 Robolectric）。
        unitTests.isReturnDefaultValues = true
    }
}

// KGP 2.2：kotlinOptions DSL 已 deprecation —— 项目级 compilerOptions DSL
// （与 memory-core / browser-chrome 同款接线）。
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // 门面与数据模型直供（NodeDictEntry / MacroSkill / EpisodeSummary /
    // ChatFact / GraphEntity / FingerprintAlias 全部自 memory-core 传递）
    api(project(":memory-core"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.compose)

    // 状态机协程（快照加载 / 布局迭代 / 搜索防抖）
    implementation(libs.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}

// composite build 依赖替换：宿主 includeBuild 时按 group:name 自动替换远端坐标
group = "com.apex.memkit"
version = "1.0.0"

mavenPublishing {
    // Android 库走 vanniktech 自动变体配置（release AAR + sources；javadoc 空占位）
    coordinates(
        groupId = "com.apex.memkit",
        artifactId = "memory-graph-ui",
        // 与上方 project version 同源（CI smoke check 按此 grep 校验 mavenLocal 产物）
        version = project.version.toString(),
    )
    pom {
        name.set("Apex Kit Memory :: Graph UI")
        description.set(
            "Interactive Compose visualization of the cognitive memory graph: six memory " +
                "node kinds (episodes, macro skills, chat facts, knowledge entities, scene " +
                "nodes, app clusters) linked by seven typed relation edges, with a pure-Kotlin " +
                "force-directed layout engine, pan/zoom/drag canvas, tap-to-inspect detail " +
                "sheets, filtering, search and legend — zero Hilt, host feeds data via a " +
                "MemoryGraphSource port"
        )
        url.set("https://github.com/Ultra-Guru/apex-kit-memory")
        licenses {
            license {
                name.set("MIT")
                url.set("https://opensource.org/licenses/MIT")
            }
        }
        developers {
            developer { id.set("Ultra-Guru") }
        }
        scm {
            connection.set("scm:git:git@github.com:Ultra-Guru/apex-kit-memory.git")
            url.set("https://github.com/Ultra-Guru/apex-kit-memory")
        }
    }
}
