package com.apex.memkit.graph.graph

import com.apex.memkit.core.MemKit
import com.apex.memkit.core.store.allFacts
import com.apex.memkit.core.store.readGraph
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map

/**
 * 记忆图谱数据源端口 —— UI 模块与记忆存储之间的唯一边界。
 *
 * 库 UI 零 Hilt / 零存储依赖：宿主注入任意 [MemoryGraphSource] 实现
 * （生产用 [MemKitGraphSource] 直读库门面；预览 / 测试可喂演示数据）。
 * [load] 在状态机的 IO 协程调用 —— 实现方自行保证线程安全与容错
 * （适配器对 store 的调用全为 mutex 串行的挂起读，天然安全）。
 */
fun interface MemoryGraphSource {

    /** 拉取一次五轨记忆快照（+ 别名桥语料）。 */
    suspend fun load(): GraphData

    /**
     * 数据变更信号（可选能力）：每次发射 = 记忆面可能已变化，观察方应重拉快照。
     *
     * 默认无信号 —— 静态源（演示数据 / 一次性快照）零改动兼容；生产适配器
     * 接 store 修订号（见 [MemKitGraphSource]）实现「记忆生长实时可见」。
     * 状态机对该流去抖合并（写入风暴 → 单次软刷新节拍）。
     */
    fun changes(): Flow<Unit> = emptyFlow()
}

/**
 * 生产适配器 —— [MemKit] 门面 → [GraphData] 快照。
 *
 * 全部走 store 公开查询面（allEpisodes / allMacros / allFacts / readGraph /
 * allNodes / allAliases），一次快照 = 一致性视图（store 内部 mutex 串行，
 * 各集合读之间无并发写穿透 —— 图谱构建的确定性输入）。
 */
class MemKitGraphSource(private val kit: MemKit) : MemoryGraphSource {

    override suspend fun load(): GraphData {
        val store = kit.store
        val (entities, relations) = store.readGraph()
        return GraphData(
            episodes = store.allEpisodes(),
            macros = store.allMacros(),
            facts = store.allFacts(),
            entities = entities,
            relations = relations,
            sceneNodes = store.allNodes(),
            aliases = store.allAliases(),
        )
    }

    /**
     * 变更信号 = store 修订号（markDirty 单点驱动的「可能变更」节拍）。
     * [drop] 跳过 StateFlow 当前值 —— 信号只表「之后的变更」。
     */
    override fun changes(): Flow<Unit> =
        kit.store.revision.drop(1).map { Unit }
}

/** 演示数据源（预览 / 空态引导用 —— 见 DemoGraphData）。 */
class DemoGraphSource(private val data: GraphData) : MemoryGraphSource {
    override suspend fun load(): GraphData = data
}
