package com.apex.memkit.core.procedure

import com.apex.memkit.core.ActionPerformer
import com.apex.memkit.core.KitClock
import com.apex.memkit.core.KitLogger
import com.apex.memkit.core.MemKitConfig
import com.apex.memkit.core.model.MacroSkill
import kotlinx.coroutines.delay

/**
 * 宏回放器 —— 逐步验证的「肌肉记忆」执行。
 *
 * 继承旧 BypassExecutionEngine 的核心纪律（每步执行后捕获屏面、状态
 * 偏离即熔断），两处修正：
 * - 验证口径与蒸馏同源：用 [MacroDistiller.overlapRatio]（旧实现自称
 *   Jaccard 实为三档硬编码，阈值 0.95 的前缀分支是死路）；
 * - 步数熔断显式化：[MemKitConfig.Procedure.maxStepsPerReplay] 上限
 *   先于执行判定，失控循环不再可能。
 *
 * 每步成本诚实声明：一步 = 一次 UI 捕获 + 一次降维 + 若干次哈希 ——
 * 「微秒级」旧宣称不实；真实收益是**0 token 与决策延迟的消除**。
 */
class MacroRunner(
    private val config: MemKitConfig,
    private val performer: ActionPerformer,
    private val clock: KitClock,
    private val logger: KitLogger,
) {

    sealed class Result {
        /** 执行成功：[steps] 为已执行步数。 */
        data class Executed(val steps: Int) : Result()

        /** 状态偏离 / 执行失败：[reason] 供回退 LLM 的诊断。 */
        data class Failed(val reason: String) : Result()
    }

    /**
     * 回放宏。
     *
     * @param sceneProbe 每步执行后的屏面指纹采集（宿主注入：捕获 → 降维 → 指纹集合；
     *   返回 null = 无法观测，立即失败回退 LLM）
     */
    suspend fun run(
        macro: MacroSkill,
        sceneProbe: suspend () -> Set<String>?,
    ): Result {
        if (macro.steps.isEmpty()) return Result.Failed("空宏")
        if (macro.steps.size > config.procedure.maxStepsPerReplay) {
            return Result.Failed("宏步数 ${macro.steps.size} 超回放上限 ${config.procedure.maxStepsPerReplay}")
        }
        for ((index, step) in macro.steps.withIndex()) {
            val stepNo = index + 1
            if (!performer.perform(step.action)) {
                return Result.Failed("第 $stepNo 步动作执行失败：${step.action.notation()}")
            }
            delay(config.procedure.actionIntervalMs)
            val current = sceneProbe() ?: return Result.Failed("第 $stepNo 步后无法观测屏面")
            val expected = step.nextAnchorFingerprints
            if (expected.isEmpty()) continue
            val overlap = MacroDistiller.overlapRatio(expected, current)
            if (overlap < config.procedure.entryMatchThreshold) {
                logger.log(
                    KitLogger.Level.WARN, TAG,
                    "宏 ${macro.skillId} 第 $stepNo 步状态偏离（重合率 ${"%.2f".format(overlap)}）—— 熔断回退 LLM",
                )
                return Result.Failed("第 $stepNo 步状态偏离（重合率 ${"%.2f".format(overlap)}）")
            }
        }
        return Result.Executed(macro.steps.size)
    }

    companion object {
        private const val TAG = "MacroRunner"
    }
}
