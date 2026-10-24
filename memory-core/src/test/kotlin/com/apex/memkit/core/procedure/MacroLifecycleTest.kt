package com.apex.memkit.core.procedure

import com.apex.memkit.core.MemKitConfig
import com.apex.memkit.core.SilentLogger
import com.apex.memkit.core.TestClock
import com.apex.memkit.core.model.FingerprintAlias
import com.apex.memkit.core.model.MacroAction
import com.apex.memkit.core.model.MacroSkill
import com.apex.memkit.core.model.MacroStats
import com.apex.memkit.core.model.MacroStep
import com.apex.memkit.core.model.SceneNode
import com.apex.memkit.core.store.InMemoryStorageBackend
import com.apex.memkit.core.store.MemoryStore
import com.apex.memkit.core.store.NodeDictEntry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 匹配 + 回放 + 维护：程序性记忆的闭环。 */
class MacroLifecycleTest {

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

    private fun macro(
        id: String,
        pkg: String? = "com.app",
        entry: Set<String> = setOf("e1"),
        steps: List<MacroStep> = listOf(MacroStep(MacroAction.Tap(1, 1), setOf("e1"), setOf("e2"))),
        stats: MacroStats = MacroStats(successCount = 5, energy = 5f),
    ) = MacroSkill(
        skillId = id, name = id, goal = "goal", appPackage = pkg,
        entryFingerprints = entry, exitFingerprint = "exit",
        steps = steps, stats = stats, sourceEpisodeId = "ep", createdAt = 1L,
    )

    @Test
    fun `集合覆盖率匹配 + appPackage 精确一致（P0-1 回归锁）`() = runTest {
        val entry = setOf("e1", "e2", "e3", "e4", "e5")
        store.saveMacro(macro("m1", pkg = "com.app", entry = entry))
        val matcher = MacroMatcher(store, config, logger)

        // 包名不一致 → 不命中（采集 null vs 匹配 com.app 的旧断链形态）
        assertNull(matcher.findBest(entry, "com.other"))
        // 包名一致 + 全覆盖 → 命中
        assertNotNull(matcher.findBest(entry, "com.app"))
        // 覆盖率 3/5 = 0.6 < 0.8 阈值 → 不命中
        assertNull(matcher.findBest(setOf("e1", "e2", "e3", "x", "y"), "com.app"))
        // 覆盖率 4/5 = 0.8 恰好达标 → 命中（屏面轻微抖动容忍）
        assertNotNull(matcher.findBest(setOf("e1", "e2", "e3", "e4", "z"), "com.app"))
        // 双方都未知包名 → 命中（null == null 一致）
        store.saveMacro(macro("m2", pkg = null, entry = entry))
        assertNotNull(matcher.findBest(entry, null))
    }

    @Test
    fun `别名桥匹配（正向校验闭环）`() = runTest {
        val entry = setOf("old1", "old2")
        store.saveMacro(macro("m1", entry = entry))
        // 升级后旧指纹漂移到新指纹
        store.addAlias(FingerprintAlias("old1", "new1", "com.app", 0.9f, "1.0", "2.0", 1))
        store.addAlias(FingerprintAlias("old2", "new2", "com.app", 0.9f, "1.0", "2.0", 1))
        val matcher = MacroMatcher(store, config, logger)
        // 当前屏只有新指纹 → 经别名命中
        val hit = matcher.findBest(setOf("new1", "new2"), "com.app")
        assertNotNull(hit)
        assertTrue(hit!!.viaAlias)
        // 新指纹没在当前屏出现 → 别名不算命中（不得用旧指纹复验）
        assertNull(matcher.findBest(setOf("unrelated"), "com.app"))
    }

