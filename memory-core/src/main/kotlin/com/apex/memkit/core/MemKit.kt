package com.apex.memkit.core

import com.apex.memkit.core.chat.ChatDistiller
import com.apex.memkit.core.chat.ChatEngine
import com.apex.memkit.core.chat.ChatRecall
import com.apex.memkit.core.chat.ChatSignals
import com.apex.memkit.core.ingest.IngestGuard
import com.apex.memkit.core.ingest.SceneReducer
import com.apex.memkit.core.model.ChatFact
import com.apex.memkit.core.model.EpisodeSummary
import com.apex.memkit.core.model.ExperienceReport
import com.apex.memkit.core.model.ExperienceResult
import com.apex.memkit.core.model.GraphEntity
import com.apex.memkit.core.model.GraphRelation
import com.apex.memkit.core.model.GraphSearchResult
import com.apex.memkit.core.model.MacroSkill
import com.apex.memkit.core.model.MaintenanceReport
import com.apex.memkit.core.procedure.MacroMaintenance
import com.apex.memkit.core.procedure.MacroMatcher
import com.apex.memkit.core.procedure.MacroRunner
import com.apex.memkit.core.session.SessionDirector
import com.apex.memkit.core.store.InMemoryStorageBackend
import com.apex.memkit.core.store.FactAddResult
import com.apex.memkit.core.store.MemoryStore
import com.apex.memkit.core.store.NodeDictEntry
import com.apex.memkit.core.store.StorageBackend
import com.apex.memkit.core.store.addGraphRelations
import com.apex.memkit.core.store.addObservations
import com.apex.memkit.core.store.addOrBumpFact
import com.apex.memkit.core.store.allFacts
import com.apex.memkit.core.store.clearQuarantine
import com.apex.memkit.core.store.quarantineCount
import com.apex.memkit.core.store.readGraph
import com.apex.memkit.core.store.searchGraph
import com.apex.memkit.core.store.upsertGraphEntities
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

    // ── 经验整理面（任务后主动沉淀：宿主写入工具的库侧落点）════════

    /**
     * 保存长期事实（画像 / 近况 / 里程碑）。
     *
     * 幂等：同 kind + 规范化内容同 id → 不重复堆积，重要性取 max、
     * 访问计数累加（重复披露 = 强化信号）。
     */
    suspend fun saveFact(
        content: String,
        kind: ChatFact.Kind,
        importance: Float = 0.6f,
    ): FactAddResult {
        val result = store.addOrBumpFact(content.trim(), kind, ChatFact.Source.MANUAL, importance.coerceIn(0f, 1f))
        requestFlush()
        return result
    }

    /** 全量事实（工具层回读 / 测试断言面）。 */
    suspend fun allFacts(): List<ChatFact> = store.allFacts()

    /**
     * 任务后经验整理 —— 一次提交把「摘要 + 教训 + 实体 + 关系」全量沉淀。
     *
     * 写入语义：
     * 1. 摘要事实「任务「goal」完成：outcome」—— success 决定里程碑/近况；
     * 2. lessons 逐条入账本（PROFILE —— 持久经验类事实：参与关键词召回与
     *    容量治理；STATE 只召回最新一条，放教训会永久沉底）；
     * 3. entities upsert（同名实体观察合并去重）；
     * 4. relations 去重追加；悬空端（实体不在图内且不在本次提交内）自动
     *    补建占位实体（entityType = "placeholder"）—— 三元组永不悬空。
     */
    suspend fun consolidateExperience(report: ExperienceReport): ExperienceResult {
        val summary = "任务「${report.goal.trim()}」${if (report.success) "完成" else "未成"}：${report.outcome.trim()}"
        val summaryFact = store.addOrBumpFact(
            content = summary,
            kind = if (report.success) ChatFact.Kind.MILESTONE else ChatFact.Kind.STATE,
            source = ChatFact.Source.MANUAL,
            importance = if (report.success) 0.75f else 0.65f,
        )
        var lessonsAdded = 0
        var lessonsBumped = 0
        report.lessons.map { it.trim() }.filter { it.isNotBlank() }.forEach { lesson ->
            val r = store.addOrBumpFact(lesson, ChatFact.Kind.PROFILE, ChatFact.Source.MANUAL, 0.6f)
            if (r.added) lessonsAdded++ else if (r.bumped) lessonsBumped++
        }
        // 关系悬空端补建：from / to 不在图内且不在本次实体提交内 → 占位实体
        val present = store.readGraph().first.associateBy { it.name }
        val declared = report.entities.associateBy { it.name }
        val placeholderNames = linkedSetOf<String>()
        report.relations.forEach { r ->
            listOf(r.from, r.to).forEach { endpoint ->
                if (endpoint.isNotBlank() && present[endpoint] == null && declared[endpoint] == null) {
                    placeholderNames.add(endpoint)
                }
            }
        }
        val placeholders = placeholderNames.map { GraphEntity(name = it, entityType = "placeholder") }
        val entityNames = store.upsertGraphEntities(report.entities + placeholders)
        val relationsAdded = store.addGraphRelations(report.relations)
        requestFlush()
        return ExperienceResult(
            summaryAdded = summaryFact.added,
            lessonsAdded = lessonsAdded,
            lessonsBumped = lessonsBumped,
            entitiesUpserted = entityNames.size,
            relationsAdded = relationsAdded,
        )
    }

    /** 知识图谱实体 upsert（同名合并观察；返回新增实体名）。 */
    suspend fun upsertGraphEntities(entities: List<GraphEntity>): List<String> {
        val added = store.upsertGraphEntities(entities)
        requestFlush()
        return added
    }

    /** 知识图谱关系追加（三元组整体去重；返回新增数）。 */
    suspend fun addGraphRelations(relations: List<GraphRelation>): Int {
        val added = store.addGraphRelations(relations)
        requestFlush()
        return added
    }

    /** 实体追加观察（实体不存在返回 false）。 */
    suspend fun addEntityObservations(entityName: String, contents: List<String>): Boolean {
        val ok = store.addObservations(entityName, contents)
        if (ok) requestFlush()
        return ok
    }

    /** 知识图谱读回（工具层的 create-then-verify 面）。 */
    suspend fun readGraph(): Pair<List<GraphEntity>, List<GraphRelation>> = store.readGraph()

    /** 知识图谱搜索（命中子图：实体 + 关联关系）。 */
    suspend fun searchGraph(query: String): GraphSearchResult = store.searchGraph(query)

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
