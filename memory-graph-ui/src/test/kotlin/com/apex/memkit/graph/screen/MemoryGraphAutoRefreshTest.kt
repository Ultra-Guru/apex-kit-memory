package com.apex.memkit.graph.screen

import com.apex.memkit.core.model.ChatFact
import com.apex.memkit.graph.canvas.GraphCamera
import com.apex.memkit.graph.graph.DemoGraphData
import com.apex.memkit.graph.graph.GraphData
import com.apex.memkit.graph.graph.MemoryGraphSource
import com.apex.memkit.graph.graph.MemoryNodeKind
import com.apex.memkit.graph.layout.Vec2
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动软刷新测试 —— 变更信号驱动的无感重载（保位 / 保选中 / 去抖合并 / 静默失败）。
 *
 * 调度器纪律：debounce 的虚拟时间要求状态机的 scope 用 [StandardTestDispatcher]
 * （[MemoryGraphStateTest] 的 Unconfined 场景里 delay 走真实时钟，推不动去抖）；
 * 布局的 withContext(Default) 为真实线程，统一以 [MemoryGraphState.awaitIdle] 收口。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MemoryGraphAutoRefreshTest {

    /** 可变数据 + 可发信号的可故障源（模拟活记忆库）。 */
    private class LiveSource(initial: GraphData) : MemoryGraphSource {
        val signal = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
        var data = initial
        var loadCount = 0
        var failNext = false

        /** 首次 load 的挂起时长（虚拟时间 —— 制造「首装在途」窗口）。 */
        var loadDelayMs = 0L

        override suspend fun load(): GraphData {
            if (loadDelayMs > 0) {
                delay(loadDelayMs)
                loadDelayMs = 0
            }
            if (failNext) {
                failNext = false
                throw IllegalStateException("boom")
            }
            loadCount++
            return data
        }

        override fun changes() = signal
    }

    private fun newState(scope: CoroutineScope, source: MemoryGraphSource) =
        MemoryGraphState(source, scope)

    @Test
    fun `变更信号触发软刷新 - 新记忆生效且探索现场保全`() = runTest {
        val source = LiveSource(DemoGraphData.data())
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val state = newState(scope, source)
        state.load()
        advanceUntilIdle()  // 首装 + 观察协程启动
        state.awaitIdle()
        val factsBefore = state.displayGraph.stats.facts

        // 建立探索现场：选中宏节点 + 拖走一个情景节点
        val macro = state.displayGraph.nodes.first { it.kind == MemoryNodeKind.MACRO }
        state.select(macro.id)
        val dragged = state.displayGraph.nodes.first { it.kind == MemoryNodeKind.EPISODE }
        val draggedBefore = state.positions[dragged.id]!!
        state.dragNode(dragged.id, Vec2(120f, 80f))

        // 新记忆长出来：数据换新 + 发信号
        source.data = DemoGraphData.data().copy(
            facts = DemoGraphData.data().facts + ChatFact(
                id = "fact_new_live",
                content = "用户刚学会用记忆图谱看关系",
                kind = ChatFact.Kind.STATE,
                source = ChatFact.Source.HEURISTIC,
                importance = 0.7f,
                createdAt = 1_728_002_000_000,
            ),
        )
        assertTrue(source.signal.tryEmit(Unit))
        advanceUntilIdle()  // 去抖虚拟窗口 + 软刷新
        state.awaitIdle()

        assertEquals(factsBefore + 1, state.displayGraph.stats.facts)  // 新事实已可见
        assertEquals(macro.id, state.selection)                        // 选中保留
        assertNotNull(state.selectedNode)
        assertEquals(draggedBefore + Vec2(120f, 80f), state.positions[dragged.id])  // 拖拽落点保位
        assertEquals(state.displayGraph.nodes.size, state.positions.size)
    }

    @Test
    fun `写入风暴去抖合并为单次重拉`() = runTest {
        val source = LiveSource(DemoGraphData.data())
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val state = newState(scope, source)
        state.load()
        advanceUntilIdle()
        state.awaitIdle()
        assertEquals(1, source.loadCount)

        repeat(5) { assertTrue(source.signal.tryEmit(Unit)) }  // 同窗风暴
        advanceUntilIdle()
        state.awaitIdle()
        assertEquals(2, source.loadCount)  // 1 首装 + 1 合并软刷新
    }

    @Test
    fun `探索态相机稳定 - 巡航态重适配纳入新节点`() = runTest {
        val source = LiveSource(DemoGraphData.data())
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val state = newState(scope, source)
        state.load()
        advanceUntilIdle()
        state.awaitIdle()

        // 探索态：选中 + 手动推满缩放 → 软刷新不得动相机
        val entity = state.displayGraph.nodes.first { it.kind == MemoryNodeKind.ENTITY }
        state.select(entity.id)
        state.zoom(100f, androidx.compose.ui.geometry.Offset(500f, 500f))  // 钳到 MAX_SCALE
        val zoomed = state.camera
        assertTrue(zoomed.scale >= GraphCamera.MAX_SCALE - 0.01f)
        source.signal.tryEmit(Unit)
        advanceUntilIdle()
        state.awaitIdle()
        assertEquals(zoomed, state.camera)  // 探索中不打扰

        // 巡航态（无选中）：软刷新重适配（新节点纳入视野）
        state.select(null)
        source.data = DemoGraphData.data().copy(
            entities = DemoGraphData.data().entities + com.apex.memkit.core.model.GraphEntity(
                "新实体", "thing", listOf("新观察"),
            ),
        )
        source.signal.tryEmit(Unit)
        advanceUntilIdle()
        state.awaitIdle()
        assertTrue(state.displayGraph.stats.entities >= 5)
        // 巡航态重适配后相机回到适配区间（不再钉在用户手动极值上）
        assertTrue(state.camera.scale < GraphCamera.MAX_SCALE)
    }

    @Test
    fun `软刷新失败静默保持现状`() = runTest {
        val source = LiveSource(DemoGraphData.data())
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val state = newState(scope, source)
        state.load()
        advanceUntilIdle()
        state.awaitIdle()
        val nodesBefore = state.displayGraph.stats.nodes

        source.failNext = true
        assertTrue(source.signal.tryEmit(Unit))
        advanceUntilIdle()
        state.awaitIdle()

        assertNull(state.loadError)                            // 不进显式错误态
        assertEquals(nodesBefore, state.displayGraph.stats.nodes)  // 原图谱保留
    }

    @Test
    fun `静态源无信号不刷新`() = runTest {
        var data = DemoGraphData.data()
        val source = object : MemoryGraphSource {
            override suspend fun load(): GraphData = data
        }
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val state = newState(scope, source)
        state.load()
        advanceUntilIdle()
        state.awaitIdle()
        val factsBefore = state.displayGraph.stats.facts

        data = data.copy(facts = emptyList())  // 数据变了但无信号
        advanceUntilIdle()
        state.awaitIdle()
        assertEquals(factsBefore, state.displayGraph.stats.facts)  // 图谱保持原样
    }

    @Test
    fun `首装在途时软刷新让位`() = runTest {
        val source = LiveSource(DemoGraphData.data())
        source.loadDelayMs = 5000  // 首装挂起 5s（虚拟）—— 制造确定的在途窗口
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val state = newState(scope, source)
        state.load()
        advanceTimeBy(1000)  // collector 订阅 + 首装进入挂起窗口（loading=true）
        assertTrue(state.loading)

        source.signal.tryEmit(Unit)  // 在途窗口内到达的信号
        advanceUntilIdle()           // 去抖在 2500ms 触发 → 让位返回；首装 5000ms 完成
        state.awaitIdle()

        assertEquals(1, source.loadCount)  // 让位：软刷新未重拉（等下一信号再同步）
        assertTrue(state.displayGraph.stats.facts >= 4)  // 首装数据完整
        assertFalse(state.loading)
    }
}
