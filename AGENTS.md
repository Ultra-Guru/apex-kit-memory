# AGENTS.md — apex-kit-memory 仓库协作规则

> 本文件是库仓库自身的规则文件：宿主 Android-Guru-Agent 的 Coding 模式
> Rules 系统会自动发现工作区根的 AGENTS.md 并注入会话上下文。优先级：
> 本文件 > 用户全局规则 > 一般偏好；均低于安全与权限约束。

## 构建与验证

- **模块**：纯 Kotlin JVM 单模块（`memory-core`，坐标
  `com.apex.memkit:memory-core`），Kotlin 2.2.21 + Gradle 8.14 工具链 +
  JDK 17 + coroutines 1.10.2 + serialization 1.9.0 —— 与宿主
  Android-Guru-Agent 的 composite build 兼容矩阵对齐（同 apex-kit-courier
  / apex-loom-kit / apex-standard-kit）；**宿主升级 Kotlin 后本库必须同步**，
  否则下游复合构建解析失败；
- **本地验证**：`./gradlew :memory-core:test`（71 项纯 JVM 测试）+
  `publishToMavenLocal` 坐标冒烟；
- **纯度铁律**：`memory-core/src/main` 禁止任何 `android.* / androidx.* /
  java.awt / javax.swing` import；`java.io.*` 仅允许出现在
  `store/Storage.kt`（CI guard-rails 硬门禁）；
- **零宿主词汇**：库源码禁止出现宿主项目坐标/词汇（CI vocabulary-scan
  硬门禁）—— 库不 import 任何宿主类型，宿主类型一律经 `Ports.kt` 的
  端口（SceneCapture / ActionPerformer / ChatModel / KitClock / KitLogger）
  由适配层注入。

## 硬性 CI 门禁（违反即红）

1. **文件预算**：main 单文件 ≤ 700 行 / test ≤ 600 行（超了按职责缝拆）；
2. **括号平衡**：改完跑 `python3 scripts/kotlin_balance.py <files>`
   （如无该脚本，宿主仓有同款可借用）；
3. **测试纪律**：JUnit4 + kotlinx-coroutines-test（`runTest`）；无 mock
   框架，手写 fake（见 `TestHarness.kt`）；断言值先读源码核实，不臆造。

## 架构原则（本库的核心不变量）

- **单写者纪律**：`MemoryStore` 全部公开操作经同一把 mutex；新增状态
  一律收编进 MemoryStore（状态）+ ChatGraphOps（操作）的既有模式；
- **捕获单源**：所有指纹必须出自「SceneCapture → SceneReducer.reduce」
  这一条路径 —— 任何旁路自算指纹都是 P0 级回归（见 MacroLifecycleTest
  的 P0-1/P0-2 回归锁）；
- **appPackage 单源**：只能来自捕获产物，贯穿 Episode / 宏 / 匹配；
- **类型化动作**：引擎动作字符串只在 `ActionNotation` 解析一次，
  宏步骤一律 `MacroAction` sealed 实例；
- **确定性 id**：宏 id = SHA-256(goal|entry|steps)，禁止时间戳/随机后缀；
- **防御式 IO**：坏 JSON 折叠为空文档 + 备份留档；捕获失败静默降级；
  一切端口实现失败不阻断调用方；
- **调参收口**：新增数值阈值必须进 `MemKitConfig`，禁止散落魔法数。

## 提交与 PR 约定

- 提交信息：`类型(范围): 中文摘要 —— 关键词 / 关键词`；
- PR 描述含验证表（编译/测试/门禁结果）；
- PR 由仓库主人合并（agent 只提交不合并）；
- 破坏 API 变更看 guard-rails 的 api-surface 工件 diff，README 兼容
  矩阵同步更新。
