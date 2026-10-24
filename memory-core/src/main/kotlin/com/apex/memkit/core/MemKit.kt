package com.apex.memkit.core

import com.apex.memkit.core.chat.ChatDistiller
import com.apex.memkit.core.chat.ChatEngine
import com.apex.memkit.core.chat.ChatRecall
import com.apex.memkit.core.chat.ChatSignals
import com.apex.memkit.core.ingest.IngestGuard
import com.apex.memkit.core.ingest.SceneReducer
import com.apex.memkit.core.model.EpisodeSummary
import com.apex.memkit.core.model.MacroSkill
import com.apex.memkit.core.model.MaintenanceReport
import com.apex.memkit.core.procedure.MacroMaintenance
import com.apex.memkit.core.procedure.MacroMatcher
import com.apex.memkit.core.procedure.MacroRunner
import com.apex.memkit.core.session.SessionDirector
import com.apex.memkit.core.store.InMemoryStorageBackend
import com.apex.memkit.core.store.MemoryStore
import com.apex.memkit.core.store.NodeDictEntry
import com.apex.memkit.core.store.StorageBackend
import com.apex.memkit.core.store.clearQuarantine
import com.apex.memkit.core.store.quarantineCount
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * MemKit —— 记忆库门面（宿主的唯一接触面）。
 *
 * 生命周期：[create] → [start]（写入管道）→ 业务调用 → [close]（终冲）。
 * 全部子系统的构造与接线在此收口：
 * - [store] 唯一事实源（内存态 + 文档持久化）；
 * - [sessions] 会话指挥官（任务级采集编排）；
 * - [chat] 聊天记忆引擎（双速捕获 + 蒸馏 + 召回）；
 * - [guard] / [matcher] / [runner] / [maintenance] 程序性记忆四件套。
 *
 * 写入管道（合并落盘）：业务操作只改内存态并标 dirty；[requestFlush] 经
 * 合流通道唤醒单写者循环，按 [MemKitConfig.Writer.flushIntervalMs] 节拍
 * 合并落盘 —— 高频采集不会放大成逐帧 IO（旧 MemoryWriterActor 的
 * 「批 20 条仍逐条 execute」名不副实问题不复存在）。
 */
