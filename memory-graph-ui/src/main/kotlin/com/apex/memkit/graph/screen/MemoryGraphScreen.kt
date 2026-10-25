package com.apex.memkit.graph.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apex.memkit.graph.R
import com.apex.memkit.graph.canvas.GraphPalette
import com.apex.memkit.graph.canvas.MemoryGraphCanvas
import com.apex.memkit.graph.canvas.defaultGraphPalette
import com.apex.memkit.graph.detail.NodeDetailSheet
import com.apex.memkit.graph.detail.kindLabel
import com.apex.memkit.graph.graph.MemoryGraphSource
import com.apex.memkit.graph.graph.MemoryNodeKind

/**
 * 记忆图谱整页 —— 自带 Scaffold 的独立入口（宿主可导航直达）。
 *
 * 结构：TopAppBar（标题 + 图例 + 刷新）→ 统计条 → 过滤 chips + 搜索 →
 * 画布（含加载 / 空 / 错误态覆盖层与缩放控制）→ 选中节点详情 Sheet。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryGraphScreen(
    source: MemoryGraphSource,
    modifier: Modifier = Modifier,
    contentWindowInsets: androidx.compose.foundation.layout.WindowInsets =
        androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
) {
    val state = rememberMemoryGraphState(source)
    val palette = defaultGraphPalette()
    var showLegend by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = contentWindowInsets,
        topBar = {
            TopAppBar(
                windowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
                title = {
                    Column {
                        Text(stringResource(R.string.memgraph_title))
                        Text(
                            stringResource(R.string.memgraph_subtitle),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showLegend = true }) {
                        Icon(Icons.AutoMirrored.Outlined.HelpOutline, contentDescription = stringResource(R.string.memgraph_legend))
                    }
                    IconButton(onClick = { state.load() }) {
                        Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.memgraph_action_refresh))
                    }
                },
            )
        },
    ) { padding ->
        MemoryGraphContent(
            state = state,
            palette = palette,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        )
    }

    if (showLegend) {
        LegendDialog(palette = palette, onDismiss = { showLegend = false })
    }
}

/**
 * 图谱内容体（无 Scaffold / 无顶栏）—— 宿主嵌入既有页面的形态
 * （例：宿主记忆页在列表视图与图谱视图之间切换）。
 */
@Composable
fun MemoryGraphContent(
    state: MemoryGraphState,
    modifier: Modifier = Modifier,
    palette: com.apex.memkit.graph.canvas.GraphPalette = defaultGraphPalette(),
) {
    Column(modifier = modifier) {
        StatsBar(state)
        FilterBar(state, palette)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .onGloballyPositioned { coords ->
                    val s = coords.size
                    state.viewportPx = Offset(s.width.toFloat(), s.height.toFloat())
                },
        ) {
            MemoryGraphCanvas(
                graph = state.displayGraph,
                positions = state.positions,
                camera = state.camera,
                selection = state.selection,
                neighborIds = state.neighbors,
                searchMatches = state.searchMatches,
                palette = palette,
                modifier = Modifier.fillMaxSize(),
                onNodeTap = { state.select(it) },
                onNodeDrag = { id, delta -> state.dragNode(id, delta) },
                onNodeDragEnd = { state.endNodeDrag(it) },
                onPan = { state.pan(it) },
                onZoom = { f, focus -> state.zoom(f, focus) },
            )

            // 缩放 / 视图控制（右下角浮层）
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalAlignment = Alignment.End,
            ) {
                FloatingToolButton(Icons.Filled.Add, stringResource(R.string.memgraph_action_zoom_in)) {
                    state.zoom(1.25f, Offset(state.viewportPx.x / 2f, state.viewportPx.y / 2f))
                }
                FloatingToolButton(Icons.Filled.Remove, stringResource(R.string.memgraph_action_zoom_out)) {
                    state.zoom(0.8f, Offset(state.viewportPx.x / 2f, state.viewportPx.y / 2f))
                }
                FloatingToolButton(Icons.Filled.CenterFocusStrong, stringResource(R.string.memgraph_action_reset_view)) {
                    state.resetView()
                }
                FloatingToolButton(Icons.Filled.AutoAwesome, stringResource(R.string.memgraph_action_relayout)) {
                    state.relayout()
                }
            }

            // 状态覆盖层
            StateOverlays(state)
        }
    }

    // 详情 Sheet（选中节点 → 记忆内幕）
    val selected = state.selectedNode
    if (selected != null) {
        NodeDetailSheet(
            node = selected,
            graph = state.displayGraph,
            palette = palette,
            onNavigate = { state.navigateTo(it) },
            onDismiss = { state.select(null) },
        )
    }
}

// ══ 统计条 ═══════════════════════════════════════════════════════

@Composable
private fun StatsBar(state: MemoryGraphState) {
    val stats = state.displayGraph.stats
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StatText(stringResource(R.string.memgraph_stat_episodes, stats.episodes))
        StatText(stringResource(R.string.memgraph_stat_macros, stats.macros))
        StatText(stringResource(R.string.memgraph_stat_facts, stats.facts))
        StatText(stringResource(R.string.memgraph_stat_entities, stats.entities))
        if (stats.sceneNodes > 0) StatText(stringResource(R.string.memgraph_stat_scene_nodes, stats.sceneNodes))
        if (stats.appClusters > 0) StatText(stringResource(R.string.memgraph_stat_clusters, stats.appClusters))
    }
}

