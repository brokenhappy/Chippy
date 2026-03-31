package com.woutwerkman

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform

expect fun currentTimeMillis(): Long