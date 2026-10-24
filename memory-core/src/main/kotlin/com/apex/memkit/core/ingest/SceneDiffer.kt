package com.apex.memkit.core.ingest

import com.apex.memkit.core.MemKitConfig
import com.apex.memkit.core.model.Bounds
import com.apex.memkit.core.model.MovedNode
import com.apex.memkit.core.model.Scene
import com.apex.memkit.core.model.SceneDelta

/**
 * 场景差分器 —— 两次捕获之间的拓扑变化计算。
 *
 * 继承旧 DifferentialIngestor 的成熟语义（集合差 + 位移阈值 + 页面跳转
 * 短路），修复其与蒸馏锚点「同名概念两套阈值」（0.6 / 0.7）的口径分裂：
 * 页面跳转判定统一走 [SceneDelta.isPageTransition]，阈值见
 * [MemKitConfig.Ingest.pageTransitionRatio]。
 */
object SceneDiffer {

    /**
     * 计算差分。
     *
     * @param from 前一场景（可为 null：首帧 → 全量新增）
     * @param to 当前场景
     * @param transitionAction 差分期间执行的动作（因果归档）
     */
    fun computeDelta(
        from: Scene?,
        to: Scene,
        config: MemKitConfig,
        transitionAction: String? = null,
    ): SceneDelta {
        if (from == null) {
            return SceneDelta(
                addedNodes = to.nodes,
                removedFingerprints = emptyList(),
                moved = emptyList(),
                addedEdges = to.edges,
                removedEdgeIds = emptyList(),
                fromFingerprint = "",
                toFingerprint = to.fingerprint,
                transitionAction = transitionAction,
            )
        }
        val before = from.nodes.associateBy { it.fingerprint }
        val after = to.nodes.associateBy { it.fingerprint }
        val addedNodes = to.nodes.filter { it.fingerprint !in before }
        val removed = from.nodes.filter { it.fingerprint !in after }.map { it.fingerprint }

        // 位移判定：页面跳转时短路（整页切换逐节点比对无意义且噪声大）
        val delta = SceneDelta(
            addedNodes = addedNodes,
            removedFingerprints = removed,
            moved = emptyList(),
            addedEdges = to.edges.filter { e -> e.sourceFp !in before || e.targetFp !in before },
            removedEdgeIds = from.edges.map { it.id }.filter { id -> id !in to.edges.mapTo(mutableSetOf()) { it.id } },
            fromFingerprint = from.fingerprint,
            toFingerprint = to.fingerprint,
            transitionAction = transitionAction,
        )
        val moved = if (delta.isPageTransition(config.ingest.pageTransitionRatio)) {
            emptyList()
        } else {
            val threshold = config.ingest.movedThresholdPx
            val movedList = ArrayList<MovedNode>()
            for ((fp, oldNode) in before) {
                val newNode = after[fp] ?: continue
                if (oldNode.bounds != newNode.bounds &&
                    centerDistance(oldNode.bounds, newNode.bounds) >= threshold
                ) {
                    movedList.add(MovedNode(fp, oldNode.bounds, newNode.bounds))
                }
            }
            movedList
        }
        return delta.copy(moved = moved)
    }

    private fun centerDistance(a: Bounds, b: Bounds): Int {
        val dx = (a.centerX - b.centerX).toDouble()
        val dy = (a.centerY - b.centerY).toDouble()
        return kotlin.math.sqrt(dx * dx + dy * dy).toInt()
    }
}
