package com.apex.memkit.core.model

import kotlinx.serialization.Serializable

/**
 * 情景（Episode）—— 一次 Agent 任务的完整记忆容器：目标、上下文、
 * 轨迹统计与派生宏。
 *
 * 旧 EpisodeEntity 的 `llm_steps` 恒 0、`is_distilled` 恒 false、`skill_id`
 * 恒 null（三组永久性谎言字段）——本模型只保留能被真实写入的统计：
 * [actionCount] 由会话记录推进，[macroIds] 由蒸馏回填，无从造假。
 */
@Serializable
data class EpisodeRecord(
    val episodeId: String,
    val goal: String,
    val appPackage: String? = null,
    val appVersion: String? = null,
    val activityLabel: String? = null,
    val status: EpisodeStatus = EpisodeStatus.RUNNING,
    val startedAt: Long,
    val finishedAt: Long? = null,
    /** 会话期间记录的 UI 动作数（真实计数）。 */
    val actionCount: Int = 0,
    /** 会话观测到的去重节点指纹（场景重建 / 归档的最小充分统计）。 */
    val observedFingerprints: Set<String> = emptySet(),
    /** 蒸馏产出的宏技能 id（成功任务才有；空 = 未蒸馏或无可蒸馏轨迹）。 */
    val macroIds: List<String> = emptyList(),
    val manual: Boolean = false,
)

/** 情景终态。`DISTILLED` 并入 [EpisodeRecord.macroIds] 的存在性表达。 */
@Serializable
enum class EpisodeStatus { RUNNING, SUCCEEDED, FAILED, ABANDONED;

    companion object {
        /** 持久化兼容解析（含旧库 RUNNING/SUCCEEDED/FAILED/DISTILLED 映射）。 */
        fun safe(name: String?): EpisodeStatus =
            when (name?.uppercase()) {
                "SUCCEEDED", "DISTILLED" -> SUCCEEDED
                "FAILED" -> FAILED
                "ABANDONED" -> ABANDONED
                else -> RUNNING
            }
    }
}

/** 情景摘要（列表 / 召回工具输出视图）。 */
@Serializable
data class EpisodeSummary(
    val episodeId: String,
    val goal: String,
    val appPackage: String?,
    val status: EpisodeStatus,
    val startedAt: Long,
    val finishedAt: Long?,
    val actionCount: Int,
    val macroIds: List<String>,
    val manual: Boolean = false,
)

/** 手工整理笔记的独立情景标记（旧 `manual_*` Episode 语义）。 */
object ManualEpisode {
    const val ID_PREFIX = "manual_"
    fun isManual(id: String) = id.startsWith(ID_PREFIX)
}
