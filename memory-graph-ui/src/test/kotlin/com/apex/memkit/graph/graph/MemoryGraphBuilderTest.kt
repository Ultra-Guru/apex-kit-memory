package com.apex.memkit.graph.graph

import com.apex.memkit.core.model.GraphRelation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图谱构建器测试 —— 六类节点成形与六类边推导的语义锁。
 *
 * 全部断言针对 [DemoGraphData]（固定语料）—— 任何构建规则的回归
 * （边类型接反 / cap 越界 / 聚合漏项）在此立刻现形。
 */
class MemoryGraphBuilderTest {

    private fun build(
        includeSceneNodes: Boolean = false,
        episodeCap: Int = 120,
        entityCap: Int = 200,
    ): MemoryGraph = MemoryGraphBuilder(
        MemoryGraphBuilder.Options(includeSceneNodes = includeSceneNodes, episodeCap = episodeCap, entityCap = entityCap),
    ).build(DemoGraphData.data())

    // ══ 节点成形 ═══════════════════════════════════════════════════

    @Test
    fun `六类节点全部成形且 id 前缀正确`() {
        val g = build(includeSceneNodes = true)
        val kinds = g.nodes.mapTo(mutableSetOf()) { it.kind }
        assertEquals(MemoryNodeKind.entries.toSet(), kinds)
        assertTrue(g.nodesById.containsKey(MemoryGraphBuilder.Ids.episode("ep_demo_1")))
        assertTrue(g.nodesById.containsKey(MemoryGraphBuilder.Ids.macro("macro_demo_send")))
        assertTrue(g.nodesById.containsKey(MemoryGraphBuilder.Ids.fact("fact_p1")))
        assertTrue(g.nodesById.containsKey(MemoryGraphBuilder.Ids.entity("Alice")))
        assertTrue(g.nodesById.containsKey(MemoryGraphBuilder.Ids.scene("fp_wechat_home_0000aaaa")))
        assertTrue(g.nodesById.containsKey(MemoryGraphBuilder.Ids.cluster("com.tencent.mm")))
    }

    @Test
    fun `场景层关闭时无场景节点且观察边退化为应用簇`() {
        val g = build(includeSceneNodes = false)
        assertEquals(0, g.stats.sceneNodes)
        assertNull(g.nodesById[MemoryGraphBuilder.Ids.scene("fp_wechat_home_0000aaaa")])
        // 场景节点缺席 → 指向场景节点的观察边不存在（悬空剔除）
        val sceneEdges = g.edges.filter { it.kind == MemoryEdgeKind.OBSERVED_IN }
            .filter { it.targetId.startsWith("scene:") }
        assertEquals(0, sceneEdges.size)
        // 情景 → 应用簇的 OBSERVED_IN 边仍应存在
        val ep = MemoryGraphBuilder.Ids.episode("ep_demo_1")
        val cluster = MemoryGraphBuilder.Ids.cluster("com.tencent.mm")
        assertTrue(g.edges.any { it.kind == MemoryEdgeKind.OBSERVED_IN && it.sourceId == ep && it.targetId == cluster })
    }

    @Test
    fun `情景载荷预解析观察节点文本`() {
        val g = build(includeSceneNodes = true)
        val ep = g.nodesById[MemoryGraphBuilder.Ids.episode("ep_demo_1")]
        val payload = ep?.payload as MemoryNodePayload.Episode
        assertEquals(3, payload.observedNodeTexts.size)
        assertTrue(payload.observedNodeTexts.any { it.contains("搜索") })
    }

    @Test
    fun `cap 截取生效且确定性`() {
        val g1 = build(episodeCap = 2)
        val g2 = build(episodeCap = 2)
        assertEquals(2, g1.stats.episodes)
        // cap 按 startedAt 降序 —— 保留最近两条（ep_demo_3 / ep_demo_manual）
        assertNull(g1.nodesById[MemoryGraphBuilder.Ids.episode("ep_demo_1")])
        assertNotNull(g1.nodesById[MemoryGraphBuilder.Ids.episode("ep_demo_manual")])
        // 确定性：同输入两次构建产出同节点集与同边集
        assertEquals(g1.nodes.map { it.id }, g2.nodes.map { it.id })
        assertEquals(g1.edges, g2.edges)
    }

    @Test
    fun `未知应用簇收纳包名缺失的记忆`() {
        val g = build()
        val unknown = g.nodesById[MemoryGraphBuilder.Ids.cluster(null)]
        assertNotNull(unknown)
        val payload = unknown!!.payload as MemoryNodePayload.AppCluster
        // ep_demo_manual（无包名情景）折入未知簇
        assertEquals(1, payload.episodeCount)
    }

    // ══ 边推导 ═════════════════════════════════════════════════════

    @Test
    fun `派生宏边沿 macroIds 连线`() {
        val g = build()
        val ep1 = MemoryGraphBuilder.Ids.episode("ep_demo_1")
        val send = MemoryGraphBuilder.Ids.macro("macro_demo_send")
        assertTrue(g.edges.any { it.kind == MemoryEdgeKind.DERIVED_MACRO && it.sourceId == ep1 && it.targetId == send })
    }

