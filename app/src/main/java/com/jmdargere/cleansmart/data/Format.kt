package com.jmdargere.cleansmart.data

import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.Locale

private val FR = Locale.FRANCE

/** 1 536 000 000 -> "1,4 Go" */
fun fmtBytes(b: Long): String {
    val units = listOf("Go" to (1L shl 30), "Mo" to (1L shl 20), "Ko" to (1L shl 10))
    for ((n, v) in units) {
        if (b >= v) {
            val x = b.toDouble() / v
            val nf = NumberFormat.getNumberInstance(FR).apply { maximumFractionDigits = if (x >= 100) 0 else 1 }
            return nf.format(x) + " " + n
        }
    }
    return "$b o"
}

fun fmtNum(n: Number): String = NumberFormat.getIntegerInstance(FR).format(n)

fun fmtDate(ms: Long): String =
    if (ms <= 0) "" else DateFormat.getDateInstance(DateFormat.MEDIUM, FR).format(Date(ms))

fun plural(n: Int, one: String, many: String = one + "s") = "${fmtNum(n)} " + if (n > 1) many else one
