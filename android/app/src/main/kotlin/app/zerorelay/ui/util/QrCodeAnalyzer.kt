package app.zerorelay.ui.util

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.ChecksumException
import com.google.zxing.DecodeHintType
import com.google.zxing.FormatException
import com.google.zxing.NotFoundException
import com.google.zxing.MultiFormatReader
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.PlanarYUVLuminanceSource
import java.nio.ByteBuffer

/**
 * CameraX → ZXing QR decode (no Google ML Kit; F-Droid friendly).
 */
class QrCodeAnalyzer(
    private val onQrCode: (String) -> Unit,
) : ImageAnalysis.Analyzer {

    private val reader =
        MultiFormatReader().apply {
            setHints(
                mapOf(
                    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                    DecodeHintType.TRY_HARDER to true,
                ),
            )
        }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        try {
            val plane = imageProxy.planes.firstOrNull()
            if (plane == null) return
            val width = imageProxy.width
            val height = imageProxy.height
            val rowStride = plane.rowStride
            val data = plane.buffer.duplicate().toByteArray()
            val yPlane =
                if (rowStride == width) {
                    data
                } else {
                    val cropped = ByteArray(width * height)
                    var inputOffset = 0
                    var outputOffset = 0
                    for (y in 0 until height) {
                        System.arraycopy(data, inputOffset, cropped, outputOffset, width)
                        inputOffset += rowStride
                        outputOffset += width
                    }
                    cropped
                }
            val source = PlanarYUVLuminanceSource(yPlane, width, height, 0, 0, width, height, false)
            val bitmap = BinaryBitmap(HybridBinarizer(source))
            val text = reader.decodeWithState(bitmap).text?.trim()
            if (!text.isNullOrEmpty()) {
                onQrCode(text)
            }
        } catch (_: NotFoundException) {
        } catch (_: ChecksumException) {
        } catch (_: FormatException) {
        } finally {
            reader.reset()
            imageProxy.close()
        }
    }
}

private fun ByteBuffer.toByteArray(): ByteArray {
    rewind()
    val out = ByteArray(remaining())
    get(out)
    return out
}
