package com.apex.memkit.core.session

import com.apex.memkit.core.KitClock
import com.apex.memkit.core.KitLogger
import com.apex.memkit.core.MemKitConfig
import com.apex.memkit.core.RawCapture
import com.apex.memkit.core.SceneCapture
import com.apex.memkit.core.ingest.IngestGuard
import com.apex.memkit.core.ingest.SceneDiffer
import com.apex.memkit.core.ingest.SceneFingerprint
import com.apex.memkit.core.ingest.SceneReducer
import com.apex.memkit.core.model.EpisodeRecord
import com.apex.memkit.core.model.EpisodeStatus
import com.apex.memkit.core.model.GuardVerdict
import com.apex.memkit.core.model.Scene
import com.apex.memkit.core.model.SceneNode
import com.apex.memkit.core.procedure.ActionNotation
import com.apex.memkit.core.procedure.MacroDistiller
import com.apex.memkit.core.procedure.TraceStep
import com.apex.memkit.core.store.MemoryStore
import com.apex.memkit.core.store.quarantinedAmong
import com.apex.memkit.core.store.quarantineFingerprint
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 会话指挥官 —— 任务级记忆采集的编排中枢（原 CsMemSessionManager 的
 * 并发安全重制）。
 *
 * 对旧实现的三个根治：
 * 1. **会话键控**：旧 @Singleton 持有单份可变会话状态，并发两个任务互相
 *   践踏；本类以 [sessionId]（宿主按引擎任务派生，如协程 Job 身份）键控
 *   [SessionState]，多任务互不干扰；
 * 2. **捕获单源**：采集 / 差分 / 轨迹全部经同一条「[SceneCapture] →
 *   [SceneReducer.reduce] → 指纹」路径（旧系统采集走嵌套树、匹配走扁平
 *   剥离视图，指纹系统性不一致 —— P0-2 根治）；
 * 3. **上下文贯穿**：appPackage / appVersion 从捕获产物单源取得并写入
 *   Episode / 宏 / 节点（旧 afterAction 从不传包名 —— P0-1 / P0-3 根治）。
 *
 * 所有方法防御式：任何异常（无障碍关闭 / 捕获失败）只记日志绝不阻断
 * Agent 主流程 —— 观察者契约的硬约束。
 */
