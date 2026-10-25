package com.apex.memkit.graph.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apex.memkit.graph.R
import com.apex.memkit.graph.canvas.GraphPalette
import com.apex.memkit.graph.graph.EdgeLabels
import com.apex.memkit.graph.graph.MemoryEdgeKind
import com.apex.memkit.graph.graph.MemoryGraph
import com.apex.memkit.graph.graph.MemoryNode
import com.apex.memkit.graph.graph.MemoryNodeKind
import com.apex.memkit.graph.graph.MemoryNodePayload

/**
 * 节点详情 Sheet —— 「点击节点看里面有什么记忆」的容器。
 *
 * 结构：类别徽标 + 标题行（类别名 + 关联数）→ 按载荷分派的六类详情面板
 * → 关联关系区（每条连线的具体关联 + 点击漫游）。
 * 面板内的「跳转」芯片（溯源情景 / 派生宏 / 邻接实体 / 观察节点）直接调
 * [onNavigate] —— 图谱上的记忆漫游入口。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodeDetailSheet(
    node: MemoryNode,
    graph: MemoryGraph,
    palette: GraphPalette,
    onNavigate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            DetailHeader(node = node, graph = graph, palette = palette)
            Spacer(Modifier.height(12.dp))
            when (val payload = node.payload) {
                is MemoryNodePayload.Episode ->
                    EpisodeDetailPane(payload, onNavigate = onNavigate)
                is MemoryNodePayload.Macro ->
                    MacroDetailPane(payload, onNavigate = onNavigate)
                is MemoryNodePayload.ChatFact ->
                    ChatFactDetailPane(payload)
                is MemoryNodePayload.Entity ->
                    EntityDetailPane(payload, onNavigate = onNavigate)
                is MemoryNodePayload.SceneNode ->
                    SceneNodeDetailPane(payload)
                is MemoryNodePayload.AppCluster ->
                    AppClusterDetailPane(payload)
            }
            RelationsSection(node = node, graph = graph, palette = palette, onNavigate = onNavigate)
            Spacer(Modifier.height(28.dp)) // 导航条避让
        }
    }
}

/** 头部：类别色点 + 类别名 + 关联数 + 主标签。 */
@Composable
private fun DetailHeader(node: MemoryNode, graph: MemoryGraph, palette: GraphPalette) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            color = palette.nodeColor(node.kind),
            shape = CircleShape,
            modifier = Modifier.size(34.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = palette.nodeGlyph(node.kind),
                    style = MaterialTheme.typography.titleMedium,
                    color = androidx.compose.ui.graphics.Color.White,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                text = kindLabel(node.kind) + " · " +
                    stringResource(R.string.memgraph_detail_neighbors, graph.neighborsOf(node.id).size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = node.label,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    node.subtitle?.let {
        Spacer(Modifier.height(4.dp))
        Text(
            text = it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 类别名（过滤条 / 详情头共用口径）。 */
@Composable
fun kindLabel(kind: MemoryNodeKind): String = stringResource(
    when (kind) {
        MemoryNodeKind.EPISODE -> R.string.memgraph_kind_episode
        MemoryNodeKind.MACRO -> R.string.memgraph_kind_macro
        MemoryNodeKind.CHAT_FACT -> R.string.memgraph_kind_chat_fact
        MemoryNodeKind.ENTITY -> R.string.memgraph_kind_entity
        MemoryNodeKind.SCENE_NODE -> R.string.memgraph_kind_scene_node
        MemoryNodeKind.APP_CLUSTER -> R.string.memgraph_kind_app_cluster
    },
)

// ══ 详情面板的通用小件 ═══════════════════════════════════════════

/** 区块标题（详情面板的统一节段头）。 */
@Composable
internal fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 14.dp, bottom = 6.dp),
    )
}

/** 元数据行（label: value 的紧凑双列）。 */
@Composable
internal fun MetaRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}

