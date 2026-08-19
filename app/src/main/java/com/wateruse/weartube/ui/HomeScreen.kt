package com.wateruse.weartube.ui

import com.wateruse.weartube.data.friendlyError
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.wateruse.weartube.data.Repo
import com.wateruse.weartube.player.PlayerController

@Composable
fun HomeScreen(
    onOpenVideo: (String) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenNowPlaying: () -> Unit,
) {
    val state = rememberScalingLazyListState(initialCenterItemIndex = 0)
    var feed by remember { mutableStateOf<Repo.HomeFeed?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(reloadKey) {
        loading = true
        error = null
        try {
            feed = Repo.homeFeed()
        } catch (e: Exception) {
            error = friendlyError(e, "Couldn't load feed")
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
            modifier = Modifier,
        ) {
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Button(
                        onClick = onOpenSearch,
                        colors = ButtonDefaults.secondaryButtonColors(),
                        modifier = Modifier.size(40.dp),
                    ) { Icon(Icons.Filled.Search, contentDescription = "Search") }
                    val nowPlaying by PlayerController.nowPlaying.collectAsState()
                    if (nowPlaying != null) {
                        Button(
                            onClick = onOpenNowPlaying,
                            colors = ButtonDefaults.primaryButtonColors(),
                            modifier = Modifier.size(40.dp),
                        ) { Icon(Icons.Filled.MusicNote, contentDescription = "Now playing") }
                    }
                    Button(
                        onClick = onOpenLibrary,
                        colors = ButtonDefaults.secondaryButtonColors(),
                        modifier = Modifier.size(40.dp),
                    ) { Icon(Icons.Filled.LibraryBooks, contentDescription = "Library") }
                    Button(
                        onClick = { reloadKey++ },
                        colors = ButtonDefaults.secondaryButtonColors(),
                        modifier = Modifier.size(40.dp),
                    ) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") }
                }
            }

            if (loading) {
                item { CenteredSpinner() }
            } else if (error != null) {
                item { ErrorRetry(error!!) { reloadKey++ } }
            } else {
                val f = feed
                if (f != null) {
                    if (f.subscriptionVideos.isNotEmpty()) {
                        item { SectionHeader("Subscriptions") }
                        items(f.subscriptionVideos, key = { "s${it.id}" }) { v ->
                            VideoCard(v) { NavCache.put(v); onOpenVideo(v.id) }
                        }
                    }
                    if (f.forYou.isNotEmpty()) {
                        item { SectionHeader("For you") }
                        items(f.forYou, key = { "f${it.id}" }) { v ->
                            VideoCard(v) { NavCache.put(v); onOpenVideo(v.id) }
                        }
                    }
                    if (f.subscriptionVideos.isEmpty() && f.forYou.isEmpty()) {
                        item {
                            Text(
                                "Search for videos and subscribe to channels — your feed builds itself from there.",
                                style = MaterialTheme.typography.caption1,
                                color = MaterialTheme.colors.secondary,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 20.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
