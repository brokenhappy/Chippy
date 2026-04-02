package com.woutwerkman.net

actual fun generateQrCodePng(data: String): ByteArray {
    error("QR code generation is not supported on web clients")
}
