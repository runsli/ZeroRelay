package app.zerorelay.ui.safety

/** Formats identity fingerprints for readable side-by-side comparison. */
object SafetyNumberFormat {
    fun displayRows(fingerprint: String): List<String> {
        val segments = fingerprint.split("-").filter { it.isNotBlank() }
        if (segments.isEmpty()) return listOf(fingerprint)
        return segments.chunked(2).map { row -> row.joinToString("  ") }
    }

    fun displayText(fingerprint: String): String = displayRows(fingerprint).joinToString("\n")
}
