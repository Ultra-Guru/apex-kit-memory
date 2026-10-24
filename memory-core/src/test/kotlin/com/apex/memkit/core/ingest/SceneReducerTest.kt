package com.apex.memkit.core.ingest

import com.apex.memkit.core.model.NodeRole
import com.apex.memkit.core.rawNode
import com.apex.memkit.core.testCapture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 语义降维器：三条修剪规则 + 角色分类 + 空间边。 */
class SceneReducerTest {

    @Test
    fun `不可见节点被剔除但子树继续遍历`() {
        val root = rawNode(
            className = "android.view.View", text = "", resId = "",
            bounds = "[0,0][0,0]",  // 零面积 → 不可见
            children = listOf(rawNode(text = "按钮", bounds = "[0,0][100,100]", clickable = true)),
        )
        val scene = SceneReducer.reduce(testCapture(listOf(root)), 1L)!!
        assertEquals(1, scene.nodes.size)
        assertEquals("按钮", scene.nodes[0].text)
    }

    @Test
    fun `纯装饰节点不产出但仍是哈希载体`() {
        fun tree(containerResId: String) = rawNode(
            className = "android.view.ViewGroup", text = "", resId = containerResId,
            bounds = "[0,0][1080,2400]",
            children = listOf(rawNode(text = "提交", bounds = "[0,0][200,100]", clickable = true)),
        )
        val a = SceneReducer.reduce(testCapture(listOf(tree("containerA"))), 1L)!!
        val b = SceneReducer.reduce(testCapture(listOf(tree("containerB"))), 1L)!!
        // 带 resourceId 的容器可寻址 → 保留（产出 = 容器 + 按钮）
        assertEquals(2, a.nodes.size)
        assertEquals(2, b.nodes.size)
        // 无 resId 的纯装饰层（若换成 text="" resId=""）不产出但作哈希载体：
        // 按钮指纹仍随父层 resId 变化而变化
        val buttonA = a.nodes.single { it.text == "提交" }
        val buttonB = b.nodes.single { it.text == "提交" }
        assertNotEquals(buttonA.fingerprint, buttonB.fingerprint)
    }

    @Test
    fun `角色分类按 className 关键字优先`() {
        val scene = SceneReducer.reduce(
            testCapture(
                listOf(
                    rawNode(className = "android.widget.EditText", bounds = "[0,0][100,50]", text = "输入"),
                    rawNode(className = "android.widget.Switch", bounds = "[0,0][100,50]", clickable = true),
                    rawNode(className = "androidx.recyclerview.widget.RecyclerView", bounds = "[0,0][100,50]", scrollable = true),
                    rawNode(className = "android.widget.TextView", text = "纯文本", bounds = "[0,0][100,50]"),
                    rawNode(className = "android.widget.ImageView", resId = "img", bounds = "[0,0][100,50]"),
                ),
            ),
            1L,
        )!!
        assertEquals(NodeRole.INPUT, scene.nodes[0].role)
        assertEquals(NodeRole.TOGGLE, scene.nodes[1].role)
        assertEquals(NodeRole.SCROLLABLE, scene.nodes[2].role)
        assertEquals(NodeRole.TEXT, scene.nodes[3].role)
        assertEquals(NodeRole.IMAGE, scene.nodes[4].role)
    }

    @Test
    fun `场景指纹跨帧稳定`() {
        val tree = listOf(
            rawNode(text = "A", bounds = "[0,0][100,100]", clickable = true),
            rawNode(text = "B", bounds = "[0,200][100,300]", clickable = true),
        )
        val a = SceneReducer.reduce(testCapture(tree), 1L)!!
        val b = SceneReducer.reduce(testCapture(tree), 2L)!!
        assertEquals(a.fingerprint, b.fingerprint)
    }

    @Test
    fun `空树返回 null`() {
        assertNull(SceneReducer.reduce(testCapture(emptyList()), 1L))
        assertNull(SceneReducer.reduce(testCapture(listOf(rawNode(bounds = "[0,0][0,0]"))), 1L))
    }

    @Test
    fun `几何包含产生 CONTAINS 边`() {
        val scene = SceneReducer.reduce(
            testCapture(
                listOf(
                    rawNode(className = "android.view.ViewGroup", resId = "root", bounds = "[0,0][1000,2000]"),
                    rawNode(text = "内嵌按钮", bounds = "[100,100][300,200]", clickable = true),
                ),
            ),
            1L,
        )!!
        assertNotNull(scene.edges.firstOrNull { it.kind == com.apex.memkit.core.model.EdgeKind.CONTAINS })
    }

    @Test
    fun `appPackage 与 appVersion 从捕获产物贯穿`() {
        val scene = SceneReducer.reduce(
            testCapture(listOf(rawNode(text = "X", bounds = "[0,0][10,10]")), pkg = "com.other.app", version = "9.9"),
            1L,
        )!!
        assertEquals("com.other.app", scene.appPackage)
        assertEquals("9.9", scene.appVersion)
    }

    @Test
    fun `兄弟邻接边稳定`() {
        fun row() = listOf(
            rawNode(text = "左", bounds = "[0,0][100,100]", clickable = true),
            rawNode(text = "右", bounds = "[120,0][220,100]", clickable = true),
        )
        val a = SceneReducer.reduce(testCapture(row()), 1L)!!
        val b = SceneReducer.reduce(testCapture(row()), 2L)!!
        assertEquals(a.edges.map { it.id }.toSet(), b.edges.map { it.id }.toSet())
        assertTrue(a.edges.any { it.kind == com.apex.memkit.core.model.EdgeKind.ADJACENT })
    }
}
