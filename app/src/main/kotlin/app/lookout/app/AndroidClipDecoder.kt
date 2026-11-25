package app.lookout.app

import android.graphics.Bitmap
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import app.lookout.core.ClipDecoder
import app.lookout.core.FrameRequest
import app.lookout.core.Img
import app.lookout.core.LookoutError
import app.lookout.core.NRect
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Decodes MP4 recordings with MediaCodec in one sequential pass. Only the frames that a request asks
 * for are converted, and only the requested region, at the requested size.
 */
object AndroidClipDecoder : ClipDecoder {
    private const val TIMEOUT_US = 10_000L

    /** B-frames can come out of order, so input runs a little past the window. */
    private const val INPUT_MARGIN_US = 500_000L

    override fun decode(file: File, fromUs: Long, toUs: Long, request: (Long) -> FrameRequest?, onFrame: (Long, Img?) -> Unit) {
        val rotation = MediaMetadataRetriever().let { r ->
            try {
                r.setDataSource(file.path)
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            } finally {
                r.release()
            }
        }
        val extractor = MediaExtractor()
        extractor.setDataSource(file.path)
        val track = selectVideo(extractor, file)
        val format = extractor.getTrackFormat(track)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        try {
            codec.configure(format, null, null, 0)
            codec.start()
            if (fromUs > 0) extractor.seekTo(fromUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            val inputEnd = if (toUs > Long.MAX_VALUE - INPUT_MARGIN_US) Long.MAX_VALUE else toUs + INPUT_MARGIN_US
            while (true) {
                if (!inputDone) {
                    // Do not wait for an input buffer: when they are all queued, the output is what must move.
                    val i = codec.dequeueInputBuffer(0)
                    if (i >= 0) {
                        val n = extractor.readSampleData(codec.getInputBuffer(i)!!, 0)
                        if (n < 0 || extractor.sampleTime > inputEnd) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (o < 0) continue
                val pts = info.presentationTimeUs
                val end = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                if (info.size > 0 && pts in fromUs..toUs) {
                    val req = request(pts)
                    val img = req?.let { r -> codec.getOutputImage(o)?.use { convert(it, r, rotation) } }
                    onFrame(pts, img)
                }
                codec.releaseOutputBuffer(o, false)
                if (end) break
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            extractor.release()
        }
    }

    /** Steps through the samples of the video track with no codec. Samples come in decode order, so B-frames need the sort. */
    override fun times(file: File): List<Long> {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            selectVideo(extractor, file)
            val out = ArrayList<Long>()
            while (true) {
                val t = extractor.sampleTime
                if (t < 0) break
                out += t
                extractor.advance()
            }
            out.sort()
            return out
        } finally {
            extractor.release()
        }
    }

    /** Selects the first video track of [extractor] and returns its index. */
    private fun selectVideo(extractor: MediaExtractor, file: File): Int {
        val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            ?: throw LookoutError("clip_corrupt", 500, "${file.name} has no video track")
        extractor.selectTrack(track)
        return track
    }

    /** Samples the requested upright region of a YUV frame into an ARGB bitmap (BT.601, limited range). */
    private fun convert(image: Image, req: FrameRequest, rotation: Int): BitmapImg {
        val crop = image.cropRect
        val rw = crop.width()
        val rh = crop.height()
        val turned = rotation % 180 != 0
        val uw = if (turned) rh else rw
        val uh = if (turned) rw else rh
        val roi = req.roi ?: NRect.FULL
        val px = roi.toPixels(uw, uh)
        val scale = minOf(1.0, req.maxEdge.toDouble() / max(px.w, px.h))
        val ow = max(1, (px.w * scale).roundToInt())
        val oh = max(1, (px.h * scale).roundToInt())
        val (yp, up, vp) = image.planes
        val y = yp.buffer
        val u = up.buffer
        val v = vp.buffer
        // Each raw axis depends on one upright axis only, so the buffer offsets split into a
        // part per output column and a part per output row. Precompute both.
        val colY = IntArray(ow); val colU = IntArray(ow); val colV = IntArray(ow)
        val rowY = IntArray(oh); val rowU = IntArray(oh); val rowV = IntArray(oh)
        fun rawX(i: Int) = crop.left + i.coerceIn(0, rw - 1)
        fun rawY(i: Int) = crop.top + i.coerceIn(0, rh - 1)
        for (ox in 0 until ow) {
            val ux = px.x + (ox * 2 + 1) * px.w / (ow * 2)
            when (rotation) {
                90 -> rawY(rh - 1 - ux).let { colY[ox] = it * yp.rowStride; colU[ox] = it / 2 * up.rowStride; colV[ox] = it / 2 * vp.rowStride }
                180 -> rawX(rw - 1 - ux).let { colY[ox] = it * yp.pixelStride; colU[ox] = it / 2 * up.pixelStride; colV[ox] = it / 2 * vp.pixelStride }
                270 -> rawY(ux).let { colY[ox] = it * yp.rowStride; colU[ox] = it / 2 * up.rowStride; colV[ox] = it / 2 * vp.rowStride }
                else -> rawX(ux).let { colY[ox] = it * yp.pixelStride; colU[ox] = it / 2 * up.pixelStride; colV[ox] = it / 2 * vp.pixelStride }
            }
        }
        for (oy in 0 until oh) {
            val uy = px.y + (oy * 2 + 1) * px.h / (oh * 2)
            when (rotation) {
                90 -> rawX(uy).let { rowY[oy] = it * yp.pixelStride; rowU[oy] = it / 2 * up.pixelStride; rowV[oy] = it / 2 * vp.pixelStride }
                180 -> rawY(rh - 1 - uy).let { rowY[oy] = it * yp.rowStride; rowU[oy] = it / 2 * up.rowStride; rowV[oy] = it / 2 * vp.rowStride }
                270 -> rawX(rw - 1 - uy).let { rowY[oy] = it * yp.pixelStride; rowU[oy] = it / 2 * up.pixelStride; rowV[oy] = it / 2 * vp.pixelStride }
                else -> rawY(uy).let { rowY[oy] = it * yp.rowStride; rowU[oy] = it / 2 * up.rowStride; rowV[oy] = it / 2 * vp.rowStride }
            }
        }
        val out = IntArray(ow * oh)
        for (oy in 0 until oh) {
            val ry = rowY[oy]
            val ru = rowU[oy]
            val rv = rowV[oy]
            val base = oy * ow
            for (ox in 0 until ow) {
                val c = (y.get(ry + colY[ox]).toInt() and 0xFF) - 16
                val d = (u.get(ru + colU[ox]).toInt() and 0xFF) - 128
                val e = (v.get(rv + colV[ox]).toInt() and 0xFF) - 128
                val r = ((298 * c + 409 * e + 128) shr 8).coerceIn(0, 255)
                val g = ((298 * c - 100 * d - 208 * e + 128) shr 8).coerceIn(0, 255)
                val b = ((298 * c + 516 * d + 128) shr 8).coerceIn(0, 255)
                out[base + ox] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return BitmapImg(Bitmap.createBitmap(out, ow, oh, Bitmap.Config.ARGB_8888))
    }
}
