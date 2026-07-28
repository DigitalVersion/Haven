package sh.haven.feature.connections.tinder

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.feature.connections.ProfileStatus
import sh.haven.feature.connections.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun GridBrowseView(
    profiles: List<ConnectionProfile>,
    previewState: TinPreviewState,
    profileStatuses: Map<String, ProfileStatus>,
    onConnect: (ConnectionProfile) -> Unit,
    onRequestKill: (Pair<String, String>) -> Unit,
    gridState: LazyGridState,
    isFiltering: Boolean,
    modifier: Modifier = Modifier,
) {
    val timeText = if (previewState.lastFetchAt != null) {
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        sdf.format(Date(previewState.lastFetchAt))
    } else ""
    val staleText = if (previewState.lastFetchOk) {
        "Tin: updated $timeText"
    } else {
        if (timeText.isNotEmpty()) "Tin offline — cache $timeText" else "Tin API unreachable"
    }
    val staleColor = if (previewState.lastFetchOk) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error

    Column(modifier = modifier.fillMaxSize()) {
        // Thin top banner for fetch status
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(staleColor.copy(alpha = 0.1f))
                .padding(vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = staleText,
                style = MaterialTheme.typography.bodySmall,
                color = staleColor
            )
        }

        if (profiles.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = if (isFiltering) {
                        stringResource(R.string.connections_grid_empty_filter)
                    } else {
                        stringResource(R.string.connections_tinder_empty)
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                state = gridState,
                contentPadding = PaddingValues(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(profiles, key = { it.id }) { profile ->
                    GridCellItem(
                        profile = profile,
                        previewState = previewState,
                        onConnect = onConnect,
                        onRequestKill = onRequestKill
                    )
                }
            }
        }
    }
}

@Composable
fun GridCellItem(
    profile: ConnectionProfile,
    previewState: TinPreviewState,
    onConnect: (ConnectionProfile) -> Unit,
    onRequestKill: (Pair<String, String>) -> Unit
) {
    val key = TinPreviewClient.tinSessionKeyOf(profile)
    val card = key?.let { previewState.cards[it] }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(170.dp)
            .clickable { onConnect(profile) },
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Header Row: Status dot + Label + Kill Icon
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val isAlive = card?.alive == true
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(if (isAlive) Color.Green else Color.Gray, CircleShape)
                )
                Text(
                    text = profile.label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (card != null) {
                    IconButton(
                        onClick = { onRequestKill(key) },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.DeleteForever,
                            contentDescription = stringResource(R.string.connections_tinder_kill_desc),
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            // Host details
            Text(
                text = "${profile.username}@${profile.host.substringBefore('.')}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            // Preview Text (meaningful lines or fallback)
            val tailLines = remember(card?.snapshotPlain) { meaningfulTail(card?.snapshotPlain) }
            val displayPreview = if (tailLines.isNotEmpty()) {
                tailLines.joinToString("\n")
            } else {
                card?.preview ?: stringResource(R.string.connections_grid_no_preview)
            }
            
            Text(
                text = displayPreview,
                fontFamily = FontFamily.Monospace,
                fontSize = 8.sp,
                lineHeight = 10.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            )

            // Running/Waiting badges
            if (card?.running == true || card?.waitingAsk != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (card.running) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9)),
                            shape = RoundedCornerShape(2.dp)
                        ) {
                            Text(
                                text = "Run",
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 8.sp,
                                color = Color(0xFF2E7D32)
                            )
                        }
                    }
                    if (card.waitingAsk != null) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0)),
                            shape = RoundedCornerShape(2.dp)
                        ) {
                            Text(
                                text = "Wait",
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 8.sp,
                                color = Color(0xFFE65100),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}
