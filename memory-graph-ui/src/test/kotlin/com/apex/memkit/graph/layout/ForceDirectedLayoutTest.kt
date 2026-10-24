package com.apex.memkit.graph.layout

import com.apex.memkit.core.store.addGraphRelations
import com.apex.memkit.core.store.upsertFactFromDistill
import com.apex.memkit.core.store.upsertGraphEntities
import com.apex.memkit.graph.graph.MemoryGraphBuilder
import com.apex.memkit.graph.graph.MemoryGraphSource
import com.apex.memkit.graph.graph.DemoGraphData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * 力导向布局引擎测试 —— 确定性 / 收敛性 / 节点分离 / 聚类分离 / 锚定。
 */
class ForceDirectedLayoutTest {

    private val graph = MemoryGraphBuilder(
        MemoryGraphBuilder.Options(includeSceneNodes = true),
    ).build(DemoGraphData.data())

    private val engine = ForceDirectedLayout()

    @Test
    fun `同 seed 同布局（确定性契约）`() {
        val a = engine.layout(graph, seed = 0L)
        val b = engine.layout(graph, seed = 0L)
        assertEquals(a, b)
    }

    @Test
    fun `不同 seed 布局不同（seed 真实参与初始摆位）`() {
        val a = engine.layout(graph, seed = 0L)
        val b = engine.layout(graph, seed = 42L)
        assertTrue(a != b)
    }

    @Test
    fun `全节点有坐标且有限`() {
        val positions = engine.layout(graph)
        assertEquals(graph.nodes.size, positions.size)
        positions.values.forEach { p ->
            assertTrue(p.x.isFinite() && p.y.isFinite())
            // 向心引力约束下的量级护栏（漫游逃逸即 > 100k）
            assertTrue("坐标逃逸: $p", kotlin.math.abs(p.x) < 100_000f && kotlin.math.abs(p.y) < 100_000f)
        }
    }

    @Test
    fun `边端点距离显著小于随机距离（弹簧生效）`() {
        val positions = engine.layout(graph)
        // 连线端点的平均距离 vs 全节点对平均距离 —— 弹簧应把邻端拉得更近
        val edgeDist = graph.edges.mapNotNull { e ->
            val a = positions[e.sourceId] ?: return@mapNotNull null
            val b = positions[e.targetId] ?: return@mapNotNull null
            a.distanceTo(b)
        }.average()
        val nodes = graph.nodes.mapNotNull { positions[it.id] }
        var allDist = 0.0; var count = 0
        for (i in nodes.indices) for (j in i + 1 until nodes.size) {
            allDist += nodes[i].distanceTo(nodes[j]); count++
        }
        val avgAll = allDist / count
        assertTrue(
            "边端点均距 $edgeDist 应明显小于全对均距 $avgAll",
            edgeDist < avgAll * 0.9,
        )
    }

    @Test
    fun `同簇节点质心距离小于异簇（聚类重力生效）`() {
        val positions = engine.layout(graph)
        val byCluster = graph.nodes.groupBy({ it.clusterKey }, { positions[it.id] })
            .mapValues { (_, ps) -> ps.filterNotNull() }
        // 取两个最大的簇比较簇内均距 vs 簇间质心距
        val clusters = byCluster.entries.filter { it.value.size >= 2 }.sortedByDescending { it.value.size }
        assertTrue(clusters.size >= 2)
        val intra = clusters.take(3).map { (key, ps) ->
            var d = 0.0; var c = 0
            for (i in ps.indices) for (j in i + 1 until ps.size) { d += ps[i].distanceTo(ps[j]); c++ }
            key to (d / c.coerceAtLeast(1))
        }
        val (k1, d1) = intra[0]
        val (k2, d2) = intra[1]
        val c1 = byCluster.getValue(k1).centroid()
        val c2 = byCluster.getValue(k2).centroid()
        val inter = c1.distanceTo(c2)
        assertTrue(
            "簇间质心距 $inter 应大于簇内均距 ${maxOf(d1, d2)}",
            inter > maxOf(d1, d2),
        )
    }

    @Test
    fun `pinned 节点纹丝不动`() {
        val anchorId = graph.nodes.first().id
        val anchorPos = Vec2(1234f, -567f)
        val positions = engine.layout(graph, seed = 3L, pinned = mapOf(anchorId to anchorPos))
        assertEquals(anchorPos, positions[anchorId])
    }

    @Test
    fun `relax 以锚点固定且其余节点微调`() {
        val base = engine.layout(graph, seed = 0L)
        val anchorId = graph.nodes.first { graph.neighborsOf(it.id).isNotEmpty() }.id
        val dragged = base.toMutableMap()
        dragged[anchorId] = base.getValue(anchorId) + Vec2(80f, 40f)
        val relaxed = engine.relax(graph, dragged, anchorId)
        // 锚点保持拖拽落点
        assertEquals(dragged[anchorId], relaxed[anchorId])
        // 其余节点相对原位有位移但不爆炸（60% 折中 + 40% 原位）
        val other = graph.nodes.first { it.id != anchorId }.id
        val moved = relaxed.getValue(other).distanceTo(base.getValue(other))
        assertTrue("邻居位移 $moved 应温和（< 300）", moved < 300f)
    }

    @Test
    fun `空图返回空布局`() {
        val empty = MemoryGraphBuilder().build(com.apex.memkit.graph.graph.GraphData())
        assertTrue(engine.layout(empty).isEmpty())
    }

