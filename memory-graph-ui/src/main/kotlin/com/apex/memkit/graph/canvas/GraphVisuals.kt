package com.apex.memkit.graph.canvas

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import com.apex.memkit.graph.graph.MemoryEdgeKind
import com.apex.memkit.graph.graph.MemoryNode
import com.apex.memkit.graph.graph.MemoryNodeKind
import com.apex.memkit.graph.layout.Vec2
import kotlin.math.sqrt

/**
 * 记忆图谱视觉契约 —— 六类节点的着色 / 字符 / 半径与六类边的线型。
 *
 * 设计纪律：**类别色为固定色相**（不取 MaterialTheme 主色 —— 类别语义
 * 必须跨主题稳定：用户对「蓝色=情景、紫色=宏」的认知不能随夜间模式漂移），
 * 但底色 / 文本色跟随主题。宿主可用 [LocalGraphPalette] 整体覆盖。
 */
data class GraphPalette(
    // 节点类别色（固定色相，明度中位 —— 明暗主题均可读）
    val episodeColor: Color = Color(0xFF4F83E8),   // 蓝 —— 情景记忆
    val macroColor: Color = Color(0xFF9C6ADE),     // 紫 —— 程序性记忆（宏）
    val factColor: Color = Color(0xFF2BB3A3),      // 青 —— 语义记忆（聊天事实）
    val entityColor: Color = Color(0xFFE8A33D),    // 琥珀 —— 知识图谱实体
    val sceneNodeColor: Color = Color(0xFF7A93A8), // 蓝灰 —— UI 场景节点
    val clusterColor: Color = Color(0xFF5CA84F),   // 绿 —— 应用簇
    // 边类别色
    val derivedEdgeColor: Color = Color(0xFF4F83E8),
    val chainEdgeColor: Color = Color(0xFF9C6ADE),
    val observedEdgeColor: Color = Color(0xFF7A93A8),
    val scopeEdgeColor: Color = Color(0xFF5CA84F),
    val relationEdgeColor: Color = Color(0xFFE8A33D),
    val aliasEdgeColor: Color = Color(0xFFB05C3F), // 锈红 —— 跨版本别名桥
    // 语义高亮
    val crystallizedRingColor: Color = Color(0xFFF2C94C), // 宏晶化金环
    val searchRingColor: Color = Color(0xFFF2994A),       // 搜索命中橙环
    val selectionRingColor: Color = Color(0xFFFFFFFF),    // 选中白环（叠加高亮）
) {
    fun nodeColor(kind: MemoryNodeKind): Color = when (kind) {
        MemoryNodeKind.EPISODE -> episodeColor
        MemoryNodeKind.MACRO -> macroColor
        MemoryNodeKind.CHAT_FACT -> factColor
        MemoryNodeKind.ENTITY -> entityColor
        MemoryNodeKind.SCENE_NODE -> sceneNodeColor
        MemoryNodeKind.APP_CLUSTER -> clusterColor
    }

    fun edgeColor(kind: MemoryEdgeKind): Color = when (kind) {
        MemoryEdgeKind.DERIVED_MACRO -> derivedEdgeColor
        MemoryEdgeKind.MACRO_CHAIN -> chainEdgeColor
        MemoryEdgeKind.OBSERVED_IN -> observedEdgeColor
        MemoryEdgeKind.APP_SCOPE -> scopeEdgeColor
        MemoryEdgeKind.GRAPH_RELATION -> relationEdgeColor
        MemoryEdgeKind.ALIAS_BRIDGE -> aliasEdgeColor
    }

    /** 节点中心字符（类别判别的第二通道 —— 色弱可达性）。 */
    fun nodeGlyph(kind: MemoryNodeKind): String = when (kind) {
        MemoryNodeKind.EPISODE -> "情"
        MemoryNodeKind.MACRO -> "宏"
        MemoryNodeKind.CHAT_FACT -> "忆"
        MemoryNodeKind.ENTITY -> "知"
        MemoryNodeKind.SCENE_NODE -> "界"
        MemoryNodeKind.APP_CLUSTER -> "簇"
    }
}

/** 宿主覆盖点（默认 [defaultGraphPalette]；Provide 读主题做明暗微调）。 */
val LocalGraphPalette = staticCompositionLocalOf { GraphPalette() }

/**
 * 主题感知调色板（背景 / 文本跟随明暗，类别色保持固定）。
 */
