package com.apex.memkit.core.procedure

import com.apex.memkit.core.KitClock
import com.apex.memkit.core.KitLogger
import com.apex.memkit.core.MemKitConfig
import com.apex.memkit.core.model.FingerprintAlias
import com.apex.memkit.core.model.MacroSkill
import com.apex.memkit.core.model.MaintenanceReport
import com.apex.memkit.core.store.MemoryStore
import com.apex.memkit.core.store.NodeDictEntry
import com.apex.memkit.core.store.expireQuarantine

/**
 * 宏维护周期 —— 衰减 / 剪枝 / 保鲜 / 晶化 / 别名发现（唯一实现）。
 *
 * 旧系统把「衰减 + 剪枝」写了三遍（EntropyManager.performMaintenanceCycle、
 * DreamRenderer 步骤①②、MemoryWriterActor 的 Decay/Prune 死事件），本类是
 * 全部维护语义的唯一归属；宿主的周期调度（WorkManager 约束、节拍）经
 * [runCycle] 触发。
 *
 * 别名发现（原「拓扑同胚迁移」）的触发源修正：节点字典携带**目标 App**
 * 的版本（捕获端口提供），某包出现新版本节点而宏入口仍引用旧版本节点
 * 时，按属性相似度（resourceId 归一 0.5 + 文本 Levenshtein 0.4 + 类名末级
 * 0.1，阈值 [MemKitConfig.Procedure.aliasMatchThreshold]）建立旧指纹 →
 * 新指纹的别名桥 —— 别名只增不改历史，召回侧正向校验闭环。
 */
