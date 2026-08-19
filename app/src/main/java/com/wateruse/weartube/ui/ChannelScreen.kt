package com.wateruse.weartube.ui

import com.wateruse.weartube.data.friendlyError
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import coil3.compose.AsyncImage
import com.wateruse.weartube.data.Channel
import com.wateruse.weartube.data.Repo
import com.wateruse.weartube.data.InnerTube
import com.wateruse.weartube.data.Store
import com.wateruse.weartube.data.TvAuth
import com.wateruse.weartube.data.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ChannelScreen(channelId: String, onOpenVideo: (String) -> Unit) {
    val state = rememberScalingLazyListState(initialCenterItemIndex = 0)
    val scope = rememberCoroutineScope()
    var channel by remember { mutableStateOf<Channel?>(null) }
    var videos by remember { mutableStateOf<List<Video>>(emptyList()) }
    var continuation by remember { mutableStateOf<String?>(null) }
    var subscribed by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(channelId, reloadKey) {
        loading = true
        error = null
        subscribed = withContext(Dispatchers.IO) {
            if (TvAuth.isSignedIn) Store.isSubscribed(channelId) || Repo.accountSubscribed(channelId)
            else Store.isSubscribed(channelId)
        }
        try {
            val (header, page) = Repo.channel(channelId)
            channel = header
            videos = page.items
            continuation = page.continuation
        } catch (e: Exception) {
            error = friendlyError(e, "Couldn't load channel")
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
            val ch = channel
            if (ch != null) {
                if (ch.thumb.isNotEmpty()) {
                    item {
                        AsyncImage(
                            model = ch.thumb,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp).clip(CircleShape),
                            contentScale = ContentScale.Crop,
                        )
                    }
                }
                item {
                    Text(
                        ch.title,
                        style = MaterialTheme.typography.title3,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    )
                }
                if (ch.subs.isNotEmpty()) {
                    item {
                        Text(
                            ch.subs,
                            style = MaterialTheme.typography.caption3,
                            color = MaterialTheme.colors.secondary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                item {
                    Chip(
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                val now = Store.toggleSubscription(ch)
                                // mirror to the signed-in account when TV auth is present
                                if (TvAuth.isSignedIn && TvAuth.refreshIfNeeded() != null) {
                                    if (now) InnerTube.subscribeAccount(ch.id)
                                    else InnerTube.unsubscribeAccount(ch.id)
                                }
                                withContext(Dispatchers.Main) { subscribed = now }
                            }
                        },
                        label = {
                            Text(
                                if (subscribed) "Subscribed ✓" else "Subscribe",
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Center,
                            )
                        },
                        colors = if (subscribed) ChipDefaults.secondaryChipColors() else ChipDefaults.primaryChipColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            if (loading) {
                item { CenteredSpinner() }
            } else if (error != null) {
                item { ErrorRetry(error!!) { reloadKey++ } }
            } else {
                items(videos, key = { it.id }) { v ->
                    // channel pages omit the uploader name; fill it in for the player screen
                    val vv = if (v.channel.isEmpty() && channel != null) v.copy(channel = channel!!.title, channelId = channelId) else v
                    VideoCard(vv) { NavCache.put(vv); onOpenVideo(vv.id) }
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
