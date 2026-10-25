package com.apex.memkit.core.model

/**
 * 经验整理请求 —— agent 任务完成后主动沉淀经验的结构化载荷。
 *
 * 「任务后整理经验」的完整语义（宿主工具层把 LLM 的自由文本裁成
 * 结构化字段后经 [com.apex.memkit.core.MemKit.consolidateExperience] 提交）：
 * - 摘要事实：goal + outcome 自动成形一条里程碑/近况事实；
 * - 教训事实：lessons 逐条入账本（同义重复自动归并强化）；
 * - 知识图谱：entities / relations 建入语义图（实体 upsert、关系去重）。
 */
data class ExperienceReport(
    /** 任务目标（与 Episode.goal 同源口径）。 */
    val goal: String,
    /** 结果摘要（做了什么 / 成败与偏差 —— 摘要事实的正文成分）。 */
    val outcome: String,
    /** 任务成败（决定摘要事实的类别：里程碑 vs 近况）。 */
    val success: Boolean,
    /** 经验教训（可复用的因果知识 —— 逐条沉淀为语义记忆）。 */
    val lessons: List<String> = emptyList(),
    /** 知识实体（任务中确认的主体 —— 人物 / 应用 / 方法 / 工具）。 */
    val entities: List<GraphEntity> = emptyList(),
    /** 实体关系（任务中确认的三元组；悬空端在提交时自动补建占位实体）。 */
    val relations: List<GraphRelation> = emptyList(),
) {
    init {
        require(goal.isNotBlank()) { "goal 不能为空" }
        require(outcome.isNotBlank()) { "outcome 不能为空" }
    }
}

/**
 * 经验整理结果 —— 写入面回报（工具层把它转述给 LLM，模型据此确认沉淀）。
 */
data class ExperienceResult(
    /** 摘要事实（added = 新增 / bumped = 重复强化）。 */
    val summaryAdded: Boolean,
    /** 教训事实新增条数。 */
    val lessonsAdded: Int,
    /** 教训事实重复强化条数（幂等归并）。 */
    val lessonsBumped: Int,
    /** 新建实体数（含悬空端自动补建的占位实体）。 */
    val entitiesUpserted: Int,
    /** 关系新增条数。 */
    val relationsAdded: Int,
) {
    /** 总写入量（摘要 + 教训 + 实体 + 关系）。 */
    val totalWrites: Int
        get() = (if (summaryAdded) 1 else 0) + lessonsAdded + entitiesUpserted + relationsAdded

    /** 摘要是否为重复强化（同任务再次整理 = 经验被强化）。 */
    val summaryBumped: Boolean get() = !summaryAdded
}
