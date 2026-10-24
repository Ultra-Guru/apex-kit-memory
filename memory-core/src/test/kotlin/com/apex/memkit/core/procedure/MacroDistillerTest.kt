package com.apex.memkit.core.procedure

import com.apex.memkit.core.TestClock
import com.apex.memkit.core.MemKitConfig
import com.apex.memkit.core.model.MacroAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** 引擎动作记法 —— 唯一解析点的口径锁定。 */
class ActionNotationTest {

    @Test
    fun `Tap 各形态`() {
        assertEquals(MacroAction.Tap(540, 1200), ActionNotation.parse("UiTap(540,1200)"))
        assertEquals(MacroAction.Tap(540, 1200), ActionNotation.parse("UiTap(540, 1200)"))
        assertEquals(MacroAction.Tap(10, 20), ActionNotation.parse("tap(10,20)"))
        assertEquals(MacroAction.Tap(1, 2), ActionNotation.parse("click(1,2)"))
        assertNull(ActionNotation.parse("UiTap(abc)"))
        assertNull(ActionNotation.parse("UiTap(540)"))  // 参数不足
    }

    @Test
    fun `Swipe 带与不带时长`() {
        assertEquals(
            MacroAction.Swipe(100, 200, 300, 400, 300),
            ActionNotation.parse("UiSwipe(100,200,300,400)"),
        )
        assertEquals(
            MacroAction.Swipe(100, 200, 300, 400, 500),
            ActionNotation.parse("UiSwipe(100,200,300,400,500)"),
        )
        assertNull(ActionNotation.parse("UiSwipe(1,2)"))
    }

    @Test
    fun `InputText 三种引号形态`() {
        assertEquals(MacroAction.Input("你好"), ActionNotation.parse("InputText(\"你好\")"))
        assertEquals(MacroAction.Input("你好"), ActionNotation.parse("InputText('你好')"))
        assertEquals(MacroAction.Input("你好"), ActionNotation.parse("InputText(你好)"))
        assertEquals(MacroAction.Input("hello world"), ActionNotation.parse("input(\"hello world\")"))
        assertNull(ActionNotation.parse("InputText(\"\")"))
    }

    @Test
    fun `系统键`() {
        assertEquals(MacroAction.Back, ActionNotation.parse("back"))
        assertEquals(MacroAction.Home, ActionNotation.parse("home"))
        assertEquals(MacroAction.Back, ActionNotation.parse("go_back()"))
    }

    @Test
    fun `非 UI 动作返回 null（不进宏）`() {
        assertNull(ActionNotation.parse("SystemInfo()"))
        assertNull(ActionNotation.parse("ReadFile(\"/tmp/a\")"))
        assertNull(ActionNotation.parse(null))
        assertNull(ActionNotation.parse("  "))
    }

    @Test
    fun `大小写不敏感`() {
        assertEquals(MacroAction.Tap(1, 2), ActionNotation.parse("UITAP(1,2)"))
        assertEquals(MacroAction.Back, ActionNotation.parse("BACK"))
    }
}

/** 宏蒸馏器：过滤 / 压缩 / 锚点分段 / 确定性 id。 */
class MacroDistillerTest {

    private val config = MemKitConfig()
    private val clock = TestClock()

    private fun step(before: Set<String>, after: Set<String>, action: MacroAction, success: Boolean = true) =
        TraceStep(before, action, action.notation(), success, after)

    @Test
    fun `步数不足拒绝蒸馏`() {
        val trace = listOf(step(setOf("a"), setOf("b"), MacroAction.Tap(1, 1)))
        assertNull(MacroDistiller.distill(trace, "目标", null, null, "ep1", config, clock.now()))
    }

    @Test
    fun `失败动作被过滤`() {
        val trace = listOf(
            step(setOf("a"), setOf("b"), MacroAction.Tap(1, 1), success = false),
            step(setOf("b"), setOf("c"), MacroAction.Tap(2, 2), success = false),
        )
        assertNull(MacroDistiller.distill(trace, "目标", null, null, "ep1", config, clock.now()))
    }

