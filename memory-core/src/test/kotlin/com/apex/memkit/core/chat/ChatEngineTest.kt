package com.apex.memkit.core.chat

import com.apex.memkit.core.MemKitConfig
import com.apex.memkit.core.SilentLogger
import com.apex.memkit.core.TestClock
import com.apex.memkit.core.FakeModel
import com.apex.memkit.core.store.InMemoryStorageBackend
import com.apex.memkit.core.store.MemoryStore
import com.apex.memkit.core.store.addOrBumpFact
import com.apex.memkit.core.store.allFacts
import com.apex.memkit.core.store.pendingTurnCount
import com.apex.memkit.core.store.replaceAllFacts
import com.apex.memkit.core.store.replaceStateFact
import com.apex.memkit.core.store.readGraph
import com.apex.memkit.core.store.upsertGraphEntities
import com.apex.memkit.core.model.ChatFact
import com.apex.memkit.core.model.GraphEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 聊天记忆引擎：双速捕获 / 蒸馏协议 / 召回渲染。 */
class ChatEngineTest {

    private lateinit var store: MemoryStore
    private lateinit var config: MemKitConfig
    private lateinit var clock: TestClock
    private val logger = SilentLogger

    @Before
    fun setup() {
        config = MemKitConfig()
        clock = TestClock()
        store = MemoryStore(config, InMemoryStorageBackend(), clock, logger)
    }

    private fun engine(model: FakeModel?): ChatEngine {
        val distiller = ChatDistiller(store, config, clock, logger)
        val recaller = ChatRecall(store, config, clock, logger)
        val signals = ChatSignals(config)
        return ChatEngine(store, config, clock, logger, signals, distiller, recaller, null, model, autoPump = false)
    }

    @Test
    fun `启发式自我披露入账 PROFILE`() = runTest {
        val e = engine(null)
        e.onTurn("我叫小明，我在学吉他。帮我查个天气。", "好的")
        val profile = store.allFacts().filter { it.kind == ChatFact.Kind.PROFILE }
        assertTrue(profile.any { it.content.contains("小明") })
        assertTrue(profile.any { it.content.contains("吉他") })
        // 任务指令不入账
        assertTrue(store.allFacts().none { it.content.contains("天气") })
    }

    @Test
    fun `里程碑双命中与创作护栏`() = runTest {
        val e = engine(null)
        e.onTurn("我下周三有面试，帮我写一段面试自我介绍的文案", "好的")
        // 「帮我写」护栏：创作素材句不入账；但用户真实里程碑（面试）正当入账
        val afterFirst = store.allFacts().filter { it.kind == ChatFact.Kind.MILESTONE }
        assertEquals(1, afterFirst.size)
        assertTrue(afterFirst[0].content.contains("面试"))
        assertTrue(store.allFacts().none { it.content.contains("文案") })

        e.onTurn("提醒一下，10月1号是我生日", "生日快乐！")
        val milestones = store.allFacts().filter { it.kind == ChatFact.Kind.MILESTONE }
        assertEquals(2, milestones.size)  // 面试 + 生日
        assertTrue(milestones.any { it.content.contains("生日") })
    }

    @Test
    fun `情绪基调滚动窗 → 近况单条替换`() = runTest {
        val e = engine(null)
        e.onTurn("今天好累", "辛苦了")
        e.onTurn("心累，想哭", "抱抱")
        e.onTurn("难过，失望", "我理解")
        val state = store.allFacts().filter { it.kind == ChatFact.Kind.STATE }
        assertEquals(1, state.size)
        assertTrue(state[0].content.contains("低落"))
    }

    @Test
    fun `蒸馏协议 - skip 信号`() = runTest {
        val e = engine(FakeModel("""{"skip": true}"""))
        for (i in 1..6) e.onTurn("第 $i 轮任务对话", "回复")
        val outcome = e.pumpDistillation()!!
        assertTrue(outcome.skipped)
        assertEquals(0, store.allFacts().size)  // 无新增
        assertEquals(0, store.pendingTurnCount())  // 队列已消费
    }

