package com.apex.memkit.graph.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.apex.memkit.core.model.EpisodeStatus
import com.apex.memkit.graph.graph.MemoryEdge
import com.apex.memkit.graph.graph.MemoryEdgeKind
import com.apex.memkit.graph.graph.MemoryGraph
import com.apex.memkit.graph.graph.MemoryNodeKind
import com.apex.memkit.graph.graph.MemoryNodePayload
import com.apex.memkit.graph.layout.Vec2
import kotlin.math.min

/**
 * 交互式记忆图谱画布 —— 「记忆节点 + 关系连线」的渲染与手势面。
 *
 * 手势（单 modifier 统一处理，无手势间抢占）：
 * - **单指拖空白**：平移视图；**单指拖节点**：移动节点（松手触发邻域松弛）；
 * - **双指捏合**：以质心为锚缩放 + 平移；
 * - **单击**：命中节点 → 选中（详情 Sheet 弹出）；点空白 → 取消选中；
 * - 缩放阈值驱动的 LOD：低倍只画节点，中倍加标签，高倍加关系标签。
 *
 * 渲染层次（一次 DrawScope 完成，无多 pass 分层）：
 * 边（曲线 + 箭头）→ 节点（类别色圆 + 类别字符 + 状态点 + 晶化金环 +
 * 选中/搜索环）→ 屏幕空间标签。
 *
 * 聚焦语义（选中存在时）：选中节点 + 邻接节点全亮，其余压暗到 25%；
 * 关联边加粗，其余压到 8% —— 「点一个节点，看清它的记忆关系网」。
 */
@Composable
fun MemoryGraphCanvas(
    graph: MemoryGraph,
    positions: Map<String, Vec2>,
    camera: GraphCamera,
    selection: String?,
    neighborIds: Set<String>,
    searchMatches: Set<String>,
    palette: GraphPalette,
    modifier: Modifier = Modifier,
    onNodeTap: (String?) -> Unit,
    onNodeDrag: (nodeId: String, worldDelta: Vec2) -> Unit,
    onNodeDragEnd: (nodeId: String) -> Unit,
    onPan: (screenDelta: Offset) -> Unit,
    onZoom: (factor: Float, screenFocus: Offset) -> Unit,
    labelColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    val textMeasurer = rememberTextMeasurer()
    // 屏幕空间标签缓存（文本 → layout；节点标签随节点集稳定）
    val labelCache = remember { HashMap<String, TextLayoutResult>() }
    val glyphCache = remember { HashMap<String, TextLayoutResult>() }
    val edgeLabelCache = remember { HashMap<String, TextLayoutResult>() }

    // 手势闭包的活值注入：pointerInput(Unit) 只装配一次手势流（相机/坐标/
    // 回调高频变更不重启检测 —— 重启会打断进行中的拖拽与捏合），
    // 闭包内一律经 rememberUpdatedState 持有者读最新值。
    val liveGraph by rememberUpdatedState(graph)
    val livePositions by rememberUpdatedState(positions)
    val liveCamera by rememberUpdatedState(camera)
    val liveTap by rememberUpdatedState(onNodeTap)
    val liveDrag by rememberUpdatedState(onNodeDrag)
    val liveDragEnd by rememberUpdatedState(onNodeDragEnd)
    val livePan by rememberUpdatedState(onPan)
    val liveZoom by rememberUpdatedState(onZoom)

    Canvas(
        modifier = modifier
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var draggedId: String? = null
                    var panning = false
                    var multiTouch = false
                    var totalDrag = 0f
                    var lastCentroid = down.position
                    var lastSpread = -1f

                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break

                        if (pressed.size >= 2) {
                            // 双指：捏合缩放（质心锚定）+ 平移
                            multiTouch = true
                            val c = centroid(pressed)
                            val s = spread(pressed)
                            if (lastSpread > 0f && s > 0f) {
                                liveZoom(s / lastSpread, c)
                            }
                            livePan(c - lastCentroid)
                            lastCentroid = c
                            lastSpread = s
                            event.changes.forEach { it.consume() }
                        } else {
                            val p = pressed[0]
                            if (lastSpread > 0f) {
                                // 双指 → 单指回退：重置基准，直接进入平移态
                                // （避免以首指原始落点重新命中检测导致远跳拖拽）
                                lastCentroid = p.position
                                lastSpread = -1f
                                panning = true
                            }
                            val delta = p.positionChange()
                            if (delta != Offset.Zero) {
                                totalDrag += delta.getDistance()
                                if (draggedId == null && !panning) {
                                    val hit = hitTest(liveGraph, livePositions, liveCamera, down.position)
                                    if (hit != null) draggedId = hit else panning = true
                                }
                                val id = draggedId
                                if (id != null) {
                                    liveDrag(id, Vec2(delta.x / liveCamera.scale, delta.y / liveCamera.scale))
                                } else if (panning) {
                                    livePan(delta)
                                }
                                p.consume()
                            }
                            lastCentroid = p.position
                        }
                    }

                    // tap 判定：无位移且无双指（< touchSlop）→ 命中节点则选中，否则清选中
                    if (!multiTouch && totalDrag < viewConfiguration.touchSlop) {
                        liveTap(hitTest(liveGraph, livePositions, liveCamera, down.position))
                    } else {
                        draggedId?.let(liveDragEnd)
                    }
                }
            },
    ) {
        drawGraph(
            graph = graph,
            positions = positions,
            camera = camera,
            selection = selection,
            neighborIds = neighborIds,
            searchMatches = searchMatches,
            palette = palette,
            labelColor = labelColor,
            textMeasurer = textMeasurer,
            labelCache = labelCache,
            glyphCache = glyphCache,
            edgeLabelCache = edgeLabelCache,
        )
    }
}

