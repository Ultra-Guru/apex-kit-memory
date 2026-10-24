package com.apex.memkit.core.procedure

import com.apex.memkit.core.MemKitConfig
import com.apex.memkit.core.model.MacroAction
import com.apex.memkit.core.model.MacroSkill
import com.apex.memkit.core.model.MacroStep
import java.security.MessageDigest

/**
 * 轨迹步骤 —— 会话期间的 UI 动作观测记录（蒸馏的原料）。
 *
 * @param beforeFps 动作执行前的屏面指纹集合
 * @param action 类型化动作（宿主侧经 [ActionNotation] 解析一次后传入）
 * @param notation 原始动作记法（日志 / 归档用）
 * @param success 动作是否执行成功（失败动作不进宏 —— 鼠标连点失败不是技能）
 * @param afterFps 动作执行后的屏面指纹集合
 */
data class TraceStep(
    val beforeFps: Set<String>,
    val action: MacroAction,
    val notation: String,
    val success: Boolean,
    val afterFps: Set<String>,
)

/**
 * 宏蒸馏器 —— 成功任务的轨迹 → 程序性记忆（宏技能）。
 *
 * 三阶段（继承旧 TraceDistiller 的框架，四处根治）：
 * 1. **过滤**：只保留成功的 UI 步骤（非 UI 动作在 [ActionNotation] 处即
 *   已折 null，不再依赖 when 字符串匹配兜底）；
 * 2. **压缩**：连续相同动作去重；
 * 3. **编译**：按状态锚点（前后指纹重合率跌破
 *   [MemKitConfig.Procedure.anchorChangeRatio] 即发生状态跃迁）切段，
 *   每段挑一个代表性动作（Tap > Input > Swipe 优先）。
 *
 * 对旧实现的两处根治：
 * - **确定性 id**：`macro_ + SHA-256(goal|entry|steps)` —— 旧 id 带随机
 *   时间戳后缀，同任务再蒸馏产生近似重复宏且外部体系永远查不到；
 *   确定性 id 让重复蒸馏自动归并为同一宏的强化；
 * - **入口集合**：旧「首帧第一个节点指纹」语义极不稳健（首个节点
 *   常是不可见容器）；新入口 = 首锚点屏面的指纹集合，匹配用覆盖率。
 */
object MacroDistiller {

    /** 蒸馏入口。无可蒸馏轨迹（步数不足 / 全失败）返回 null。 */
    fun distill(
        trace: List<TraceStep>,
        goal: String,
        appPackage: String?,
        appVersion: String?,
        sourceEpisodeId: String,
        config: MemKitConfig,
        now: Long,
    ): MacroSkill? {
        // 1. 过滤：成功 UI 步骤
        val effective = trace.filter { it.success }
        if (effective.size < config.procedure.minStepsForDistill) return null

        // 2. 压缩：连续相同动作去重（连点场景取首击；回放器不重放无效重复）
        val compressed = ArrayList<TraceStep>(effective.size)
        for (step in effective) {
            val last = compressed.lastOrNull()
            if (last != null && last.action == step.action) continue
            compressed.add(step)
        }
        if (compressed.size < config.procedure.minStepsForDistill) return null

        // 3. 锚点切段：相邻步骤间重合率跌破阈值 = 状态跃迁
        val ratio = config.procedure.anchorChangeRatio
        val boundaryIdx = compressed.indices.filter { i ->
            val step = compressed[i]
            i == 0 || overlapRatio(step.beforeFps, step.afterFps) < ratio
        }
        if (boundaryIdx.size < config.procedure.minStepsForDistill) return null

        // 每段（锚点 i → 锚点 i+1）挑一个代表性动作：Tap > Input > Swipe > 其它
        val steps = ArrayList<MacroStep>(boundaryIdx.size)
        for (k in boundaryIdx.indices) {
            val from = boundaryIdx[k]
            val to = boundaryIdx.getOrNull(k + 1)?.minus(1) ?: compressed.lastIndex
            if (to < from) continue  // 相邻双锚（瞬时跳变）无段可代表
            val segment = compressed.subList(from, to + 1)
            val action = pickRepresentative(segment) ?: continue
            steps.add(
                MacroStep(
                    action = action,
                    anchorFingerprints = compressed[from].beforeFps,
                    nextAnchorFingerprints = compressed[to].afterFps,
                ),
            )
        }
        if (steps.size < config.procedure.minStepsForDistill) return null

        val entry = steps.first().anchorFingerprints
        val exit = steps.last().nextAnchorFingerprints.firstOrNull() ?: steps.last().anchorFingerprints.firstOrNull() ?: return null
        val skillId = deterministicId(goal, entry, steps)

        return MacroSkill(
            skillId = skillId,
            name = distillName(goal),
            goal = goal,
            appPackage = appPackage,
            appVersion = appVersion,
            entryFingerprints = entry,
            exitFingerprint = exit,
            steps = steps,
            sourceEpisodeId = sourceEpisodeId,
            createdAt = now,
        )
    }

    /** 段内代表性动作：Tap > Input > Swipe > 其它（段内首现优先）。 */
    private fun pickRepresentative(segment: List<TraceStep>): MacroAction? =
        segment.firstNotNullOfOrNull { it.action as? MacroAction.Tap }
            ?: segment.firstNotNullOfOrNull { it.action as? MacroAction.Input }
            ?: segment.firstNotNullOfOrNull { it.action as? MacroAction.Swipe }
            ?: segment.first().action

    /** 集合重合率：|交| / max(|from|, 1)。 */
    fun overlapRatio(from: Set<String>, to: Set<String>): Float {
        if (from.isEmpty()) return 1f
        val inter = from.intersect(to).size
        return inter.toFloat() / from.size
    }

    /** 确定性 id：goal + 入口 + 步骤记法的稳定哈希。 */
    private fun deterministicId(goal: String, entry: Set<String>, steps: List<MacroStep>): String {
        val payload = buildString {
            append(goal.trim())
            append('|')
            entry.toSortedSet().forEach { append(it); append('|') }
            append('|')
            steps.forEach { append(it.action.notation()).append(';') }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(20).let { "macro_$it" }
    }

    /** 宏名：目标文本前 4 词（沿用旧语义）。 */
    fun distillName(goal: String): String {
        val words = goal.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        return if (words.isEmpty()) "技能" else words.take(4).joinToString(" ")
    }
}
