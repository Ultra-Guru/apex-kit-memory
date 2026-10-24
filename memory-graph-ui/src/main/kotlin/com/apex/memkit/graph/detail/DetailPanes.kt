package com.apex.memkit.graph.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apex.memkit.core.model.ChatFact
import com.apex.memkit.core.model.EpisodeStatus
import com.apex.memkit.graph.R
import com.apex.memkit.graph.graph.MemoryGraphBuilder
import com.apex.memkit.graph.graph.MemoryNodePayload

/**
 * 六类记忆的详情面板 —— 每类记忆「里面有什么」的完整呈现。
 *
 * 面板契约：只读（展示原始记忆数据 + 跳转芯片），不提供编辑 /
 * 删除 —— 破坏性操作留在宿主的列表管理页（职责分离）。
 */

// ══ 情景记忆面板 ═════════════════════════════════════════════════

@Composable
internal fun EpisodeDetailPane(payload: MemoryNodePayload.Episode, onNavigate: (String) -> Unit) {
    val e = payload.record
    Column {
        SectionLabel(stringResource(R.string.memgraph_episode_goal))
        Text(e.goal.ifBlank { "—" }, style = MaterialTheme.typography.bodyMedium)

        Row(
            modifier = Modifier.padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusBadge(e.status)
            Text(
                stringResource(R.string.memgraph_episode_actions, e.actionCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (e.manual) Text(
                stringResource(R.string.memgraph_episode_manual),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }

        Spacer(Modifier.height(6.dp))
        MetaRow(
            stringResource(R.string.memgraph_episode_time_range,
                formatTime(e.startedAt), e.finishedAt?.let { formatTime(it) }
                    ?: stringResource(R.string.memgraph_episode_running_now)),
            e.appPackage ?: "—",
        )
        e.appVersion?.let { MetaRow("version", it) }

        // 派生宏（点击跳转到对应宏节点）
        if (e.macroIds.isNotEmpty()) {
            SectionLabel(stringResource(R.string.memgraph_episode_derived_macros))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                e.macroIds.forEach { macroId ->
                    JumpChip("宏 $macroId") { onNavigate(MemoryGraphBuilder.Ids.macro(macroId)) }
                }
            }
        }

        // 观察到的 UI 节点（记忆的最小观测单位）
        if (e.observedFingerprints.isNotEmpty()) {
            SectionLabel(stringResource(R.string.memgraph_episode_observed, payload.observedNodeTexts.size))
            if (payload.observedNodeTexts.isEmpty()) {
                Text(
                    stringResource(R.string.memgraph_episode_observed_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(modifier = Modifier.height(((payload.observedNodeTexts.size.coerceAtMost(6)) * 30).dp)) {
                    items(payload.observedNodeTexts.take(12)) { text ->
                        Text(
                            "· $text",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(vertical = 3.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(status: EpisodeStatus) {
    val (labelRes, color) = when (status) {
        EpisodeStatus.RUNNING -> R.string.memgraph_episode_status_running to Color(0xFFE8A33D)
        EpisodeStatus.SUCCEEDED -> R.string.memgraph_episode_status_succeeded to Color(0xFF58C472)
        EpisodeStatus.FAILED -> R.string.memgraph_episode_status_failed to Color(0xFFE05C5C)
        EpisodeStatus.ABANDONED -> R.string.memgraph_episode_status_abandoned to Color(0xFF9AA3AD)
    }
    Surface(color = color.copy(alpha = 0.16f), shape = MaterialTheme.shapes.small) {
        Text(
            stringResource(labelRes),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

// ══ 宏技能面板 ═══════════════════════════════════════════════════

@Composable
internal fun MacroDetailPane(payload: MemoryNodePayload.Macro, onNavigate: (String) -> Unit) {
    val m = payload.macro
    val stats = m.stats
    Column {
        SectionLabel(stringResource(R.string.memgraph_macro_goal))
        Text(m.goal.ifBlank { "—" }, style = MaterialTheme.typography.bodyMedium)
        MetaRow("app", m.appPackage ?: "—")
        m.appVersion?.let { MetaRow("version", it) }

        // 能量条（0..10）
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.memgraph_macro_energy, stats.energy),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LinearProgressIndicator(
            progress = { (stats.energy / 10f).coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
        )

        if (stats.totalRuns() > 0) {
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(
                    R.string.memgraph_macro_success_rate,
                    (stats.successRate() * 100).toInt(),
                    stats.successCount, stats.failureCount,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                stringResource(R.string.memgraph_macro_never_run),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (stats.crystallized) {
            Text(
                stringResource(R.string.memgraph_macro_crystallized),
                style = MaterialTheme.typography.labelMedium,
                color = Color(0xFFB8860B),
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        // 动作序列时间线
        SectionLabel(stringResource(R.string.memgraph_macro_steps_title, m.steps.size))
        m.steps.forEachIndexed { i, step ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${i + 1}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    step.action.notation(),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    stringResource(R.string.memgraph_macro_step_anchor, step.nextAnchorFingerprints.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // 入口 / 出口指纹
        SectionLabel(stringResource(R.string.memgraph_macro_entry_points, m.entryFingerprints.size))
        Text(
            stringResource(R.string.memgraph_macro_exit_point) + ": " +
                m.exitFingerprint.take(16) + "…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
        )

        // 溯源情景（点击跳转）
        m.sourceEpisodeId?.let { epId ->
            SectionLabel(stringResource(R.string.memgraph_macro_source_episode))
            JumpChip(epId) { onNavigate(MemoryGraphBuilder.Ids.episode(epId)) }
        }
    }
}

// ══ 聊天事实面板 ═════════════════════════════════════════════════

@Composable
internal fun ChatFactDetailPane(payload: MemoryNodePayload.ChatFact) {
    val f = payload.fact
    Column {
        Text(f.content, style = MaterialTheme.typography.bodyMedium)

        KindBadge(f.kind)
        SourceBadge(f.source)

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                stringResource(R.string.memgraph_fact_importance, (f.importance * 100).toInt()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.memgraph_fact_credibility, (f.credibility * 100).toInt()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.memgraph_fact_accessed, f.accessCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LinearProgressIndicator(
            progress = { f.importance.coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
        )
        MetaRow("created", formatTime(f.createdAt))
        if (f.lastAccessedAt > 0) MetaRow("last access", formatTime(f.lastAccessedAt))
    }
}

@Composable
private fun KindBadge(kind: ChatFact.Kind) {
    val res = when (kind) {
        ChatFact.Kind.PROFILE -> R.string.memgraph_fact_kind_profile
        ChatFact.Kind.STATE -> R.string.memgraph_fact_kind_state
        ChatFact.Kind.MILESTONE -> R.string.memgraph_fact_kind_milestone
    }
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
        Text(
            stringResource(res),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun SourceBadge(source: ChatFact.Source) {
    val res = when (source) {
        ChatFact.Source.HEURISTIC -> R.string.memgraph_fact_source_heuristic
        ChatFact.Source.DISTILLED -> R.string.memgraph_fact_source_distilled
        ChatFact.Source.MANUAL -> R.string.memgraph_fact_source_manual
        ChatFact.Source.MIGRATED -> R.string.memgraph_fact_source_migrated
    }
    Text(
        stringResource(res),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// ══ 知识实体面板 ═════════════════════════════════════════════════

@Composable
internal fun EntityDetailPane(payload: MemoryNodePayload.Entity, onNavigate: (String) -> Unit) {
    val entity = payload.entity
    Column {
        MetaRow(stringResource(R.string.memgraph_entity_type), entity.entityType)

        SectionLabel(stringResource(R.string.memgraph_entity_observations, entity.observations.size))
        if (entity.observations.isEmpty()) {
            Text(
                stringResource(R.string.memgraph_entity_no_observations),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            entity.observations.take(15).forEach { obs ->
                Text(
                    "· $obs",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 2.dp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (payload.incoming.isNotEmpty()) {
            SectionLabel(stringResource(R.string.memgraph_entity_relations_in))
            payload.incoming.take(8).forEach { rel ->
                RelationRow("${rel.from} —${rel.relationType}→", rel.from) {
                    onNavigate(MemoryGraphBuilder.Ids.entity(rel.from))
                }
            }
        }
        if (payload.outgoing.isNotEmpty()) {
            SectionLabel(stringResource(R.string.memgraph_entity_relations_out))
            payload.outgoing.take(8).forEach { rel ->
                RelationRow("—${rel.relationType}→ ${rel.to}", rel.to) {
                    onNavigate(MemoryGraphBuilder.Ids.entity(rel.to))
                }
            }
        }
    }
}

@Composable
private fun RelationRow(text: String, targetName: String, onJump: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        JumpChip(stringResource(R.string.memgraph_detail_jump), onJump)
    }
}

// ══ 场景节点面板 ═════════════════════════════════════════════════

@Composable
internal fun SceneNodeDetailPane(payload: MemoryNodePayload.SceneNode) {
    val n = payload.node
    Column {
        MetaRow(stringResource(R.string.memgraph_scene_role), n.role.name)
        n.resourceId?.let { MetaRow(stringResource(R.string.memgraph_scene_resource_id), it) }
        n.className?.let { MetaRow(stringResource(R.string.memgraph_scene_class), it) }
        MetaRow("app", n.appPackage ?: "—")
        n.appVersion?.let { MetaRow("version", it) }
        Text(
            stringResource(R.string.memgraph_scene_occurrences, n.occurrences, n.energy),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
        if (n.firstSeenAt > 0 || n.lastSeenAt > 0) {
            Text(
                stringResource(R.string.memgraph_scene_seen_range, formatTime(n.firstSeenAt), formatTime(n.lastSeenAt)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SectionLabel("fingerprint")
        Text(
            n.fingerprint,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ══ 应用簇面板 ═══════════════════════════════════════════════════

@Composable
internal fun AppClusterDetailPane(payload: MemoryNodePayload.AppCluster) {
    val p = payload
    Column {
        SectionLabel(stringResource(R.string.memgraph_cluster_members))
        Text(
            stringResource(R.string.memgraph_cluster_stats, p.nodeCount, p.episodeCount, p.macroCount),
            style = MaterialTheme.typography.bodyMedium,
        )
        p.appPackage ?: run {
            Text(
                stringResource(R.string.memgraph_cluster_unknown),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (p.topNodes.isNotEmpty()) {
            SectionLabel(stringResource(R.string.memgraph_cluster_top_nodes))
            p.topNodes.forEach { n ->
                Text(
                    "· ${n.displayText()}（${n.occurrences}）",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }
        if (p.episodeGoals.isNotEmpty()) {
            SectionLabel(stringResource(R.string.memgraph_cluster_recent_goals))
            p.episodeGoals.forEach { goal ->
                Text(
                    "· $goal",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }
        if (p.topNodes.isEmpty() && p.episodeGoals.isEmpty()) {
            Text(
                stringResource(R.string.memgraph_cluster_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
