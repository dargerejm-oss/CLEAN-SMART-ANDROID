package com.jmdargere.cleansmart.data

import com.jmdargere.cleansmart.Tr
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.Locale

/** La langue d'affichage des nombres et des dates (suit la langue de l'interface). */
private val loc: Locale get() = Tr.locale

/** 1 536 000 000 -> "1,4 Go" (ou "1.4 GB" en anglais) */
fun fmtBytes(b: Long): String {
    val en = Tr.anglais
    val units = listOf((if (en) "GB" else "Go") to (1L shl 30), (if (en) "MB" else "Mo") to (1L shl 20), (if (en) "KB" else "Ko") to (1L shl 10))
    for ((n, v) in units) {
        if (b >= v) {
            val x = b.toDouble() / v
            val nf = NumberFormat.getNumberInstance(loc).apply { maximumFractionDigits = if (x >= 100) 0 else 1 }
            return nf.format(x) + " " + n
        }
    }
    return "$b " + if (en) "B" else "o"
}

fun fmtNum(n: Number): String = NumberFormat.getIntegerInstance(loc).format(n)

fun fmtDate(ms: Long): String =
    if (ms <= 0) "" else DateFormat.getDateInstance(DateFormat.MEDIUM, loc).format(Date(ms))

/** "3 photos" / "1 photo" : le mot est traduit si l'interface est en anglais. */
fun plural(n: Int, one: String, many: String = one + "s") = "${fmtNum(n)} " + Tr.t(if (n > 1) many else one)
