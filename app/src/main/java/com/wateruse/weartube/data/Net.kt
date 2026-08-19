package com.wateruse.weartube.data

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object Net {

    /**
     * System DNS (IPv4 + IPv6).
     *
     * An IPv4-only Dns was tried 2026-08-18 to dodge googlevideo's `ip=` URL binding
     * breaking when Android rotates RFC-4941 IPv6 privacy addresses. It DID change the
     * bound address (URLs came back with ip=<v4>), but it is not enabled because the
     * two egresses are not equivalent: this network's IPv4 NAT address is shared with
     * other machines and can carry their bot-flag reputation, while the watch's IPv6
     * egress is its own. Forcing v4 therefore traded a rare failure for a common one.
     * If mid-playback 403 storms are ever traced to a v6 address change, revisit —
     * but verify the v4 egress is clean first.
     */
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
}
