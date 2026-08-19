package com.wateruse.weartube.ui

import android.app.Activity
import android.app.RemoteInput
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.input.RemoteInputIntentHelper
import com.wateruse.weartube.data.Store

private const val KEY_QUERY = "query"

fun searchInputIntent(): Intent {
    val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
    val remoteInput = RemoteInput.Builder(KEY_QUERY).setLabel("Search YouTube").build()
    RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(remoteInput))
    return intent
}

fun queryFromResult(resultCode: Int, data: Intent?): String? {
    if (resultCode != Activity.RESULT_OK || data == null) return null
    val results = RemoteInput.getResultsFromIntent(data) ?: return null
    return results.getCharSequence(KEY_QUERY)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
}

@Composable
fun SearchScreen(onSearch: (String) -> Unit) {
    val state = rememberScalingLazyListState(initialCenterItemIndex = 0)
    var recent by remember { mutableStateOf(Store.recentSearches()) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        queryFromResult(result.resultCode, result.data)?.let { q ->
            Store.addSearch(q)
            onSearch(q)
        }
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
            item {
                Chip(
                    onClick = { launcher.launch(searchInputIntent()) },
                    label = { Text("Search YouTube") },
                    icon = { Icon(Icons.Filled.Keyboard, contentDescription = null) },
                    colors = ChipDefaults.primaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (recent.isNotEmpty()) {
                item { SectionHeader("Recent") }
                items(recent, key = { it }) { q ->
                    Chip(
                        onClick = {
                            Store.addSearch(q)
                            onSearch(q)
                        },
                        label = { Text(q, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        icon = { Icon(Icons.Filled.History, contentDescription = null) },
                        colors = ChipDefaults.secondaryChipColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    Chip(
                        onClick = {
                            Store.clearSearches()
                            recent = emptyList()
                        },
                        label = { Text("Clear recent") },
                        icon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                        colors = ChipDefaults.secondaryChipColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
