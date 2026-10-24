package com.apex.memkit.graph.screen

import androidx.compose.ui.geometry.Offset
import com.apex.memkit.graph.canvas.GraphCamera
import com.apex.memkit.graph.graph.DemoGraphData
import com.apex.memkit.graph.graph.DemoGraphSource
import com.apex.memkit.graph.graph.MemoryNodeKind
import com.apex.memkit.graph.layout.Vec2
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 状态机测试 —— 装载 / 过滤保位 / 搜索 / 选择漫游 / 拖拽锚定 / 相机数学。
 *
 * compose runtime 的快照机制为纯 Kotlin —— 单测可直接驱动
 * [MemoryGraphState]（无需 Robolectric / 预览环境）。
 */
class MemoryGraphStateTest {

    private fun newState() = MemoryGraphState(DemoGraphSource(DemoGraphData.data()), scope)

    @Test
    fun `装载产出全节点坐标与适配相机`() = runTest {
        val state = newState()
        state.load()
        state.awaitIdle()
        assertFalse(state.loading)
        assertNull(state.loadError)
        assertEquals(state.displayGraph.nodes.size, state.positions.size)
        // 相机已适配（scale 落在合法区间且非默认 1 的「未适配」值不可强断 —— 只验证区间）
        assertTrue(state.camera.scale in GraphCamera.MIN_SCALE..GraphCamera.MAX_SCALE)
    }

    @Test
    fun `类型过滤保位（存活节点坐标不变）`() = runTest {
        val state = newState()
        state.load()
        state.awaitIdle()
        val episodeId = state.displayGraph.nodes.first { it.kind == MemoryNodeKind.EPISODE }.id
        val before = state.positions[episodeId]!!

        state.toggleKind(MemoryNodeKind.ENTITY)  // 关闭实体
        state.awaitIdle()
        assertEquals(0, state.displayGraph.stats.entities)
        // 存活的情景节点保位（过滤不打乱空间认知）
        assertEquals(before, state.positions[episodeId])
        assertEquals(state.displayGraph.nodes.size, state.positions.size)
    }

    @Test
    fun `不允许清空全部类型`() = runTest {
        val state = newState()
        state.load()
        state.awaitIdle()
        // 逐个尝试关闭（SCENE_NODE 默认关 → 首次 toggle 是开启）：任何时刻集合非空
        MemoryNodeKind.entries.forEach { state.toggleKind(it) }
        state.awaitIdle()
        assertTrue(state.enabledKinds.isNotEmpty())
    }

    @Test
    fun `选中与邻接集合`() = runTest {
        val state = newState()
        state.load()
        state.awaitIdle()
        val macro = state.displayGraph.nodes.first { it.kind == MemoryNodeKind.MACRO && it.label == "发送消息给 Alice" }
        state.select(macro.id)
        assertEquals(macro.id, state.selection)
        assertNotNull(state.selectedNode)
        assertTrue(state.neighbors.isNotEmpty())
        state.select(null)
        assertNull(state.selection)
        assertTrue(state.neighbors.isEmpty())
    }

    @Test
    fun `navigateTo 自动开启被过滤类型`() = runTest {
        val state = newState()
        state.load()
        state.awaitIdle()
        val macroId = state.displayGraph.nodes.first { it.kind == MemoryNodeKind.MACRO }.id
        state.toggleKind(MemoryNodeKind.MACRO)  // 关闭宏
        state.awaitIdle()
        assertFalse(MemoryNodeKind.MACRO in state.enabledKinds)

        state.navigateTo(macroId)  // 跳转意图 > 过滤器
        assertTrue(MemoryNodeKind.MACRO in state.enabledKinds)
        assertEquals(macroId, state.selection)
    }

    @Test
    fun `搜索命中按 label 与 subtitle 匹配`() = runTest {
        val state = newState()
        state.load()
        state.awaitIdle()
        state.searchQuery = "Alice"
        assertTrue(state.searchMatches.isNotEmpty())
        assertTrue(state.searchMatches.any { id ->
            state.displayGraph.nodesById[id]?.label?.contains("Alice") == true
        })
        state.searchQuery = "不存在的记忆xyz"
        assertTrue(state.searchMatches.isEmpty())
        state.searchQuery = ""
        assertTrue(state.searchMatches.isEmpty())
    }

