package app.lookout.core

import kotlinx.serialization.Serializable

/** A line of text that the reader found. [box] is normalized to the image it read. */
class TextLine(val text: String, val box: NRect, val confidence: Double? = null)

/** On-device text recognition. Android uses ML Kit. Desktop has none. */
fun interface TextReader {
    suspend fun read(img: Img): List<TextLine>
}

/** A number in read text, with the unit that follows it, if any. */
@Serializable
data class NumberReading(val value: Double, val text: String, val unit: String? = null)

object Numbers {
    // A time (12:05 or 1:02:33), or a sign, digits and a decimal part. Then an optional short unit such as V, mA, °C or %.
    private val NUMBER = Regex("""(?<![\w.,:])(\d{1,2}(?::\d{2}){1,2}|[-+−]?\d+(?:[.,]\d+)?)(?:\s?([a-zA-Zµ°Ω%]{1,3}))?(?![\w:])""")

    // OCR often reads a zero on a display as the letter O: 57OK is 570K.
    private val LETTER_O = Regex("""(?<=\d)[Oo]+(?=[\dA-Za-z%]|\b)""")

    /**
     * Finds the numbers in [text] in reading order. A comma is a decimal point unless three digits follow it.
     * A time such as 12:05 or 1:02:33 is one reading in seconds (m:ss or h:mm:ss), with unit "time".
     */
    fun parse(text: String): List<NumberReading> = NUMBER.findAll(LETTER_O.replace(text) { "0".repeat(it.value.length) }).mapNotNull { m ->
        val raw = m.groupValues[1]
        if (':' in raw) return@mapNotNull NumberReading(raw.split(':').fold(0.0) { acc, p -> acc * 60 + p.toInt() }, raw, "time")
        val normalized = raw.replace('−', '-').let { s ->
            val comma = s.indexOf(',')
            if (comma >= 0 && s.length - comma - 1 == 3) s.replace(",", "") else s.replace(',', '.')
        }
        val unit = m.groupValues[2].takeIf { it.isNotEmpty() }
        normalized.toDoubleOrNull()?.let { NumberReading(it, m.value.trim(), unit) }
    }.toList()
}
