package com.apex.memkit.core.procedure

import com.apex.memkit.core.KitLogger
import com.apex.memkit.core.MemKitConfig
import com.apex.memkit.core.model.MacroSkill
import com.apex.memkit.core.store.MemoryStore

/**
 * 宏匹配器 —— 当前屏面 → 候选宏。
 *
 * 匹配模型（对旧 `findBestMacro` 的两处根治）：
 * 1. **入口集合覆盖率**：`|当前屏 ∩ 入口锚| / |入口锚| ≥ 阈值` 即命中
 *   （旧「单指纹 + 0.95 阈值 + 三档假 Jaccard」实为仅精确匹配，屏面
 *   轻微抖动即失配）；
 * 2. **appPackage 精确一致**：采集侧与匹配侧的包名同源于捕获端口
 *   （含双方都为 null 的未知情形）—— 旧系统采集侧恒 null、匹配侧取
 *   前台包的 P0 断链不复存在。
 *
 * 别名桥：入口指纹不在当前屏时，经 [MemoryStore.aliasesFrom] 反查
 * 候选新指纹，新指纹确实出现在当前屏才算命中（正向校验闭环）——
 * App 升级后 UI 漂移的宏有出路。
 */
class MacroMatcher(
    private val store: MemoryStore,
    private val config: MemKitConfig,
    private val logger: KitLogger,
) {

    /** 覆盖率与是否全靠别名命中（诊断位）。 */
    data class Match(val macro: MacroSkill, val coverage: Float, val viaAlias: Boolean)

    /**
     * 找最佳宏。
     *
     * @param currentFps 当前屏面指纹集合（与采集同一条降维路径的产物）
     * @param appPackage 当前捕获的包名（同源口径）
     */
    suspend fun findBest(currentFps: Set<String>, appPackage: String?): Match? {
        if (currentFps.isEmpty()) return null
        val threshold = config.procedure.entryMatchThreshold
        val minSuccess = config.procedure.minMacroSuccessBeforeReplay
        var best: Match? = null

        for (macro in store.macrosForPackage(appPackage)) {
            if (macro.stats.successCount < minSuccess) continue
            if (macro.entryFingerprints.isEmpty()) continue
            var hits = 0
            var viaAlias = false
            for (fp in macro.entryFingerprints) {
                when {
                    fp in currentFps -> hits++
                    else -> {
                        // 别名桥：旧指纹的候选新指纹出现在当前屏才算命中
                        val resolved = store.aliasesFrom(fp).firstOrNull { it.newFingerprint in currentFps }
                        if (resolved != null) {
                            hits++
                            viaAlias = true
                        }
                    }
                }
            }
            val coverage = hits.toFloat() / macro.entryFingerprints.size
            if (coverage >= threshold && (best == null || coverage > best.coverage)) {
                best = Match(macro, coverage, viaAlias)
            }
        }
        if (best != null && best.viaAlias) {
            logger.log(
                KitLogger.Level.INFO, TAG,
                "宏 ${best.macro.skillId} 经别名桥命中（覆盖率 ${"%.2f".format(best.coverage)}）",
            )
        }
        return best
    }

    companion object {
        private const val TAG = "MacroMatcher"
    }
}
