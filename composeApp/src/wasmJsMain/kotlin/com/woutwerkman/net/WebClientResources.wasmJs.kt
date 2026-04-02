package com.woutwerkman.net

internal actual suspend fun loadWebClientResources(): WebClientResources {
    error("Web clients don't serve web resources")
}
