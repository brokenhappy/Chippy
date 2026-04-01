package com.woutwerkman.game.ui

import androidx.compose.ui.graphics.ImageBitmap

/** Decode a PNG byte array into a Compose [ImageBitmap]. */
expect fun decodeImageBitmap(bytes: ByteArray): ImageBitmap