class MacroMaintenance(
    private val store: MemoryStore,
    private val config: MemKitConfig,
    private val clock: KitClock,
    private val logger: KitLogger,
) {

    /**
     * 执行一轮维护。单步失败折叠为报告错误项，绝不中断其余步骤
     * （继承旧 DreamRenderer 的 try-catch 收 errors 纪律）。
     */
    suspend fun runCycle(): MaintenanceReport {
        val report = MaintenanceReport()
        safe("能量衰减") { report.decayedMacros = decayEnergies() }
        safe("低能剪枝") {
            report.prunedMacros = pruneMacros()
            report.prunedNodes = pruneNodes()
        }
        safe("陈旧保鲜") { report.refreshedMacros = refreshStale() }
        safe("宏晶化") { report.crystallizedMacros = crystallize() }
        safe("别名发现") { report.newAliases = discoverAliases() }
        safe("隔离过期") { report.quarantinedExpired = store.expireQuarantine(ttlMs()) }
        safe("容量治理") {
            val (eps, nodes, _) = store.enforceLimits()
            report.prunedEpisodes += eps
            report.prunedNodes += nodes
        }
        safe("落盘") { store.flush() }
        return report
    }

    /**
     * 回放结果记账：成功 +0.5 能量（封顶 10），失败 −0.5（见底 0），
     * lastExecutedAt 刷新。晶化宏不参与能量变化（已固化）。
     */
    suspend fun recordRun(macro: MacroSkill, success: Boolean): MacroSkill {
        val now = clock.now()
        val updated = macro.copy(
            stats = macro.stats.copy(
                successCount = macro.stats.successCount + if (success) 1 else 0,
                failureCount = macro.stats.failureCount + if (success) 0 else 1,
                energy = if (macro.stats.crystallized) macro.stats.energy
                else (macro.stats.energy + if (success) 0.5f else -0.5f).coerceIn(0f, 10f),
                lastExecutedAt = now,
            ),
        )
        store.saveMacro(updated)
        return updated
    }

    // ── 步骤实现 ──────────────────────────────────────────────────

    private suspend fun decayEnergies(): Int {
        val factor = config.maintenance.decayFactor
        var decayed = 0
        store.mutateMacros { m ->
            if (m.stats.crystallized) m
            else {
                val next = m.copy(stats = m.stats.copy(energy = m.stats.energy * factor))
                if (next.stats.energy != m.stats.energy) decayed++
                next
            }
        }
        store.mutateNodes { n -> n.copy(energy = n.energy * factor) }
        return decayed
    }

    /** 节点低能回收：低于地板的节点从字典删除（情景只存指纹，删除安全）。 */
    private suspend fun pruneNodes(): Int {
        val floor = config.maintenance.pruneNodeFloor
        return store.mutateNodes { n -> if (n.energy < floor) null else n }
    }

    private suspend fun pruneMacros(): Int {
        val floor = config.maintenance.pruneMacroFloor
        var pruned = 0
        store.mutateMacros { m ->
            if (!m.stats.crystallized && m.stats.energy < floor) {
                pruned++
                null
            } else m
        }
        return pruned
    }

    /** 保鲜：超窗未回放的宏记一次软失败（能量 −0.5）—— 修复旧「把次数当时间戳」事故的语义。 */
    private suspend fun refreshStale(): Int {
        val windowMs = config.maintenance.staleAfterDays * 24L * 60 * 60 * 1000
        val now = clock.now()
        var refreshed = 0
        store.mutateMacros { m ->
            if (m.stats.crystallized || m.stats.lastExecutedAt == 0L) m
            else if (now - m.stats.lastExecutedAt > windowMs) {
                refreshed++
                m.copy(stats = m.stats.copy(energy = (m.stats.energy - 0.5f).coerceAtLeast(0f)))
            } else m
        }
        return refreshed
    }

    private suspend fun crystallize(): Int {
        val cfg = config.maintenance
        var crystallized = 0
        store.mutateMacros { m ->
            val s = m.stats
            if (!s.crystallized && s.energy >= cfg.crystallizeEnergy &&
                s.successCount >= cfg.crystallizeMinSuccess && s.successRate() >= cfg.crystallizeMinRate
            ) {
                crystallized++
                m.copy(stats = s.copy(crystallized = true))
            } else m
        }
        return crystallized
    }

    /**
     * 别名发现：旧版本节点 × 同包新版本节点的属性相似度比对。
     */
    private suspend fun discoverAliases(): Int {
        val nodes = store.allNodes()
        val byPackage = nodes.groupBy { it.appPackage }
        var created = 0
        var budget = config.maintenance.aliasDiscoveryCap

        for ((pkg, entries) in byPackage) {
            if (pkg == null) continue
            val currentVersion = entries.filter { it.appVersion != null }
                .maxByOrNull { it.lastSeenAt }?.appVersion ?: continue
            val oldNodes = entries.filter { it.appVersion != null && it.appVersion != currentVersion }
            if (oldNodes.isEmpty()) continue
            val newNodes = entries.filter { it.appVersion == currentVersion }
            if (newNodes.isEmpty()) continue

            // 只为仍被宏引用的旧节点建别名（宏的入口指纹是别名的主要消费方）
            val referenced = store.allMacros()
                .filter { it.appPackage == pkg }
                .flatMapTo(mutableSetOf()) { it.entryFingerprints }
            val candidates = oldNodes.filter { it.fingerprint in referenced }
            if (candidates.isEmpty()) continue

            for (old in candidates) {
                if (budget <= 0) return created
                var best: Pair<NodeDictEntry, Float>? = null
                for (newNode in newNodes) {
                    if (newNode.role != old.role) continue
                    budget--
                    if (budget <= 0) break
                    val score = similarity(old, newNode)
                    if (score >= config.procedure.aliasMatchThreshold &&
                        (best == null || score > best.second)
                    ) {
                        best = newNode to score
                    }
                }
                val hit = best ?: continue
                store.addAlias(
                    FingerprintAlias(
                        oldFingerprint = old.fingerprint,
                        newFingerprint = hit.first.fingerprint,
                        appPackage = pkg,
                        score = hit.second,
                        fromVersion = old.appVersion,
                        toVersion = currentVersion,
                        createdAt = clock.now(),
                    ),
                )
                created++
            }
        }
        return created
    }

    /** 属性相似度：resourceId 归一 0.5 + 文本 0.4 + 类名末级 0.1（旧 TopologyMigrator 口径）。 */
    private fun similarity(old: NodeDictEntry, new: NodeDictEntry): Float {
        val w = config.procedure.aliasWeights
        var score = 0f
        val oldRes = normalizeResId(old.resourceId)
        val newRes = normalizeResId(new.resourceId)
        if (oldRes != null && oldRes == newRes) score += w.resourceId
        val oldText = old.text
        val newText = new.text
        if (oldText != null && newText != null && oldText.isNotBlank() && newText.isNotBlank()) {
            val sim = levenshteinRatio(oldText, newText)
            if (sim >= 0.8f || oldText.contains(newText) || newText.contains(oldText)) score += w.text
        }
        if (old.className != null && old.className == new.className) score += w.className
        return score
    }

    private fun normalizeResId(resId: String?): String? =
        resId?.takeIf { it.isNotBlank() }?.substringAfterLast(':')?.substringAfterLast('/')?.lowercase()

    private fun levenshteinRatio(a: String, b: String): Float {
        if (a == b) return 1f
        val dp = IntArray(b.length + 1)
        for (j in dp.indices) dp[j] = j
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(
                    dp[j] + 1,
                    dp[j - 1] + 1,
                    prev + if (a[i - 1] == b[j - 1]) 0 else 1,
                )
                prev = tmp
            }
        }
        val maxLen = maxOf(a.length, b.length)
        return if (maxLen == 0) 1f else 1f - dp[b.length].toFloat() / maxLen
    }

    private fun ttlMs(): Long =
        config.guard.quarantineTtlDays * 24L * 60 * 60 * 1000

    private inline fun safe(step: String, block: () -> Unit) {
        runCatching(block).onFailure {
            logger.log(KitLogger.Level.WARN, TAG, "维护步骤「$step」失败（跳过）", it)
        }
    }

    companion object {
        private const val TAG = "MacroMaintenance"
    }
}