    @Test
    fun `拖拽节点即时生效且锚点保持落点`() = runTest {
        val state = newState()
        state.load()
        state.awaitIdle()
        val id = state.displayGraph.nodes.first().id
        val before = state.positions[id]!!
        state.dragNode(id, Vec2(50f, -30f))
        assertEquals(before + Vec2(50f, -30f), state.positions[id])
        state.endNodeDrag(id)
        state.awaitIdle()
        assertEquals(before + Vec2(50f, -30f), state.positions[id])  // 锚点纹丝不动
    }

    @Test
    fun `场景层切换重建图谱`() = runTest {
        val state = newState()
        state.load()
        state.awaitIdle()
        assertEquals(0, state.displayGraph.stats.sceneNodes)
        state.setSceneLayer(true)
        state.awaitIdle()
        assertTrue(state.displayGraph.stats.sceneNodes > 0)
        state.setSceneLayer(false)
        state.awaitIdle()
        assertEquals(0, state.displayGraph.stats.sceneNodes)
    }

    @Test
    fun `加载失败进入错误态而非崩溃`() = runTest {
        val state = MemoryGraphState(
            com.apex.memkit.graph.graph.MemoryGraphSource { throw IllegalStateException("boom") },
            scope,
        )
        state.load()
        state.awaitIdle()
        assertFalse(state.loading)
        assertEquals("boom", state.loadError)
        assertTrue(state.displayGraph.stats.isEmpty)
    }

    private companion object {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
    }
}

/** 相机数学 —— 缩放锚定 / 视口适配。 */
class GraphCameraTest {

    @Test
    fun `缩放保持焦点世界坐标不变`() {
        val camera = GraphCamera(scale = 1f, offset = Offset(0f, 0f))
        val focus = Offset(300f, 400f)
        val worldBefore = camera.screenToWorld(focus)
        val zoomed = GraphCamera.zoom(camera, 2.5f, focus)
        val worldAfter = zoomed.screenToWorld(focus)
        assertEquals(worldBefore.x, worldAfter.x, 0.01f)
        assertEquals(worldBefore.y, worldAfter.y, 0.01f)
        assertEquals(2.5f, zoomed.scale, 0.01f)
    }

    @Test
    fun `缩放区间钳制`() {
        val camera = GraphCamera(scale = GraphCamera.MAX_SCALE, offset = Offset.Zero)
        val over = GraphCamera.zoom(camera, 10f, Offset.Zero)
        assertEquals(GraphCamera.MAX_SCALE, over.scale, 0.01f)
        val under = GraphCamera.zoom(GraphCamera(scale = GraphCamera.MIN_SCALE), 0.01f, Offset.Zero)
        assertEquals(GraphCamera.MIN_SCALE, under.scale, 0.01f)
    }

    @Test
    fun `fit 视口居中且含包围盒`() {
        val bounds = com.apex.memkit.graph.canvas.GraphGeometry.Bounds(-100f, -50f, 100f, 50f)
        val camera = GraphCamera.fit(
            GraphCamera(),
            bounds,
            viewport = Offset(1000f, 800f),
        )
        val corners = listOf(
            Vec2(bounds.minX, bounds.minY), Vec2(bounds.maxX, bounds.minY),
            Vec2(bounds.minX, bounds.maxY), Vec2(bounds.maxX, bounds.maxY),
        )
        corners.forEach { c ->
            val s = camera.worldToScreen(c)
            assertTrue("角点 ${c} 落屏外: $s", s.x in -1f..1001f && s.y in -1f..801f)
        }
        // 中心点贴近视口中心
        val center = camera.worldToScreen(Vec2(bounds.centerX, bounds.centerY))
        assertEquals(500f, center.x, 30f)
        assertEquals(400f, center.y, 30f)
    }

    @Test
    fun `世界屏幕互逆`() {
        val camera = GraphCamera(scale = 0.7f, offset = Offset(120f, -80f))
        val p = Vec2(333f, 444f)
        val round = camera.screenToWorld(camera.worldToScreen(p))
        assertEquals(p.x, round.x, 0.01f)
        assertEquals(p.y, round.y, 0.01f)
    }
}