// ══ 相机 ═════════════════════════════════════════════════════════

/** 视图相机（世界 → 屏幕：screen = world × scale + offset）。 */
data class GraphCamera(
    val scale: Float = 1f,
    val offset: Offset = Offset.Zero,
) {
    fun worldToScreen(p: Vec2): Offset = Offset(p.x * scale + offset.x, p.y * scale + offset.y)
    fun screenToWorld(s: Offset): Vec2 = Vec2((s.x - offset.x) / scale, (s.y - offset.y) / scale)

    companion object {
        const val MIN_SCALE = 0.12f
        const val MAX_SCALE = 4f

        /** 缩放（质心锚定：缩放前后质心世界坐标不变）。 */
        fun zoom(camera: GraphCamera, factor: Float, focus: Offset): GraphCamera {
            val newScale = (camera.scale * factor).coerceIn(MIN_SCALE, MAX_SCALE)
            val worldFocus = camera.screenToWorld(focus)
            val newOffset = Offset(
                focus.x - worldFocus.x * newScale,
                focus.y - worldFocus.y * newScale,
            )
            return camera.copy(scale = newScale, offset = newOffset)
        }

        /** 包围盒适配视口（留边距；空内容返回原相机）。 */
        fun fit(camera: GraphCamera, bounds: GraphGeometry.Bounds?, viewport: Offset): GraphCamera {
            if (bounds == null) return camera
            val vw = viewport.x.coerceAtLeast(1f)
            val vh = viewport.y.coerceAtLeast(1f)
            val margin = 0.88f // 12% 边距
            val scale = min(vw / bounds.width, vh / bounds.height)
                .coerceIn(MIN_SCALE, 1.6f) * margin
            return GraphCamera(
                scale = scale,
                offset = Offset(
                    vw / 2f - bounds.centerX * scale,
                    vh / 2f - bounds.centerY * scale,
                ),
            )
        }
    }
}

// ══ 手势辅助 ═════════════════════════════════════════════════════

