# apex-kit-memory

> `com.apex.memkit:memory-core` —— 纯 Kotlin JVM 统一认知记忆引擎（零 Android 依赖）
> `com.apex.memkit:memory-graph-ui` —— 记忆图谱交互可视化 Compose 库（六类节点 × 六类关系边）

把「屏幕感知转化为可复用程序性记忆」与「对话沉淀为长期事实」收进一个可移植库：
UI 场景差分摄取、类型化宏技能（验证回放 / 别名桥匹配）、聊天事实账本
（启发式 + LLM 蒸馏）、MCP 语义知识图谱 —— 全部运行在原子 JSON 文档存储与
单写者管道之上；`memory-graph-ui` 再把全部五轨记忆画成一张可漫游的关系网：
力导向布局、捏合缩放、点按节点看记忆内幕、类型过滤 / 搜索 / 图例，
以及 store 修订号驱动的自动软刷新 —— 记忆生长实时可见且不打断探索。

主仓库 [Android-Guru-Agent](https://github.com/Ultra-Guru/Android-Guru-Agent)
经 composite build 消费本库（兄弟目录 `../apex-kit-memory` 或嵌套
`apex-kit-memory` 命中 `settings.gradle.kts` 条件 includeBuild 时按坐标
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

```
com.apex.memkit.graph（memory-graph-ui，Android Compose 库）
├── graph/                          GraphModels（六类 MemoryNode / 六类 MemoryEdge / 类型化载荷）
│                                   MemoryGraphBuilder（确定性构建：cap 截取 / 应用簇聚合 / 边推导）
│                                   GraphSource（端口：load 快照 + changes 变更信号）
│                                   + MemKitGraphSource（门面适配，接 store 修订号）+ DemoGraphData
├── layout/                         ForceDirectedLayout（spring-embedder：斥力/弹簧/簇重力/向心，
│                                   seeded 确定性 / pinned 锚定 / relax 邻域松弛 —— 纯 Kotlin 可测）
├── canvas/                         MemoryGraphCanvas（平移/捏合/节点拖拽手势 + LOD 渲染：
│                                   类别色圆+字符+状态点+晶化金环+选中/搜索环、曲线箭头边、
│                                   屏幕空间标签影描边）+ GraphPalette（类别色固定色相）+ 相机
├── detail/                         NodeDetailSheet + 六类详情面板（情景目标/派生宏跳转、宏步骤
│                                   时间线+能量条、事实重要性、实体观察与关系、节点指纹、簇样本）
└── screen/                         MemoryGraphState（状态机：装载/保位过滤/搜索/漫游跳转/拖拽
                                    + 变更信号去抖软刷新：保位重排/选中保留/探索态相机稳定）
                                    + MemoryGraphScreen / MemoryGraphContent（统计条+过滤搜索+图例）
```

**六类记忆节点**：情景（蓝·情）· 宏技能（紫·宏，晶化金环）· 聊天事实
（青·忆，按画像/近况/里程碑聚团）· 知识实体（琥珀·知）· UI 场景节点
（蓝灰·界，可选层）· 应用簇（绿·簇）。

**六类关系边**：蒸馏产出（情景→宏）· 宏链衔接（出口指纹→入口指纹，长划线）·
观察关系（情景→节点/应用，短划线）· 应用归属（宏/节点→簇，点线）·
知识关系（实体↔实体，带 relationType 标签）· 版本别名桥（锈红虚点线）。

**交互**：单指拖空白平移 / 拖节点移动（松手邻域松弛）· 双指捏合缩放 ·
点按节点选中（邻接高亮、其余淡出）+ 详情 Sheet · 详情内跳转芯片漫游
（被过滤类型自动点亮）· 类型过滤 chips（保位增量布局）· 搜索命中橙环 ·
图例对话框 · 重置视图 / 重新布局 / 缩放按钮。

**自动软刷新（记忆生长实时可见）**：store 的每次写入都驱动修订号
（`MemoryStore.revision`，markDirty 单点）—— 生产源把它接成变更信号
（`MemoryGraphSource.changes()`），状态机去抖合并（1.5s 窗口）后无感重拉
快照：新记忆在画布上邻域松弛落位、被删记忆自然消失，而存活节点保位、
选中保留、探索态相机稳定 —— 后台任务边跑边看图，不闪进度条不打断探索；
软刷新失败静默保持现状，首装/手动刷新在途时信号让位。静态源（无信号）
行为与旧版完全一致。

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

### 记忆图谱 UI（memory-graph-ui）

```kotlin
// 宿主 Compose 环境任意位置（零 Hilt / 零导航依赖）：
val source = MemKitGraphSource(memKit)          // 或任意 MemoryGraphSource 实现

// 整页形态（自带 Scaffold + 顶栏 + 图例 + 刷新）
MemoryGraphScreen(source = source)

// 嵌入形态（宿主已有外壳时 —— 统计条/过滤/搜索/画布/详情 Sheet 全含）
val state = rememberMemoryGraphState(source)
MemoryGraphContent(state = state, modifier = Modifier.fillMaxSize())
```

## 构建与测试

```bash
./gradlew :memory-core:test                  # 65 项纯 JVM 测试
./gradlew :memory-graph-ui:testDebugUnitTest # 43 项纯 JVM 测试
                                            #（构建器 14 / 布局 8 / 源适配 2 / 相机 4 /
                                            #  状态机 9 / 自动软刷新 6）
./gradlew publishToMavenLocal                # 双构件坐标冒烟（memory-core + memory-graph-ui）
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
