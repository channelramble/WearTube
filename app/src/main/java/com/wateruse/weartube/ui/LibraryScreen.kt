package com.wateruse.weartube.ui

import android.accounts.AccountManager
import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.filled.WatchLater
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import androidx.wear.compose.material.ToggleChip
import androidx.wear.compose.material.ToggleChipDefaults
import com.wateruse.weartube.data.Auth
import com.wateruse.weartube.data.Settings
import com.wateruse.weartube.data.Store
import com.wateruse.weartube.data.TvAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LibraryScreen(
    onOpenVideo: (String) -> Unit,
    onOpenChannel: (String) -> Unit,
    onOpenSection: (String) -> Unit,
    onSignIn: () -> Unit,
) {
    val state = rememberScalingLazyListState(initialCenterItemIndex = 0)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var account by remember { mutableStateOf(Auth.accountName) }

    val accountPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val name = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
        if (result.resultCode == Activity.RESULT_OK && !name.isNullOrEmpty()) {
            Auth.accountName = name
            account = name
            scope.launch(Dispatchers.IO) { Auth.fetchToken(context as? Activity) }
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
            item { SectionHeader("Account") }
            item {
                var tvSignedIn by remember { mutableStateOf(TvAuth.isSignedIn) }
                // Sign-out needs a deliberate second tap. A single-tap sign-out is far
                // too easy to hit by accident on a watch — a stray tap during testing
                // wiped the session and the whole device-code flow had to be redone.
                var confirmSignOut by remember { mutableStateOf(false) }
                LaunchedEffect(confirmSignOut) {
                    if (confirmSignOut) {
                        kotlinx.coroutines.delay(5000)
                        confirmSignOut = false
                    }
                }
                Chip(
                    onClick = {
                        when {
                            !tvSignedIn -> onSignIn()
                            confirmSignOut -> {
                                TvAuth.signOut()
                                tvSignedIn = false
                                confirmSignOut = false
                            }
                            else -> confirmSignOut = true
                        }
                    },
                    label = {
                        Text(
                            when {
                                !tvSignedIn -> "Sign in with Google"
                                confirmSignOut -> "Tap again to sign out"
                                else -> "Signed in"
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    secondaryLabel = {
                        Text(
                            when {
                                !tvSignedIn -> "For your real home feed"
                                confirmSignOut -> "Or wait to cancel"
                                else -> "Your recommendations"
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    icon = { Icon(Icons.Filled.AccountCircle, contentDescription = null) },
                    colors = when {
                        !tvSignedIn -> ChipDefaults.primaryChipColors()
                        confirmSignOut -> ChipDefaults.primaryChipColors()
                        else -> ChipDefaults.secondaryChipColors()
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item { SectionHeader("Library") }
            item {
                Chip(
                    onClick = { onOpenSection("watchlater") },
                    label = { Text("Watch Later") },
                    icon = { Icon(Icons.Filled.WatchLater, contentDescription = null) },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Chip(
                    onClick = { onOpenSection("history") },
                    label = { Text("History") },
                    icon = { Icon(Icons.Filled.History, contentDescription = null) },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Chip(
                    onClick = { onOpenSection("subs") },
                    label = { Text("Subscriptions") },
                    icon = { Icon(Icons.Filled.Subscriptions, contentDescription = null) },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Chip(
                    onClick = { onOpenSection("playlists") },
                    label = { Text("Playlists") },
                    icon = { Icon(Icons.Filled.PlaylistPlay, contentDescription = null) },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item { SectionHeader("Settings") }
            item {
                var height by remember { mutableStateOf(Settings.preferredHeight) }
                Chip(
                    onClick = {
                        height = when (height) {
                            0 -> 144; 144 -> 240; 240 -> 360; 360 -> 480; else -> 0
                        }
                        Settings.preferredHeight = height
                    },
                    label = { Text("Quality: ${if (height == 0) "Auto" else "${height}p"}") },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                var autoplay by remember { mutableStateOf(Settings.autoplayNext) }
                ToggleChip(
                    checked = autoplay,
                    onCheckedChange = {
                        autoplay = it
                        Settings.autoplayNext = it
                    },
                    label = { Text("Autoplay next") },
                    toggleControl = {
                        Icon(ToggleChipDefaults.switchIcon(autoplay), contentDescription = null)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                var audioOnly by remember { mutableStateOf(Settings.audioOnly) }
                ToggleChip(
                    checked = audioOnly,
                    onCheckedChange = {
                        audioOnly = it
                        Settings.audioOnly = it
                    },
                    label = { Text("Audio only") },
                    toggleControl = {
                        Icon(ToggleChipDefaults.switchIcon(audioOnly), contentDescription = null)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
fun LibrarySectionScreen(
    section: String,
    onOpenVideo: (String) -> Unit,
    onOpenChannel: (String) -> Unit,
    onOpenPlaylist: (String) -> Unit,
) {
    val state = rememberScalingLazyListState(initialCenterItemIndex = 0)
    var refresh by remember { mutableStateOf(0) }
    var accountChannels by remember { mutableStateOf<List<com.wateruse.weartube.data.Channel>>(emptyList()) }
    var accountPlaylists by remember { mutableStateOf<List<com.wateruse.weartube.data.Playlist>>(emptyList()) }

    LaunchedEffect(section) {
        // Account data comes from the TV client (InnerTube), NOT the Data API —
        // GMS/Data-API tokens are rejected for this app (UnregisteredOnApiConsole).
        if (TvAuth.isSignedIn) {
            when (section) {
                "subs" -> accountChannels = com.wateruse.weartube.data.Repo.accountChannels()
                "playlists" -> accountPlaylists = com.wateruse.weartube.data.Repo.accountPlaylists()
            }
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
            when (section) {
                "watchlater" -> {
                    item { SectionHeader("Watch Later") }
                    val list = Store.watchLater()
                    if (list.isEmpty()) item { EmptyNote("Videos you save appear here.") }
                    items(list, key = { it.id }) { v ->
                        VideoCard(v) { NavCache.put(v); onOpenVideo(v.id) }
                    }
                }
                "history" -> {
                    item { SectionHeader("History") }
                    val list = Store.history()
                    if (list.isEmpty()) item { EmptyNote("Watched videos appear here.") }
                    items(list, key = { it.id }) { v ->
                        VideoCard(v) { NavCache.put(v); onOpenVideo(v.id) }
                    }
                    if (list.isNotEmpty()) {
                        item {
                            Chip(
                                onClick = {
                                    Store.clearHistory()
                                    refresh++
                                },
                                label = { Text("Clear history") },
                                icon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                                colors = ChipDefaults.secondaryChipColors(),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
                "subs" -> {
                    item { SectionHeader("Subscriptions") }
                    val local = Store.subscriptions()
                    val accountIds = accountChannels.map { it.id }.toSet()
                    val list = accountChannels + local.filterNot { it.id in accountIds }
                    if (list.isEmpty()) item { EmptyNote("Subscribe to channels from their pages.") }
                    items(list, key = { it.id }) { ch ->
                        ChannelRow(ch) { onOpenChannel(ch.id) }
                    }
                }
                "playlists" -> {
                    item { SectionHeader("Playlists") }
                    val local = Store.savedPlaylists()
                    val accountIds = accountPlaylists.map { it.id }.toSet()
                    val list = accountPlaylists + local.filterNot { it.id in accountIds }
                    if (list.isEmpty()) item { EmptyNote("Save playlists from their pages.") }
                    items(list, key = { it.id }) { p ->
                        PlaylistCard(p) { onOpenPlaylist(p.id) }
                    }
                }
                else -> {
                    // unknown section (bad deep link) — never leave a blank screen
                    item { SectionHeader("Library") }
                    item { EmptyNote("Nothing here.") }
                }
            }
            // keyed read so "Clear history" refreshes the list
            if (refresh < 0) item { Text("") }
        }
    }
}

@Composable
private fun EmptyNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.caption2,
        color = MaterialTheme.colors.secondary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}
