package com.apex.memkit.graph.graph

import com.apex.memkit.core.model.ChatFact
import com.apex.memkit.core.model.EpisodeRecord
import com.apex.memkit.core.model.EpisodeStatus
import com.apex.memkit.core.model.FingerprintAlias
import com.apex.memkit.core.model.GraphEntity
import com.apex.memkit.core.model.GraphRelation
import com.apex.memkit.core.model.MacroAction
import com.apex.memkit.core.model.MacroSkill
import com.apex.memkit.core.model.MacroStats
import com.apex.memkit.core.model.MacroStep
import com.apex.memkit.core.store.NodeDictEntry
import com.apex.memkit.core.model.NodeRole

/**
 * 演示数据 —— [DemoGraphSource] 的固定语料（预览 / 测试 / 空态引导）。
 *
 * 覆盖六类节点 + 六类边的全部成形路径：两个应用的情景/宏/节点、跨宏
 * 链衔接、晶化宏、四类聊天事实、知识三元组、别名桥 —— 一次构建即可
 * 在图上看到全部图例元素。
 */
object DemoGraphData {

    private const val PKG_WECHAT = "com.tencent.mm"
    private const val PKG_SETTINGS = "com.android.settings"

    // 场景节点指纹（别名桥 / 宏链锚点共用）
    private const val FP_HOME = "fp_wechat_home_0000aaaa"
    private const val FP_CHAT_LIST = "fp_wechat_chatlist_1111bbbb"
    private const val FP_SEARCH_OLD = "fp_wechat_search_old_2222cccc"
    private const val FP_SEARCH_NEW = "fp_wechat_search_new_3333dddd"
    private const val FP_SETTINGS_HOME = "fp_settings_home_4444eeee"
    private const val FP_SETTINGS_WIFI = "fp_settings_wifi_5555ffff"

