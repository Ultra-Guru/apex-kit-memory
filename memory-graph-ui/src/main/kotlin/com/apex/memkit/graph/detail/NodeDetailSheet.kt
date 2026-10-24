package com.apex.memkit.graph.detail

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
import com.apex.memkit.graph.graph.MemoryGraph
import com.apex.memkit.graph.graph.MemoryNode
import com.apex.memkit.graph.graph.MemoryNodeKind
import com.apex.memkit.graph.graph.MemoryNodePayload

/**
 * 节点详情 Sheet —— 「点击节点看里面有什么记忆」的容器。
 *
 * 结构：类别徽标 + 标题行（类别名 + 关联数）→ 按载荷分派的六类详情面板。
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
