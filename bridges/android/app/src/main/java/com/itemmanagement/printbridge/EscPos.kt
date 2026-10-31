package com.itemmanagement.printbridge

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ThermalLine(
    val text: String,
    val bold: Boolean = false,
    val align: String? = null,
    val doubleSize: Boolean = false,
)

object EscPos {
    private const val ESC = 0x1B.toByte()
    private const val GS = 0x1D.toByte()

    fun columns(paperWidth: String): Int = if (paperWidth == "MM58") 32 else 48

    fun build(lines: List<ThermalLine>, paperWidth: String): ByteArray {
        val width = columns(paperWidth)
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(ESC, 0x40.toByte()))
        out.write(byteArrayOf(ESC, 't'.code.toByte(), 0))

        for (line in lines) {
            val safe = safe(line.text)
            val isDouble = line.doubleSize && safe.length <= width / 2
            val align = when (line.align) {
                "center" -> 1
                "right" -> 2
                else -> 0
            }
            out.write(byteArrayOf(ESC, 'a'.code.toByte(), align.toByte()))
            out.write(byteArrayOf(ESC, 'E'.code.toByte(), if (line.bold) 1 else 0))
            out.write(byteArrayOf(GS, '!'.code.toByte(), if (isDouble) 0x11 else 0x00))
            wrap(safe, if (isDouble) width / 2 else width).forEach { part ->
                out.write(part.toByteArray(StandardCharsets.US_ASCII))
                out.write(0x0A)
            }
        }

        out.write(byteArrayOf(ESC, 'E'.code.toByte(), 0))
        out.write(byteArrayOf(GS, '!'.code.toByte(), 0))
        out.write(byteArrayOf(ESC, 'a'.code.toByte(), 0))
        out.write("\n\n\n".toByteArray(StandardCharsets.US_ASCII))
        out.write(byteArrayOf(GS, 'V'.code.toByte(), 66, 3))
        return out.toByteArray()
    }

    fun testSlip(printerName: String, paperWidth: String): ByteArray {
        val width = columns(paperWidth)
        val stamp = SimpleDateFormat("dd MMM yyyy HH:mm:ss", Locale.US).format(Date())
        return build(
            listOf(
                ThermalLine("ITEM MANAGEMENT", bold = true, align = "center", doubleSize = true),
                ThermalLine("Print bridge test", align = "center"),
                ThermalLine("-".repeat(width)),
                ThermalLine("Printer: $printerName"),
                ThermalLine("Paper: ${if (paperWidth == "MM58") "58 mm" else "80 mm"}"),
                ThermalLine("Time: $stamp"),
                ThermalLine("-".repeat(width)),
                ThermalLine("If you can read this,", align = "center"),
                ThermalLine("silent printing is ready.", align = "center"),
                ThermalLine(""),
            ),
            paperWidth,
        )
    }

    private fun wrap(text: String, width: Int): List<String> {
        if (text.isEmpty()) return listOf("")
        val result = mutableListOf<String>()
        val current = StringBuilder()
        for (rawWord in text.split(' ').filter { it.isNotEmpty() }) {
            var word = rawWord
            while (word.length > width) {
                if (current.isNotEmpty()) {
                    result.add(current.toString())
                    current.clear()
                }
                result.add(word.substring(0, width))
                word = word.substring(width)
            }
            when {
                current.isEmpty() -> current.append(word)
                current.length + 1 + word.length <= width -> current.append(' ').append(word)
                else -> {
                    result.add(current.toString())
                    current.clear()
                    current.append(word)
                }
            }
        }
        if (current.isNotEmpty()) result.add(current.toString())
        return result
    }

    private fun safe(text: String?): String {
        if (text.isNullOrEmpty()) return ""
        val builder = StringBuilder(text.length)
        for (ch in text) {
            when {
                ch == '\u20B9' -> builder.append("Rs")
                ch == '\u2013' || ch == '\u2014' -> builder.append('-')
                ch == '\u2018' || ch == '\u2019' -> builder.append('\'')
                ch == '\u201C' || ch == '\u201D' -> builder.append('"')
                ch == '\u2026' -> builder.append("...")
                ch == '\t' -> builder.append(' ')
                ch.code in 32..126 -> builder.append(ch)
                else -> builder.append('?')
            }
        }
        return builder.toString()
    }
}