@Composable
fun defaultGraphPalette(): GraphPalette {
    // 底色不进 palette（画布直接铺 MaterialTheme.colorScheme.surface）；
    // 选中环在暗色下用纯白、亮色下用深墨 —— 始终与类别色形成对比
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return GraphPalette(
        selectionRingColor = if (dark) Color.White else Color(0xE6101014),
    )
}

private fun Color.luminance(): Float = (0.299f * red + 0.587f * green + 0.114f * blue)

/** 边线型（六类关系的第三判别通道：实线 / 长划 / 点划 / 短划 / 细点 / 虚点）。 */
fun edgePathEffect(kind: MemoryEdgeKind, scale: Float): PathEffect? = when (kind) {
    MemoryEdgeKind.DERIVED_MACRO -> null // 实线
    MemoryEdgeKind.GRAPH_RELATION -> null
    MemoryEdgeKind.MACRO_CHAIN -> PathEffect.dashPathEffect(floatArrayOf(14f * scale, 8f * scale))
    MemoryEdgeKind.OBSERVED_IN -> PathEffect.dashPathEffect(floatArrayOf(6f * scale, 6f * scale))
    MemoryEdgeKind.APP_SCOPE -> PathEffect.dashPathEffect(floatArrayOf(2f * scale, 7f * scale))
    MemoryEdgeKind.ALIAS_BRIDGE -> PathEffect.dashPathEffect(floatArrayOf(3f * scale, 5f * scale))
}

/**
 * 图谱几何 —— 世界坐标系的统一度量（渲染 / 命中检测共用；布局引擎的
 * 物理间距按 [ForceDirectedLayout] 自己的 spec，视觉半径略小于物理半径
 * —— 标签留白由物理保证）。
 */
object GraphGeometry {

    /** 节点视觉半径（世界单位）：14 + weight × 30 → 14..44。 */
    fun nodeRadius(weight: Float): Float = 14f + weight.coerceIn(0f, 1f) * 30f

    /** 命中检测半径（视觉半径 + 8 世界单位宽容）。 */
    fun hitRadius(weight: Float): Float = nodeRadius(weight) + 8f

    /** 箭头尺寸（世界单位 × 缩放由渲染层处理）。 */
    const val ARROW_SIZE = 9f

    /**
     * 边中点标签的显示缩放阈值。
     *
     * 六类边全部带具体关联标签后的新阈值：默认 fit 视图（scale 常在 0.3..0.9）
     * 只画线不画字防噪；轻放大即显示；选中态的邻接边无条件显示
     * （点一个节点立刻看清它的全部具体关联，不受缩放限制）。
     */
    const val EDGE_LABEL_MIN_SCALE = 1.02f

    /** 单帧边标签上限（候选按「选中邻接优先 → strength 降序」裁剪）。 */
    const val MAX_EDGE_LABELS = 80

    /** 节点标签的显示缩放阈值。 */
    const val NODE_LABEL_MIN_SCALE = 0.55f

    /**
     * 全图包围盒（fit-to-view 的输入；空图返回 null）。
     */
    fun bounds(positions: Map<String, Vec2>, radiusOf: (String) -> Float): Bounds? {
        if (positions.isEmpty()) return null
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        positions.forEach { (id, p) ->
            val r = radiusOf(id)
            minX = minOf(minX, p.x - r); maxX = maxOf(maxX, p.x + r)
            minY = minOf(minY, p.y - r); maxY = maxOf(maxY, p.y + r)
        }
        return Bounds(minX, minY, maxX, maxY)
    }

    data class Bounds(val minX: Float, val minY: Float, val maxX: Float, val maxY: Float) {
        val width: Float get() = maxOf(1f, maxX - minX)
        val height: Float get() = maxOf(1f, maxY - minY)
        val centerX: Float get() = (minX + maxX) / 2f
        val centerY: Float get() = (minY + maxY) / 2f
    }
}

/** 节点半径快捷（payload 权重 → 几何）。 */
fun MemoryNode.worldRadius(): Float = GraphGeometry.nodeRadius(weight)

/** 距离平方（命中检测免开方）。 */
internal fun dist2(ax: Float, ay: Float, bx: Float, by: Float): Float {
    val dx = ax - bx; val dy = ay - by
    return dx * dx + dy * dy
}

internal fun fastLen(x: Float, y: Float): Float = sqrt(x * x + y * y)
