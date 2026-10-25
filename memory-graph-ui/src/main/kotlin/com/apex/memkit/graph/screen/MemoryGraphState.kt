package com.apex.memkit.graph.screen

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import com.apex.memkit.graph.canvas.GraphCamera
import com.apex.memkit.graph.canvas.GraphGeometry
import com.apex.memkit.graph.graph.GraphData
import com.apex.memkit.graph.graph.MemoryGraph
import com.apex.memkit.graph.graph.MemoryGraphBuilder
import com.apex.memkit.graph.graph.MemoryGraphSource
import com.apex.memkit.graph.graph.MemoryNodeKind
import com.apex.memkit.graph.layout.ForceDirectedLayout
import com.apex.memkit.graph.layout.Vec2
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 记忆图谱状态机 —— 画布 / 过滤 / 搜索 / 选择 / 布局的编排中枢。
 *
 * 生命周期：`rememberMemoryGraphState(source)` 创建 → 宿主 Composable 的
 * LaunchedEffect 调 [load] → 交互经回调方法驱动。无 Android 生命周期依赖
 * （scope 由调用方给定 —— Compose 用 rememberCoroutineScope，VM 场景用
 * viewModelScope）；纯状态逻辑（过滤 / 搜索 / 邻接）可直接单测。
 *
 * 图谱数据流：[source] 快照 → 构建器（六类节点 + 六类边）→ 类型过滤子图
 * → 力导向布局 → 相机适配。过滤切换走「保位增量布局」（存活节点锚定、
 * 新增节点松弛落位）—— 关掉一类过滤器不会打乱已建立的空间认知；
 * 场景层切换是数据重塑（节点集 / 边集都变）→ 全量重排。
 *
 * 自动软刷新：源提供变更信号（[MemoryGraphSource.changes]，如 store 修订
 * 号）时，去抖合并后无感重拉快照 —— 记忆生长实时可见。刷新保全探索现场
 * （存活节点保位 / 选中保留 / 探索态相机稳定），失败静默保持现状；静态源
 * （无信号）零开销直通，行为与旧版完全一致。
 */
