package com.apex.memkit.core.store

import com.apex.memkit.core.MemKitConfig
import com.apex.memkit.core.SilentLogger
import com.apex.memkit.core.TestClock
import com.apex.memkit.core.model.ChatFact
import com.apex.memkit.core.model.ChatTurn
import com.apex.memkit.core.model.EpisodeRecord
import com.apex.memkit.core.model.EpisodeStatus
import com.apex.memkit.core.model.FingerprintAlias
import com.apex.memkit.core.model.GraphEntity
import com.apex.memkit.core.model.GraphRelation
import com.apex.memkit.core.model.MacroAction
import com.apex.memkit.core.model.MacroSkill
import com.apex.memkit.core.model.MacroStep
import com.apex.memkit.core.model.SceneNode
import com.apex.memkit.core.rawNode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 存储层全套：episodes / nodes / macros / aliases / chat / graph /
 * quarantine / 容量治理 / 持久化往返。
 */
class MemoryStoreTest {

    private lateinit var backend: InMemoryStorageBackend
    private lateinit var clock: TestClock
    private lateinit var store: MemoryStore
    private val config = MemKitConfig()

    @Before
    fun setup() {
        backend = InMemoryStorageBackend()
        clock = TestClock()
        store = MemoryStore(config, backend, clock, SilentLogger)
    }

    private fun node(fp: String, text: String = "n-$fp") = SceneNode(
        fingerprint = fp, role = com.apex.memkit.core.model.NodeRole.BUTTON,
        text = text, bounds = com.apex.memkit.core.model.Bounds(0, 0, 10, 10), interactive = true,
    )

    private fun macro(id: String, pkg: String? = "com.app", entry: Set<String> = setOf("e1", "e2")) = MacroSkill(
        skillId = id, name = id, goal = "g", appPackage = pkg,
        entryFingerprints = entry, exitFingerprint = "x",
        steps = listOf(
            MacroStep(MacroAction.Tap(1, 1), setOf("e1"), setOf("s1")),
        ),
        createdAt = 1L,
    )

    @Test
    fun `episode 生命周期与真实统计`() = runTest {
        store.addEpisode(EpisodeRecord("ep1", "目标", status = EpisodeStatus.RUNNING, startedAt = 1))
        store.updateEpisode("ep1") { it.copy(actionCount = 7) }
        val summary = store.recentEpisodes(10).single()
        assertEquals(7, summary.actionCount)
        store.updateEpisode("ep1") { it.copy(status = EpisodeStatus.SUCCEEDED, macroIds = listOf("m1")) }
        assertEquals(EpisodeStatus.SUCCEEDED, store.recentEpisodes(10).single().status)
        assertEquals(listOf("m1"), store.recentEpisodes(10).single().macroIds)
        assertTrue(store.removeEpisode("ep1"))
        assertNull(store.episodeById("ep1"))
        assertFalse(store.removeEpisode("ep1"))
    }

    @Test
    fun `节点批量 upsert 新增与见过计数`() = runTest {
        val (added, seen) = store.upsertNodes(listOf(node("a"), node("b")), "com.app", "1.0")
        assertEquals(2, added)
        assertEquals(0, seen)
        val (added2, seen2) = store.upsertNodes(listOf(node("a"), node("c")), "com.app", "1.0")
        assertEquals(1, added2)
        assertEquals(1, seen2)
        val all = store.allNodes().associateBy { it.fingerprint }
        assertEquals(2, all["a"]!!.occurrences)
        assertEquals(1, all["b"]!!.occurrences)
        assertEquals("com.app", all["a"]!!.appPackage)
        assertEquals(3, store.nodeCount())
    }

    @Test
    fun `节点搜索按文本命中并按出现次数排序`() = runTest {
        store.upsertNodes(listOf(node("a", "设置面板"), node("b", "普通")), null, null)
        store.upsertNodes(listOf(node("a", "设置面板")), null, null)  // a 再现
        val hits = store.searchNodes("设置", 10)
        assertEquals(1, hits.size)
        assertEquals("a", hits[0].fingerprint)
        assertTrue(store.searchNodes("不存在", 10).isEmpty())
    }

    @Test
    fun `宏 CRUD 与包名索引`() = runTest {
        store.saveMacro(macro("m1"))
        store.saveMacro(macro("m2", pkg = null))
        assertEquals(2, store.macroCount())
        assertEquals(setOf("m1"), store.macrosForPackage("com.app").map { it.skillId }.toSet())
        assertEquals(setOf("m2"), store.macrosForPackage(null).map { it.skillId }.toSet())
        store.updateMacro("m1") { it.copy(name = "改名") }
        assertEquals("改名", store.macroById("m1")!!.name)
        val top = store.topMacros(10)
        assertEquals(2, top.size)
    }

    @Test
    fun `别名去重保留最高分`() = runTest {
        store.addAlias(FingerprintAlias("old", "new1", null, 0.6f, null, null, 1))
        store.addAlias(FingerprintAlias("old", "new1", null, 0.9f, null, null, 2))
        store.addAlias(FingerprintAlias("old", "new2", null, 0.8f, null, null, 3))
        val list = store.aliasesFrom("old")
        assertEquals(2, list.size)
        assertEquals(0.9f, list.first { it.newFingerprint == "new1" }.score, 0.001f)
    }

