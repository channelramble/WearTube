package com.wateruse.weartube.ui

import com.wateruse.weartube.data.friendlyError
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.wateruse.weartube.data.Repo
import com.wateruse.weartube.data.SearchItem
import kotlinx.coroutines.launch

@Composable
fun ResultsScreen(
    query: String,
    onOpenVideo: (String) -> Unit,
    onOpenChannel: (String) -> Unit,
    onOpenPlaylist: (String) -> Unit,
    onSearch: (String) -> Unit,
) {
    val state = rememberScalingLazyListState(initialCenterItemIndex = 0)
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<SearchItem>>(emptyList()) }
    var continuation by remember { mutableStateOf<String?>(null) }
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(query, reloadKey) {
        loading = true
        error = null
        try {
            val page = Repo.search(query)
            items = page.items
            continuation = page.continuation
        } catch (e: Exception) {
            error = friendlyError(e, "Search failed")
        }
        loading = false
        suggestions = Repo.suggestions(query).filterNot { it.equals(query, ignoreCase = true) }.take(4)
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
            item { SectionHeader(query) }
            if (loading) {
                item { CenteredSpinner() }
            } else if (error != null) {
                item { ErrorRetry(error!!) { reloadKey++ } }
            } else {
                items.forEachIndexed { idx, it ->
                    when (it) {
                        is SearchItem.V -> item(key = "v$idx${it.video.id}") {
                            VideoCard(it.video) { NavCache.put(it.video); onOpenVideo(it.video.id) }
                        }
                        is SearchItem.C -> item(key = "c$idx${it.channel.id}") {
                            ChannelRow(it.channel) { onOpenChannel(it.channel.id) }
                        }
                        is SearchItem.P -> item(key = "p$idx${it.playlist.id}") {
                            PlaylistCard(it.playlist) { onOpenPlaylist(it.playlist.id) }
                        }
                    }
                }
                if (continuation != null) {
                    item {
                        MoreChip(loadingMore) {
                            val token = continuation ?: return@MoreChip
                            loadingMore = true
                            scope.launch {
                                try {
                                    val page = Repo.searchMore(token)
                                    items = items + page.items
                                    continuation = page.continuation
                                } catch (_: Exception) {
                                    continuation = null
                                }
                                loadingMore = false
                            }
                        }
                    }
                }
                if (suggestions.isNotEmpty()) {
                    item { SectionHeader("Related searches") }
                    suggestions.forEach { s ->
                        item(key = "sug$s") {
                            Chip(
                                onClick = { onSearch(s) },
                                label = { Text(s, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                colors = ChipDefaults.secondaryChipColors(),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }
}