class SessionDirector(
    private val store: MemoryStore,
    private val config: MemKitConfig,
    private val guard: IngestGuard,
    private val capture: SceneCapture?,
    private val clock: KitClock,
    private val logger: KitLogger,
) {

    /** 单会话的可变状态（仅所属 sessionId 的操作线程序贯访问）。 */
    private class SessionState(
        val episodeId: String,
        val goal: String,
        var appPackage: String?,
        var appVersion: String?,
        var lastScene: Scene?,
        val observed: MutableSet<String>,
        var actionCount: Int,
        val trace: MutableList<TraceStep>,
    )

    private val sessions = ConcurrentHashMap<String, SessionState>()
    private val counter = AtomicLong(0)

    /** 任务结束产物（蒸馏出的宏 id，空 = 未蒸馏）。 */
    data class EndResult(val episodeId: String, val macroSkillIds: List<String>)

    // ═══ 生命周期 ═════════════════════════════════════════════════

    /**
     * 任务开始：建档 + 首帧快照。
     *
     * @param sessionId 宿主派生的任务键（同任务 [recordAction]/[end] 须用同一键）
     */
    suspend fun begin(sessionId: String, goal: String, appPackage: String?): EndResult? {
        val now = clock.now()
        val episodeId = "ep_${now}_${counter.incrementAndGet()}"
        var firstScene: Scene? = null
        var pkg = appPackage
        var version: String? = null
        runCatching {
            firstScene = captureAndGuard(state = null)  // 首帧无会话可归属，仍采集
            firstScene?.let {
                pkg = it.appPackage ?: pkg
                version = it.appVersion
            }
        }.onFailure { logger.log(KitLogger.Level.WARN, TAG, "首帧捕获失败（忽略）：${it.message}") }

        val episode = EpisodeRecord(
            episodeId = episodeId,
            goal = goal,
            appPackage = pkg,
            appVersion = version,
            status = EpisodeStatus.RUNNING,
            startedAt = now,
        )
        store.addEpisode(episode)
        val state = SessionState(episodeId, goal, pkg, version, firstScene, mutableSetOf(), 0, mutableListOf())
        firstScene?.let { scene ->
            state.observed.addAll(scene.fingerprints())
            store.upsertNodes(scene.nodes, scene.appPackage, scene.appVersion)
        }
        sessions[sessionId] = state
        return EndResult(episodeId, emptyList())
    }

    /**
     * 每个动作后：捕获当前屏 → 差分摄取 → 轨迹追加。
     *
     * @param actionNotation 宿主引擎的动作描述（`UiTap(540,1200)` 形态）；
     *   非 UI 动作自动降级为「仅观测」
     */
    suspend fun recordAction(sessionId: String, actionNotation: String, success: Boolean) {
        val state = sessions[sessionId] ?: return
        runCatching {
            val before = state.lastScene
            val action = ActionNotation.parse(actionNotation)
            val scene = captureAndGuard(state)
            if (scene != null) {
                state.observed.addAll(scene.fingerprints())
                if (scene.appPackage != null) state.appPackage = scene.appPackage
                if (scene.appVersion != null) state.appVersion = scene.appVersion
                val delta = SceneDiffer.computeDelta(before, scene, config, actionNotation)
                if (delta.addedNodes.isNotEmpty()) {
                    store.upsertNodes(delta.addedNodes, scene.appPackage, scene.appVersion)
                }
                if (action != null) {
                    state.trace.add(
                        TraceStep(
                            beforeFps = before?.fingerprints() ?: emptySet(),
                            action = action,
                            notation = actionNotation,
                            success = success,
                            afterFps = scene.fingerprints(),
                        ),
                    )
                }
                state.lastScene = scene
            } else if (action != null) {
                // 捕获失败也要记轨迹（before/after 空集 → 蒸馏时该步不构成锚点）
                state.trace.add(
                    TraceStep(emptySet(), action, actionNotation, success, emptySet()),
                )
            }
            state.actionCount++
            store.updateEpisode(state.episodeId) {
                it.copy(actionCount = state.actionCount, appPackage = state.appPackage, appVersion = state.appVersion)
            }
        }.onFailure { logger.log(KitLogger.Level.WARN, TAG, "动作观测失败（忽略）：${it.message}") }
    }

    /**
     * 任务结束：终态回填；成功任务蒸馏轨迹 → 宏。
     */
    suspend fun end(sessionId: String, success: Boolean): EndResult {
        val state = sessions.remove(sessionId) ?: return EndResult("", emptyList())
        val now = clock.now()
        val macroIds = ArrayList<String>(1)
        runCatching {
            if (success && state.trace.size >= config.procedure.minStepsForDistill) {
                val macro = MacroDistiller.distill(
                    trace = state.trace.toList(),
                    goal = state.goal,
                    appPackage = state.appPackage,
                    appVersion = state.appVersion,
                    sourceEpisodeId = state.episodeId,
                    config = config,
                    now = now,
                )
                if (macro != null) {
                    store.saveMacro(macro)
                    macroIds.add(macro.skillId)
                }
            }
        }.onFailure { logger.log(KitLogger.Level.WARN, TAG, "轨迹蒸馏失败（忽略）：${it.message}") }

        store.updateEpisode(state.episodeId) {
            it.copy(
                status = if (success) EpisodeStatus.SUCCEEDED else EpisodeStatus.FAILED,
                finishedAt = now,
                actionCount = state.actionCount,
                observedFingerprints = state.observed.takeIf { s -> s.isNotEmpty() } ?: it.observedFingerprints,
                macroIds = macroIds,
            )
        } ?: store.addEpisode(
            EpisodeRecord(
                episodeId = state.episodeId,
                goal = state.goal,
                appPackage = state.appPackage,
                appVersion = state.appVersion,
                status = if (success) EpisodeStatus.SUCCEEDED else EpisodeStatus.FAILED,
                startedAt = now,
                finishedAt = now,
                actionCount = state.actionCount,
                observedFingerprints = state.observed,
                macroIds = macroIds,
            ),
        )
        return EndResult(state.episodeId, macroIds)
    }

    /**
     * 手工整理笔记（旧 organizeText 语义收编）：文本按行 → TEXT 语义节点，
     * 写入独立 MANUAL 情景（可被 searchNodes 召回；不占活跃任务轨迹）。
     */
    suspend fun ingestManualNotes(goal: String, text: String): String {
        val now = clock.now()
        val episodeId = "manual_${now}_${counter.incrementAndGet()}"
        val lines = text.lines().map { it.trim() }.filter { it.length >= 2 }.take(200)
        val nodes = lines.map { line ->
            val fp = SceneFingerprint.node("note", "", line, NodeRoleText, null)
            SceneNode(
                fingerprint = fp,
                role = com.apex.memkit.core.model.NodeRole.TEXT,
                text = line.take(200),
                className = "note",
                interactive = false,
            )
        }
        store.addEpisode(
            EpisodeRecord(
                episodeId = episodeId,
                goal = goal,
                status = EpisodeStatus.SUCCEEDED,
                startedAt = now,
                finishedAt = now,
                observedFingerprints = nodes.mapTo(mutableSetOf()) { it.fingerprint },
                manual = true,
            ),
        )
        if (nodes.isNotEmpty()) store.upsertNodes(nodes, appPackage = null, appVersion = null)
        return episodeId
    }

    /** 活跃会话数（诊断位）。 */
    fun activeSessionCount(): Int = sessions.size

    // ── 内部：捕获 + 防护（采集侧唯一路径）──────────────────────────

    private suspend fun captureAndGuard(state: SessionState?): Scene? {
        val port = capture ?: return null
        val raw: RawCapture = port.capture() ?: return null
        val scene = SceneReducer.reduce(raw, clock.now()) ?: return null

        val verdict = guard.inspect(scene)
        when (verdict.verdict) {
            GuardVerdict.FRAME_BLOCKED -> {
                logger.log(KitLogger.Level.WARN, TAG, "整帧可疑（${verdict.quarantined.size} 节点）—— 阻断摄取")
                verdict.quarantined.forEach { store.quarantineFingerprint(it.fingerprint, "frame_blocked") }
                return null
            }

            GuardVerdict.NODE_QUARANTINE -> {
                val quarantined = verdict.quarantined.mapTo(mutableSetOf()) { it.fingerprint }
                quarantined.forEach { store.quarantineFingerprint(it, "suspicious") }
                // 隔离节点从本帧剔除后继续摄取（部分可信）
                val filtered = scene.copy(nodes = scene.nodes.filter { it.fingerprint !in quarantined })
                if (filtered.nodes.isEmpty()) return null
                return filtered
            }

            GuardVerdict.PASS -> {
                // 已在隔离名单的节点直接剔除（TTL 内不再入库）
                val known = store.quarantinedAmong(scene.nodes.map { it.fingerprint })
                if (known.isEmpty()) return scene
                val filtered = scene.copy(nodes = scene.nodes.filter { it.fingerprint !in known })
                return if (filtered.nodes.isEmpty()) null else filtered
            }
        }
    }

    companion object {
        private const val TAG = "SessionDirector"
        private const val NodeRoleText = "TEXT"
    }
}
