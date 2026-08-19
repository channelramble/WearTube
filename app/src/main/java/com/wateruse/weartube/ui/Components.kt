package com.wateruse.weartube.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import coil3.compose.AsyncImage
import com.wateruse.weartube.data.Channel
import com.wateruse.weartube.data.Playlist
import com.wateruse.weartube.data.Video

@Composable
fun VideoCard(video: Video, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colors.surface)
            .clickable(onClick = onClick)
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            if (video.thumbOrFallback.isNotEmpty()) {
                AsyncImage(
                    model = video.thumbOrFallback,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
            val badge = if (video.isLive) "LIVE" else video.duration
            if (badge.isNotEmpty()) {
                Text(
                    badge,
                    style = MaterialTheme.typography.caption3,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .background(
                            if (video.isLive) Color(0xCCC4302B) else Color(0xB3000000),
                            RoundedCornerShape(4.dp)
                        )
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }
            if (video.watchedPercent > 0) {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth(video.watchedPercent / 100f)
                        .height(3.dp)
                        .background(MaterialTheme.colors.primary)
                )
            }
        }
        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(
                video.title,
                style = MaterialTheme.typography.caption1,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = listOf(video.channel, video.views, video.published)
                .filter { it.isNotEmpty() }
                .joinToString(" · ")
            if (meta.isNotEmpty()) {
                Text(
                    meta,
                    style = MaterialTheme.typography.caption3,
                    color = MaterialTheme.colors.secondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
fun PlaylistCard(playlist: Playlist, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colors.surface)
            .clickable(onClick = onClick)
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            if (playlist.thumb.isNotEmpty()) {
                AsyncImage(
                    model = playlist.thumb,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
            Text(
                if (playlist.count.isNotEmpty()) "▶ ${playlist.count}" else "Playlist",
                style = MaterialTheme.typography.caption3,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .background(Color(0xB3000000), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            )
        }
        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(
                playlist.title,
                style = MaterialTheme.typography.caption1,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (playlist.channel.isNotEmpty()) {
                Text(
                    playlist.channel,
                    style = MaterialTheme.typography.caption3,
                    color = MaterialTheme.colors.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
fun ChannelRow(channel: Channel, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colors.surface)
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (channel.thumb.isNotEmpty()) {
            AsyncImage(
                model = channel.thumb,
                contentDescription = null,
                modifier = Modifier.size(32.dp).clip(RoundedCornerShape(16.dp)),
                contentScale = ContentScale.Crop,
            )
        }
        Column(Modifier.padding(start = 8.dp).weight(1f)) {
            Text(
                channel.title,
                style = MaterialTheme.typography.caption1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (channel.subs.isNotEmpty()) {
                Text(
                    channel.subs,
                    style = MaterialTheme.typography.caption3,
                    color = MaterialTheme.colors.secondary,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.caption1,
        color = MaterialTheme.colors.primary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp),
    )
}

@Composable
fun CenteredSpinner() {
    Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(indicatorColor = MaterialTheme.colors.primary)
    }
}

@Composable
fun ErrorRetry(message: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            message,
            style = MaterialTheme.typography.caption2,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colors.secondary,
        )
        Chip(
            onClick = onRetry,
            label = { Text("Retry") },
            colors = ChipDefaults.secondaryChipColors(),
        )
    }
}

@Composable
fun MoreChip(loading: Boolean, onClick: () -> Unit) {
    if (loading) {
        CenteredSpinner()
    } else {
        Chip(
            onClick = onClick,
            label = { Text("More", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
            colors = ChipDefaults.secondaryChipColors(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
