package com.wateruse.weartube.ui

import com.wateruse.weartube.data.friendlyError
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.wateruse.weartube.data.Comment
import com.wateruse.weartube.data.Repo
import kotlinx.coroutines.launch

@Composable
fun CommentsScreen(videoId: String) {
    val state = rememberScalingLazyListState(initialCenterItemIndex = 0)
    val scope = rememberCoroutineScope()
    var comments by remember { mutableStateOf<List<Comment>>(emptyList()) }
    var continuation by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var disabled by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(videoId, reloadKey) {
        loading = true
        error = null
        try {
            val page = Repo.commentsFirstPage(videoId)
            if (page == null) {
                disabled = true
            } else {
                comments = page.items
                continuation = page.continuation
            }
        } catch (e: Exception) {
            error = friendlyError(e, "Couldn't load comments")
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
            item { SectionHeader("Comments") }
            if (loading) {
                item { CenteredSpinner() }
            } else if (disabled) {
                item {
                    Text(
                        "Comments are off for this video.",
                        style = MaterialTheme.typography.caption2,
                        color = MaterialTheme.colors.secondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else if (error != null) {
                item { ErrorRetry(error!!) { reloadKey++ } }
            } else {
                comments.forEachIndexed { idx, c ->
                    item(key = "c$idx") { CommentCard(c) }
                }
                if (continuation != null) {
                    item {
                        MoreChip(loadingMore) {
                            val token = continuation ?: return@MoreChip
                            loadingMore = true
                            scope.launch {
                                try {
                                    val page = Repo.commentsMore(token)
                                    comments = comments + page.items
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

@Composable
private fun CommentCard(c: Comment) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colors.surface)
            .padding(8.dp)
    ) {
        val head = listOf(c.author, c.published).filter { it.isNotEmpty() }.joinToString(" · ")
        if (head.isNotEmpty()) {
            Text(
                head,
                style = MaterialTheme.typography.caption3,
                color = MaterialTheme.colors.secondary,
            )
        }
        Text(c.text, style = MaterialTheme.typography.caption2)
        val foot = buildList {
            if (c.likes.isNotEmpty()) add("👍 ${c.likes}")
            if (c.replyCount.isNotEmpty()) add("${c.replyCount} replies")
        }.joinToString(" · ")
        if (foot.isNotEmpty()) {
            Text(
                foot,
                style = MaterialTheme.typography.caption3,
                color = MaterialTheme.colors.secondary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