/** 可跳转芯片（记忆漫游入口：溯源情景 / 派生宏 / 邻接实体）。 */
@Composable
internal fun JumpChip(text: String, onClick: () -> Unit) {
    androidx.compose.material3.AssistChip(
        onClick = onClick,
        label = {
            Text(text, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
    )
}

// ══ 关联关系区（详情 Sheet 底部统一段落）════════════════════

/** 详情面板展示的关联行上限（防大度数节点撑爆 Sheet）。 */
private const val RELATION_ROWS_MAX = 12

/**
 * 关联关系区 —— 本节点每条连线的「具体关联」清单。
 *
 * 数据源 [MemoryGraph.edgesOf]；每行呈现：对端类别色点 + 对端标签 +
 * 具体关联标签（[EdgeLabels.labelOf]：知识关系用 relationType，其余五类
 * 用类型语义名，合并边 ×N）+ 方向箭头（出边 → 对端 / 入边 ← 对端）。
 * 点行即漫游（对端被过滤时自动点亮其类型 —— 与跳转芯片同款行为）。
 */
@Composable
private fun RelationsSection(
    node: MemoryNode,
    graph: MemoryGraph,
    palette: GraphPalette,
    onNavigate: (String) -> Unit,
) {
    val edges = graph.edgesOf(node.id)
    SectionLabel(stringResource(R.string.memgraph_detail_relations, edges.size))
    if (edges.isEmpty()) {
        Text(
            stringResource(R.string.memgraph_detail_relations_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val kindNames = edgeKindNames()
    edges.take(RELATION_ROWS_MAX).forEach { (edge, otherId) ->
        val other = graph.nodesById[otherId] ?: return@forEach
        RelationRow(
            other = other,
            relation = EdgeLabels.labelOf(edge, kindNames)
                ?: stringResource(R.string.memgraph_edge_label_relation),
            outgoing = edge.sourceId == node.id,
            color = palette.edgeColor(edge.kind),
            onClick = { onNavigate(otherId) },
        )
    }
    if (edges.size > RELATION_ROWS_MAX) {
        Text(
            stringResource(R.string.memgraph_detail_relations_more, edges.size - RELATION_ROWS_MAX),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** 六类边的类型语义名（与画布同源口径；知识关系边优先用自身 relationType）。 */
@Composable
private fun edgeKindNames(): Map<MemoryEdgeKind, String> = mapOf(
    MemoryEdgeKind.DERIVED_MACRO to stringResource(R.string.memgraph_edge_label_derived),
    MemoryEdgeKind.MACRO_CHAIN to stringResource(R.string.memgraph_edge_label_chain),
    MemoryEdgeKind.OBSERVED_IN to stringResource(R.string.memgraph_edge_label_observed),
    MemoryEdgeKind.APP_SCOPE to stringResource(R.string.memgraph_edge_label_scope),
    MemoryEdgeKind.GRAPH_RELATION to stringResource(R.string.memgraph_edge_label_relation),
    MemoryEdgeKind.ALIAS_BRIDGE to stringResource(R.string.memgraph_edge_label_alias),
)

/** 单条关联行：对端色点 + 对端标签 —具体关联→/←（点击漫游）。 */
@Composable
private fun RelationRow(
    other: MemoryNode,
    relation: String,
    outgoing: Boolean,
    color: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(color = color, shape = CircleShape, modifier = Modifier.size(8.dp)) {}
        Spacer(Modifier.width(8.dp))
        Text(
            text = other.label,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = (if (outgoing) " —$relation→ " else " ←$relation— "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = kindLabel(other.kind),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 时间格式化（统一口径：MM-dd HH:mm；同年内省略年份）。 */
internal fun formatTime(epochMs: Long): String {
    if (epochMs <= 0) return "—"
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = epochMs }
    val now = java.util.Calendar.getInstance()
    val sameYear = cal.get(java.util.Calendar.YEAR) == now.get(java.util.Calendar.YEAR)
    return java.text.SimpleDateFormat(
        if (sameYear) "MM-dd HH:mm" else "yyyy-MM-dd",
        java.util.Locale.getDefault(),
    ).format(java.util.Date(epochMs))
}