    @Test
    fun `逐步验证回放：正常与偏离`() = runTest {
        val performer = RecordingPerformer()
        val runner = MacroRunner(config, performer, clock, logger)
        val step1 = MacroStep(MacroAction.Tap(1, 1), setOf("a"), setOf("b1", "b2"))
        val step2 = MacroStep(MacroAction.Input("hi"), setOf("b1", "b2"), setOf("c1"))
        val m = macro("m", entry = setOf("a"), steps = listOf(step1, step2))

        // 正常回放：每步后屏面覆盖下一锚点
        var probeCount = 0
        val screens = listOf(setOf("b1", "b2"), setOf("c1"))
        val ok = runner.run(m) { screens.getOrNull(probeCount++).also { } ?: screens.last() }
        assertTrue(ok is MacroRunner.Result.Executed)
        assertEquals(2, performer.performed.size)

        // 偏离：第一步后屏面完全跑偏 → 熔断
        val bad = runner.run(m) { setOf("ad", "popup") }
        assertTrue(bad is MacroRunner.Result.Failed)
        // 熔断发生在第一步后 → 只执行了 1 个动作
        assertEquals(1 + 2, performer.performed.size)  // 累计：2（正常）+1（偏离首步）
    }

    @Test
    fun `维护周期：衰减-剪枝-晶化-保鲜-记账`() = runTest {
        val maintenance = MacroMaintenance(store, config, clock, logger)
        // 低能宏 → 剪枝
        store.saveMacro(macro("low", stats = MacroStats(successCount = 1, energy = 0.01f)))
        // 达标宏 → 晶化（能量 9 / 成功 12 / 成功率 1.0）
        store.saveMacro(
            macro("star", stats = MacroStats(successCount = 12, failureCount = 0, energy = 9f)),
        )
        // 普通宏 → 衰减
        store.saveMacro(macro("normal", stats = MacroStats(successCount = 3, energy = 4f)))

        val report = maintenance.runCycle()
        assertEquals(1, report.crystallizedMacros)
        assertTrue(report.prunedMacros >= 1)
        assertTrue(report.decayedMacros >= 1)
        assertNull(store.macroById("low"))
        assertTrue(store.macroById("star")!!.stats.crystallized)
        assertTrue(store.macroById("normal")!!.stats.energy < 4f)

        // 记账：成功 +0.5 封顶 10
        val before = store.macroById("normal")!!
        val updated = maintenance.recordRun(before, success = true)
        assertEquals(before.stats.successCount + 1, updated.stats.successCount)
        assertEquals(before.stats.energy + 0.5f, updated.stats.energy, 0.001f)
    }

    @Test
    fun `别名发现：版本队列漂移场景`() = runTest {
        // 旧版本节点（宏入口引用）与新版本节点属性相似
        val oldNode = NodeDictEntry(
            fingerprint = "old1", role = com.apex.memkit.core.model.NodeRole.BUTTON,
            text = "确认订单", resourceId = "com.app:id/btn_confirm", className = "android.widget.Button",
            appPackage = "com.app", appVersion = "1.0", firstSeenAt = 1, lastSeenAt = 10,
        )
        val newNode = oldNode.copy(
            fingerprint = "new1", text = "确认订单（新版）", resourceId = "com.app:id/btn_confirm",
            appVersion = "2.0", lastSeenAt = 100, firstSeenAt = 90,
        )
        store.upsertNodes(listOf(oldNode.toSceneNode()), "com.app", "1.0")
        store.upsertNodes(listOf(newNode.toSceneNode()), "com.app", "2.0")
        store.saveMacro(macro("m1", entry = setOf("old1"), steps = listOf(MacroStep(MacroAction.Tap(1, 1), setOf("old1"), setOf("x")))))

        val maintenance = MacroMaintenance(store, config, clock, logger)
        val report = maintenance.runCycle()
        assertEquals(1, report.newAliases)
        val alias = store.aliasesFrom("old1").single()
        assertEquals("new1", alias.newFingerprint)
        assertTrue(alias.score >= 0.7f)
    }

    private fun NodeDictEntry.toSceneNode() = SceneNode(
        fingerprint = fingerprint, role = role, text = text, resourceId = resourceId,
        className = className, bounds = com.apex.memkit.core.model.Bounds(0, 0, 1, 1),
        interactive = true,
    )

    private class RecordingPerformer : com.apex.memkit.core.ActionPerformer {
        val performed = mutableListOf<MacroAction>()
        override suspend fun perform(action: MacroAction): Boolean {
            performed.add(action)
            return true
        }
    }
}
