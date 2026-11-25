package app.lookout.core

import kotlinx.serialization.Serializable
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File

/** A recording resolution. [width] x [height] is the landscape size; the clip keeps the camera's orientation. */
enum class VideoResolution(val wire: String, val width: Int, val height: Int) {
    HD("720p", 1280, 720),
    FHD("1080p", 1920, 1080);

    companion object {
        fun parse(text: String): VideoResolution = entries.firstOrNull { it.wire == text.trim().lowercase() }
            ?: throw LookoutError("invalid_param", 400, "resolution must be one of ${entries.joinToString { it.wire }}")
    }
}

@Serializable
data class RecordingCaps(val fps: List<Int>, val resolutions: List<String>, val audio: Boolean = false)

data class RecordSpec(val fps: Int, val resolution: VideoResolution, val audio: Boolean = false)

/**
 * What the source reports once the encoder is rolling. [startedAtMs] is the wall time of the first frame, within
 * [precisionMs]. [videoCrop] is the part of the look frame that the video covers, in upright 0..1 coordinates.
 */
data class RecordingStarted(
    val startedAtMs: Long,
    val width: Int,
    val height: Int,
    val fps: Int,
    val precisionMs: Int,
    val extension: String,
    val videoCrop: NRect = NRect.FULL,
    val audio: Boolean = false,
)

/** How a recording ended. A null [error] means the file is complete. */
data class RecordingEnd(val error: String? = null)

/** Which part of a decoded frame to convert, and the long edge of the result. Coordinates are upright. */
class FrameRequest(val roi: NRect?, val maxEdge: Int)

/** Mono 16-bit signed PCM audio. Only the first [size] entries of [samples] are valid. */
class Pcm(val samples: ShortArray, val sampleRate: Int, val size: Int = samples.size) {
    init {
        require(sampleRate > 0) { "sampleRate must be positive" }
        require(size in 0..samples.size) { "size $size out of range 0..${samples.size}" }
    }
}

/** Reads recorded clips. Decoding a file does not touch camera hardware. */
interface ClipDecoder {
    /**
     * Decodes the frames with presentation times in [fromUs]..[toUs] in order. [request] decides for each
     * frame whether to convert it (null skips the pixels). [onFrame] gets the time and the image, if any.
     */
    fun decode(file: File, fromUs: Long, toUs: Long, request: (ptsUs: Long) -> FrameRequest?, onFrame: (ptsUs: Long, img: Img?) -> Unit)

    /** The presentation time of every frame in time order, read from the container without decoding. */
    fun times(file: File): List<Long>

    /** Decodes the audio track in [fromUs]..[toUs] as mono 16-bit PCM, or null when the file has no audio track. */
    fun audio(file: File, fromUs: Long, toUs: Long): Pcm? = null
}

/**
 * A small clip format for sources without a video encoder (the replay source, tests and the desktop
 * runner): a header, then for each frame an 8-byte time in microseconds, a 4-byte length and a JPEG.
 */
object LkClip {
    const val EXTENSION = "lkclip"
    private const val MAGIC = 0x4C4B434C // "LKCL"

    class Writer(file: File) : AutoCloseable {
        private val out = DataOutputStream(BufferedOutputStream(file.outputStream()))

        init { out.writeInt(MAGIC) }

        @Synchronized
        fun write(ptsUs: Long, jpeg: ByteArray) {
            out.writeLong(ptsUs)
            out.writeInt(jpeg.size)
            out.write(jpeg)
        }

        @Synchronized
        override fun close() = out.close()
    }

    class Decoder(private val ops: ImageOps) : ClipDecoder {
        private val comp = Compositor(ops)

        override fun decode(file: File, fromUs: Long, toUs: Long, request: (Long) -> FrameRequest?, onFrame: (Long, Img?) -> Unit) {
            open(file).use { input ->
                while (true) {
                    val pts = try { input.readLong() } catch (_: EOFException) { return }
                    val len = input.readInt()
                    val jpeg = ByteArray(len).also { input.readFully(it) }
                    if (pts < fromUs) continue
                    if (pts > toUs) return
                    val req = request(pts)
                    val img = req?.let { comp.fit(comp.crop(ops.decode(jpeg), it.roi), it.maxEdge) }
                    onFrame(pts, img)
                }
            }
        }

        override fun times(file: File): List<Long> = open(file).use { input ->
            val out = ArrayList<Long>()
            while (true) {
                val pts = try { input.readLong() } catch (_: EOFException) { break }
                var left = input.readInt()
                while (left > 0) {
                    val n = input.skipBytes(left)
                    if (n <= 0) throw EOFException("${file.name} ends inside a frame")
                    left -= n
                }
                out += pts
            }
            out
        }

        override fun audio(file: File, fromUs: Long, toUs: Long): Pcm? = null

        private fun open(file: File): DataInputStream {
            val input = DataInputStream(BufferedInputStream(file.inputStream()))
            if (input.readInt() != MAGIC) {
                input.close()
                throw LookoutError("clip_corrupt", 500, "${file.name} is not a Lookout clip")
            }
            return input
        }
    }
}

/** Uses [mp4] for .mp4 files and the Lookout clip decoder for the rest. */
class ClipDecoders(private val mp4: ClipDecoder?, private val lk: ClipDecoder) : ClipDecoder {
    override fun decode(file: File, fromUs: Long, toUs: Long, request: (Long) -> FrameRequest?, onFrame: (Long, Img?) -> Unit) =
        pick(file).decode(file, fromUs, toUs, request, onFrame)

    override fun times(file: File): List<Long> = pick(file).times(file)

    override fun audio(file: File, fromUs: Long, toUs: Long): Pcm? = pick(file).audio(file, fromUs, toUs)

    private fun pick(file: File): ClipDecoder =
        if (file.extension == "mp4") mp4 ?: throw LookoutError("clip_unsupported", 501, "No MP4 decoder on this platform") else lk
}
