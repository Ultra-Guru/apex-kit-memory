package com.apex.memkit.core.session

import com.apex.memkit.core.FakeCapture
import com.apex.memkit.core.MemKitConfig
import com.apex.memkit.core.SilentLogger
import com.apex.memkit.core.TestClock
import com.apex.memkit.core.ingest.IngestGuard
import com.apex.memkit.core.rawNode
import com.apex.memkit.core.store.InMemoryStorageBackend
import com.apex.memkit.core.store.MemoryStore
import com.apex.memkit.core.testCapture
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 会话指挥官：生命周期 / 上下文贯穿（P0 回归锁）/ 并发键控。 */
class SessionDirectorTest {

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

    private fun director(capture: FakeCapture) =
        SessionDirector(store, config, IngestGuard(config), capture, clock, logger)

    @Test
    fun `生命周期 - 成功任务蒸馏宏并回填情景`() = runTest {
        // 屏A（4 节点）→ UiTap → 屏B（全新）→ InputText → 屏C（全新）
        val screenA = testCapture(
            listOf(
                rawNode(text = "输入框", resId = "et_input", bounds = "[0,0][500,100]", clickable = true),
                rawNode(text = "打印按钮", resId = "btn_print", bounds = "[0,200][500,300]", clickable = true),
                rawNode(text = "说明文字", bounds = "[0,400][500,450]"),
                rawNode(className = "android.view.ViewGroup", resId = "root", bounds = "[0,0][1080,2400]"),
            ),
        )
        val screenB = testCapture(
            listOf(
                rawNode(text = "确认", resId = "btn_ok", bounds = "[0,0][500,100]", clickable = true),
                rawNode(text = "取消", resId = "btn_cancel", bounds = "[0,200][500,300]", clickable = true),
                rawNode(text = "标题", bounds = "[0,400][500,450]"),
                rawNode(className = "android.view.ViewGroup", resId = "root", bounds = "[0,0][1080,2400]"),
            ),
        )
        val screenC = testCapture(
            listOf(
                rawNode(text = "完成", resId = "tv_done", bounds = "[0,0][500,100]"),
                rawNode(text = "结果", resId = "tv_result", bounds = "[0,200][500,300]"),
                rawNode(text = "分享", resId = "btn_share", bounds = "[0,400][500,500]", clickable = true),
                rawNode(className = "android.view.ViewGroup", resId = "root", bounds = "[0,0][1080,2400]"),
            ),
        )
        val capture = FakeCapture(screenA, screenB, screenC)
        val director = director(capture)

        director.begin("task-1", "打印 HELLO", appPackage = null)
        director.recordAction("task-1", "UiTap(540,1200)", success = true)
        director.recordAction("task-1", "InputText(\"HELLO\")", success = true)
        val end = director.end("task-1", success = true)

        // P0-1 回归锁：情景与宏的 appPackage 来自捕获端口（非 null）
        val episode = store.episodeById(end.episodeId)!!
        assertEquals("com.example.app", episode.appPackage)
        assertEquals(2, episode.actionCount)
        assertEquals(1, episode.macroIds.size)

        val macro = store.macroById(episode.macroIds[0])!!
        assertEquals("com.example.app", macro.appPackage)
        assertEquals(2, macro.steps.size)
        // 节点字典带包名（P0-3 回归锁）
        assertTrue(store.allNodes().all { it.appPackage == "com.example.app" })
    }

    @Test
    fun `失败任务不蒸馏`() = runTest {
        val a = testCapture(listOf(rawNode(text = "A", bounds = "[0,0][10,10]", clickable = true)))
        val b = testCapture(listOf(rawNode(text = "B", bounds = "[0,0][10,10]", clickable = true)))
        val director = director(FakeCapture(a, b))
        director.begin("t", "目标", null)
        director.recordAction("t", "UiTap(1,1)", success = true)
        val end = director.end("t", success = false)
        assertEquals(0, end.macroSkillIds.size)
        val episode = store.episodeById(end.episodeId)!!
        assertEquals(com.apex.memkit.core.model.EpisodeStatus.FAILED, episode.status)
        assertEquals(1, episode.actionCount)
    }

    @Test
    fun `并发会话键控互不践踏`() = runTest {
        val a = testCapture(listOf(rawNode(text = "A", bounds = "[0,0][10,10]", clickable = true)))
        val director = director(FakeCapture(a))
        director.begin("task-A", "目标A", null)
        director.begin("task-B", "目标B", null)
        assertEquals(2, director.activeSessionCount())
        director.recordAction("task-A", "UiTap(1,1)", true)
        val endA = director.end("task-A", true)
        val endB = director.end("task-B", true)
        // 两个情景各自独立统计
        assertEquals(1, store.episodeById(endA.episodeId)!!.actionCount)
        assertEquals(0, store.episodeById(endB.episodeId)!!.actionCount)
        assertEquals("目标A", store.episodeById(endA.episodeId)!!.goal)
        assertEquals("目标B", store.episodeById(endB.episodeId)!!.goal)
        assertEquals(0, director.activeSessionCount())
    }

    @Test
    fun `捕获端口缺席时静默降级`() = runTest {
        val director = SessionDirector(store, config, IngestGuard(config), null, clock, logger)
        director.begin("t", "目标", null)
        director.recordAction("t", "UiTap(1,1)", true)
        val end = director.end("t", true)
        // 情景仍建档（无观测）但不蒸馏（无轨迹）
        val episode = store.episodeById(end.episodeId)
        assertNotNull(episode)
        assertEquals(0, end.macroSkillIds.size)
    }

    @Test
    fun `手工笔记入独立 MANUAL 情景并可检索`() = runTest {
        val director = SessionDirector(store, config, IngestGuard(config), null, clock, logger)
        val episodeId = director.ingestManualNotes("关于项目", "项目使用 Kotlin 2.2 构建。\n团队采用周发布节奏。")
        assertTrue(episodeId.startsWith("manual_"))
        val hits = store.searchNodes("Kotlin", 10)
        assertEquals(1, hits.size)
        assertEquals("项目使用 Kotlin 2.2 构建。", hits[0].text)
        val summary = store.recentEpisodes(1).single()
        assertTrue(summary.manual)
    }
}
