package com.woutwerkman.net

import kotlinx.coroutines.CoroutineScope

actual suspend fun <T> hostingWebClient(
    connection: PeerNetConnection,
    block: suspend CoroutineScope.(url: String) -> T,
): T {
    error("Web clients cannot host other web clients")
}