class MemKit private constructor(
    val config: MemKitConfig,

    /** 唯一事实源（迁移器 / 图谱 transport / 记忆页查询的直访面；单写者纪律由 store 内部保证）。 */
    val store: MemoryStore,
    private val capture: SceneCapture?,
    performer: ActionPerformer?,
    chatModel: ChatModel?,
    private val logger: KitLogger,
    private val clock: KitClock,
    private val ownedScope: CoroutineScope?,
) {

    // ── 子系统（构造顺序即依赖顺序）────────────────────────────────
    private val guard = IngestGuard(config)
    private val matcher = MacroMatcher(store, config, logger)
    private val runner = MacroRunner(config, performer ?: NoopPerformer, clock, logger)
    val maintenance = MacroMaintenance(store, config, clock, logger)
    private val chatDistiller = ChatDistiller(store, config, clock, logger)
    private val chatRecaller = ChatRecall(store, config, clock, logger)
    private val chatSignals = ChatSignals(config)
    val sessions = SessionDirector(store, config, guard, capture, clock, logger)
    val chat = ChatEngine(
        store, config, clock, logger, chatSignals, chatDistiller, chatRecaller,
        ownedScope, chatModel,
    )

    // ── 写入管道（单写者 + 合流）──────────────────────────────────
    private val flushSignal = Channel<Unit>(Channel.CONFLATED)
    private var pipelineJob: kotlinx.coroutines.Job? = null
    private val pipelineLock = Any()

    /** 旁路执行结果（引擎据此决定是否跳过本轮 LLM）。 */
    sealed class BypassResult {
        /** 捕获端口缺席 / 捕获失败 —— 未尝试。 */
        object NotAvailable : BypassResult()

        /** 无匹配宏。 */
        object NotMatched : BypassResult()

        /** 执行成功。 */
        data class Executed(val actionCount: Int) : BypassResult()

        /** 执行失败（偏离 / 熔断）—— 回退 LLM。 */
        data class Failed(val reason: String) : BypassResult()
    }

    /** 启动写入管道（幂等；宿主 App 入口调用一次）。 */
    fun start() {
        synchronized(pipelineLock) {
            if (pipelineJob?.isActive == true) return
            val scope = ownedScope ?: CoroutineScope(kotlinx.coroutines.SupervisorJob())
            pipelineJob = scope.launch {
                for (tick in flushSignal) {
                    delay(config.writer.flushIntervalMs)  // 节拍合流：高频信号折叠为一次落盘
                    runCatching { store.flush() }
                        .onFailure { logger.log(KitLogger.Level.WARN, TAG, "节拍落盘失败(忽略)：${it.message}") }
                }
            }
        }
    }

    /** 请求落盘（合流；立即返回不阻塞）。 */
    fun requestFlush() {
        flushSignal.trySend(Unit)
    }

    /** 停止管道并终冲（App 退场调用；可重新 [start]）。 */
    suspend fun close() {
        synchronized(pipelineLock) {
            pipelineJob?.cancel()
            pipelineJob = null
        }
        runCatching { store.flush() }
    }

    // ── 旁路回放（每轮 LLM 推理前）════════════════════════════════

    /**
     * 尝试肌肉记忆旁路。
     *
     * 捕获 → 降维 → 指纹集合（与采集侧同一条路径 —— 指纹可比对性由
     * 构造保证）→ 包名一致的候选宏 → 覆盖率匹配（含别名桥）→ 逐步
     * 验证回放 → 成功记账。
     */
    suspend fun tryBypass(): BypassResult {
        val port = capture ?: return BypassResult.NotAvailable
        val raw = runCatching { port.capture() }.getOrNull() ?: return BypassResult.NotAvailable
        val scene = SceneReducer.reduce(raw, clock.now()) ?: return BypassResult.NotAvailable
        val currentFps = scene.fingerprints()

        val match = matcher.findBest(currentFps, scene.appPackage) ?: return BypassResult.NotMatched
        val result = runner.run(match.macro) {
            runCatching { port.capture() }.getOrNull()
                ?.let { SceneReducer.reduce(it, clock.now()) }
                ?.fingerprints()
        }
        return when (result) {
            is MacroRunner.Result.Executed -> {
                maintenance.recordRun(match.macro, success = true)
                requestFlush()
                BypassResult.Executed(result.steps)
            }

            is MacroRunner.Result.Failed -> {
                maintenance.recordRun(match.macro, success = false)
                requestFlush()
                BypassResult.Failed(result.reason)
            }
        }
    }

    // ── 维护周期（宿主 WorkManager 调用面）══════════════════════════

    /** 立即执行一轮维护（梦境整理的手动入口同款）。 */
    suspend fun runMaintenanceNow(): MaintenanceReport = maintenance.runCycle()

    // ── 查询面（召回工具 / 记忆页 / 市场投影共用）════════════════════

    suspend fun recentEpisodes(limit: Int): List<EpisodeSummary> = store.recentEpisodes(limit)

    suspend fun searchNodes(query: String, limit: Int): List<NodeDictEntry> =
        store.searchNodes(query, limit)

    suspend fun topMacros(limit: Int): List<MacroSkill> = store.topMacros(limit)

    suspend fun macroById(id: String): MacroSkill? = store.macroById(id)

    suspend fun counts(): Counts = Counts(
        episodes = store.episodeCount(),
        nodes = store.nodeCount(),
        macros = store.macroCount(),
        aliases = store.aliasCount(),
    )

    suspend fun deleteEpisode(id: String): Boolean = store.removeEpisode(id)

    /** 手工整理（消息长按「整理进记忆」入口）。 */
    suspend fun ingestManualNotes(goal: String, text: String): String =
        sessions.ingestManualNotes(goal, text)

    suspend fun quarantineCount(): Int = store.quarantineCount()

    suspend fun clearQuarantine(): Int = store.clearQuarantine()

    data class Counts(val episodes: Int, val nodes: Int, val macros: Int, val aliases: Int)

    // ── 构造 ═════════════════════════════════════════════════════

    companion object {
        private const val TAG = "MemKit"

        /**
         * 创建记忆库。
         *
         * @param storage 持久化后端（宿主给 [com.apex.memkit.core.store.FileStorageBackend]，
         *   测试给 [InMemoryStorageBackend]）
         * @param scope 写入管道 / 后台蒸馏的宿主作用域（null = 库自建
         *   SupervisorJob 作用域；App 建议传应用级 scope）
         */
        suspend fun create(
            config: MemKitConfig = MemKitConfig(),
            storage: StorageBackend,
            capture: SceneCapture? = null,
            performer: ActionPerformer? = null,
            chatModel: ChatModel? = null,
            logger: KitLogger = SilentLogger,
            clock: KitClock = KitClock { System.currentTimeMillis() },
            scope: CoroutineScope? = null,
        ): MemKit {
            val store = MemoryStore(config, storage, clock, logger)
            store.load()
            return MemKit(config, store, capture, performer, chatModel, logger, clock, scope)
        }
    }
}

/** 无执行端口时的空实现（动作必然失败 → 回放熔断，安全降级）。 */
private object NoopPerformer : ActionPerformer {
    override suspend fun perform(action: com.apex.memkit.core.model.MacroAction): Boolean = false
}
