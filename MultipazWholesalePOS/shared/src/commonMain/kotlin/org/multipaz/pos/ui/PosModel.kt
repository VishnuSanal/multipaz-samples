package org.multipaz.pos.ui

/** The high-level screens of the terminal simulation. */
enum class PosScreen { AMOUNT_ENTRY, CHECKOUT, SETTLEMENT }

/** Formats an amount in cents as US currency, e.g. 1234567 -> "$12,345.67". */
fun formatCurrency(cents: Long): String {
    val negative = cents < 0
    val abs = if (negative) -cents else cents
    val dollars = abs / 100
    val rem = (abs % 100).toString().padStart(2, '0')
    val grouped = groupThousands(dollars.toString())
    return (if (negative) "-$" else "$") + "$grouped.$rem"
}

/** Formats a raw dollars-and-cents string typed on the numpad for display, e.g. "1450.7" -> "1,450.70". */
fun formatAmountEntry(raw: String): String {
    if (raw.isEmpty()) return "0.00"
    val dot = raw.indexOf('.')
    val intPart = if (dot >= 0) raw.substring(0, dot) else raw
    val fracPart = if (dot >= 0) raw.substring(dot + 1) else ""
    val intGrouped = groupThousands(intPart.ifEmpty { "0" }.trimStart('0').ifEmpty { "0" })
    return if (dot >= 0) "$intGrouped.${fracPart.padEnd(2, '0').take(2)}" else "$intGrouped.00"
}

private fun groupThousands(digits: String): String {
    val sb = StringBuilder()
    val n = digits.length
    for (i in digits.indices) {
        if (i > 0 && (n - i) % 3 == 0) sb.append(',')
        sb.append(digits[i])
    }
    return sb.toString()
}
