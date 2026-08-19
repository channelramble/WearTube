package com.wateruse.weartube.player

import android.content.Context
import android.media.AudioManager
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Speed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import coil3.compose.AsyncImage
import com.wateruse.weartube.ui.NavCache
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private fun formatTime(ms: Long): String {
    if (ms < 0) return "0:00"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun formatSpeed(speed: Float): String {
    val s = if (speed == speed.toInt().toFloat()) speed.toInt().toString()
    else "%.2f".format(speed).trimEnd('0').trimEnd('.')
    return "$s×"
}

@Composable
private fun ControlPill(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(32.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0x2EFFFFFF))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.caption2, color = Color.White)
    }
}

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(videoId: String, onOpenDetails: (String) -> Unit) {
    val context = LocalContext.current
    val activePlayer by PlayerController.playerFlow.collectAsState()
    val nowPlaying by PlayerController.nowPlaying.collectAsState()
    val isLoading by PlayerController.isLoading.collectAsState()
    val error by PlayerController.error.collectAsState()
    val choice by PlayerController.choice.collectAsState()
    val bundle by PlayerController.bundle.collectAsState()

    var controlsVisible by remember { mutableStateOf(true) }
    var showQuality by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(true) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var speed by remember { mutableFloatStateOf(1f) }
    val focusRequester = remember { FocusRequester() }
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    LaunchedEffect(videoId) {
        PlayerController.ensureService(context)
        if (PlayerController.nowPlaying.value?.id != videoId) {
            PlayerController.play(NavCache.video(videoId), NavCache.takeQueue())
        }
        focusRequester.requestFocus()
    }

    // Re-claim rotary (bezel = volume) every time this screen becomes the top
    // destination again. A one-shot request at composition fires mid nav-transition,
    // while the outgoing screen (e.g. Details) still owns focus — its disposal then
    // clears focus and the bezel goes dead. ON_RESUME of the back-stack entry lands
    // after the transition settles.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                try {
                    focusRequester.requestFocus()
                } catch (_: IllegalStateException) {
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // observe play state
    LaunchedEffect(Unit) {
        while (isActive) {
            PlayerController.player?.let { p ->
                isPlaying = p.isPlaying
                positionMs = p.currentPosition
                durationMs = p.duration.coerceAtLeast(0)
                speed = p.playbackParameters.speed
            }
            delay(500)
        }
    }

    // auto-hide controls while playing
    LaunchedEffect(controlsVisible, isPlaying) {
        if (controlsVisible && isPlaying) {
            delay(4000)
            controlsVisible = false
        }
    }

    val audioOnlyActive = choice?.height == 0

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onRotaryScrollEvent { ev ->
                val dir = if (ev.verticalScrollPixels > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
                true
            }
            .focusRequester(focusRequester)
            .focusable()
            .pointerInput(Unit) {
                detectTapGestures(onTap = { controlsVisible = !controlsVisible })
            }
    ) {
        if (!audioOnlyActive) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                        keepScreenOn = true
                    }
                },
                update = { view -> view.player = activePlayer },
                onRelease = { view -> view.player = null },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            // audio mode: artwork + title, screen may sleep
            val v = nowPlaying
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (v != null && v.thumb.isNotEmpty()) {
                    AsyncImage(
                        model = v.thumb,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        alpha = 0.35f,
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        bundle?.title ?: v?.title.orEmpty(),
                        style = MaterialTheme.typography.caption1,
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                    Text(
                        bundle?.channel ?: v?.channel.orEmpty(),
                        style = MaterialTheme.typography.caption3,
                        color = MaterialTheme.colors.secondary,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }

        if (isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(indicatorColor = MaterialTheme.colors.primary)
            }
        }

        error?.let { msg ->
            Column(
                Modifier.fillMaxSize().padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    msg,
                    style = MaterialTheme.typography.caption1,
                    textAlign = TextAlign.Center,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
                Chip(
                    onClick = { PlayerController.play(NavCache.video(videoId)) },
                    label = { Text("Retry") },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        // quality picker overlay
        if (showQuality) {
            Column(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xE6000000))
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 28.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("Quality", style = MaterialTheme.typography.caption1, color = MaterialTheme.colors.primary)
                bundle?.choices?.forEach { c ->
                    Chip(
                        onClick = {
                            showQuality = false
                            PlayerController.applyChoice(c)
                        },
                        label = {
                            Text(
                                c.label + if (choice == c) "  ✓" else "",
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Center,
                            )
                        },
                        colors = if (choice == c) ChipDefaults.primaryChipColors() else ChipDefaults.secondaryChipColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        // controls overlay
        AnimatedVisibility(
            visible = controlsVisible && error == null && !showQuality,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(Modifier.fillMaxSize().background(Color(0x66000000))) {
                Text(
                    bundle?.title ?: nowPlaying?.title.orEmpty(),
                    style = MaterialTheme.typography.caption2,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 22.dp)
                        .padding(horizontal = 36.dp),
                )

                Row(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = { PlayerController.player?.let { it.seekTo((it.currentPosition - 10_000).coerceAtLeast(0)) } },
                        colors = ButtonDefaults.secondaryButtonColors(),
                        modifier = Modifier.size(40.dp),
                    ) { Icon(Icons.Filled.Replay10, contentDescription = "Back 10s") }
                    Button(
                        onClick = {
                            PlayerController.player?.let { p ->
                                if (p.isPlaying) p.pause() else {
                                    if (p.playbackState == Player.STATE_ENDED) p.seekTo(0)
                                    p.play()
                                }
                            }
                        },
                        colors = ButtonDefaults.primaryButtonColors(),
                        modifier = Modifier.size(52.dp),
                    ) {
                        Icon(
                            if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                        )
                    }
                    Button(
                        onClick = { PlayerController.player?.let { it.seekTo(it.currentPosition + 10_000) } },
                        colors = ButtonDefaults.secondaryButtonColors(),
                        modifier = Modifier.size(40.dp),
                    ) { Icon(Icons.Filled.Forward10, contentDescription = "Forward 10s") }
                }

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 14.dp)
                        .padding(horizontal = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // seek bar: tap position -> seek
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(14.dp)
                            .pointerInput(durationMs) {
                                detectTapGestures { offset ->
                                    if (durationMs > 0) {
                                        val frac = (offset.x / size.width).coerceIn(0f, 1f)
                                        PlayerController.player?.seekTo((durationMs * frac).toLong())
                                    }
                                }
                            },
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(Color(0x4DFFFFFF))
                        )
                        if (durationMs > 0) {
                            Box(
                                Modifier
                                    .fillMaxWidth((positionMs.toFloat() / durationMs).coerceIn(0f, 1f))
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(MaterialTheme.colors.primary)
                            )
                        }
                    }
                    Text(
                        "${formatTime(positionMs)} / ${formatTime(durationMs)}",
                        style = MaterialTheme.typography.caption3,
                        color = MaterialTheme.colors.secondary,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ControlPill(
                            text = choice?.let { if (it.height == 0) "♪" else it.label } ?: "…",
                        ) { showQuality = true }
                        ControlPill(text = formatSpeed(speed)) {
                            val speeds = listOf(1f, 1.25f, 1.5f, 2f, 0.75f)
                            val next = speeds[(speeds.indexOfFirst { it == speed }.coerceAtLeast(0) + 1) % speeds.size]
                            PlayerController.player?.setPlaybackSpeed(next)
                        }
                        Button(
                            onClick = { onOpenDetails(videoId) },
                            colors = ButtonDefaults.secondaryButtonColors(),
                            modifier = Modifier.size(32.dp),
                        ) { Icon(Icons.Filled.Info, contentDescription = "Details", modifier = Modifier.size(16.dp)) }
                    }
                }
            }
        }
    }
}
