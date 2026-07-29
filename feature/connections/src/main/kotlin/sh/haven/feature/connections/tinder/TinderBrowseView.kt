package sh.haven.feature.connections.tinder

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.feature.connections.ProfileStatus
import sh.haven.feature.connections.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TinderBrowseView(
    profiles: List<ConnectionProfile>,
    previewState: TinPreviewState,
    profileStatuses: Map<String, ProfileStatus>,
    filesStatuses: Map<String, ProfileStatus> = emptyMap(),
    onConnect: (ConnectionProfile) -> Unit,
    onOpenFiles: (ConnectionProfile) -> Unit = {},
    onRequestKill: (Pair<String, String>) -> Unit,
    initialPage: Int,
    onPageChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (profiles.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.connections_tinder_empty),
                style = MaterialTheme.typography.bodyMedium
            )
        }
        return
    }

    val pagerState = rememberPagerState(
        initialPage = initialPage.coerceIn(0, maxOf(0, profiles.size - 1))
    ) {
        profiles.size
    }

    LaunchedEffect(pagerState.currentPage) {
        onPageChanged(pagerState.currentPage)
    }

    LaunchedEffect(profiles.size) {
        if (pagerState.currentPage >= profiles.size && profiles.isNotEmpty()) {
            pagerState.scrollToPage(profiles.size - 1)
        }
    }

    // NestedScrollConnection to swallow vertical scrolls/flings inside the TV container
    val tvBlockConnection = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                return Offset(0f, available.y)
            }
            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                return Velocity(0f, available.y)
            }
        }
    }

    VerticalPager(
        state = pagerState,
        beyondViewportPageCount = 1,
        modifier = modifier.fillMaxSize()
    ) { pageIndex ->
        val profile = profiles.getOrNull(pageIndex) ?: return@VerticalPager
        val key = TinPreviewClient.tinSessionKeyOf(profile)
        val card = key?.let { previewState.cards[it] }

        Card(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // 1. Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val isAlive = card?.alive == true
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(if (isAlive) Color.Green else Color.Gray, CircleShape)
                    )
                    Text(
                        text = profile.label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "${pageIndex + 1}/${profiles.size}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (card != null) {
                        IconButton(onClick = { onRequestKill(key) }) {
                            Icon(
                                imageVector = Icons.Outlined.DeleteForever,
                                contentDescription = stringResource(R.string.connections_tinder_kill_desc),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }

                // 2. Subtitle details
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "${profile.username}@${profile.host}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        // Terminal status chip
                        val termStatus = profileStatuses[profile.id] ?: ProfileStatus.DISCONNECTED
                        val termColor = when (termStatus) {
                            ProfileStatus.CONNECTED -> Color(0xFF4CAF50)
                            ProfileStatus.ERROR -> Color(0xFFF44336)
                            ProfileStatus.CONNECTING, ProfileStatus.RECONNECTING -> Color(0xFFFFB300)
                            else -> MaterialTheme.colorScheme.outline
                        }
                        Card(
                            colors = CardDefaults.cardColors(containerColor = termColor.copy(alpha = 0.12f)),
                            border = androidx.compose.foundation.BorderStroke(1.dp, termColor.copy(alpha = 0.5f)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                if (termStatus == ProfileStatus.CONNECTING || termStatus == ProfileStatus.RECONNECTING) {
                                    androidx.compose.material3.CircularProgressIndicator(
                                        modifier = Modifier.size(10.dp),
                                        strokeWidth = 1.dp,
                                        color = termColor
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .background(termColor, CircleShape)
                                    )
                                }
                                Text(
                                    text = "Terminal: ${termStatus.name}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = termColor
                                )
                            }
                        }

                        // Files status chip
                        if (profile.isSsh) {
                            val filesStatus = filesStatuses[profile.id] ?: ProfileStatus.DISCONNECTED
                            val filesColor = when (filesStatus) {
                                ProfileStatus.CONNECTED -> Color(0xFF4CAF50)
                                ProfileStatus.ERROR -> Color(0xFFF44336)
                                ProfileStatus.CONNECTING, ProfileStatus.RECONNECTING -> Color(0xFFFFB300)
                                else -> MaterialTheme.colorScheme.outline
                            }
                            Card(
                                colors = CardDefaults.cardColors(containerColor = filesColor.copy(alpha = 0.12f)),
                                border = androidx.compose.foundation.BorderStroke(1.dp, filesColor.copy(alpha = 0.5f)),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    if (filesStatus == ProfileStatus.CONNECTING || filesStatus == ProfileStatus.RECONNECTING) {
                                        androidx.compose.material3.CircularProgressIndicator(
                                            modifier = Modifier.size(10.dp),
                                            strokeWidth = 1.dp,
                                            color = filesColor
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .size(6.dp)
                                                .background(filesColor, CircleShape)
                                        )
                                    }
                                    Text(
                                        text = "Files: ${filesStatus.name}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = filesColor
                                    )
                                }
                            }
                        }
                    }
                    val previewText = card?.preview ?: ""
                    if (previewText.isNotEmpty()) {
                        Text(
                            text = previewText,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    if (card?.running == true || card?.waitingAsk != null) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(top = 4.dp)
                        ) {
                            if (card.running) {
                                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9))) {
                                    Text(
                                        text = "Running",
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFF2E7D32)
                                    )
                                }
                            }
                            if (card.waitingAsk != null) {
                                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0))) {
                                    Text(
                                        text = "Wait: ${card.waitingAsk}",
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFFE65100)
                                    )
                                }
                            }
                        }
                    }
                }

                // 3. TV (snapshot text block)
                Box(
                    modifier = Modifier
                        .weight(0.55f)
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .background(Color(0xFF111111), RoundedCornerShape(8.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                        .padding(8.dp)
                        .nestedScroll(tvBlockConnection)
                ) {
                    val scrollState = rememberScrollState()
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                    ) {
                        val textToDisplay = card?.snapshotPlain ?: stringResource(R.string.connections_tinder_no_preview)
                        Text(
                            text = textToDisplay,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = Color.LightGray,
                            softWrap = false
                        )
                    }
                }

                // 4. Stale/Fresh label
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
                Text(
                    text = staleText,
                    style = MaterialTheme.typography.bodySmall,
                    color = staleColor,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )

                // 5. Connect Buttons
                if (profile.isSsh) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { onConnect(profile) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.connections_tinder_open_terminal_btn))
                        }
                        Button(
                            onClick = { onOpenFiles(profile) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.connections_tinder_open_files_btn))
                        }
                    }
                } else {
                    Button(
                        onClick = { onConnect(profile) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    ) {
                        Text(stringResource(R.string.connections_tinder_connect_btn))
                    }
                }
            }
        }
    }
}
