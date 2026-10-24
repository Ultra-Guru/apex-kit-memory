package com.apex.memkit.graph.layout

import com.apex.memkit.graph.graph.MemoryEdgeKind
import com.apex.memkit.graph.graph.MemoryGraph
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 二维向量（布局引擎的坐标原语 —— 不引 androidx.compose.geometry，
 * 布局层保持纯 Kotlin 可测；渲染层负责 Vec2 → Offset 的映射）。
 */
data class Vec2(val x: Float, val y: Float) {
    operator fun plus(o: Vec2) = Vec2(x + o.x, y + o.y)
    operator fun minus(o: Vec2) = Vec2(x - o.x, y - o.y)
    operator fun times(s: Float) = Vec2(x * s, y * s)
    fun length(): Float = sqrt(x * x + y * y)
    fun distanceTo(o: Vec2): Float = (this - o).length()

    companion object {
        val ZERO = Vec2(0f, 0f)

        /** 从原点出发的极坐标点（初始摆位的确定性圆环用）。 */
        fun polar(radius: Float, angleRad: Float) = Vec2(
            radius * cos(angleRad),
            radius * sin(angleRad),
        )
    }
}

/**
 * 力导向布局引擎 —— spring-embedder 变体（Fruchterman-Reingold 血统 +
 * 簇重力扩展），记忆图谱的「有机感」来源。
 *
 * 四种力：
 * 1. **全局斥力**（库仑）：任意两节点互斥，强度按节点半径折算 ——
 *   大节点推开邻域，杜绝标签互相覆盖；
 * 2. **边弹簧**（胡克）：连线两端拉向理想长度（按边类型的语义距离：
 *   知识关系近、应用归属远 —— 同簇视觉上更紧）；
 * 3. **簇重力**：同 [clusterKey][com.apex.memkit.graph.graph.MemoryNode.clusterKey]
 *   节点向簇质心聚拢（情景/宏/节点按应用聚团、事实按类别聚团）；
 * 4. **向心引力**：全体向画布原点回收（防漫游逃逸）。
 *
 * 确定性纪律：初始摆位由 [seed] 驱动的圆环 + 簇方位角决定 —— 同图谱
 * 同种子必产出同布局（回归测试断言的根基）；迭代内用累积位移上限
 * （温度冷却）收敛，不做真实物理积分（无速度项 —— 稳定且可中断）。
 *
 * 性能：O(iterations × (N² + E))，N ≤ ~900、iterations ≤ 300 时
 * 桌面 JVM < 300ms、中端手机 < 1.5s —— 状态机在 Default 调度器跑，
 * 不卡 UI 线程。
 */
