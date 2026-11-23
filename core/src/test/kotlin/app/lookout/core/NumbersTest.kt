package app.lookout.core

import kotlin.test.Test
import kotlin.test.assertEquals

class NumbersTest {
    private fun values(text: String) = Numbers.parse(text).map { it.value to it.unit }

    @Test fun readsMeterStyleValues() {
        assertEquals(listOf(12.04 to "V"), values("DC 12.04 V"))
        assertEquals(listOf(-3.3 to "mA"), values("−3.3mA"))
        assertEquals(listOf(215.0 to "°C", 60.0 to "°C"), values("Nozzle 215°C / Bed 60°C"))
        assertEquals(listOf(45.0 to "%"), values("45%"))
    }

    @Test fun commaIsADecimalPointUnlessThreeDigitsFollow() {
        assertEquals(listOf(3.14 to null), values("3,14"))
        assertEquals(listOf(1000.0 to null), values("1,000"))
    }

    @Test fun timeIsOneReadingInSeconds() {
        assertEquals(listOf(1002.0 to "time"), values("16:42"))
        assertEquals(listOf(3753.0 to "time", 45.0 to "%"), values("Left 1:02:33 45%"))
    }

    @Test fun letterOAfterADigitIsAZero() {
        assertEquals(listOf(570.0 to "K", 8.0 to "h"), values("Adam Mockler 57OK 8h ago"))
        assertEquals(listOf(100.0 to "V"), values("1O0V"))
        assertEquals(listOf(5.0), Numbers.parse("5 OK").map { it.value }, "a separate O is not a zero")
    }

    @Test fun ignoresDigitsInsideWords() {
        assertEquals(emptyList(), values("ESP32 LM7805"))
        assertEquals(listOf(5.0 to "V"), values("LM7805 out 5 V"))
    }
}