    @Test
    fun `蒸馏协议 - new 落账`() = runTest {
        val e = engine(FakeModel("""{"new": ["用户是一名吉他爱好者"], "update": []}"""))
        for (i in 1..6) e.onTurn("我平时玩吉他", "不错")
        e.pumpDistillation()!!
        val profile = store.allFacts().filter { it.kind == ChatFact.Kind.PROFILE }
        assertTrue(profile.any { it.content == "用户是一名吉他爱好者" })
    }

    @Test
    fun `蒸馏协议 - update 逐字替换`() = runTest {
        // 预置一条旧画像
        store.addOrBumpFact("用户在学吉他", ChatFact.Kind.PROFILE, ChatFact.Source.HEURISTIC, 0.5f)
        val e = engine(FakeModel("""{"new": [], "update": [{"old": "用户在学吉他", "content": "用户已能熟练弹吉他"}]}"""))
        for (i in 1..6) e.onTurn("我最近练会了新曲子", "厉害")
        e.pumpDistillation()!!
        val contents = store.allFacts().map { it.content }
        assertTrue(contents.contains("用户已能熟练弹吉他"))
        assertTrue(contents.none { it == "用户在学吉他" })
    }

    @Test
    fun `蒸馏失败保留队列重试`() = runTest {
        val e = engine(FakeModel(null))  // 模型失败
        for (i in 1..6) e.onTurn("对话 $i", "回复")
        val outcome = e.pumpDistillation()
        assertNull(outcome)
        assertEquals(6, store.pendingTurnCount())
    }

    @Test
    fun `召回渲染与触达`() = runTest {
        clock.advance(86_400_000L * 30)
        store.addOrBumpFact("用户名叫小明", ChatFact.Kind.PROFILE, ChatFact.Source.HEURISTIC, 0.9f)
        store.addOrBumpFact("用户下周三面试", ChatFact.Kind.MILESTONE, ChatFact.Source.HEURISTIC, 0.8f)
        store.replaceStateFact("近期情绪基调：低落（2 轮）", clock.now())
        store.upsertGraphEntities(listOf(GraphEntity("吉他", "乐器", listOf("用户每周练习"))))

        val e = engine(null)
        val rendered = e.recall("帮我准备面试，对了用吉他放松")!!
        assertTrue(rendered.contains("### 用户画像"))
        assertTrue(rendered.contains("小明"))
        assertTrue(rendered.contains("### 用户近况"))
        assertTrue(rendered.contains("### 重要时刻"))
        assertTrue(rendered.contains("面试"))
        assertTrue(rendered.contains("### 相关记忆"))
        assertTrue(rendered.contains("[吉他]"))
        // 触达生效
        assertTrue(store.allFacts().any { it.accessCount > 0 && it.kind == ChatFact.Kind.PROFILE })
    }

    @Test
    fun `空账本召回返回 null`() = runTest {
        val e = engine(null)
        assertNull(e.recall("随便说点什么"))
    }

    @Test
    fun `近重复合并治理`() = runTest {
        val now = clock.now()
        val f1 = ChatFact("id1", "用户 喜欢喝 咖啡", ChatFact.Kind.PROFILE, ChatFact.Source.DISTILLED, 0.6f, createdAt = now)
        val f2 = ChatFact("id2", "用户 喜欢 喝 咖啡 加奶", ChatFact.Kind.PROFILE, ChatFact.Source.DISTILLED, 0.5f, createdAt = now + 1)
        store.replaceAllFacts(listOf(f1, f2))
        val (merged, _) = ChatConsolidator.consolidate(store, config, clock)
        // 词元 Jaccard = |{喜欢,喝,咖啡}| / |{用户,喜欢,喝,咖啡,加奶}| = 3/5 < 0.75 → 不合并
        assertEquals(0, merged)
        val (merged2, _) = ChatConsolidator.consolidate(store, config, clock)
        assertEquals(0, merged2)
    }


}
