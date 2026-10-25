package com.apex.memkit.core.facade

import com.apex.memkit.core.MemKit
import com.apex.memkit.core.MemKitConfig
import com.apex.memkit.core.SilentLogger
import com.apex.memkit.core.TestClock
import com.apex.memkit.core.model.ChatFact
import com.apex.memkit.core.model.ExperienceReport
import com.apex.memkit.core.model.GraphEntity
import com.apex.memkit.core.model.GraphRelation
import com.apex.memkit.core.store.InMemoryStorageBackend
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 经验整理面（任务后主动沉淀）的门面契约锁。
 *
 * 覆盖：摘要事实成形 / 教训幂等归并 / 实体 upsert / 关系悬空端补建 /
 * saveFact 与图谱写入面。断言值全部先读 ChatGraphOps 与 MemKit 源码核实。
 */
class ExperienceConsolidationTest {

    private lateinit var backend: InMemoryStorageBackend
    private lateinit var clock: TestClock

    @Before
    fun setup() {
        backend = InMemoryStorageBackend()
        clock = TestClock()
    }

    private suspend fun newKit(): MemKit {
        val kit = MemKit.create(
            MemKitConfig(), backend,
            logger = SilentLogger, clock = clock,
        )
        kit.start()
        return kit
    }

    private fun report(
        lessons: List<String> = listOf("权限弹窗出现时先等待 1 秒再点同意"),
        entities: List<GraphEntity> = listOf(
            GraphEntity(name = "微信", entityType = "app", observations = listOf("聊天应用")),
        ),
        relations: List<GraphRelation> = listOf(
            GraphRelation(from = "微信", to = "发送图片", relationType = "支持功能"),
        ),
        success: Boolean = true,
    ) = ExperienceReport(
        goal = "给张三发送图片",
        outcome = "经聊天窗口附件选择完成",
        success = success,
        lessons = lessons,
        entities = entities,
        relations = relations,
    )

    @Test
    fun `整理写入摘要事实与教训与图谱`() = runTest {
        val kit = newKit()
        val result = kit.consolidateExperience(report())

        // 摘要事实：任务「给张三发送图片」完成：…（MILESTONE，新增）
        assertTrue(result.summaryAdded)
        assertEquals(1, result.lessonsAdded)
        assertEquals(2, result.entitiesUpserted)   // 微信 + 发送图片（悬空端占位）
        assertEquals(1, result.relationsAdded)     // 微信-支持功能->发送图片
        assertTrue(result.totalWrites >= 4)

        val facts = kit.allFacts()
        assertTrue(facts.any { it.kind == ChatFact.Kind.MILESTONE && it.content.contains("给张三发送图片") && it.content.contains("完成") })
        assertTrue(facts.any { it.kind == ChatFact.Kind.PROFILE && it.content.contains("权限弹窗") })

        val (entities, relations) = kit.readGraph()
        assertTrue(entities.any { it.name == "微信" && it.entityType == "app" })
        assertTrue(relations.any { it.from == "微信" && it.to == "发送图片" && it.relationType == "支持功能" })
        kit.close()
    }

    @Test
    fun `同任务重复整理 - 事实幂等强化不重复堆积`() = runTest {
        val kit = newKit()
        kit.consolidateExperience(report())
        val second = kit.consolidateExperience(report())

        // 摘要与教训同内容 → 同 id → bumped 而非 added
        assertFalse(second.summaryAdded)
        assertEquals(0, second.lessonsAdded)
        assertEquals(1, second.lessonsBumped)
        // 事实总量不翻倍（1 摘要 + 1 教训）
        assertEquals(2, kit.allFacts().size)
        kit.close()
    }

    @Test
    fun `失败任务摘要走 STATE 且含未成标记`() = runTest {
        val kit = newKit()
        kit.consolidateExperience(report(lessons = emptyList(), entities = emptyList(), relations = emptyList(), success = false))
        val summary = kit.allFacts().single()
        assertEquals(ChatFact.Kind.STATE, summary.kind)
        assertTrue(summary.content.contains("未成"))
        kit.close()
    }

    @Test
    fun `关系悬空端自动补建占位实体`() = runTest {
        val kit = newKit()
        // 「发送图片」既不在图内也不在本次实体提交内 → 占位补建
        kit.consolidateExperience(
            report(entities = emptyList()),
        )
        val (entities, _) = kit.readGraph()
        assertEquals(2, entities.size)  // 微信（占位） + 发送图片（占位）
        assertTrue(entities.all { it.entityType == "placeholder" })
        kit.close()
    }

    @Test
    fun `空教训与空图谱只写摘要`() = runTest {
        val kit = newKit()
        val result = kit.consolidateExperience(
            ExperienceReport(goal = "查天气", outcome = "已完成查询", success = true),
        )
        assertTrue(result.summaryAdded)
        assertEquals(0, result.lessonsAdded)
        assertEquals(0, result.entitiesUpserted)
        assertEquals(0, result.relationsAdded)
        assertEquals(1, kit.allFacts().size)
        kit.close()
    }

    @Test
    fun `saveFact 幂等强化并落正确来源`() = runTest {
        val kit = newKit()
        val first = kit.saveFact("用户偏好夜间模式", ChatFact.Kind.PROFILE, 0.8f)
        val second = kit.saveFact("用户偏好夜间模式", ChatFact.Kind.PROFILE, 0.5f)
        assertTrue(first.added)
        assertTrue(second.bumped)
        val fact = kit.allFacts().single()
        assertEquals(ChatFact.Source.MANUAL, fact.source)
        assertEquals(0.8f, fact.importance, 0.001f)  // 幂等合并取 max
        kit.close()
    }

    @Test
    fun `图谱写入面 - upsert 合并观察与关系去重`() = runTest {
        val kit = newKit()
        kit.upsertGraphEntities(listOf(GraphEntity(name = "GitHub", entityType = "platform", observations = listOf("代码托管"))))
        // 同名实体再 upsert：观察合并不重复
        kit.upsertGraphEntities(listOf(GraphEntity(name = "GitHub", entityType = "platform", observations = listOf("代码托管", "支持 PR"))))

        val first = kit.addGraphRelations(listOf(GraphRelation("GitHub", "Android-Guru-Agent", "托管")))
        val second = kit.addGraphRelations(listOf(GraphRelation("GitHub", "Android-Guru-Agent", "托管")))
        assertEquals(1, first)
        assertEquals(0, second)

        val (entities, _) = kit.readGraph()
        val gh = entities.single()
        assertEquals(listOf("代码托管", "支持 PR"), gh.observations)

        // 追加观察到既有实体；不存在实体返回 false
        assertTrue(kit.addEntityObservations("GitHub", listOf("支持 Issues")))
        assertFalse(kit.addEntityObservations("不存在", listOf("x")))
        kit.close()
    }

    @Test
    fun `searchGraph 命中整理产物`() = runTest {
        val kit = newKit()
        kit.consolidateExperience(report())
        val hit = kit.searchGraph("微信")
        assertTrue(hit.entities.any { it.name == "微信" })
        kit.close()
    }
}