    @Test
    fun `聊天事实幂等键与强化`() = runTest {
        val r1 = store.addOrBumpFact("我叫小明", ChatFact.Kind.PROFILE, ChatFact.Source.HEURISTIC, 0.5f)
        val r2 = store.addOrBumpFact("我叫小明", ChatFact.Kind.PROFILE, ChatFact.Source.HEURISTIC, 0.5f)
        assertTrue(r1.added)
        assertTrue(r2.bumped)
        val facts = store.allFacts()
        assertEquals(1, facts.size)
        assertEquals(2, facts[0].accessCount)
        // 近况单条有界替换
        store.replaceStateFact("近期情绪基调：低落", 5)
        store.replaceStateFact("近期情绪基调：焦虑", 9)
        assertEquals(1, store.allFacts().count { it.kind == ChatFact.Kind.STATE })
    }

    @Test
    fun `蒸馏队列 peek-drop 语义`() = runTest {
        store.enqueueTurn(ChatTurn("u1", "a1", 1))
        store.enqueueTurn(ChatTurn("u2", "a2", 2))
        val window = store.peekQueue(6)
        assertEquals(2, window.size)
        assertEquals(2, store.pendingTurnCount())  // peek 不清队
        store.dropQueue(window)
        assertEquals(0, store.pendingTurnCount())
    }

    @Test
    fun `图谱 MCP 语义`() = runTest {
        val added = store.upsertGraphEntities(
            listOf(GraphEntity("小明", "person", listOf("程序员")), GraphEntity("项目A", "project")),
        )
        assertEquals(setOf("小明", "项目A"), added.toSet())
        // 同名合并观察
        store.upsertGraphEntities(listOf(GraphEntity("小明", "person", listOf("喜欢咖啡"))))
        val (_, relations) = store.readGraph()
        assertEquals(1, store.addGraphRelations(listOf(GraphRelation("小明", "项目A", "参与"))))
        assertEquals(0, store.addGraphRelations(listOf(GraphRelation("小明", "项目A", "参与"))))  // 三元组去重
        assertTrue(store.addObservations("小明", listOf("养猫")))
        assertFalse(store.addObservations("不存在", listOf("x")))
        assertEquals(1, store.deleteObservations("小明", listOf("养猫")))
        val hit = store.searchGraph("小明")
        assertEquals(1, hit.entities.size)
        assertTrue(store.deleteGraphEntities(listOf("小明")))
        val (after, rels) = store.readGraph()
        assertEquals(1, after.size)  // 级联删边
        assertEquals(0, rels.size)
    }

    @Test
    fun `隔离 TTL 过期放行`() = runTest {
        store.quarantineFingerprint("fp1", "test")
        assertEquals(1, store.quarantineCount())
        assertEquals(0, store.expireQuarantine(100))
        clock.advance(1000)
        assertEquals(1, store.expireQuarantine(100))
        assertEquals(0, store.quarantineCount())
    }

    @Test
    fun `容量治理按能量淘汰`() = runTest {
        val smallLimits = config.copy(
            limits = MemKitConfig.Limits(maxEpisodes = 2, maxNodes = 3, maxMacros = 2),
        )
        val s = MemoryStore(smallLimits, InMemoryStorageBackend(), clock, SilentLogger)
        s.addEpisode(EpisodeRecord("e1", "g", startedAt = 1))
        s.addEpisode(EpisodeRecord("e2", "g", startedAt = 2))
        s.addEpisode(EpisodeRecord("e3", "g", startedAt = 3))
        s.upsertNodes(listOf(node("n1"), node("n2"), node("n3"), node("n4"), node("n5")), null, null)
        s.saveMacro(macro("m1"))
        s.saveMacro(macro("m2"))
        s.saveMacro(macro("m3"))
        val (eps, nodes, _) = s.enforceLimits()
        assertEquals(1, eps)
        assertEquals(2, nodes)
        assertEquals(2, s.macroCount())
    }

    @Test
    fun `持久化往返（dirty 合并落盘 + 重载）`() = runTest {
        store.addEpisode(EpisodeRecord("ep1", "目标", status = EpisodeStatus.SUCCEEDED, startedAt = 1, actionCount = 3))
        store.upsertNodes(listOf(node("a"), node("b")), "com.app", "2.0")
        store.saveMacro(macro("m1"))
        store.addOrBumpFact("我喜欢咖啡", ChatFact.Kind.PROFILE, ChatFact.Source.HEURISTIC, 0.5f)
        store.upsertGraphEntities(listOf(GraphEntity("实体", "thing", listOf("obs"))))
        store.quarantineFingerprint("bad", "x")
        store.addAlias(FingerprintAlias("o", "n", null, 0.9f, null, null, 1))
        store.setMetaFlag("legacyImported", "done")

        val written = store.flush()
        assertTrue(written >= 7)

        // 新 store 实例重载同一后端
        val reloaded = MemoryStore(config, backend, TestClock(), SilentLogger)
        reloaded.load()
        assertNotNull(reloaded.episodeById("ep1"))
        assertEquals("com.app", reloaded.allNodes().single { it.fingerprint == "a" }.appPackage)
        assertEquals("m1", reloaded.macroById("m1")!!.skillId)
        assertEquals(1, reloaded.allFacts().size)
        assertEquals(1, reloaded.readGraph().first.size)
        assertEquals(1, reloaded.quarantineCount())
        assertEquals(1, reloaded.aliasCount())
        assertEquals("done", reloaded.metaFlag("legacyImported"))
    }

    @Test
    fun `坏文档不击穿加载`() = runTest {
        backend.write("episodes.json", "{broken json!!!")
        val reloaded = MemoryStore(config, backend, clock, SilentLogger)
        reloaded.load()  // 不抛
        assertEquals(0, reloaded.episodeCount())
    }
}