    private fun List<Vec2>.centroid(): Vec2 {
        if (isEmpty()) return Vec2.ZERO
        var x = 0f; var y = 0f
        forEach { x += it.x; y += it.y }
        return Vec2(x / size, y / size)
    }
}

/**
 * 源适配器集成测试 —— MemKit 全链路（存储 → 门面查询 → 图谱快照）。
 *
 * 用 InMemoryStorageBackend 起真实 MemKit + 脚本化捕获端口：经公开写入面
 * （会话导演 / 事实 upsert / 图谱 transport）落数据，再经
 * [com.apex.memkit.graph.graph.MemKitGraphSource] 拉快照 —— 验证「生产
 * 适配器读出的就是库里的」。
 */
class MemKitGraphSourceTest {

    /** 脚本化捕获：begin / 每步 recordAction 依次吐出预置屏面。 */
    private class ScriptedCapture(private val screens: List<com.apex.memkit.core.RawCapture>) : com.apex.memkit.core.SceneCapture {
        private var index = 0
        override suspend fun capture(): com.apex.memkit.core.RawCapture? =
            screens.getOrNull(index.coerceAtMost(screens.lastIndex)).also { if (index < screens.lastIndex) index++ }
    }

    // 屏面构造：非交互根容器（全屏）+ 可交互按钮 —— 稀释可点击面积占比，
    // 免触发摄取守门的「全屏遮罩 + 非受信包名」误隔离（SessionDirectorTest 同款形态）
    private fun screen(vararg texts: String) = com.apex.memkit.core.RawCapture(
        roots = listOf(
            com.apex.memkit.core.RawNode(
                className = "android.view.ViewGroup",
                text = "", contentDescription = "", resourceId = "root",
                bounds = "[0,0][1080,2400]", clickable = false, scrollable = false,
                children = texts.map { t ->
                    com.apex.memkit.core.RawNode(
                        className = "android.widget.Button",
                        text = t, contentDescription = "", resourceId = "id_$t",
                        bounds = "[10,10][400,200]", clickable = true, scrollable = false,
                    )
                },
            ),
        ),
        appPackage = "com.example.app", appVersion = "1.0",
    )

    @Test
    fun `适配器快照反映库内五轨数据`() = kotlinx.coroutines.test.runTest {
        val kit = com.apex.memkit.core.MemKit.create(
            storage = com.apex.memkit.core.store.InMemoryStorageBackend(),
            capture = ScriptedCapture(listOf(screen("首页"), screen("结果"), screen("详情"))),
        )
        // 情景 + 派生宏（经会话导演的公开编排面；三屏两步 → 可蒸馏轨迹）
        kit.sessions.begin("task_1", "测试目标", "com.example.app")
        kit.sessions.recordAction("task_1", "UiTap(100,100)", success = true)
        kit.sessions.recordAction("task_1", "Input(hello)", success = true)
        val end = kit.sessions.end("task_1", success = true)
        // 聊天事实（蒸馏 upsert 的公开写入面 —— 免启发式词典的脆弱断言）
        kit.store.upsertFactFromDistill(
            com.apex.memkit.core.model.ChatFact(
                id = "fact_1", content = "用户偏好 Kotlin", kind = com.apex.memkit.core.model.ChatFact.Kind.PROFILE,
                source = com.apex.memkit.core.model.ChatFact.Source.DISTILLED,
                importance = 0.8f, createdAt = 1L,
            ),
        )
        // 知识图谱（经 store 的图谱 transport）
        kit.store.upsertGraphEntities(
            listOf(com.apex.memkit.core.model.GraphEntity("Kotlin", "language", listOf("JVM 语言"))),
        )
        kit.store.addGraphRelations(
            listOf(com.apex.memkit.core.model.GraphRelation("Kotlin", "编译为", "字节码")),
        )

        val snapshot = com.apex.memkit.graph.graph.MemKitGraphSource(kit).load()

        assertTrue("应产出情景", snapshot.episodes.isNotEmpty())
        assertEquals(end.episodeId, snapshot.episodes.single().episodeId)
        assertTrue("成功任务应蒸馏出宏", snapshot.macros.isNotEmpty())
        assertEquals(1, snapshot.facts.size)
        assertEquals(1, snapshot.entities.size)
        assertEquals(1, snapshot.relations.size)
        assertTrue("会话应摄取 UI 节点", snapshot.sceneNodes.isNotEmpty())
        // 图谱构建端到端冒烟：适配器数据 → 构建器 → 六类结构完整
        val graph = MemoryGraphBuilder(MemoryGraphBuilder.Options(includeSceneNodes = true)).build(snapshot)
        assertTrue(graph.stats.episodes > 0 && graph.stats.macros > 0 && graph.stats.facts > 0 && graph.stats.entities > 0)

        kit.close()
    }

    @Test
    fun `别名查询经适配器可见`() = kotlinx.coroutines.test.runTest {
        val kit = com.apex.memkit.core.MemKit.create(
            storage = com.apex.memkit.core.store.InMemoryStorageBackend(),
        )
        kit.store.addAlias(
            com.apex.memkit.core.model.FingerprintAlias(
                oldFingerprint = "fp_old", newFingerprint = "fp_new",
                appPackage = "com.example.app", score = 0.9f, createdAt = 1L,
            ),
        )
        val snapshot = com.apex.memkit.graph.graph.MemKitGraphSource(kit).load()
        assertEquals(1, snapshot.aliases.size)
        kit.close()
    }
}
