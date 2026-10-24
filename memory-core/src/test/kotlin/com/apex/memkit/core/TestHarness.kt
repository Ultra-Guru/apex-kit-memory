package com.apex.memkit.core

import com.apex.memkit.core.model.Bounds
import com.apex.memkit.core.model.ChatFact
import com.apex.memkit.core.model.ChatTurn
import com.apex.memkit.core.model.MacroAction
import com.apex.memkit.core.model.Scene
import java.io.File
import kotlinx.coroutines.CoroutineScope

/** 测试脚手架：假时钟 / 假捕获 / 假执行器 / 假模型。 */

class TestClock(var nowMs: Long = 1_700_000_000_000L) : KitClock {
    override fun now(): Long = nowMs
    fun advance(ms: Long) {
        nowMs += ms
    }
}

/** 脚本化捕获端口：按序吐出预置捕获，耗尽后重复最后一个（或返回 null）。 */
class FakeCapture(
    vararg val captures: RawCapture?,
    private val repeatLast: Boolean = true,
) : SceneCapture {
    private var index = 0

    override suspend fun capture(): RawCapture? {
        if (captures.isEmpty()) return null
        val current = captures[index.coerceAtMost(captures.lastIndex)]
        if (index < captures.lastIndex) index++
        else if (!repeatLast) return null
        return current
    }

    /** 直接喂下一帧（运行中改脚本）。 */
    fun feed(capture: RawCapture?) {
        script.add(capture)
    }

    private val script = captures.toMutableList()
}

/** 记账执行器：记录收到的动作，可脚本化每步成败。 */
class FakePerformer(
    private val succeedAll: Boolean = true,
) : ActionPerformer {
    val performed = mutableListOf<MacroAction>()
    override suspend fun perform(action: MacroAction): Boolean {
        performed.add(action)
        return succeedAll
    }
}

/** 脚本化 LLM 端口：固定响应队列。 */
class FakeModel(vararg responses: String?) : ChatModel {
    val calls = mutableListOf<Pair<String, String>>()
    private val queue = ArrayDeque(responses.toList())

    override suspend fun complete(system: String, user: String): String? {
        calls += system to user
        return if (queue.isEmpty()) null else queue.removeFirst()
    }
}

/** 假节点树构造 DSL（典型 Android 屏面）。 */
fun rawNode(
    className: String = "android.widget.TextView",
    text: String = "",
    desc: String = "",
    resId: String = "",
    bounds: String = "[0,0][100,100]",
    clickable: Boolean = false,
    scrollable: Boolean = false,
    children: List<RawNode> = emptyList(),
) = RawNode(className, text, desc, resId, bounds, clickable, scrollable, children)

fun testCapture(roots: List<RawNode>, pkg: String? = "com.example.app", version: String? = "1.0") =
    RawCapture(roots = roots, appPackage = pkg, appVersion = version)

/** 临时目录存储后端（@After 清理由调用方负责）。 */
fun tempBackend(dir: File) = com.apex.memkit.core.store.FileStorageBackend(dir)

/** 测试用事实。 */
fun fact(
    content: String,
    kind: ChatFact.Kind = ChatFact.Kind.PROFILE,
    source: ChatFact.Source = ChatFact.Source.HEURISTIC,
    createdAt: Long = 0,
) = ChatFact(
    id = "${kind.name}:$content",
    content = content,
    kind = kind,
    source = source,
    importance = 0.5f,
    createdAt = createdAt,
)

fun turn(user: String, assistant: String = "ok", at: Long = 0) = ChatTurn(user, assistant, at)

/** 测试作用域统一入口（runTest 内用 backgroundScope 语义此处简化为独立 scope）。 */
val testScope: CoroutineScope get() = CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined)
