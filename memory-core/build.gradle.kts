import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.maven.publish)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { jvmToolchain(17) }

dependencies {
    // 零 Android 依赖：纯 Kotlin JVM 库。协程为写入管道 / 蒸馏批处理 / 维护周期
    // 的运行时底座；serialization-json 为全部持久化文档与类型化动作的编解码层
    // （容错式使用 —— 坏 JSON 一律折叠降级为空文档 + 备份留档，绝不击穿记忆面）。
    api(libs.coroutines.core)
    implementation(libs.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}

// composite build 依赖替换：宿主 includeBuild 时按 group:name 自动替换远端坐标
group = "com.apex.memkit"
version = "1.0.0"

mavenPublishing {
    configure(KotlinJvm(javadocJar = JavadocJar.None(), sourcesJar = true))
    coordinates(
        groupId = "com.apex.memkit",
        artifactId = "memory-core",
        // 与上方 project version 同源（CI smoke check 按此 grep 校验 mavenLocal 产物）
        version = project.version.toString(),
    )
    pom {
        name.set("Apex Kit Memory :: Memory Core")
        description.set(
            "Pure-Kotlin unified cognitive memory engine: UI-scene differential ingest with " +
                "content fingerprints, procedural macro skills (typed actions, validated replay, " +
                "alias-bridged matching), chat fact ledger with heuristic + LLM distillation, " +
                "and an MCP-compatible knowledge graph — all on an atomic JSON document store " +
                "with a single-writer pipeline (zero Android deps)"
        )
        url.set("https://github.com/Ultra-Guru/apex-memory-kit")
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
            connection.set("scm:git:git@github.com:Ultra-Guru/apex-memory-kit.git")
            url.set("https://github.com/Ultra-Guru/apex-memory-kit")
        }
    }
}
