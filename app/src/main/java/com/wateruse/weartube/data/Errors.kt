package com.wateruse.weartube.data

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Turns exceptions into something worth showing on a 1-inch screen.
 *
 * Raw exception text is unreadable here — offline produced
 * `Unable to resolve host "www.youtube.com": No address associated with hostname`,
 * which wrapped to three lines and told the user nothing actionable.
 */
fun friendlyError(e: Throwable, fallback: String): String = when {
    e is PlaybackBlockedException -> e.message ?: fallback
    e is UnknownHostException -> "No internet connection"
    e is SocketTimeoutException -> "Connection timed out"
    e is SSLException -> "Secure connection failed"
    e is IOException && e.message?.contains("HTTP 4") == true -> "YouTube refused the request"
    e is IOException && e.message?.contains("HTTP 5") == true -> "YouTube is having trouble"
    e is IOException && (
        e.message?.contains("resolve host", ignoreCase = true) == true ||
            e.message?.contains("Network is unreachable", ignoreCase = true) == true ||
            e.message?.contains("failed to connect", ignoreCase = true) == true
        ) -> "No internet connection"
    else -> e.message?.takeIf { it.length in 1..60 && !it.contains("Exception") } ?: fallback
}
