package com.apex.memkit.graph.graph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 边显示标签解析器测试 —— 「连线显示具体关联」的语义锁。
 *
 * 六类边全部可解析：知识关系优先用具体 relationType，其余五类用
 * 类型语义名；合并强度 >1 追加 ×N；语义名缺席时优雅降级为 null。
 */
class EdgeLabelsTest {

    private val kindNames = mapOf(
        MemoryEdgeKind.DERIVED_MACRO to "蒸馏产出",
        MemoryEdgeKind.MACRO_CHAIN to "宏链衔接",
        MemoryEdgeKind.OBSERVED_IN to "观察到",
        MemoryEdgeKind.APP_SCOPE to "归属",
        MemoryEdgeKind.GRAPH_RELATION to "知识关系",
        MemoryEdgeKind.ALIAS_BRIDGE to "版本别名桥",
    )

    @Test
    fun `知识关系边优先用具体 relationType`() {
        val edge = MemoryEdge("entity:A", "entity:B", MemoryEdgeKind.GRAPH_RELATION, label = "works_at")
        assertEquals("works_at", EdgeLabels.labelOf(edge, kindNames))
    }

    @Test
    fun `知识关系边 relationType 空白时兜底类型语义名`() {
        val edge = MemoryEdge("entity:A", "entity:B", MemoryEdgeKind.GRAPH_RELATION, label = "  ")
        assertEquals("知识关系", EdgeLabels.labelOf(edge, kindNames))
    }

    @Test
    fun `其余五类边用类型语义名`() {
        assertEquals(
            "蒸馏产出",
            EdgeLabels.labelOf(MemoryEdge("ep:1", "macro:m", MemoryEdgeKind.DERIVED_MACRO), kindNames),
        )
        assertEquals(
            "宏链衔接",
            EdgeLabels.labelOf(MemoryEdge("macro:a", "macro:b", MemoryEdgeKind.MACRO_CHAIN), kindNames),
        )
        assertEquals(
            "观察到",
            EdgeLabels.labelOf(MemoryEdge("ep:1", "cluster:pkg", MemoryEdgeKind.OBSERVED_IN), kindNames),
        )
        assertEquals(
            "归属",
            EdgeLabels.labelOf(MemoryEdge("macro:m", "cluster:pkg", MemoryEdgeKind.APP_SCOPE), kindNames),
        )
        assertEquals(
            "版本别名桥",
            EdgeLabels.labelOf(MemoryEdge("scene:old", "scene:new", MemoryEdgeKind.ALIAS_BRIDGE), kindNames),
        )
    }

    @Test
    fun `合并强度大于一时追加计数`() {
        val merged = MemoryEdge("ep:1", "cluster:pkg", MemoryEdgeKind.OBSERVED_IN, strength = 7)
        assertEquals("观察到 ×7", EdgeLabels.labelOf(merged, kindNames))
        val mergedRelation = MemoryEdge("entity:A", "entity:B", MemoryEdgeKind.GRAPH_RELATION, label = "owns", strength = 2)
        assertEquals("owns ×2", EdgeLabels.labelOf(mergedRelation, kindNames))
    }

    @Test
    fun `语义名缺席且无标签时返回 null（不渲染）`() {
        val edge = MemoryEdge("ep:1", "macro:m", MemoryEdgeKind.DERIVED_MACRO)
        assertNull(EdgeLabels.labelOf(edge, emptyMap()))
    }

    @Test
    fun `构建语料上的全量边都可解析`() {
        val graph = MemoryGraphBuilder().build(DemoGraphData.data())
        val unresolved = graph.edges.filter { EdgeLabels.labelOf(it, kindNames) == null }
        assertEquals("全部六类边都应能解析出具体关联标签：$unresolved", 0, unresolved.size)
    }
}
