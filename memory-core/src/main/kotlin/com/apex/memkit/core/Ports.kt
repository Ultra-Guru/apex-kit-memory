package com.apex.memkit.core

import com.apex.memkit.core.model.MacroAction

/**
 * 宿主端口 —— 库与平台能力之间的唯一接缝（端口-适配器）。
 *
 * 本库为纯 Kotlin JVM（零 Android 依赖）：UI 捕获、UI 动作执行、LLM 调用、
 * 时钟与日志全部经本文件定义的端口由宿主注入。宿主适配层的职责就是把
 * 平台类型映射到这些端口类型 —— 一次映射、处处复用（这是旧记忆系统
 * 「同一 UI 树走嵌套/扁平两条路径导致指纹系统性不一致」P0 断链的根治：
 * 采集与匹配共用同一端口产物，不存在第二条指纹路径）。
 */

/** 库内时钟（毫秒 epoch）。宿主注入 `System::currentTimeMillis`；测试注入假钟。 */
fun interface KitClock {
    fun now(): Long
}

/** 库内日志端口。宿主映射到自有日志中枢；实现必须吞掉一切自身异常。 */
interface KitLogger {
    fun log(level: Level, tag: String, message: String, error: Throwable? = null)

    enum class Level { DEBUG, INFO, WARN, ERROR }
}

/** 库内可用的空实现（测试 / 宿主未注入时静默）。 */
object SilentLogger : KitLogger {
    override fun log(level: KitLogger.Level, tag: String, message: String, error: Throwable?) {
        // 静默
    }
}

/**
 * 一次 UI 捕获的原始产物（宿主 → 库）。
 *
 * [roots] 必须是**真实嵌套树**（父节点 children 已填充）——语义降维的父
 * 上下文指纹依赖真实层级。宿主侧禁止喂「剥离子节点引用的扁平视图」，
 * 否则指纹与采集侧不可比对（这正是旧系统 P0-2 的教训）。
 *
 * @param appPackage 被捕获前台 App 包名（未知为 null；**这是 appPackage 的唯一
 *   真源**，Episode / 宏 / 匹配全程用它贯穿 —— 修复旧系统「采集侧 null、匹配侧
 *   取前台包」的断链）
 * @param appVersion 被捕获 App 版本名（未知为 null）—— 语义是**目标 App** 的
 *   版本而非宿主自身版本；拓扑别名迁移据此触发（旧系统把宿主版本错记在节点上，
 *   导致外部 App 升级指纹漂移永远无触发源）
 */
data class RawCapture(
    val roots: List<RawNode>,
    val appPackage: String? = null,
    val appVersion: String? = null,
    val activityLabel: String? = null,
)

/**
 * 原始 UI 节点（宿主无障碍树节点 → 库的中性投影）。
 *
 * 字段与常见无障碍节点（AccessibilityNodeInfo）一一对应；[bounds] 为
 * `"[x1,y1][x2,y2]"` 文本形式（与宿主既有捕获通道同构，避免宿主侧二次解析）。
 */
data class RawNode(
    val className: String,
    val text: String,
    val contentDescription: String,
    val resourceId: String,
    val bounds: String,
    val clickable: Boolean,
    val scrollable: Boolean,
    val children: List<RawNode> = emptyList(),
)

/**
 * UI 捕获端口（库 ← 宿主）。
 *
 * 实现约定：
 * - 捕获失败 / 无障碍不可用 / 屏幕无内容 → 返回 null（库按「无观测」处理，
 *   绝不抛异常击穿调用方）；
 * - 返回的 [RawCapture.roots] 必须是嵌套树（见 [RawCapture] 契约）。
 */
fun interface SceneCapture {
    suspend fun capture(): RawCapture?
}

/**
 * UI 动作执行端口（库 → 宿主）。
 *
 * 宏技能回放的唯一执行通道。实现应把类型化动作映射为平台 UI 动作
 * （无障碍手势 / root input 等），返回是否执行成功。
 */
fun interface ActionPerformer {
    suspend fun perform(action: MacroAction): Boolean
}

/**
 * 聊天蒸馏的 LLM 端口（库 ← 宿主）。
 *
 * 宿主把提示词交给自家模型运行时（建议路由到便宜模型）并返回原始响应文本；
 * 失败 / 超时返回 null —— 蒸馏队列保留待重试，绝不抛异常。
 *
 * @param complete 单轮补全：[system] 为协议提示词（含写入门禁与输出格式约定），
 *   [user] 为待蒸馏的多轮对话窗口文本。
 */
fun interface ChatModel {
    suspend fun complete(system: String, user: String): String?
}
