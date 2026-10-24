package com.apex.memkit.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 宏技能（MacroSkill）—— 程序性记忆：一条「入口状态 → 类型化动作序列 →
 * 出口状态」的可回放技能。
 *
 * 对旧 `FSMMacro` 的三处根治：
 * 1. **动作类型化**：`actionParams` 裸字符串（三处正则双保险解析）改为
 *   [MacroAction] sealed 层次，序列化即结构；
 * 2. **入口集合匹配**：单一 `initialFingerprint` 改为 [entryFingerprints]
 *   集合 + 覆盖率阈值匹配，屏面轻微抖动不再失配；
 * 3. **appPackage 全程贯穿**：从捕获端口单源取得并随宏存储 / 随查询精确
 *   匹配，修复旧系统「采集侧 null、匹配侧前台包」的 P0 断链。
 */
@Serializable
data class MacroSkill(
    val skillId: String,
    val name: String,
    val goal: String,
    val appPackage: String? = null,
    val appVersion: String? = null,
    /** 入口状态锚点指纹集合（蒸馏自任务首锚点屏）。 */
    val entryFingerprints: Set<String>,
    /** 出口状态锚点（回放终验 + 邻接宏链接用）。 */
    val exitFingerprint: String,
    /** 类型化动作序列（含每步的状态锚点：执行后必经屏）。 */
    val steps: List<MacroStep>,
    val stats: MacroStats = MacroStats(),
    /** 溯源情景（旧系统恒 null —— 现为蒸馏时的真实回填）。 */
    val sourceEpisodeId: String? = null,
    val createdAt: Long,
)

/**
 * 宏的一步：动作 + 期望的下一个状态锚点集合。
 *
 * 回放器每步执行后捕获屏面，验证当前指纹集合与 [nextAnchorFingerprints]
 * 的重合达标才继续 —— 状态偏离（广告弹窗 / 界面改版）立即熔断回退 LLM。
 */
@Serializable
data class MacroStep(
    val action: MacroAction,
    /** 步前置状态锚点（该步执行时屏面应覆盖的指纹集合）。 */
    val anchorFingerprints: Set<String>,
    /** 步后状态锚点（执行后屏面应覆盖的指纹集合）。 */
    val nextAnchorFingerprints: Set<String>,
)

/**
 * 类型化宏动作 —— 宿主 [com.apex.memkit.core.ActionPerformer] 的执行契约。
 *
 * 覆盖旧引擎轨迹中全部 UI 动作形态；非 UI 动作（读文件 / 搜索等）在蒸馏
 * 阶段即被过滤，不进入宏（旧系统把 `SystemInfo(...)` 之类也喂进轨迹再靠
 * canonicalActionType 兜底，已收敛到蒸馏入口一处过滤）。
 */
@Serializable
sealed class MacroAction {

    /** 点按坐标。 */
    @Serializable
    @SerialName("tap")
    data class Tap(val x: Int, val y: Int) : MacroAction()

    /** 滑动（起点 → 终点，时长毫秒）。 */
    @Serializable
    @SerialName("swipe")
    data class Swipe(
        val x1: Int, val y1: Int, val x2: Int, val y2: Int,
        val durationMs: Long = 300,
    ) : MacroAction()

    /** 输入文本。 */
    @Serializable
    @SerialName("input")
    data class Input(val text: String) : MacroAction()

    /** 系统返回键。 */
    @Serializable
    @SerialName("back")
    data object Back : MacroAction()

    /** 回到桌面。 */
    @Serializable
    @SerialName("home")
    data object Home : MacroAction()

    /** 人类可读记法（宏列表 / 召回工具输出用）。 */
    fun notation(): String = when (this) {
        is Tap -> "Tap($x,$y)"
        is Swipe -> "Swipe($x1,$y1,$x2,$y2${if (durationMs != 300L) ",$durationMs" else ""})"
        is Input -> "Input(\"$text\")"
        is Back -> "Back"
        is Home -> "Home"
    }
}

/** 宏技能统计（能量模型：使用增能、时间衰减、高频高成晶化）。 */
@Serializable
data class MacroStats(
    val successCount: Int = 0,
    val failureCount: Int = 0,
    /** 能量（0..10）：成功回放 +1、衰减周期 ×系数、低于阈值被剪枝。 */
    val energy: Float = 1.0f,
    /** 晶化：能量与成功率达标后的永久固化（免疫衰减 / 剪枝 / 淘汰）。 */
    val crystallized: Boolean = false,
    val lastExecutedAt: Long = 0,
    val lastValidatedAt: Long = 0,
) {
    fun totalRuns(): Int = successCount + failureCount
    fun successRate(): Float = if (totalRuns() == 0) 0f else successCount.toFloat() / totalRuns()
}

/**
 * 拓扑别名 —— 跨版本指纹桥（App 升级导致 resourceId/文案漂移时，
 * 旧入口指纹经别名解析为新指纹，宏不集体失配）。
 *
 * 设计沿用旧 migration_map 的「别名桥而非改写历史」+「召回侧正向校验」
 * 口径，但触发源修正为**目标 App** 版本变化（旧系统错比对宿主自身版本）。
 */
@Serializable
data class FingerprintAlias(
    val oldFingerprint: String,
    val newFingerprint: String,
    val appPackage: String? = null,
    val score: Float,
    val fromVersion: String? = null,
    val toVersion: String? = null,
    val createdAt: Long,
)

/** 维护周期报告（宿主 UI / 日志消费；字段可变 —— 维护步骤逐项回填）。 */
data class MaintenanceReport(
    var decayedMacros: Int = 0,
    var prunedMacros: Int = 0,
    var prunedNodes: Int = 0,
    var crystallizedMacros: Int = 0,
    var refreshedMacros: Int = 0,
    var newAliases: Int = 0,
    var prunedEpisodes: Int = 0,
    var quarantinedExpired: Int = 0,
    var errors: List<String> = emptyList(),
) {
    fun describe(): String = buildString {
        append("宏衰减 $decayedMacros / 剪枝 $prunedMacros / 节点剪枝 $prunedNodes / 晶化 $crystallizedMacros")
        if (refreshedMacros > 0) append(" / 陈旧保鲜 $refreshedMacros")
        if (newAliases > 0) append(" / 新别名 $newAliases")
        if (prunedEpisodes > 0) append(" / 情景淘汰 $prunedEpisodes")
        if (quarantinedExpired > 0) append(" / 隔离过期放行 $quarantinedExpired")
    }
}
