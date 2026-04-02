package com.woutwerkman.net

import qrcode.QRCode

actual fun generateQrCodePng(data: String): ByteArray =
    QRCode.ofSquares()
        .withSize(25)
        .build(data)
        .renderToBytes()
