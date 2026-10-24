package com.apex.memkit.core.ingest

import com.apex.memkit.core.RawCapture
import com.apex.memkit.core.RawNode
import com.apex.memkit.core.model.Bounds
import com.apex.memkit.core.model.EdgeKind
import com.apex.memkit.core.model.NodeRole
import com.apex.memkit.core.model.Scene
import com.apex.memkit.core.model.SceneEdge
import com.apex.memkit.core.model.SceneNode

/**
 * 语义降维器 —— 原始无障碍树 → 记忆场景。
 *
 * 三条修剪规则（继承自旧 UiTreePruner 的生产验证核心，本库差异化资产）：
 * 1. **不可见剔除**：bounds 缺失 / 零面积的子树不产出（仍向下遍历 ——
 *   不可见容器里可能有可见子元素）；
 * 2. **纯装饰剔除**：无文本、无 contentDescription、无 resourceId、不可点、
 *   不可滑动的节点不产出 —— 但**仍作为哈希载体**：其指纹继续作为子节点的
 *   父上下文参与计算（被折叠的层级仍贡献结构区分度，这是「合并的容器
 *   仍可作为 hash 载体」的设计）；
 * 3. **空间边生成**：父子包含边（CONTAINS）+ 兄弟水平邻接边（ADJACENT）。
 *
 * 与旧实现的差异：旧版另有「连续 3 层无语义容器合并」启发式（体积优化，
 * 但引入重父化的指纹不稳定面）；本库以全局节点字典去重 + 容量上限替代，
 * 指纹语义更简单稳定。ADJACENT 邻接判定的 gap 区间保留旧值。
 */
object SceneReducer {

    /** 兄弟水平邻接判定：中心水平间距 ∈ [GAP_MIN, GAP_MAX] 且垂直有重叠。 */
    private const val SIBLING_GAP_MIN = -50
    private const val SIBLING_GAP_MAX = 200

    /** 降维入口：原始捕获 → 记忆场景。空树 / 全被过滤返回 null（无观测）。 */
    fun reduce(capture: RawCapture, capturedAt: Long): Scene? {
        val out = ArrayList<SceneNode>(capture.roots.size * 8)
        capture.roots.forEach { walk(it, parentFp = null, depth = 0, out = out) }
        if (out.isEmpty()) return null
        val edges = buildEdges(out)
        return Scene(
            fingerprint = SceneFingerprint.scene(out.map { it.fingerprint }),
            nodes = out,
            edges = edges,
            appPackage = capture.appPackage,
            appVersion = capture.appVersion,
            activityLabel = capture.activityLabel,
            capturedAt = capturedAt,
        )
    }

    /** 深度优先遍历：所有节点都算指纹（哈希载体），语义节点才产出。 */
    private fun walk(node: RawNode, parentFp: String?, depth: Int, out: MutableList<SceneNode>) {
        val text = node.text.ifBlank { node.contentDescription }
        val role = classifyRole(node, text)
        val fp = SceneFingerprint.node(node.className, node.resourceId, text ?: "", role.name, parentFp)
        val bounds = Bounds.parseBounds(node.bounds)
        val interactive = node.clickable || node.scrollable
        if (bounds != null && (interactive || text.isNotBlank() || node.resourceId.isNotBlank())) {
            out.add(
                SceneNode(
                    fingerprint = fp,
                    role = role,
                    text = text.takeIf { it.isNotBlank() },
                    resourceId = node.resourceId.takeIf { it.isNotBlank() },
                    className = node.className.takeIf { it.isNotBlank() },
                    bounds = bounds,
                    depth = depth,
                    interactive = interactive,
                ),
            )
        }
        node.children.forEach { walk(it, parentFp = fp, depth = depth + 1, out = out) }
    }

    /**
     * 角色分类：className 关键字优先（结构信号最强），标志位兜底，
     * 文本存在时 TEXT 兜底 —— 保证任何产出节点都有可读角色。
     */
    fun classifyRole(node: RawNode, text: String?): NodeRole {
        val cls = node.className.lowercase()
        return when {
            cls.contains("edittext") || cls.contains("textinput") || cls.contains("editor") -> NodeRole.INPUT
            cls.contains("checkbox") || cls.contains("switch") ||
                cls.contains("radiobutton") || cls.contains("toggle") -> NodeRole.TOGGLE
            cls.contains("button") || cls.contains("imagebutton") -> NodeRole.BUTTON
            cls.contains("scrollview") || cls.contains("recyclerview") || cls.contains("viewpager") ||
                cls.contains("listview") || cls.contains("gridview") || cls.contains("pager") -> NodeRole.SCROLLABLE
            cls.contains("imageview") || cls.contains("image") -> NodeRole.IMAGE
            cls.contains("toolbar") || cls.contains("actionbar") || cls.contains("navigationview") ||
                cls.contains("bottomnav") || cls.contains("tablayout") || cls.contains("menu") -> NodeRole.NAVIGATION
            cls.contains("dialog") || cls.contains("popup") || cls.contains("alert") ||
                cls.contains("sheet") || cls.contains("toast") -> NodeRole.DIALOG
            node.scrollable -> NodeRole.SCROLLABLE
            node.clickable -> NodeRole.BUTTON
            !text.isNullOrBlank() -> NodeRole.TEXT
            else -> NodeRole.UNKNOWN
        }
    }

    /** 空间边：输出节点间的包含（按出现序父子链不可知，改用几何包含）+ 兄弟邻接。 */
    private fun buildEdges(nodes: List<SceneNode>): List<SceneEdge> {
        if (nodes.size <= 1) return emptyList()
        val edges = ArrayList<SceneEdge>(nodes.size * 2)
        // 几何包含边：小节点完全落在大节点内 → CONTAINS（屏幕区域互斥假设下
        // 仍能恢复层级拓扑，与树遍历产出的包含语义一致）
        val sorted = nodes.sortedByDescending { it.bounds.area() }
        for (i in sorted.indices) {
            val child = sorted[i]
            // 面积降序：包含者必在更小面积的前序位（j < i），取最近的几何父
            for (j in i - 1 downTo 0) {
                val parent = sorted[j]
                if (contains(parent.bounds, child.bounds)) {
                    edges.add(SceneEdge(SceneFingerprint.edge(parent.fingerprint, child.fingerprint), parent.fingerprint, child.fingerprint, EdgeKind.CONTAINS))
                    break
                }
            }
        }
        // 兄弟邻接边：同水平带、中心水平间距在窗口内、垂直有重叠
        val byY = nodes.sortedBy { it.bounds.centerY }
        for (i in byY.indices) {
            val a = byY[i]
            for (j in i + 1 until byY.size) {
                val b = byY[j]
                if (b.bounds.y1 > a.bounds.y2) break  // 已越过 a 的垂直带
                if (!verticalOverlap(a.bounds, b.bounds)) continue
                val gap = b.bounds.centerX - a.bounds.centerX
                if (gap in SIBLING_GAP_MIN..SIBLING_GAP_MAX && gap != 0) {
                    edges.add(SceneEdge(SceneFingerprint.edge(a.fingerprint, b.fingerprint), a.fingerprint, b.fingerprint, EdgeKind.ADJACENT))
                }
            }
        }
        return edges
    }

    private fun contains(outer: Bounds, inner: Bounds): Boolean =
        outer.x1 <= inner.x1 && outer.y1 <= inner.y1 && outer.x2 >= inner.x2 && outer.y2 >= inner.y2 &&
            (outer.area() > inner.area())

    private fun verticalOverlap(a: Bounds, b: Bounds): Boolean =
        a.y1 < b.y2 && b.y1 < a.y2
}
