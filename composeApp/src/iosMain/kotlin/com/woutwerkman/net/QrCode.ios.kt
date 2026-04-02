package com.woutwerkman.net

import kotlinx.cinterop.*
import platform.CoreImage.CIFilter
import platform.CoreImage.filterWithName
import platform.Foundation.*
import platform.UIKit.UIImage
import platform.UIKit.UIImagePNGRepresentation

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
actual fun generateQrCodePng(data: String): ByteArray {
    val filter = CIFilter.filterWithName("CIQRCodeGenerator")
        ?: error("CIQRCodeGenerator not available")
    filter.setDefaults()
    val nsString = NSString.create(string = data)
    val nsData = nsString.dataUsingEncoding(NSUTF8StringEncoding)
        ?: error("Failed to encode URL")
    filter.setValue(nsData, forKey = "inputMessage")
    filter.setValue("L", forKey = "inputCorrectionLevel")

    val outputImage = filter.outputImage ?: error("QR filter produced no output")
    val transform = platform.CoreGraphics.CGAffineTransformMakeScale(10.0, 10.0)
    val scaled = outputImage.imageByApplyingTransform(transform)

    val uiImage = UIImage(cIImage = scaled)
    val pngData = UIImagePNGRepresentation(uiImage) ?: error("PNG encoding failed")

    return ByteArray(pngData.length.toInt()).also { bytes ->
        bytes.usePinned { pinned ->
            pngData.getBytes(pinned.addressOf(0), pngData.length)
        }
    }
}