class ForceDirectedLayout(
    private val spec: LayoutSpec = LayoutSpec(),
) {

    /**
     * 布局参数（调参面 —— 宿主可按数据形态微调；默认值按
     * 「百节点量级 + 六簇」的记忆图谱校准）。
     */
    data class LayoutSpec(
        val repulsion: Float = 1800f,
        val springStrength: Float = 0.06f,
        val clusterGravity: Float = 0.35f,
        val centerGravity: Float = 0.015f,
        val damping: Float = 0.85f,
        val maxStep: Float = 24f,
        val minNodeDistance: Float = 60f,
        val iterations: Int = 300,
        /** 边类型 → 理想长度（语义距离：知识关系贴近、应用归属偏远）。 */
        val edgeLengths: Map<MemoryEdgeKind, Float> = mapOf(
            MemoryEdgeKind.GRAPH_RELATION to 140f,
            MemoryEdgeKind.DERIVED_MACRO to 170f,
            MemoryEdgeKind.MACRO_CHAIN to 190f,
            MemoryEdgeKind.ALIAS_BRIDGE to 150f,
            MemoryEdgeKind.OBSERVED_IN to 240f,
            MemoryEdgeKind.APP_SCOPE to 300f,
        ),
    )

    /** 节点半径（斥力距离与摆位密度按半径折算；渲染层用同一函数保持一致）。 */
    private fun radius(weight: Float): Float =
        spec.minNodeDistance / 2f * (0.6f + weight)

    /**
     * 计算全图布局。
     *
     * @param graph 目标图谱
     * @param seed 初始摆位种子（默认 0 —— 生产态确定性）
     * @param pinned 固定节点（拖拽落点 / 局部重排时保持用户意图）
     * @return 节点 id → 世界坐标
     */
    fun layout(graph: MemoryGraph, seed: Long = 0L, pinned: Map<String, Vec2> = emptyMap()): Map<String, Vec2> {
        if (graph.nodes.isEmpty()) return emptyMap()
        val ids = graph.nodes.map { it.id }
        val weights = graph.nodes.associate { it.id to it.weight }
        val clusters = graph.nodes.associate { it.id to it.clusterKey }

        // ── 初始摆位：簇方位角圆环 + 簇内小圆环（seeded 抖动保确定性）──
        val positions = initialPositions(graph, seed, pinned)

        // 边端点索引（O(E) 预处理 —— 迭代内不再查 map）
        val edges = graph.edges.map { Triple(it.sourceId, it.targetId, spec.edgeLengths[it.kind] ?: 180f) }
        val idIndex = ids.withIndex().associate { (i, id) -> id to i }
        val indexedEdges = edges.mapNotNull { (s, t, len) ->
            val si = idIndex[s] ?: return@mapNotNull null
            val ti = idIndex[t] ?: return@mapNotNull null
            Triple(si, ti, len)
        }

        val px = FloatArray(ids.size) { positions.getValue(ids[it]).x }
        val py = FloatArray(ids.size) { positions.getValue(ids[it]).y }
        val isPinned = BooleanArray(ids.size) { ids[it] in pinned }
        val radii = FloatArray(ids.size) { radius(weights.getValue(ids[it])) }

        // 簇质心缓存（每 iter 重算一次 —— 簇内成员变动只有 pinned 拖拽，成本可忽略）
        val clusterKeys = clusters.values.distinct().sorted()
        val clusterIndex = clusterKeys.withIndex().associate { (i, k) -> k to i }
        val nodeCluster = IntArray(ids.size) { clusterIndex[clusters.getValue(ids[it])] ?: -1 }
        val cxs = FloatArray(clusterKeys.size)
        val cys = FloatArray(clusterKeys.size)

        // ── 主迭代（位移上限随迭代线性冷却 —— 前期大挪移、后期微调）──
        for (iter in 0 until spec.iterations) {
            val temperature = spec.maxStep * (1f - iter.toFloat() / spec.iterations)
            val fx = FloatArray(ids.size)
            val fy = FloatArray(ids.size)

            // 簇质心
            val cCount = IntArray(clusterKeys.size)
            java.util.Arrays.fill(cxs, 0f); java.util.Arrays.fill(cys, 0f); java.util.Arrays.fill(cCount, 0)
            for (i in ids.indices) {
                val c = nodeCluster[i]
                if (c >= 0) { cxs[c] += px[i]; cys[c] += py[i]; cCount[c]++ }
            }
            for (c in clusterKeys.indices) if (cCount[c] > 0) { cxs[c] /= cCount[c]; cys[c] /= cCount[c] }

            // 1. 全局斥力（i<j 对称施加，避免重复计算）
            for (i in 0 until ids.size) {
                for (j in i + 1 until ids.size) {
                    var dx = px[j] - px[i]
                    var dy = py[j] - py[i]
                    var dist = sqrt(dx * dx + dy * dy)
                    if (dist < 1e-3f) {
                        // 完全重合：按索引确定性方向顶开（rng 不参与 —— 保同输入同输出）
                        dx = if ((i + j) % 2 == 0) 1f else -1f
                        dy = if ((i * 31 + j) % 3 == 0) 0.5f else -0.5f
                        dist = sqrt(dx * dx + dy * dy)
                    }
                    val minDist = radii[i] + radii[j] + 14f
                    val force = if (dist < minDist) {
                        // 近距离强推（防覆盖）+ 库仑项
                        spec.repulsion * 6f / (dist * dist)
                    } else {
                        spec.repulsion / (dist * dist)
                    }
                    val ux = dx / dist
                    val uy = dy / dist
                    fx[i] -= force * ux; fy[i] -= force * uy
                    fx[j] += force * ux; fy[j] += force * uy
                }
            }

            // 2. 边弹簧
            for ((si, ti, ideal) in indexedEdges) {
                val dx = px[ti] - px[si]
                val dy = py[ti] - py[si]
                val dist = max(1f, sqrt(dx * dx + dy * dy))
                val stretch = (dist - ideal) * spec.springStrength
                val ux = dx / dist
                val uy = dy / dist
                val fx_ = stretch * ux
                val fy_ = stretch * uy
                fx[si] += fx_; fy[si] += fy_
                fx[ti] -= fx_; fy[ti] -= fy_
            }

            // 3. 簇重力 + 4. 向心引力
            for (i in ids.indices) {
                val c = nodeCluster[i]
                if (c >= 0 && cCount[c] > 1) {
                    fx[i] += (cxs[c] - px[i]) * spec.clusterGravity
                    fy[i] += (cys[c] - py[i]) * spec.clusterGravity
                }
                fx[i] -= px[i] * spec.centerGravity
                fy[i] -= py[i] * spec.centerGravity
            }

            // 应用位移（pinned 不动；温度上限 + 阻尼）
            for (i in ids.indices) {
                if (isPinned[i]) continue
                var dx = fx[i] * spec.damping
                var dy = fy[i] * spec.damping
                val d = sqrt(dx * dx + dy * dy)
                if (d > temperature) { dx = dx / d * temperature; dy = dy / d * temperature }
                px[i] += dx
                py[i] += dy
            }
        }

        // rng 已在 initialPositions 内驱动抖动（seed 确定性契约的唯一点）

        val result = HashMap<String, Vec2>(ids.size)
        ids.forEachIndexed { i, id -> result[id] = Vec2(px[i], py[i]) }
        return result
    }

    /**
     * 局部松弛（拖拽落点后的邻域重排）：以固定锚点跑短迭代，
     * 邻居自然让位 —— 「放下节点，周围记忆轻轻弹开」的有机感。
     */
    fun relax(graph: MemoryGraph, positions: Map<String, Vec2>, anchorId: String): Map<String, Vec2> {
        val anchor = positions[anchorId] ?: return positions
        val shortSpec = spec.copy(iterations = min(spec.iterations, RELAX_ITERATIONS))
        return ForceDirectedLayout(shortSpec)
            .layout(graph, seed = RELAX_SEED, pinned = mapOf(anchorId to anchor))
            .let { relaid ->
                // 松弛结果与拖拽前位置的折中：锚点外的节点向松弛位移动 60%
                // （邻域让位可感知，同时保留 40% 既有认知位置感，避免全图跳变）
                positions.mapValues { (id, old) ->
                    if (id == anchorId) anchor
                    else relaid[id]?.let { new -> old * 0.4f + new * 0.6f } ?: old
                }
            }
    }

    /**
     * 初始摆位：簇在母圆环上均匀分布（簇字典序 → 方位角，确定性），
     * 簇内成员在小圆环上按节点 id 字典序展开 + seeded 微抖动。
     */
    private fun initialPositions(
        graph: MemoryGraph,
        seed: Long,
        pinned: Map<String, Vec2>,
    ): Map<String, Vec2> {
        val rng = Random(seed)
        val byCluster = graph.nodes.groupBy { it.clusterKey }
        val clusterKeys = byCluster.keys.sorted()
        val result = HashMap<String, Vec2>(graph.nodes.size)

        clusterKeys.forEachIndexed { ci, key ->
            val clusterAngle = (2.0 * Math.PI * ci / max(1, clusterKeys.size)).toFloat()
            val clusterRadius = 260f + 120f * min(4, clusterKeys.size)
            val center = Vec2.polar(clusterRadius, clusterAngle)
            val members = byCluster.getValue(key)
                .sortedBy { it.id }  // 簇内次序确定性
            val memberRadius = 40f + 26f * sqrt(members.size.toFloat())
            members.forEachIndexed { mi, node ->
                val angle = (2.0 * Math.PI * mi / max(1, members.size)).toFloat() +
                    (rng.nextFloat() - 0.5f) * 0.4f
                val r = memberRadius * (0.55f + 0.45f * rng.nextFloat())
                result[node.id] = pinned[node.id] ?: (center + Vec2.polar(r, angle))
            }
        }
        return result
    }

    private companion object {
        const val RELAX_ITERATIONS = 40
        const val RELAX_SEED = 7L
    }
}
