package com.apex.memkit.core

/**
 * 记忆库全量配置 —— 一处收口（旧系统的三十余个魔法数散落 6 个文件，
 * 且多处同名不同值；本文件是唯一的调参面）。
 *
 * 所有数值均给出默认值与出处说明。宿主可整体覆盖（DI 里构造一份定制
 * [MemKitConfig]），也可只改个别字段后 [copy]。
 */
data class MemKitConfig(

    /** 语义降维 / 指纹 / 差分（摄取管线）。 */
    val ingest: Ingest = Ingest(),

    /** 宏技能：蒸馏 / 匹配 / 回放 / 维护（程序性记忆）。 */
    val procedure: Procedure = Procedure(),

    /** 聊天事实账本：启发式捕获 / 评分 / 蒸馏批 / 召回。 */
    val chat: Chat = Chat(),

    /** 摄取防护（原「免疫系统」）：可疑屏判据 / 隔离 TTL。 */
    val guard: Guard = Guard(),

    /** 维护周期（原「梦境渲染」的四个仿生机制，参数全量收口）。 */
    val maintenance: Maintenance = Maintenance(),

    /** 写入管道：批尺寸 / 落盘节拍 / 邮箱容量。 */
    val writer: Writer = Writer(),

    /** 存储治理：各类集合容量上限（防无界增长）。 */
    val limits: Limits = Limits(),
) {
    /**
     * 摄取管线参数。
     *
     * @param movedThresholdPx 位移判定阈值：同指纹节点中心移动超过该值记「位移」
     * @param pageTransitionRatio 页面跳转判定：节点集合变化率超过该值时跳过
     *   位移判定（整页切换时逐节点比对无意义）
     */
    data class Ingest(
        val movedThresholdPx: Int = 20,
        val pageTransitionRatio: Float = 0.6f,
    )

    /**
     * 程序性记忆参数。
     *
     * @param anchorChangeRatio 状态锚点判定：相邻两帧指纹重合率低于该值视为
     *   「发生了状态跃迁」（锚点即宏步骤边界）
     * @param minStepsForDistill 轨迹至少含多少个有效 UI 动作才值得蒸馏成宏
     * @param entryMatchThreshold 宏入口匹配：当前屏指纹对入口集合的覆盖率阈值
     * @param aliasMatchThreshold 拓扑别名相似度阈值（role/resourceId/text 加权）
     * @param aliasWeights 别名打分权重（resourceId / text / className 末级）
     * @param actionIntervalMs 回放步间间隔（留手势动画余量）
     * @param maxStepsPerReplay 单次回放步数上限（失控熔断）
     * @param minMacroSuccessBeforeReplay 宏至少成功多少次后才允许旁路回放
     *   （未验证的宏不跳过 LLM —— 旧系统 0.95 阈值配合「仅精确匹配」名存实亡，
     *   新口径改为成功次数门）
     */
    data class Procedure(
        val anchorChangeRatio: Float = 0.7f,
        val minStepsForDistill: Int = 2,
        val entryMatchThreshold: Float = 0.8f,
        val aliasMatchThreshold: Float = 0.7f,
        val aliasWeights: AliasWeights = AliasWeights(),
        val actionIntervalMs: Long = 50,
        val maxStepsPerReplay: Int = 20,
        val minMacroSuccessBeforeReplay: Int = 1,
    )

    /** 拓扑别名打分权重（和不必为 1，阈值语义为绝对分）。 */
    data class AliasWeights(
        val resourceId: Float = 0.5f,
        val text: Float = 0.4f,
        val className: Float = 0.1f,
    )

    /**
     * 聊天记忆参数（口径沿用旧 ChatMemoryPipeline / MemoryLedgerStore 的
     * 生产验证值）。
     */
    data class Chat(
        val selfDisclosureMarkers: List<String> = listOf(
            "我叫", "我的名字", "我喜欢", "我不喜欢", "我讨厌", "我爱吃", "我在做", "我在学",
            "我的工作", "我的职业", "我住", "我是", "我养", "我用", "我平时", "我最近在",
            "i'm ", "i am ", "my name", "i like", "i live", "i work",
        ),
        val maxHeuristicPerTurn: Int = 3,
        val heuristicSentenceMaxLen: Int = 60,
        val heuristicImportance: Float = 0.5f,
        val stateWindowRounds: Int = 6,
        val milestoneImportance: Float = 0.7f,
        val maxMilestonesPerTurn: Int = 2,
        val distillBatchTurns: Int = 6,
        val distillEveryNTurns: Int = 8,
        val minDistillTurns: Int = 2,
        val distillTurnTextCap: Int = 1200,
        val distillQueueCapacity: Int = 12,
        /** LLM 蒸馏输出上限（提示词协议约定 new ≤3 条、每条 ≤40 字）。 */
        val maxFactsPerDistill: Int = 3,
        val recallMaxChars: Int = 900,
        val recallTopProfile: Int = 10,
        val recallKeywordHits: Int = 4,
        val recallRecentMilestones: Int = 5,
        val recallGraphEntities: Int = 3,
        /** 情绪基调句模板（占位符 {emo} 与 {n} 由库填充）。 */
        val stateTemplate: String = "近期情绪基调：{emo}（{n} 轮）",
    )

    /**
     * 摄取防护参数（原「免疫系统」的可配化）。
     *
     * @param overlayAreaRatio 全屏可点击遮罩判定：可点击节点屏占比阈值
     * @param sparseNodeFloor 结构稀疏判定：节点数低于该值视为可疑（悬浮窗/锁屏）
     * @param quarantineTtlDays 隔离名单 TTL（旧系统永不过期 —— 现在默认 30 天后
     *   自动放行重新评估）
     */
    data class Guard(
        val overlayAreaRatio: Float = 0.8f,
        val sparseNodeFloor: Int = 3,
        val quarantineTtlDays: Int = 30,
        val trustedPackages: List<String> = listOf(
            "com.android.systemui", "com.android.launcher3", "com.google.android.deskclock",
        ),
        val sensitivePatterns: List<String> = listOf(
            "转账", "支付密码", "验证码", "银行卡", "身份证号", "密码:", "取款",
        ),
    )

    /**
     * 维护周期参数（旧 DreamRenderer / EntropyManager 的散落数值收口；
     * 维护逻辑本身三处重复的实现只保留本库 MacroMaintenance 一份）。
     *
     * @param decayFactor 全局能量衰减系数（每周期 ×该值；晶化宏豁免）
     * @param pruneMacroFloor 宏剪枝地板：低于该能量的非晶化宏删除
     * @param pruneNodeFloor 节点剪枝地板：低于该能量的节点从字典回收
     * @param staleAfterDays 宏保鲜窗口：超过未回放视为陈旧（软降能）
     * @param crystallizeEnergy 晶化能量门
     * @param crystallizeMinSuccess 晶化最少成功次数
     * @param crystallizeMinRate 晶化最低成功率
     * @param aliasDiscoveryCap 单周期别名发现的最大比对对数（防 O(old×new) 失控）
     */
    data class Maintenance(
        val decayFactor: Float = 0.95f,
        val pruneMacroFloor: Float = 0.05f,
        val pruneNodeFloor: Float = 0.01f,
        val staleAfterDays: Int = 7,
        val crystallizeEnergy: Float = 8.0f,
        val crystallizeMinSuccess: Int = 10,
        val crystallizeMinRate: Float = 0.9f,
        val aliasDiscoveryCap: Int = 200,
    )

    /**
     * 写入管道参数（单消费者邮箱 + 合并落盘）。
     *
     * @param mailboxCapacity 有界邮箱容量：满则挂起背压（不丢事件）
     * @param flushIntervalMs 落盘节拍：即使不足一批，最多延迟这么久必然落盘
     */
    data class Writer(
        val mailboxCapacity: Int = 256,
        val flushIntervalMs: Long = 500,
    )

    /**
     * 存储治理：内存态集合容量上限。超限由维护周期按「能量低者先出、
     * 同能量取最旧」淘汰 —— 记忆库永不无界增长。
     */
    data class Limits(
        val maxEpisodes: Int = 2000,
        val maxNodes: Int = 20000,
        val maxMacros: Int = 500,
        val maxChatFactsProfile: Int = 60,
        val maxChatFactsState: Int = 3,
        val maxChatFactsMilestone: Int = 30,
    )
}
