package com.wateruse.weartube.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.wateruse.weartube.data.TvAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Device-code sign-in. The watch only ever DISPLAYS a short code; the user types it
 * into their own browser at google.com/device. No password is handled here.
 */
@Composable
fun TvSignInScreen(onSignedIn: () -> Unit) {
    var code by remember { mutableStateOf<TvAuth.DeviceCode?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("Requesting code…") }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(attempt) {
        error = null
        status = "Requesting code…"
        code = null
        val dc = try {
            withContext(Dispatchers.IO) { TvAuth.requestDeviceCode() }
        } catch (e: Exception) {
            error = e.message ?: "Couldn't reach Google"
            return@LaunchedEffect
        }
        code = dc
        status = "Waiting for you to approve…"

        // poll until authorized or the code expires (~30 min)
        val deadline = System.currentTimeMillis() + 30 * 60 * 1000
        while (System.currentTimeMillis() < deadline) {
            delay(dc.intervalSec * 1000L)
            val done = try {
                withContext(Dispatchers.IO) { TvAuth.pollOnce(dc.deviceCode) }
            } catch (e: Exception) {
                error = when (e.message) {
                    "access_denied" -> "Approval was declined."
                    "expired_token" -> "Code expired — tap Retry."
                    else -> e.message ?: "Sign-in failed"
                }
                return@LaunchedEffect
            }
            if (done) {
                status = "Signed in!"
                onSignedIn()
                return@LaunchedEffect
            }
        }
        error = "Code expired — tap Retry."
    }

    Scaffold(timeText = { TimeText() }) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "Sign in",
                style = MaterialTheme.typography.title3,
                color = MaterialTheme.colors.primary,
            )
            val err = error
            if (err != null) {
                Text(
                    err,
                    style = MaterialTheme.typography.caption2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Chip(
                    onClick = { attempt++ },
                    label = { Text("Retry") },
                    colors = ChipDefaults.secondaryChipColors(),
                )
            } else {
                Text(
                    "On your phone, open",
                    style = MaterialTheme.typography.caption2,
                    color = MaterialTheme.colors.secondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "google.com/device",
                    style = MaterialTheme.typography.caption1,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "and enter",
                    style = MaterialTheme.typography.caption2,
                    color = MaterialTheme.colors.secondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
                Text(
                    code?.userCode ?: "· · · · ·",
                    style = MaterialTheme.typography.title2,
                    color = MaterialTheme.colors.primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )
                Text(
                    status,
                    style = MaterialTheme.typography.caption3,
                    color = MaterialTheme.colors.secondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
