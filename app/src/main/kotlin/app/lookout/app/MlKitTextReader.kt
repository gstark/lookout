package app.lookout.app

import app.lookout.core.Img
import app.lookout.core.NRect
import app.lookout.core.TextLine
import app.lookout.core.TextReader
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** ML Kit text recognition with the bundled Latin model, so it works offline with no download. */
class MlKitTextReader : TextReader {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    override suspend fun read(img: Img): List<TextLine> {
        val bitmap = (img as BitmapImg).bitmap
        val text = suspendCancellableCoroutine { cont ->
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }
        val w = bitmap.width.toDouble()
        val h = bitmap.height.toDouble()
        return text.textBlocks.flatMap { it.lines }.mapNotNull { line ->
            val b = line.boundingBox ?: return@mapNotNull null
            TextLine(
                line.text,
                NRect.clamped(b.left / w, b.top / h, b.width() / w, b.height() / h),
                line.confidence.toDouble().takeIf { it > 0 },
            )
        }
    }
}
