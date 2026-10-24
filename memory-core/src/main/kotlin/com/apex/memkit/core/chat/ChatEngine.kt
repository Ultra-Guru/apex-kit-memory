package com.apex.memkit.core.chat

import com.apex.memkit.core.ChatModel
import com.apex.memkit.core.KitClock
import com.apex.memkit.core.KitLogger
import com.apex.memkit.core.MemKitConfig
import com.apex.memkit.core.model.ChatEmotion
import com.apex.memkit.core.model.ChatTurn
import com.apex.memkit.core.store.MemoryStore
import com.apex.memkit.core.store.addOrBumpFact
import com.apex.memkit.core.store.enqueueTurn
import com.apex.memkit.core.store.pendingTurnCount
import com.apex.memkit.core.store.replaceStateFact
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.ArrayDeque

/**
 * 聊天记忆引擎 —— onTurn / recall 的编排层（原 ChatMemoryPipeline 的
 * 库化重制）。
 *
 * 双速捕获纪律完整继承：
 * - **同步零成本层**（当轮生效）：自我披露入账 PROFILE / 情绪基调滚动窗
 *   → 近况单条有界替换 / 日期×事件双命中入账 MILESTONE；
 * - **异步批量层**（攒批节拍）：对话轮入持久化队列，攒到
 *   [MemKitConfig.Chat.distillBatchTurns] 或节拍到点且 ≥ [MemKitConfig.Chat.minDistillTurns]
 *   → 后台蒸馏（fire-and-forget，SupervisorJob 隔离失败传染）。
 *
 * 蒸馏触发节拍在 [onTurn] 内联判定；蒸馏本体（提示词协议 / 防御式
 * 解析 / 落账 / 队列提交）住 [ChatDistiller]，治理住 [ChatConsolidator]。
 */
class ChatEngine(
    private val store: MemoryStore,
    private val config: MemKitConfig,
    private val clock: KitClock,
    private val logger: KitLogger,
    private val signals: ChatSignals,
    private val distiller: ChatDistiller,
    private val recaller: ChatRecall,
    scope: CoroutineScope?,
    private val model: ChatModel?,
    private val autoPump: Boolean = true,
) {

    private val workScope: CoroutineScope = scope ?: CoroutineScope(SupervisorJob())

    /** 情绪基调滚动窗（跨会话单例 —— 近况是持续信号不是任务快照）。 */
    private val toneWindow = ArrayDeque<ChatEmotion>()
    private var turnCounter = 0L

    /**
     * 一轮对话结束（引擎 execute finally 调用面）。
     *
     * 轻量启发式同步完成；蒸馏重活转后台 —— 本方法不阻塞超过几毫秒。
     */
    suspend fun onTurn(userText: String, assistantText: String) {
        runCatching { captureHeuristic(userText) }
            .onFailure { logger.log(KitLogger.Level.WARN, TAG, "启发式捕获失败(忽略)：${it.message}") }
        runCatching { updateTone(userText) }
            .onFailure { logger.log(KitLogger.Level.WARN, TAG, "基调更新失败(忽略)：${it.message}") }
        runCatching { captureMilestones(userText) }
            .onFailure { logger.log(KitLogger.Level.WARN, TAG, "里程碑捕获失败(忽略)：${it.message}") }

        runCatching {
            store.enqueueTurn(ChatTurn(userText, assistantText, clock.now()))
        }
        val n = ++turnCounter
        val pending = store.pendingTurnCount()
        if (autoPump && model != null && (
                pending >= config.chat.distillBatchTurns ||
                    (n % config.chat.distillEveryNTurns == 0L && pending >= config.chat.minDistillTurns)
                )
        ) {
            workScope.launch {
                runCatching { pumpDistillation() }
                    .onFailure { logger.log(KitLogger.Level.WARN, TAG, "后台蒸馏失败(忽略)：${it.message}") }
            }
        }
    }

    /**
     * 手动/测试泵：执行一次蒸馏 + 治理（后台路径同款）。
     * 无模型端口或窗口不足 → SKIP 语义（不视为错误）。
     */
    suspend fun pumpDistillation(): ChatDistiller.DistillOutcome? {
        val m = model ?: return ChatDistiller.DistillOutcome.SKIP
        val outcome = distiller.distillOnce(m)
        if (outcome != null) {
            ChatConsolidator.consolidate(store, config, clock)
            store.flush()
        }
        return outcome
    }

    /** 召回（引擎 execute 入口调用面）：无可召回内容返回 null。 */
    suspend fun recall(userText: String): String? {
        val result = recaller.recall(userText) ?: return null
        recaller.commitTouch(result.touchedFactIds)
        return result.rendered
    }

    // ── 启发式三件套 ─────────────────────────────────────────────

    private suspend fun captureHeuristic(userText: String) {
        val captured = signals.captureSelfDisclosure(userText)
        if (captured.isEmpty()) return
        val now = clock.now()
        var added = 0
        var bumped = 0
        for (sentence in captured) {
            val r = store.addOrBumpFact(
                content = sentence,
                kind = com.apex.memkit.core.model.ChatFact.Kind.PROFILE,
                source = com.apex.memkit.core.model.ChatFact.Source.HEURISTIC,
                importance = config.chat.heuristicImportance,
            )
            if (r.added) added++ else if (r.bumped) bumped++
        }
        if (added + bumped > 0) {
            logger.log(KitLogger.Level.INFO, TAG, "启发式捕获：新增 $added 条，强化 $bumped 条（now=${now}）")
        }
    }

    private suspend fun updateTone(userText: String) {
        val signal = EmotionLexicon.detect(userText)
        if (signal == ChatEmotion.NONE) return
        synchronized(toneWindow) {
            toneWindow.addLast(signal)
            while (toneWindow.size > config.chat.stateWindowRounds) toneWindow.removeFirst()
        }
        val summary = signals.summarizeTone(toneWindow.toList()) ?: return
        runCatching { store.replaceStateFact(summary, clock.now()) }
    }

    private suspend fun captureMilestones(userText: String) {
        val milestones = signals.captureMilestones(userText)
        for (sentence in milestones) {
            store.addOrBumpFact(
                content = sentence,
                kind = com.apex.memkit.core.model.ChatFact.Kind.MILESTONE,
                source = com.apex.memkit.core.model.ChatFact.Source.HEURISTIC,
                importance = config.chat.milestoneImportance,
            )
        }
    }

    companion object {
        private const val TAG = "ChatEngine"
    }
}
