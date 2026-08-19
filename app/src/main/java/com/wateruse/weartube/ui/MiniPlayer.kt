package com.wateruse.weartube.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.wateruse.weartube.player.PlayerController
import kotlinx.coroutines.delay

/**
 * Persistent "still playing" pill shown on every screen except the player itself.
 *
 * Without it, backing out of the player left no on-screen sign that audio was
 * still going and no way to pause short of re-opening the video — the media
 * notification is a swipe away, which is not discoverable mid-listen.
 *
 * Sits at the bottom of the round display, inside the safe area, so it never
 * collides with the TimeText clock at the top.
 */
@Composable
fun BoxScope.MiniPlayer(onOpen: (String) -> Unit) {
    val nowPlaying by PlayerController.nowPlaying.collectAsState()
    val bundle by PlayerController.bundle.collectAsState()
    val video = nowPlaying

    // Polled rather than observed: ExoPlayer's isPlaying has no flow here, and a
    // half-second tick is far cheaper than re-rendering the whole screen.
    var playing by remember { mutableStateOf(false) }
    LaunchedEffect(video?.id) {
        while (video != null) {
            playing = PlayerController.player?.isPlaying == true
            delay(500)
        }
    }

    val m = rememberMiniPlayerMetrics()

    AnimatedVisibility(
        visible = video != null,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = Modifier.align(Alignment.BottomCenter),
    ) {
        val v = video ?: return@AnimatedVisibility
        Row(
            modifier = Modifier
                .padding(bottom = m.bottomMargin)
                .width(m.pillWidth)
                .height(m.pillHeight)
                .clip(RoundedCornerShape(m.pillHeight / 2))
                .background(Color(0xF21A1A1A))
                .clickable { onOpen(v.id) }
                .padding(start = m.pillHeight * 0.28f, end = m.pillHeight * 0.09f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                // NavCache has no title for deep links / after process death; the
                // resolved stream bundle always does.
                v.title.ifEmpty { bundle?.title.orEmpty() }.ifEmpty { "Playing" },
                style = MaterialTheme.typography.caption3,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).basicMarquee(),
            )
            Box(
                modifier = Modifier
                    .size(m.buttonSize)
                    .clip(RoundedCornerShape(m.buttonSize / 2))
                    .background(MaterialTheme.colors.primary)
                    .clickable {
                        val p = PlayerController.player ?: return@clickable
                        // toggle immediately so the icon never lags the tap
                        if (p.isPlaying) p.pause() else p.play()
                        playing = p.isPlaying
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (playing) "Pause" else "Play",
                    modifier = Modifier.size(m.buttonSize * 0.6f),
                    tint = Color.White,
                )
            }
        }
    }
}


/** Geometry for the pill, derived from the actual display rather than hardcoded. */
data class MiniPlayerMetrics(
    val pillWidth: androidx.compose.ui.unit.Dp,
    val pillHeight: androidx.compose.ui.unit.Dp,
    val bottomMargin: androidx.compose.ui.unit.Dp,
    val buttonSize: androidx.compose.ui.unit.Dp,
)

/**
 * Sizes the pill for whatever screen it lands on.
 *
 * On a ROUND display the usable width collapses toward the bottom edge, so a
 * fixed fraction that looks right on one watch is clipped on another. The chord
 * of the circle at the pill's lowest point is computed directly:
 *
 *     halfChord = sqrt(r² - dy²)
 *
 * and the pill is inset from that. Square/rectangular Wear devices skip the
 * geometry and simply use most of the width.
 */
@Composable
fun rememberMiniPlayerMetrics(): MiniPlayerMetrics {
    val config = androidx.compose.ui.platform.LocalConfiguration.current
    val isRound = config.isScreenRound
    val wDp = config.screenWidthDp.toFloat()
    val hDp = config.screenHeightDp.toFloat()

    // Proportional to the display so it looks the same on 41mm and 49mm watches.
    val pillHeight = (hDp * 0.135f).coerceIn(26f, 40f)
    val bottomMargin = (hDp * 0.11f).coerceIn(14f, 34f)

    val width = if (isRound) {
        val r = minOf(wDp, hDp) / 2f
        // lowest edge of the pill, measured from the top of the display
        val bottomY = hDp - bottomMargin
        val dy = (bottomY - r).coerceAtLeast(0f)
        val halfChord = kotlin.math.sqrt((r * r - dy * dy).coerceAtLeast(0f))
        // keep a small margin off the bezel so the rounded ends never touch it
        (halfChord * 2f * 0.92f).coerceIn(wDp * 0.42f, wDp * 0.86f)
    } else {
        wDp * 0.88f
    }

    return MiniPlayerMetrics(
        pillWidth = width.dp,
        pillHeight = pillHeight.dp,
        bottomMargin = bottomMargin.dp,
        buttonSize = (pillHeight * 0.82f).dp,
    )
}