    @Test
    fun `宏链边按出口指纹衔接且方向正确`() {
        val g = build()
        // search.exit = FP_SEARCH_OLD ∈ send.entry → search → send
        val search = MemoryGraphBuilder.Ids.macro("macro_demo_search")
        val send = MemoryGraphBuilder.Ids.macro("macro_demo_send")
        val chain = g.edges.filter { it.kind == MemoryEdgeKind.MACRO_CHAIN }
        assertTrue(chain.any { it.sourceId == search && it.targetId == send })
        // 反向不存在（方向语义：出口 → 入口）
        assertTrue(chain.none { it.sourceId == send && it.targetId == search })
        // wifi 宏不与微信宏衔接（不同应用锚点指纹）
        val wifi = MemoryGraphBuilder.Ids.macro("macro_demo_wifi")
        assertTrue(chain.none { it.sourceId == wifi || it.targetId == wifi })
    }

    @Test
    fun `知识关系边带 relationType 标签且悬空端剔除`() {
        val g = build()
        val relations = g.edges.filter { it.kind == MemoryEdgeKind.GRAPH_RELATION }
        // 6 条输入关系中 3 条引用「用户」实体（不在实体集）→ 悬空剔除，3 条成形
        assertEquals(3, relations.size)
        assertTrue(relations.any { it.label == "喜欢" })
        assertTrue(relations.none { it.label == null })
        // 悬空端（from=用户）全部剔除
        assertTrue(relations.none { r -> r.sourceId == MemoryGraphBuilder.Ids.entity("用户") })
        // Alice → 周末爬山（喜欢）成形且方向正确
        assertTrue(relations.any {
            it.sourceId == MemoryGraphBuilder.Ids.entity("Alice") &&
                it.targetId == MemoryGraphBuilder.Ids.entity("周末爬山")
        })
    }

    @Test
    fun `别名桥仅在场景层开启时成形`() {
        val off = build(includeSceneNodes = false)
        assertEquals(0, off.edges.count { it.kind == MemoryEdgeKind.ALIAS_BRIDGE })

        val on = build(includeSceneNodes = true)
        val bridges = on.edges.filter { it.kind == MemoryEdgeKind.ALIAS_BRIDGE }
        assertEquals(1, bridges.size)
        assertEquals(
            MemoryGraphBuilder.Ids.scene("fp_wechat_search_old_2222cccc"),
            bridges.single().sourceId,
        )
        assertEquals(
            MemoryGraphBuilder.Ids.scene("fp_wechat_search_new_3333dddd"),
            bridges.single().targetId,
        )
    }

    @Test
    fun `应用归属边连接宏与应用簇`() {
        val g = build()
        val wifi = MemoryGraphBuilder.Ids.macro("macro_demo_wifi")
        val settings = MemoryGraphBuilder.Ids.cluster("com.android.settings")
        assertTrue(g.edges.any { it.kind == MemoryEdgeKind.APP_SCOPE && it.sourceId == wifi && it.targetId == settings })
    }

    @Test
    fun `观察边连接情景与场景节点`() {
        val g = build(includeSceneNodes = true)
        val ep3 = MemoryGraphBuilder.Ids.episode("ep_demo_3")
        val searchNew = MemoryGraphBuilder.Ids.scene("fp_wechat_search_new_3333dddd")
        assertTrue(g.edges.any { it.kind == MemoryEdgeKind.OBSERVED_IN && it.sourceId == ep3 && it.targetId == searchNew })
    }

    // ══ 索引与过滤 ═════════════════════════════════════════════════

    @Test
    fun `邻接表双向索引`() {
        val g = build()
        val send = MemoryGraphBuilder.Ids.macro("macro_demo_send")
        val neighbors = g.neighborsOf(send)
        // search（宏链）+ ep_demo_1（派生）+ 微信簇（归属）+ search 宏的链回边对端
        assertTrue(MemoryGraphBuilder.Ids.macro("macro_demo_search") in neighbors)
        assertTrue(MemoryGraphBuilder.Ids.episode("ep_demo_1") in neighbors)
        assertTrue(MemoryGraphBuilder.Ids.cluster("com.tencent.mm") in neighbors)
    }

    @Test
    fun `类型过滤保留存活两端的边`() {
        val g = build(includeSceneNodes = true)
        val onlyEntity = g.filtered(setOf(MemoryNodeKind.ENTITY))
        assertEquals(4, onlyEntity.nodes.size)
        assertEquals(3, onlyEntity.edges.size) // 实体间知识关系（悬空端已剔除的 3 条）
        val onlyEpisode = g.filtered(setOf(MemoryNodeKind.EPISODE))
        assertEquals(4, onlyEpisode.nodes.size)
        assertEquals(0, onlyEpisode.edges.size) // 情景的全部邻端（宏/簇/节点）都被过滤
    }

    @Test
    fun `重复边合并计数不重复成形`() {
        val data = DemoGraphData.data().let { d ->
            // 同一实体对追加第二条同型关系 → 合并 strength=2
            d.copy(relations = d.relations + GraphRelation("Alice", "周末爬山", "喜欢"))
        }
        val g = MemoryGraphBuilder().build(data)
        val rel = g.edges.single { it.kind == MemoryEdgeKind.GRAPH_RELATION && it.label == "喜欢" }
        assertEquals(2, rel.strength)
    }
}
