package com.apex.memkit.core.facade

import com.apex.memkit.core.FakeCapture
import com.apex.memkit.core.FakePerformer
import com.apex.memkit.core.MemKit
import com.apex.memkit.core.MemKitConfig
import com.apex.memkit.core.SilentLogger
import com.apex.memkit.core.TestClock
import com.apex.memkit.core.model.MacroAction
import com.apex.memkit.core.model.MacroSkill
import com.apex.memkit.core.model.MacroStep
import com.apex.memkit.core.rawNode
import com.apex.memkit.core.store.InMemoryStorageBackend
import com.apex.memkit.core.testCapture
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** MemKit 门面：tryBypass 端到端 / 管道 / 查询面。 */
class MemKitFacadeTest {

    private lateinit var backend: InMemoryStorageBackend
    private lateinit var clock: TestClock
    private val config = MemKitConfig()

    @Before
    fun setup() {
        backend = InMemoryStorageBackend()
        clock = TestClock()
    }

    @Test
    fun `无捕获端口 tryBypass 返回 NotAvailable`() = runTest {
        val kit = MemKit.create(config, backend, logger = SilentLogger, clock = clock)
        assertTrue(kit.tryBypass() is MemKit.BypassResult.NotAvailable)
        kit.close()
    }

    @Test
    fun `无宏时 NotMatched`() = runTest {
        val scene = testCapture(listOf(rawNode(text = "A", bounds = "[0,0][10,10]", clickable = true)))
        val kit = MemKit.create(
            config, backend,
            capture = FakeCapture(scene), performer = FakePerformer(),
            logger = SilentLogger, clock = clock,
        )
        assertTrue(kit.tryBypass() is MemKit.BypassResult.NotMatched)
        kit.close()
    }

    @Test
    fun `宏命中 - 端到端回放与记账`() = runTest {
        // 目标屏：与宏入口一致（经同一条降维路径）
        val entryScreen = testCapture(
            listOf(
                rawNode(text = "输入框", resId = "et", bounds = "[0,0][500,100]", clickable = true),
                rawNode(text = "打印按钮", resId = "btn", bounds = "[0,200][500,300]", clickable = true),
                rawNode(className = "android.view.ViewGroup", resId = "root", bounds = "[0,0][1080,2400]"),
            ),
        )
        val afterScreen = testCapture(
            listOf(
                rawNode(text = "完成页", resId = "tv_done", bounds = "[0,0][500,100]"),
                rawNode(className = "android.view.ViewGroup", resId = "root", bounds = "[0,0][1080,2400]"),
            ),
        )
        val performer = FakePerformer()

        // 用同一降维器算入口/步骤锚点指纹（宿主等价路径：蒸馏器产宏入库）
        val entryScene = com.apex.memkit.core.ingest.SceneReducer.reduce(entryScreen, 1L)!!
        val afterScene = com.apex.memkit.core.ingest.SceneReducer.reduce(afterScreen, 2L)!!
        val macro = MacroSkill(
            skillId = "m_test", name = "打印", goal = "打印",
            appPackage = "com.example.app", appVersion = "1.0",
            entryFingerprints = entryScene.fingerprints(),
            exitFingerprint = afterScene.fingerprints().first(),
            steps = listOf(
                MacroStep(MacroAction.Tap(540, 50), entryScene.fingerprints(), afterScene.fingerprints()),
            ),
            stats = com.apex.memkit.core.model.MacroStats(successCount = 3, energy = 3f),
            sourceEpisodeId = "ep",
            createdAt = 1L,
        )

        val kit = MemKit.create(
            config, backend,
            capture = FakeCapture(entryScreen, afterScreen, afterScreen),
            performer = performer,
            logger = SilentLogger, clock = clock,
        )
        kit.store.saveMacro(macro)

        val result = kit.tryBypass()
        assertTrue("实际结果: $result", result is MemKit.BypassResult.Executed)
        assertEquals(1, performer.performed.size)
        // 记账：成功次数 +1
        val updated = kit.macroById("m_test")!!
        assertEquals(4, updated.stats.successCount)

        kit.close()
    }

    @Test
    fun `查询面与维护入口`() = runTest {
        val kit = MemKit.create(config, backend, logger = SilentLogger, clock = clock)
        kit.ingestManualNotes("主题", "第一条笔记内容")
        assertEquals(1, kit.recentEpisodes(10).size)
        assertEquals(1, kit.searchNodes("笔记", 10).size)
        assertEquals(1, kit.counts().episodes)
        val report = kit.runMaintenanceNow()
        assertTrue(report.errors.isEmpty())
        kit.close()
    }

    @Test
    fun `close 后可重启管道`() = runTest {
        val kit = MemKit.create(config, backend, logger = SilentLogger, clock = clock)
        kit.start()
        kit.requestFlush()
        kit.close()
        kit.start()  // 可重启（旧 MemoryWriterActor stop 后无法重启的潜伏 bug 不复存在）
        kit.close()
    }
}