class MemoryGraphState(
    private val source: MemoryGraphSource,
    private val scope: CoroutineScope,
    private val builderOptions: MemoryGraphBuilder.Options = MemoryGraphBuilder.Options(),
    private val layoutSpec: ForceDirectedLayout.LayoutSpec = ForceDirectedLayout.LayoutSpec(),
) {

    // ── 可观察状态（画布与控制条的全部输入）──────────────────────

    /** 加载 / 布局中（画布中央进度指示 + 首帧抑制）。 */
    var loading by mutableStateOf(true)
        private set

    /** 快照拉取失败信息（null = 成功；空数据不算失败）。 */
    var loadError by mutableStateOf<String?>(null)
        private set

    /** 类型过滤子图（画布渲染对象）。 */
    var displayGraph by mutableStateOf(MemoryGraph(emptyList(), emptyList(), com.apex.memkit.graph.graph.GraphStats()))
        private set

    /** 节点世界坐标（画布渲染 / 命中检测；单节点拖拽零整表拷贝）。 */
    val positions = mutableStateMapOf<String, Vec2>()

    /** 视图相机（平移缩放）。 */
    var camera by mutableStateOf(GraphCamera())
        private set

    /** 选中节点 id（null = 无聚焦；详情 Sheet 数据源）。 */
    var selection by mutableStateOf<String?>(null)
        private set

    /** 搜索词（空 = 无搜索态）。 */
    var searchQuery by mutableStateOf("")

    /** 场景节点层开关（数据重塑级过滤 —— 开 = 重建图谱）。 */
    var sceneLayerEnabled by mutableStateOf(builderOptions.includeSceneNodes)
        private set

    /** 视口尺寸（fit-to-view 的输入；画布 onSizeChanged 回填）。 */
    var viewportPx by mutableStateOf(androidx.compose.ui.geometry.Offset(1080f, 1920f))

    /** 启用的节点类型集合（过滤条 chips 的镜像）。 */
    var enabledKinds by mutableStateOf(DEFAULT_ENABLED_KINDS)
        private set

    /** 全图（未过滤 —— 统计口径用 displayGraph，此处保留重建入口）。 */
    private var fullGraph: MemoryGraph? = null

    /** 最近一次快照（场景层切换的免重拉重建输入）。 */
    private var lastData: GraphData? = null

    private var layoutJob: Job? = null
    private val layoutEngine = ForceDirectedLayout(layoutSpec)

    init {
        // 变更信号观察：去抖合并（写入风暴 → 单次软刷新节拍）。scope 取构造
        // 参数（Compose 场景 = rememberCoroutineScope —— 离开组合即取消观察）；
        // 静态源的空流直通结束，零开销。
        scope.launch { debouncedChanges().collect { softReload() } }
    }

    /** 变更信号（去抖合流）。 */
    @kotlinx.coroutines.FlowPreview
    private fun debouncedChanges(): Flow<Unit> =
        source.changes().debounce(AUTO_REFRESH_DEBOUNCE_MS)

    // ── 派生态（derivedStateOf 避免手工同步）─────────────────────

    /** 选中节点的邻接集合（聚焦亮集）。 */
    val neighbors: Set<String> by androidx.compose.runtime.derivedStateOf {
        val sel = selection ?: return@derivedStateOf emptySet()
        displayGraph.neighborsOf(sel)
    }

    /** 搜索命中集合（空词 = 空集 = 非搜索态）。 */
    val searchMatches: Set<String> by androidx.compose.runtime.derivedStateOf {
        val q = searchQuery.trim()
        if (q.isEmpty()) emptySet()
        else displayGraph.nodes.filter { it.matchesQuery(q) }.mapTo(mutableSetOf()) { it.id }
    }

    /** 选中节点对象（详情 Sheet 消费）。 */
    val selectedNode: com.apex.memkit.graph.graph.MemoryNode? by androidx.compose.runtime.derivedStateOf {
        selection?.let { displayGraph.nodesById[it] }
    }

    // ── 数据装载 ═════════════════════════════════════════════════

    /**
     * 拉取快照并完成首帧布局（进入页面 / 手动刷新调用；重入安全 ——
     * 在途任务先取消）。
     */
    fun load() {
        layoutJob?.cancel()
        layoutJob = scope.launch {
            loading = true
            loadError = null
            val data = runCatching { source.load() }
                .getOrElse { e ->
                    loadError = e.message ?: e.javaClass.simpleName
                    GraphData()
                }
            lastData = data
            val graph = MemoryGraphBuilder(builderOptions).build(data)
            fullGraph = graph
            displayGraph = graph.filtered(enabledKinds)
            selection = null
            replacePositions(runLayout(displayGraph, pinned = emptyMap()))
            camera = fitCamera()
            loading = false
        }
    }

    /** 场景节点层切换（数据重塑：重建图谱 + 全量重排；类型过滤同步跟随）。 */
    fun setSceneLayer(enabled: Boolean) {
        if (sceneLayerEnabled == enabled) return
        sceneLayerEnabled = enabled
        // 层开关与类型过滤同步：开层自动点亮 SCENE_NODE 过滤（否则重建后
        // 依旧不可见）；关层顺手关闭（保持「层 = 可见性」的直觉语义）
        enabledKinds = if (enabled) enabledKinds + MemoryNodeKind.SCENE_NODE
        else enabledKinds - MemoryNodeKind.SCENE_NODE
        val data = lastData ?: return
        layoutJob?.cancel()
        layoutJob = scope.launch {
            loading = true
            val graph = MemoryGraphBuilder(currentOptions()).build(data)
            fullGraph = graph
            displayGraph = graph.filtered(enabledKinds)
            selection = selection?.takeIf { it in displayGraph.nodesById }
            replacePositions(runLayout(displayGraph, pinned = emptyMap()))
            camera = fitCamera()
            loading = false
        }
    }

    /** 当前生效的构建选项（场景层开关叠加在宿主基础选项上）。 */
    private fun currentOptions(): MemoryGraphBuilder.Options =
        builderOptions.copy(includeSceneNodes = sceneLayerEnabled)

    // ── 自动软刷新（变更信号驱动）──────────────────────────────────

    /**
     * 无感重拉快照（[debouncedChanges] 触发）：
     * - 失败**静默**保持现状（与首装的显式错误态不同 —— 后台刷新不打扰用户）；
     * - 保位增量重排：存活节点锚定、新节点邻域松弛落位 —— 探索中的空间认知
     *   不被重置；被删除节点的坐标自然淘汰；
     * - 选中保留（节点仍存在时）；探索态（有选中）相机稳定，巡航态重适配
     *   （新长出的记忆纳入视野）。
     */
    private suspend fun softReload() {
        if (loading) return  // 首装 / 手动刷新在途 → 让位（下一信号再同步）
        val data = runCatching { source.load() }.getOrNull() ?: return
        lastData = data
        layoutJob?.cancel()
        layoutJob = scope.launch {
            val graph = MemoryGraphBuilder(currentOptions()).build(data)
            fullGraph = graph
            val newDisplay = graph.filtered(enabledKinds)
            displayGraph = newDisplay
            selection = selection?.takeIf { it in newDisplay.nodesById }
            val pins = positions.filterKeys { it in newDisplay.nodesById }
            replacePositions(runLayout(newDisplay, pinned = pins, seed = FILTER_SEED))
            if (selection == null) camera = fitCamera()
        }
    }

    // ── 过滤 ═════════════════════════════════════════════════════

    /** 类型开关切换（保位增量布局：存活节点锚定、新增节点松弛落位）。 */
    fun toggleKind(kind: MemoryNodeKind) {
        val next = if (kind in enabledKinds) enabledKinds - kind else enabledKinds + kind
        if (next.isEmpty()) return  // 不允许清空（空图无意义）
        enabledKinds = next
        val full = fullGraph ?: return
        val newDisplay = full.filtered(next)
        displayGraph = newDisplay
        selection = selection?.takeIf { it in newDisplay.nodesById }
        // 保位增量：存活节点位置作为 pinned，新节点在其邻域松弛
        val pins = positions.filterKeys { it in newDisplay.nodesById }
        layoutJob?.cancel()
        layoutJob = scope.launch {
            replacePositions(runLayout(newDisplay, pinned = pins, seed = FILTER_SEED))
        }
    }

    // ── 选择 ═════════════════════════════════════════════════════

    /** 选中节点（null = 清除聚焦）。 */
    fun select(nodeId: String?) {
        selection = nodeId?.takeIf { it in displayGraph.nodesById }
    }

    /**
     * 记忆漫游跳转（详情面板的「溯源情景 / 派生宏 / 邻接实体」芯片）。
     *
     * 目标节点被类型过滤器隐藏时自动开启对应类型（过滤语义让位于用户
     * 显式跳转意图），再选中 —— Sheet 内容无缝切换到目标记忆。
     */
    fun navigateTo(nodeId: String) {
        if (nodeId in displayGraph.nodesById) {
            selection = nodeId
            return
        }
        val full = fullGraph ?: return
        val node = full.nodesById[nodeId] ?: return
        if (node.kind !in enabledKinds) toggleKind(node.kind)
        selection = nodeId  // toggleKind 之后设置（其内部会清理失效选中）
    }

    // ── 相机 ═════════════════════════════════════════════════════

    fun pan(screenDelta: androidx.compose.ui.geometry.Offset) {
        camera = camera.copy(offset = camera.offset + screenDelta)
    }

    fun zoom(factor: Float, screenFocus: androidx.compose.ui.geometry.Offset) {
        camera = GraphCamera.zoom(camera, factor, screenFocus)
    }

    /** 重置视图（包围盒适配视口居中）。 */
    fun resetView() {
        camera = fitCamera()
    }

    private fun fitCamera(): GraphCamera {
        val bounds = GraphGeometry.bounds(positions) { id ->
            displayGraph.nodesById[id]?.let { GraphGeometry.nodeRadius(it.weight) } ?: 10f
        }
        return GraphCamera.fit(camera, bounds, viewportPx)
    }

    // ── 布局 ═════════════════════════════════════════════════════

    /** 全量重排（「重新布局」按钮 —— 保种子确定性）。 */
    fun relayout() {
        layoutJob?.cancel()
        layoutJob = scope.launch {
            loading = true
            replacePositions(runLayout(displayGraph, pinned = emptyMap()))
            camera = fitCamera()
            loading = false
        }
    }

    /** 节点拖拽（世界坐标增量；即时生效不重排）。 */
    fun dragNode(nodeId: String, worldDelta: Vec2) {
        val p = positions[nodeId] ?: return
        positions[nodeId] = p + worldDelta
    }

    /** 拖拽结束（邻域松弛：锚点固定，邻居让位）。 */
    fun endNodeDrag(nodeId: String) {
        if (nodeId !in positions) return
        layoutJob?.cancel()
        layoutJob = scope.launch {
            val relaxed = withContext(Dispatchers.Default) {
                layoutEngine.relax(displayGraph, positions, nodeId)
            }
            replacePositions(relaxed)
        }
    }

    /** 布局计算（Default 调度器 + 挂起点让 UI 可取消）。 */
    private suspend fun runLayout(
        graph: MemoryGraph,
        pinned: Map<String, Vec2>,
        seed: Long = LAYOUT_SEED,
    ): Map<String, Vec2> = withContext(Dispatchers.Default) {
        layoutEngine.layout(graph, seed = seed, pinned = pinned)
    }

    /** 等待在途装载 / 布局任务完成（测试同步点；生产 UI 不需要）。 */
    suspend fun awaitIdle() {
        layoutJob?.join()
    }

    /** 原子替换全部坐标（clear+putAll 收进单快照 —— 不产生中间空帧）。 */
    private fun replacePositions(newPositions: Map<String, Vec2>) {
        Snapshot.withMutableSnapshot {
            positions.clear()
            positions.putAll(newPositions)
        }
    }

    private companion object {
        /** 主布局种子（固定 0 —— 同数据同布局的产品契约）。 */
        const val LAYOUT_SEED = 0L

        /** 过滤增量布局种子（区分场景，保各自可复现）。 */
        const val FILTER_SEED = 11L

        /**
         * 软刷新去抖窗口（ms）：任务执行期的写入风暴（逐帧采集 / 逐动作回写）
         * 合并为一次重拉节拍 —— 既不漏变更，也不逐写重排。
         */
        const val AUTO_REFRESH_DEBOUNCE_MS = 1500L

        val DEFAULT_ENABLED_KINDS: Set<MemoryNodeKind> = buildSet {
            addAll(MemoryNodeKind.DISPLAY_ORDER)
            remove(MemoryNodeKind.SCENE_NODE)  // 场景层默认关（数量大头）
        }
    }
}

/** Compose 侧便捷创建（remember + 自动首装）。 */
@androidx.compose.runtime.Composable
fun rememberMemoryGraphState(
    source: MemoryGraphSource,
    builderOptions: MemoryGraphBuilder.Options = MemoryGraphBuilder.Options(),
    layoutSpec: ForceDirectedLayout.LayoutSpec = ForceDirectedLayout.LayoutSpec(),
): MemoryGraphState {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val state = androidx.compose.runtime.remember(source) {
        MemoryGraphState(source, scope, builderOptions, layoutSpec)
    }
    androidx.compose.runtime.LaunchedEffect(state) { state.load() }
    return state
}