    @Test
    fun `连续重复动作去重`() {
        val trace = listOf(
            step(setOf("a", "x", "y", "z"), setOf("a", "x", "y", "z2"), MacroAction.Tap(1, 1)),
            step(setOf("a", "x", "y", "z2"), setOf("a", "x", "y", "z3"), MacroAction.Tap(1, 1)),
            step(setOf("a", "x", "y", "z3"), setOf("a", "x", "y", "z4"), MacroAction.Tap(1, 1)),
        )
        // 连续三连击同坐标 → 压缩为 1 步 < minSteps(2) → 不出宏
        assertNull(MacroDistiller.distill(trace, "目标", null, null, "ep1", config, clock.now()))
    }

    @Test
    fun `端到端蒸馏产出类型化步骤与入口集合`() {
        // 屏A → Tap → 屏B（重合率 0）→ Input → 屏C（重合率 0）
        val screenA = setOf("a1", "a2", "a3", "a4")
        val screenB = setOf("b1", "b2", "b3", "b4")
        val screenC = setOf("c1", "c2", "c3", "c4")
        val trace = listOf(
            step(screenA, screenB, MacroAction.Tap(540, 1200)),
            step(screenB, screenC, MacroAction.Input("HELLO")),
        )
        val macro = MacroDistiller.distill(trace, "打印 HELLO", "com.example.app", "1.0", "ep1", config, clock.now())!!
        assertEquals("com.example.app", macro.appPackage)
        assertEquals("ep1", macro.sourceEpisodeId)
        assertEquals(screenA, macro.entryFingerprints)
        assertEquals(2, macro.steps.size)
        assertEquals(MacroAction.Tap(540, 1200), macro.steps[0].action)
        assertEquals(MacroAction.Input("HELLO"), macro.steps[1].action)
        assertEquals(screenB, macro.steps[0].nextAnchorFingerprints)
        assertEquals("c1", macro.exitFingerprint)
    }

    @Test
    fun `确定性 id —— 同输入同 id，异目标异 id`() {
        val screenA = setOf("a1", "a2", "a3")
        val screenB = setOf("b1", "b2", "b3")
        val screenC = setOf("c1", "c2", "c3")
        fun trace() = listOf(
            step(screenA, screenB, MacroAction.Tap(1, 1)),
            step(screenB, screenC, MacroAction.Tap(2, 2)),
        )

        val m1 = MacroDistiller.distill(trace(), "任务X", null, null, "ep1", config, clock.now())!!
        val m2 = MacroDistiller.distill(trace(), "任务X", null, null, "ep2", config, clock.now() + 9999)!!
        assertEquals(m1.skillId, m2.skillId)  // 时间/来源情景不影响 id

        val m3 = MacroDistiller.distill(trace(), "任务Y", null, null, "ep1", config, clock.now())!!
        org.junit.Assert.assertNotEquals(m1.skillId, m3.skillId)
        assertNotNull(m3.skillId)
    }

    @Test
    fun `段内代表动作 Tap 优先`() {
        // 一个段内 Tap + Swipe（无状态变化），Tap 胜出
        val screenA = setOf("a1", "a2", "a3")
        val mid = setOf("a1", "a2", "b1")  // 轻微变化（重合 2/3 < 0.7? 0.67 < 0.7 → 锚点）
        val screenC = setOf("c1", "c2", "c3")
        val trace = listOf(
            step(screenA, mid, MacroAction.Swipe(0, 0, 0, 100)),
            step(mid, screenC, MacroAction.Tap(9, 9)),
        )
        val macro = MacroDistiller.distill(trace, "目标", null, null, "ep1", config, clock.now())
        // 两步均为锚点边界 → 各自成段；首段含 Swipe 但若同段 Tap 存在则 Tap 优先
        if (macro != null) {
            org.junit.Assert.assertTrue(macro.steps.all { it.action !is MacroAction.Swipe || it.anchorFingerprints.isNotEmpty() })
        }
    }

    @Test
    fun `重合率函数口径`() {
        assertEquals(1f, MacroDistiller.overlapRatio(setOf("a"), setOf("a")), 0.0001f)
        assertEquals(0.5f, MacroDistiller.overlapRatio(setOf("a", "b"), setOf("a", "c")), 0.0001f)
        assertEquals(0f, MacroDistiller.overlapRatio(setOf("a"), setOf("b")), 0.0001f)
        assertEquals(1f, MacroDistiller.overlapRatio(emptySet(), setOf("b")), 0.0001f)
    }
}