private fun centroid(changes: List<PointerInputChange>): Offset {
    var x = 0f; var y = 0f
    changes.forEach { x += it.position.x; y += it.position.y }
    return Offset(x / changes.size, y / changes.size)
}

private fun spread(changes: List<PointerInputChange>): Float {
    if (changes.size < 2) return 0f
    val a = changes[0].position
    val b = changes[1].position
    return fastLen(a.x - b.x, a.y - b.y).coerceAtLeast(1f)
}

/** 命中检测：屏幕坐标 → 顶层节点（半径 + 宽容，倒序遍历 = 后画优先）。 */
internal fun hitTest(
    graph: MemoryGraph,
    positions: Map<String, Vec2>,
    camera: GraphCamera,
    screen: Offset,
): String? {
    val world = camera.screenToWorld(screen)
    var best: String? = null
    var bestD2 = Float.MAX_VALUE
    for (node in graph.nodes) {
        val p = positions[node.id] ?: continue
        val r = GraphGeometry.hitRadius(node.weight)
        val d2 = dist2(world.x, world.y, p.x, p.y)
        if (d2 <= r * r && d2 < bestD2) {
            bestD2 = d2
            best = node.id
        }
    }
    return best
}

// ══ 渲染 ═════════════════════════════════════════════════════════

