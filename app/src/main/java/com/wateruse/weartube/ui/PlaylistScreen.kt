package com.wateruse.weartube.ui

import com.wateruse.weartube.data.friendlyError
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.wateruse.weartube.data.Playlist
import com.wateruse.weartube.data.Repo
import com.wateruse.weartube.data.Store
import com.wateruse.weartube.data.Video
import kotlinx.coroutines.launch

@Composable
fun PlaylistScreen(playlistId: String, onOpenVideo: (String) -> Unit) {
    val state = rememberScalingLazyListState(initialCenterItemIndex = 0)
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var videos by remember { mutableStateOf<List<Video>>(emptyList()) }
    var continuation by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(playlistId, reloadKey) {
        loading = true
        error = null
        try {
            val (t, page) = Repo.playlist(playlistId)
            title = t
            videos = page.items
            continuation = page.continuation
        } catch (e: Exception) {
            error = friendlyError(e, "Couldn't load playlist")
        }
        loading = false
    }

    Scaffold(
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scalingLazyListState = state) },
    ) {
        ScalingLazyColumn(
            state = state,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 28.dp, bottom = 28.dp, start = 8.dp, end = 8.dp),
            autoCentering = null,
        ) {
            if (title.isNotEmpty()) {
                item {
                    Text(
                        title,
                        style = MaterialTheme.typography.title3,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
            if (loading) {
                item { CenteredSpinner() }
            } else if (error != null) {
                item { ErrorRetry(error!!) { reloadKey++ } }
            } else {
                if (videos.isNotEmpty()) {
                    item {
                        Chip(
                            onClick = {
                                val first = videos.first()
                                NavCache.put(first)
                                NavCache.setQueue(videos)
                                onOpenVideo(first.id)
                            },
                            label = {
                                Text("Play all", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                            },
                            icon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                            colors = ChipDefaults.primaryChipColors(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    item {
                        var saved by remember(playlistId) { mutableStateOf(Store.isPlaylistSaved(playlistId)) }
                        Chip(
                            onClick = {
                                saved = Store.togglePlaylist(
                                    Playlist(
                                        id = playlistId,
                                        title = title.ifEmpty { "Playlist" },
                                        count = "${videos.size}+",
                                        thumb = videos.firstOrNull()?.thumb.orEmpty(),
                                    )
                                )
                            },
                            label = {
                                Text(
                                    if (saved) "Saved to Library ✓" else "Save to Library",
                                    modifier = Modifier.fillMaxWidth(),
                                    textAlign = TextAlign.Center,
                                )
                            },
                            colors = ChipDefaults.secondaryChipColors(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                items(videos, key = { it.id }) { v ->
                    VideoCard(v) { NavCache.put(v); onOpenVideo(v.id) }
                }
                if (continuation != null) {
                    item {
                        MoreChip(loadingMore) {
                            val token = continuation ?: return@MoreChip
                            loadingMore = true
                            scope.launch {
                                try {
                                    val page = Repo.channelMore(token)
                                    videos = (videos + page.items).distinctBy { it.id }
                                    continuation = page.continuation
                                } catch (_: Exception) {
                                    continuation = null
                                }
                                loadingMore = false
                            }
                        }
                    }
                }
            }
        }
    }
}
