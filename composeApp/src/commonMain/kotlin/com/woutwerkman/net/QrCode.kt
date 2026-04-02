package com.woutwerkman.net

/** Generate a PNG-encoded QR code for the given [data] string. */
expect fun generateQrCodePng(data: String): ByteArray
