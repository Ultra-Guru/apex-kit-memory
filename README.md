# apex-memory-kit

> `com.apex.memkit:memory-core` —— 纯 Kotlin JVM 统一认知记忆引擎（零 Android 依赖）

把「屏幕感知转化为可复用程序性记忆」与「对话沉淀为长期事实」收进一个可移植库：
UI 场景差分摄取、类型化宏技能（验证回放 / 别名桥匹配）、聊天事实账本
（启发式 + LLM 蒸馏）、MCP 语义知识图谱 —— 全部运行在原子 JSON 文档存储与
单写者管道之上。

主仓库 [Android-Guru-Agent](https://github.com/Ultra-Guru/Android-Guru-Agent)
经 composite build 消费本库（兄弟目录 `../apex-memory-kit` 或嵌套
`apex-memory-kit` 命中 `settings.gradle.kts` 条件 includeBuild 时按坐标
`com.apex.memkit:memory-core` 自动替换为本地构建）。

## 架构一览

```
com.apex.memkit.core
├── Ports.kt / MemKitConfig.kt     宿主端口（SceneCapture / ActionPerformer / ChatModel / Clock / Logger）+ 全量调参面
├── model/                          Scene · Episode · MacroSkill(类型化 MacroAction) · ChatFact · Graph · Alias
├── store/                          StorageBackend 端口 + 文件/内存实现 + MemoryStore（内存索引 + dirty 合并落盘）
├── ingest/                         SceneFingerprint · SceneReducer（语义降维）· SceneDiffer（差分）· IngestGuard（摄取防护）
├── procedure/                      ActionNotation（唯一解析点）· MacroDistiller · MacroMatcher · MacroRunner · MacroMaintenance
├── chat/                           ChatSignals（启发式）· ChatDistiller（LLM 协议）· ChatRecall · ChatEngine
├── graph/ 语义在 store/ChatGraphOps（MCP memory 对齐）
├── session/                        SessionDirector（并发安全会话编排）
└── MemKit.kt                       门面（tryBypass 旁路 / 维护周期 / 查询面 / 写入管道）
```

### 相对旧 :platform:cs-mem 的关键修复

| 旧系统问题 | 本库根治 |
|---|---|
| 宏匹配 appPackage 断链（采集侧 null、匹配侧前台包）| 捕获端口单源提供 appPackage，Episode / 宏 / 匹配全程贯穿（P0-1 回归锁见 MacroLifecycleTest）|
| 采集与匹配两条指纹路径（嵌套树 vs 扁平剥离视图）系统性不一致 | 唯一降维路径，匹配与采集共用（P0-2）|
| actionParams 裸字符串三处正则解析 | `MacroAction` sealed 类型化 + `ActionNotation` 唯一解析点 |
| 单指纹 + 0.95 阈值（实为仅精确匹配）| 入口指纹集合覆盖率匹配 + 拓扑别名桥（正向校验闭环）|
| Room N+1（逐节点 UPDATE）| 内存态单写者 + dirty 文档合并落盘 |
| @Singleton 会话状态无并发保护 | SessionDirector 会话键控 |
| 宏 id 带随机时间戳（不可追溯 / 近重复膨胀）| 确定性 id：同任务再蒸馏自动归并强化 |
| Episode 统计字段恒为默认值 | 只保留能被真实写入的字段 |
| appVersion 错记宿主自身版本 | 捕获端口提供目标 App 版本，别名迁移真正可触发 |
| 维护逻辑三处重复（熵/梦/Actor 死事件）| MacroMaintenance 唯一实现 |
| 隔离键裸字符串拼接 + 永不过期 | 节点指纹键 + TTL 过期放行 |
| cs-mem 与 builtin/memory 双轨零复用 | 聊天记忆 / 图谱 / UI 轨迹统一进库 |

## 使用

```kotlin
val kit = MemKit.create(
    config = MemKitConfig(),
    storage = FileStorageBackend(File(context.filesDir, "memory_kit")),
    capture = sceneCapturePort,       // 可空：无则旁路与采集停用
    performer = actionPerformerPort,  // 可空
    chatModel = llmPort,              // 可空：无则蒸馏停用（启发式照常）
    logger = hostLogger, clock = { System.currentTimeMillis() },
)
kit.start()  // 写入管道

// 任务级隐式采集（宿主观察者契约）
kit.sessions.begin(taskId, goal, appPackage)
kit.sessions.recordAction(taskId, "UiTap(540,1200)", success = true)
kit.sessions.end(taskId, success = true)   // 成功任务自动蒸馏宏

// 每轮 LLM 推理前的肌肉记忆旁路
when (kit.tryBypass()) { is MemKit.BypassResult.Executed -> /* 跳过 LLM */ ... }

// 聊天记忆
kit.chat.onTurn(userText, assistantText)
val remembered = kit.chat.recall(userText)   // 注入 "## Remembered About You" 段

// 周期维护（宿主 WorkManager 调度）
kit.runMaintenanceNow()
```

## 构建与测试

```bash
./gradlew :memory-core:test    # 63 项纯 JVM 测试
./gradlew publishToMavenLocal  # 坐标冒烟
```

工具链矩阵与 [apex-courier-kit](https://github.com/Ultra-Guru/apex-courier-kit) /
apex-loom-kit / apex-standard-kit 对齐：Kotlin 2.2.21 · coroutines 1.10.2 ·
serialization 1.9.0 · JDK 17（composite build 兼容已验证组合；宿主升级后本库同步）。

## CI（三道防线）

1. **build-test**：编译 + 纯 JVM 单测 + publishToMavenLocal 坐标冒烟；
2. **consumer-check**：把宿主 Android-Guru-Agent 检出为兄弟布局编译
   `:app:compileDebugKotlin`（防下游崩；宿主 PR 合入后转 push/PR 触发）；
3. **notify-host**：main 全绿 → `repository_dispatch`（`memory-kit-updated`）
   通知宿主 bump lock。

## 许可

MIT —— 见 [LICENSE](LICENSE)。
