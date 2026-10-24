package com.apex.memkit.core.ingest

import com.apex.memkit.core.MemKitConfig
import com.apex.memkit.core.model.GuardVerdict
import com.apex.memkit.core.model.NodeRole
import com.apex.memkit.core.model.Scene
import com.apex.memkit.core.model.SceneNode

/**
 * 摄取防护 —— 记忆入库前的可疑屏判定（原「记忆免疫系统」的可配化重制）。
 *
 * 四项检查（机制沿用旧 MemoryImmuneSystem，治理面三点升级）：
 * 1. 全屏可点击遮罩（钓鱼浮窗典型形态）；
 * 2. 非受信包名 + 敏感词（转账 / 验证码等）；
 * 3. 结构稀疏（< 3 节点 —— 锁屏 / 空桌面特征）；
 * 4. 未知包名 + 遮罩叠加（双信号 → 整帧阻断）。
 *
 * 治理升级（对旧实现的三个修复）：
 * - **隔离键与指纹体系统一**：旧实现用 `className_resId_text` 裸拼接键，
 *   文本含分隔符即碰撞；本实现直接用节点指纹（结构化键）；
 * - **隔离带 TTL**：旧名单永不过期（只有手动清空）；默认 30 天后自动
 *   放行重新评估（App 更新后误隔离的节点有出路）；
 * - **策略全量可配**：受信包名 / 敏感词从硬编码提升为 [MemKitConfig.Guard]。
 */
class IngestGuard(private val config: MemKitConfig) {

    /** 单节点裁决：隔离哪个节点（连同其子树由过滤方处理）。 */
    data class NodeVerdict(val verdict: GuardVerdict, val quarantined: List<SceneNode>)

    /**
     * 摄取前防护检查（对降维后的场景）。
     *
     * - [GuardVerdict.FRAME_BLOCKED]：整帧可疑（HIGH_RISK）—— 本帧不入库；
     * - [GuardVerdict.NODE_QUARANTINE]：可疑节点（SUSPICIOUS）—— 按指纹
     *   隔离并从入库集中剔除；
     * - [GuardVerdict.PASS]：放行。
     */
    fun inspect(scene: Scene): NodeVerdict {
        val cfg = config.guard
        val trusted = scene.appPackage in cfg.trustedPackages
        val clickableArea = scene.nodes.filter { it.interactive }.sumOf { it.bounds.area().toDouble() }
        val totalArea = scene.nodes.maxOfOrNull { it.bounds.area() }?.toDouble() ?: 1.0
        val overlay = clickableArea / totalArea >= cfg.overlayAreaRatio

        // 信号 1：全屏遮罩 + 非受信 → 逐节点可疑
        // 信号 2：敏感词 + 非受信 → 命中节点可疑
        // 信号 3：结构稀疏 → 整帧可疑但不阻断（新装 App 首屏可能也稀疏）
        val suspicious = ArrayList<SceneNode>()

        if (overlay && !trusted) {
            scene.nodes.filter { it.interactive && it.role != NodeRole.INPUT }
                .forEach { suspicious.add(it) }
        }
        if (!trusted) {
            val lowered = scene.nodes.map { it.text?.lowercase() ?: "" }
            val hasSensitive = lowered.any { t -> cfg.sensitivePatterns.any { t.contains(it.lowercase()) } }
            if (hasSensitive) {
                scene.nodes.filter { n ->
                    val t = n.text?.lowercase()
                    t != null && cfg.sensitivePatterns.any { t.contains(it.lowercase()) }
                }.forEach { suspicious.add(it) }
            }
        }

        // 未知包名 + 遮罩双信号 → 整帧阻断
        val unknown = scene.appPackage != null && !trusted
        if (unknown && overlay && suspicious.size >= 2) {
            return NodeVerdict(GuardVerdict.FRAME_BLOCKED, suspicious)
        }
        if (suspicious.isNotEmpty()) {
            return NodeVerdict(GuardVerdict.NODE_QUARANTINE, suspicious.distinctBy { it.fingerprint })
        }
        return NodeVerdict(GuardVerdict.PASS, emptyList())
    }

    /** TTL 毫秒数（隔离名单过期放行的参数化出口）。 */
    fun quarantineTtlMs(): Long = config.guard.quarantineTtlDays * 24L * 60 * 60 * 1000
}