    fun data(): GraphData = GraphData(
        episodes = listOf(
            EpisodeRecord(
                episodeId = "ep_demo_1",
                goal = "给 Alice 发送周末爬山邀请",
                appPackage = PKG_WECHAT,
                appVersion = "8.0.47",
                activityLabel = "LauncherUI",
                status = EpisodeStatus.SUCCEEDED,
                startedAt = 1_728_000_000_000,
                finishedAt = 1_728_000_095_000,
                actionCount = 12,
                observedFingerprints = setOf(FP_HOME, FP_CHAT_LIST, FP_SEARCH_OLD),
                macroIds = listOf("macro_demo_send"),
            ),
            EpisodeRecord(
                episodeId = "ep_demo_2",
                goal = "关闭 Wi-Fi 省电",
                appPackage = PKG_SETTINGS,
                appVersion = "14",
                status = EpisodeStatus.SUCCEEDED,
                startedAt = 1_728_000_400_000,
                finishedAt = 1_728_000_460_000,
                actionCount = 7,
                observedFingerprints = setOf(FP_SETTINGS_HOME, FP_SETTINGS_WIFI),
                macroIds = listOf("macro_demo_wifi"),
            ),
            EpisodeRecord(
                episodeId = "ep_demo_3",
                goal = "在群里查找昨天的文件",
                appPackage = PKG_WECHAT,
                appVersion = "8.0.47",
                status = EpisodeStatus.FAILED,
                startedAt = 1_728_000_800_000,
                finishedAt = 1_728_000_900_000,
                actionCount = 21,
                observedFingerprints = setOf(FP_HOME, FP_SEARCH_NEW),
                macroIds = emptyList(),
            ),
            EpisodeRecord(
                episodeId = "ep_demo_manual",
                goal = "手工整理：用户偏好摘要",
                status = EpisodeStatus.SUCCEEDED,
                startedAt = 1_728_001_000_000,
                finishedAt = 1_728_001_000_100,
                actionCount = 0,
                observedFingerprints = emptySet(),
                macroIds = emptyList(),
                manual = true,
            ),
        ),
        macros = listOf(
            MacroSkill(
                skillId = "macro_demo_search",
                name = "打开搜索",
                goal = "从微信首页进入搜索页",
                appPackage = PKG_WECHAT,
                appVersion = "8.0.47",
                entryFingerprints = setOf(FP_HOME),
                exitFingerprint = FP_SEARCH_OLD,
                steps = listOf(
                    MacroStep(
                        action = MacroAction.Tap(540, 180),
                        anchorFingerprints = setOf(FP_HOME),
                        nextAnchorFingerprints = setOf(FP_SEARCH_OLD),
                    ),
                ),
                stats = MacroStats(successCount = 6, failureCount = 1, energy = 7.0f),
                sourceEpisodeId = "ep_demo_1",
                createdAt = 1_728_000_100_000,
            ),
            MacroSkill(
                skillId = "macro_demo_send",
                name = "发送消息给 Alice",
                goal = "搜索 Alice 并发送文本",
                appPackage = PKG_WECHAT,
                appVersion = "8.0.47",
                entryFingerprints = setOf(FP_SEARCH_OLD, FP_SEARCH_NEW),
                exitFingerprint = FP_CHAT_LIST,
                steps = listOf(
                    MacroStep(MacroAction.Input("Alice"), setOf(FP_SEARCH_OLD), setOf(FP_SEARCH_OLD)),
                    MacroStep(MacroAction.Tap(540, 800), setOf(FP_SEARCH_OLD), setOf(FP_CHAT_LIST)),
                    MacroStep(MacroAction.Input("周末爬山约吗？"), setOf(FP_CHAT_LIST), setOf(FP_CHAT_LIST)),
                    MacroStep(MacroAction.Tap(980, 1800), setOf(FP_CHAT_LIST), setOf(FP_CHAT_LIST)),
                ),
                stats = MacroStats(successCount = 18, failureCount = 2, energy = 9.6f, crystallized = true),
                sourceEpisodeId = "ep_demo_1",
                createdAt = 1_728_000_200_000,
            ),
            MacroSkill(
                skillId = "macro_demo_wifi",
                name = "关闭 Wi-Fi",
                goal = "设置首页 → Wi-Fi 开关关闭",
                appPackage = PKG_SETTINGS,
                appVersion = "14",
                entryFingerprints = setOf(FP_SETTINGS_HOME),
                exitFingerprint = FP_SETTINGS_WIFI,
                steps = listOf(
                    MacroStep(MacroAction.Tap(540, 700), setOf(FP_SETTINGS_HOME), setOf(FP_SETTINGS_WIFI)),
                    MacroStep(MacroAction.Swipe(540, 1000, 540, 1000, 120), setOf(FP_SETTINGS_WIFI), setOf(FP_SETTINGS_WIFI)),
                ),
                stats = MacroStats(successCount = 9, failureCount = 1, energy = 5.2f),
                sourceEpisodeId = "ep_demo_2",
                createdAt = 1_728_000_500_000,
            ),
        ),
        facts = listOf(
            ChatFact("fact_p1", "用户叫林小舟，是一名后端工程师", ChatFact.Kind.PROFILE, ChatFact.Source.DISTILLED, 0.9f, 1.0f, 1_727_000_000_000, 1_728_000_900_000, 14),
            ChatFact("fact_p2", "用户偏好 Kotlin 与 Jetpack Compose", ChatFact.Kind.PROFILE, ChatFact.Source.HEURISTIC, 0.75f, 0.9f, 1_727_000_100_000, 0, 3),
            ChatFact("fact_s1", "最近在为记忆系统重构熬夜", ChatFact.Kind.STATE, ChatFact.Source.HEURISTIC, 0.6f, 0.8f, 1_728_000_700_000, 0, 1),
            ChatFact("fact_m1", "2026 年 10 月完成记忆图谱可视化库", ChatFact.Kind.MILESTONE, ChatFact.Source.DISTILLED, 0.85f, 0.95f, 1_728_001_000_000, 0, 0),
        ),
        entities = listOf(
            GraphEntity("Alice", "person", listOf("爬山搭子", "周五常约")),
            GraphEntity("周末爬山", "activity", listOf("用户最喜欢的运动")),
            GraphEntity("Kotlin", "language", listOf("用户主力语言", "2.3.21")),
            GraphEntity("记忆系统", "project", listOf("重构进行中", "apex-kit-memory")),
        ),
        // 构造器为 (from, to, relationType) —— 自然语序「A 关系 B」写作 from=A, to=B
        relations = listOf(
            GraphRelation("Alice", "周末爬山", "喜欢"),
            GraphRelation("用户", "Alice", "认识"),          // 悬空端（用户实体不在集合 → 边被剔）
            GraphRelation("用户", "Kotlin", "使用"),          // 悬空端
            GraphRelation("记忆系统", "Kotlin", "用语言"),
            GraphRelation("用户", "记忆系统", "维护"),        // 悬空端
            GraphRelation("Alice", "记忆系统", "参与"),
        ),
        sceneNodes = listOf(
            NodeDictEntry(FP_HOME, NodeRole.NAVIGATION, "微信", "home_tab", "LinearLayout", PKG_WECHAT, "8.0.47", 1_728_000_000_000, 1_728_000_800_000, 30, 4.2f),
            NodeDictEntry(FP_CHAT_LIST, NodeRole.SCROLLABLE, "聊天列表", "chat_list", "RecyclerView", PKG_WECHAT, "8.0.47", 1_728_000_010_000, 1_728_000_800_000, 28, 3.8f),
            NodeDictEntry(FP_SEARCH_OLD, NodeRole.BUTTON, "搜索", "search_btn_old", "ImageView", PKG_WECHAT, "8.0.46", 1_728_000_020_000, 1_728_000_300_000, 12, 2.0f),
            NodeDictEntry(FP_SEARCH_NEW, NodeRole.BUTTON, "搜索", "search_btn_new", "ImageView", PKG_WECHAT, "8.0.47", 1_728_000_310_000, 1_728_000_800_000, 11, 1.8f),
            NodeDictEntry(FP_SETTINGS_HOME, NodeRole.SCROLLABLE, "设置", "settings_list", "RecyclerView", PKG_SETTINGS, "14", 1_728_000_400_000, 1_728_000_460_000, 9, 1.5f),
            NodeDictEntry(FP_SETTINGS_WIFI, NodeRole.TOGGLE, "Wi-Fi", "wifi_switch", "Switch", PKG_SETTINGS, "14", 1_728_000_420_000, 1_728_000_460_000, 9, 1.5f),
        ),
        aliases = listOf(
            FingerprintAlias(FP_SEARCH_OLD, FP_SEARCH_NEW, PKG_WECHAT, 0.92f, "8.0.46", "8.0.47", 1_728_000_310_000),
        ),
    )
}