@Composable
private fun StatText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// ══ 过滤条 + 搜索 ═════════════════════════════════════════════════

@Composable
private fun FilterBar(state: MemoryGraphState, palette: com.apex.memkit.graph.canvas.GraphPalette) {
    Column(modifier = Modifier.padding(horizontal = 12.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(MemoryNodeKind.DISPLAY_ORDER) { kind ->
                val selected = kind in state.enabledKinds
                FilterChip(
                    selected = selected,
                    onClick = { state.toggleKind(kind) },
                    label = { Text(kindLabel(kind)) },
                    leadingIcon = {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(
                                    palette.nodeColor(kind).copy(alpha = if (selected) 1f else 0.35f),
                                    CircleShape,
                                ),
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    ),
                )
            }
            item {
                FilterChip(
                    selected = state.sceneLayerEnabled,
                    onClick = { state.setSceneLayer(!state.sceneLayerEnabled) },
                    label = { Text(stringResource(R.string.memgraph_scene_layer)) },
                    leadingIcon = {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(
                                    palette.sceneNodeColor.copy(alpha = if (state.sceneLayerEnabled) 1f else 0.35f),
                                    CircleShape,
                                ),
                        )
                    },
                )
            }
        }
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = { state.searchQuery = it },
            placeholder = { Text(stringResource(R.string.memgraph_search_hint), style = MaterialTheme.typography.bodySmall) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp, bottom = 8.dp)
                .height(56.dp),
            shape = MaterialTheme.shapes.extraLarge,
        )
        val matches = state.searchMatches
        if (state.searchQuery.isNotBlank() && matches.isEmpty() && !state.loading) {
            Text(
                stringResource(R.string.memgraph_search_result, 0),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ══ 浮动工具钮 ═══════════════════════════════════════════════════

@Composable
private fun FloatingToolButton(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        tonalElevation = 2.dp,
        shadowElevation = 2.dp,
    ) {
        IconButton(onClick = onClick) {
            Icon(icon, contentDescription = desc, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ══ 状态覆盖层 ═══════════════════════════════════════════════════

@Composable
private fun StateOverlays(state: MemoryGraphState) {
    if (state.loading) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.memgraph_loading), style = MaterialTheme.typography.bodySmall)
            }
        }
    } else if (state.loadError != null) {
        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    stringResource(R.string.memgraph_load_error),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    state.loadError ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = { state.load() }) {
                    Text(stringResource(R.string.memgraph_retry))
                }
            }
        }
    } else if (state.displayGraph.stats.isEmpty) {
        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(32.dp),
            ) {
                Text("✦", fontSize = 42.sp, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.memgraph_empty_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.memgraph_empty_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ══ 图例对话框 ═══════════════════════════════════════════════════

@Composable
internal fun LegendDialog(
    palette: com.apex.memkit.graph.canvas.GraphPalette,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.memgraph_dismiss)) } },
        title = { Text(stringResource(R.string.memgraph_legend)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                LegendSection(stringResource(R.string.memgraph_legend_nodes))
                MemoryNodeKind.DISPLAY_ORDER.forEach { kind ->
                    LegendRow(color = palette.nodeColor(kind), glyph = palette.nodeGlyph(kind), label = kindLabel(kind))
                }
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                LegendSection(stringResource(R.string.memgraph_legend_edges))
                LegendLine(palette.derivedEdgeColor, stringResource(R.string.memgraph_legend_edge_derived))
                LegendLine(palette.chainEdgeColor, stringResource(R.string.memgraph_legend_edge_chain))
                LegendLine(palette.observedEdgeColor, stringResource(R.string.memgraph_legend_edge_observed))
                LegendLine(palette.scopeEdgeColor, stringResource(R.string.memgraph_legend_edge_scope))
                LegendLine(palette.relationEdgeColor, stringResource(R.string.memgraph_legend_edge_relation))
                LegendLine(palette.aliasEdgeColor, stringResource(R.string.memgraph_legend_edge_alias))
                LegendText(stringResource(R.string.memgraph_legend_edge_labels))
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                LegendSection(stringResource(R.string.memgraph_legend_gestures))
                LegendText(stringResource(R.string.memgraph_legend_gesture_pan))
                LegendText(stringResource(R.string.memgraph_legend_gesture_drag))
                LegendText(stringResource(R.string.memgraph_legend_gesture_zoom))
                LegendText(stringResource(R.string.memgraph_legend_gesture_tap))
                LegendText(stringResource(R.string.memgraph_legend_gesture_focus))
                LegendText(stringResource(R.string.memgraph_legend_badge_crystallized))
            }
        },
    )
}

@Composable
private fun LegendSection(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
    )
}

@Composable
private fun LegendRow(color: Color, glyph: String, label: String) {
    Row(
        modifier = Modifier.padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .background(color.copy(alpha = 0.25f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .background(color, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(glyph, fontSize = 8.sp, color = Color.White)
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun LegendLine(color: Color, label: String) {
    Row(
        modifier = Modifier.padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(28.dp)
                .height(3.dp)
                .background(color),
        )
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun LegendText(label: String) {
    Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp))
}
