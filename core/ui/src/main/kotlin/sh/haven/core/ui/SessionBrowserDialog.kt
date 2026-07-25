package sh.haven.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * One tile in [SessionBrowserDialog]'s grid: a live multiplexer session on
 * the host, plus (once fetched) a snippet of its actual terminal content.
 *
 * [preview] is null in two distinct situations the UI must tell apart —
 * still loading vs. genuinely unavailable — hence the separate
 * [previewLoading] flag rather than overloading null for both.
 */
data class SessionPreviewTile(
    val name: String,
    val preview: String? = null,
    val previewLoading: Boolean = true,
)

/**
 * Grid variant of [SessionPickerDialog] (v1: session-preview-grid). Where
 * the plain picker is a vertical list of names, this shows one card per
 * live session with a few lines of its actual terminal content — Batin's
 * stated philosophy: "the name alone isn't enough to know what's inside;
 * you have to SEE it" (inspired by the Tin Mobile web app's session grid,
 * not a clone of it).
 *
 * Deliberately narrow for v1:
 *  - One host/connection at a time — no cross-host tabs (that's the next
 *    round's polish, see the skill/task notes this shipped against).
 *  - Preview refresh is on-open only, no polling — [onRefresh] exists for
 *    an explicit manual re-fetch, nothing ticks on its own.
 *  - No kill/rename actions (unlike [SessionPickerDialog]) — this is a
 *    read-then-attach browser, not session management. Use the existing
 *    picker for that.
 *
 * This is intentionally a separate composable rather than an extension of
 * [SessionPickerDialog]: the two have different shapes (grid vs. list),
 * different information density per row, and — most importantly — adding
 * this as a new call site keeps every existing [SessionPickerDialog] caller
 * completely unchanged (backward compatible for profiles/managers that
 * don't opt into the browse-with-preview entry point).
 */
@Composable
fun SessionBrowserDialog(
    title: String,
    sessions: List<SessionPreviewTile>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    cancelLabel: String,
    onRefresh: (() -> Unit)? = null,
    loadingLabel: String = "Loading…",
    noPreviewLabel: String = "No preview available",
    emptyLabel: String = "No live sessions",
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    if (onRefresh != null) {
                        IconButton(onClick = onRefresh) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = cancelLabel)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))

                if (sessions.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(emptyLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 160.dp),
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(sessions, key = { it.name }) { tile ->
                            SessionPreviewCard(
                                tile = tile,
                                onClick = { onSelect(tile.name) },
                                loadingLabel = loadingLabel,
                                noPreviewLabel = noPreviewLabel,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(cancelLabel) }
                }
            }
        }
    }
}

@Composable
private fun SessionPreviewCard(
    tile: SessionPreviewTile,
    onClick: () -> Unit,
    loadingLabel: String = "Loading…",
    noPreviewLabel: String = "No preview available",
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Status dot — every name here came back from a live "list
                // sessions" exec, so v1 always renders it green. A dead/stale
                // distinction (grey dot) is future work, not something this
                // grid can know without a second round-trip per tile.
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = tile.name,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    tile.previewLoading -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            loadingLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    tile.preview.isNullOrBlank() -> Text(
                        noPreviewLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> Text(
                        text = tile.preview,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