private fun DrawScope.drawGraph(
    graph: MemoryGraph,
    positions: Map<String, Vec2>,
    camera: GraphCamera,
    selection: String?,
    neighborIds: Set<String>,
    searchMatches: Set<String>,
    palette: GraphPalette,
    labelColor: Color,
    textMeasurer: TextMeasurer,
    labelCache: MutableMap<String, TextLayoutResult>,
    glyphCache: MutableMap<String, TextLayoutResult>,
    edgeLabelCache: MutableMap<String, TextLayoutResult>,
) {
    val focusActive = selection != null
    val searchActive = searchMatches.isNotEmpty()
    val labelShadow = Color(0xB3000000) // 标签描边影（任意底色上可读）

    fun nodeAlpha(id: String): Float = when {
        !focusActive && !searchActive -> 1f
        focusActive -> if (id == selection || id in neighborIds) 1f else 0.22f
        else -> if (id in searchMatches) 1f else 0.35f
    }

    fun edgeAlpha(e: MemoryEdge): Float = when {
        !focusActive -> if (searchActive) 0.28f else 0.55f
        e.sourceId == selection || e.targetId == selection -> 0.95f
        else -> 0.07f
    }

    // ── 边 ──
    for (edge in graph.edges) {
        val a = positions[edge.sourceId] ?: continue
        val b = positions[edge.targetId] ?: continue
        val sa = camera.worldToScreen(a)
        val sb = camera.worldToScreen(b)
        // 视口剔除（粗判：两端皆远离屏幕则跳过）
        if ((sa.x < -200 && sb.x < -200) || (sa.x > size.width + 200 && sb.x > size.width + 200) ||
            (sa.y < -200 && sb.y < -200) || (sa.y > size.height + 200 && sb.y > size.height + 200)
        ) continue

        val alpha = edgeAlpha(edge)
        if (alpha < 0.03f) continue
        val color = palette.edgeColor(edge.kind).copy(alpha = alpha)
        val width = (1f + kotlin.math.ln1p(edge.strength - 1f).coerceAtLeast(0f) * 0.9f) *
            camera.scale.coerceIn(0.6f, 2.2f) *
            (if (focusActive && (edge.sourceId == selection || edge.targetId == selection)) 1.8f else 1f)

        val nodeA = graph.nodesById[edge.sourceId]
        val nodeB = graph.nodesById[edge.targetId]
        val ra = (nodeA?.let { GraphGeometry.nodeRadius(it.weight) } ?: 10f) * camera.scale
        val rb = (nodeB?.let { GraphGeometry.nodeRadius(it.weight) } ?: 10f) * camera.scale

        // 端点截断（箭头贴节点边缘）+ 轻曲率（平行视觉回避）
        val dx = sb.x - sa.x
        val dy = sb.y - sa.y
        val len = fastLen(dx, dy).coerceAtLeast(1f)
        val ux = dx / len; val uy = dy / len
        val start = Offset(sa.x + ux * ra, sa.y + uy * ra)
        val end = Offset(sb.x - ux * (rb + 4f), sb.y - uy * (rb + 4f))
        val mid = Offset((start.x + end.x) / 2f - uy * 14f, (start.y + end.y) / 2f + ux * 14f)

        val path = Path().apply {
            moveTo(start.x, start.y)
            quadraticTo(mid.x, mid.y, end.x, end.y)
        }
        drawPath(path, color, style = Stroke(width = width, cap = StrokeCap.Round))

        // 箭头（沿曲线终点切线）
        val tangentX = end.x - mid.x
        val tangentY = end.y - mid.y
        val tLen = fastLen(tangentX, tangentY).coerceAtLeast(1f)
        val tx = tangentX / tLen; val ty = tangentY / tLen
        val arrowSize = GraphGeometry.ARROW_SIZE * camera.scale.coerceIn(0.7f, 1.8f)
        if (arrowSize > 3f) {
            val p1 = Offset(end.x, end.y)
            val p2 = Offset(
                end.x - tx * arrowSize - ty * arrowSize * 0.45f,
                end.y - ty * arrowSize + tx * arrowSize * 0.45f,
            )
            val p3 = Offset(
                end.x - tx * arrowSize + ty * arrowSize * 0.45f,
                end.y - ty * arrowSize - tx * arrowSize * 0.45f,
            )
            drawPath(
                Path().apply { moveTo(p1.x, p1.y); lineTo(p2.x, p2.y); lineTo(p3.x, p3.y); close() },
                color,
            )
        }

        // 关系标签（高倍放大 + 知识关系边）
        if (edge.kind == MemoryEdgeKind.GRAPH_RELATION &&
            edge.label != null && camera.scale >= GraphGeometry.EDGE_LABEL_MIN_SCALE &&
            (alpha > 0.4f)
        ) {
            val key = edge.label!!
            val layout = edgeLabelCache.getOrPut(key) {
                textMeasurer.measure(
                    key,
                    style = TextStyle(fontSize = 9.sp, fontWeight = FontWeight.Medium),
                )
            }
            drawText(
                layout,
                color = palette.entityColor.copy(alpha = (alpha + 0.15f).coerceAtMost(1f)),
                topLeft = Offset(
                    mid.x - layout.size.width / 2f,
                    mid.y - layout.size.height / 2f,
                ),
            )
        }
    }

    // ── 节点 ──
    // 标签层收集（屏幕空间，节点之后统一画避免被圆遮盖）
    val labels = mutableListOf<Triple<Offset, String, Float>>()
    for (node in graph.nodes) {
        val p = positions[node.id] ?: continue
        val s = camera.worldToScreen(p)
        val r = GraphGeometry.nodeRadius(node.weight) * camera.scale
        if (s.x < -r - 60 || s.x > size.width + r + 60 || s.y < -r - 60 || s.y > size.height + r + 60) continue

        val alpha = nodeAlpha(node.id)
        if (alpha < 0.05f) continue
        val base = palette.nodeColor(node.kind)

        // 填充 + 描边
        drawCircle(base.copy(alpha = 0.30f * alpha), radius = r, center = s)
        drawCircle(
            base.copy(alpha = 0.95f * alpha),
            radius = r,
            center = s,
            style = Stroke(width = (2.2f * camera.scale.coerceIn(0.7f, 1.6f)).coerceAtLeast(1.2f)),
        )

        // 晶化金环（宏专属：能量满级的永久固化勋章）
        if (node.kind == MemoryNodeKind.MACRO &&
            (node.payload as? MemoryNodePayload.Macro)?.macro?.stats?.crystallized == true
        ) {
            drawCircle(
                palette.crystallizedRingColor.copy(alpha = 0.9f * alpha),
                radius = r + 4.5f * camera.scale.coerceAtLeast(0.8f),
                center = s,
                style = Stroke(width = 2.4f * camera.scale.coerceAtLeast(0.8f)),
            )
        }

        // 搜索命中环
        if (searchActive && node.id in searchMatches) {
            drawCircle(
                palette.searchRingColor.copy(alpha = 0.95f),
                radius = r + 6.5f,
                center = s,
                style = Stroke(width = 2.6f),
            )
        }

        // 选中环（最外层白环 + 呼吸感双环）
        if (node.id == selection) {
            drawCircle(
                palette.selectionRingColor.copy(alpha = 0.95f),
                radius = r + 8.5f,
                center = s,
                style = Stroke(width = 2.8f),
            )
        }

        // 类别字符（节点够大才画）
        if (r >= 11f) {
            val fontSize = (r * 1.05f).coerceIn(9f, 46f)
            val glyphKey = "${palette.nodeGlyph(node.kind)}@${fontSize.toInt()}"
            val layout = glyphCache.getOrPut(glyphKey) {
                textMeasurer.measure(
                    palette.nodeGlyph(node.kind),
                    style = TextStyle(
                        fontSize = fontSize.sp,
                        fontWeight = FontWeight.Bold,
                        color = base.copy(alpha = 0.98f),
                    ),
                )
            }
            drawText(
                layout,
                color = base.copy(alpha = 0.98f * alpha),
                topLeft = Offset(s.x - layout.size.width / 2f, s.y - layout.size.height / 2f),
            )
        }

        // 状态点（情景：成功绿 / 失败红 / 进行中琥珀 / 放弃灰）
        if (node.kind == MemoryNodeKind.EPISODE && r >= 9f) {
            val statusColor = when (
                (node.payload as? MemoryNodePayload.Episode)?.record?.status
            ) {
                EpisodeStatus.SUCCEEDED -> Color(0xFF58C472)
                EpisodeStatus.FAILED -> Color(0xFFE05C5C)
                EpisodeStatus.RUNNING -> Color(0xFFE8A33D)
                else -> Color(0xFF9AA3AD)
            }
            drawCircle(statusColor.copy(alpha = alpha), radius = (r * 0.22f).coerceIn(2.5f, 7f),
                center = Offset(s.x + r * 0.72f, s.y - r * 0.72f))
        }

        // 标签（屏幕空间字号 —— 任意缩放下可读）
        if (camera.scale >= GraphGeometry.NODE_LABEL_MIN_SCALE && (alpha > 0.5f)) {
            labels.add(Triple(Offset(s.x, s.y + r + 6f), node.label, alpha))
        }
    }

    // 标签层（屏幕空间、统一样式、影描边双绘、cap 控制总量防噪）
    val labelAlphaBase = if (focusActive) 0.95f else 0.85f
    labels.take(MAX_LABELS).forEach { (pos, text, alpha) ->
        val ellipsized = if (text.length > 18) text.take(17) + "…" else text
        val layout = labelCache.getOrPut(ellipsized) {
            textMeasurer.measure(
                ellipsized,
                style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium),
            )
        }
        val topLeft = Offset(pos.x - layout.size.width / 2f, pos.y)
        // 影层（下石1px 深色）+ 主层 —— 明暗底色均可读
        drawText(layout, color = labelShadow.copy(alpha = labelAlphaBase * alpha), topLeft = topLeft + Offset(1f, 1f))
        drawText(layout, color = labelColor.copy(alpha = labelAlphaBase * alpha), topLeft = topLeft)
    }

    // 空态提示由外层 Screen 负责（画布保持纯渲染）
}

private const val MAX_LABELS = 90
