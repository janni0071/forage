package forage.core.importer

/** Minimal RFC 4180 reader: quoted fields, doubled quotes, commas and line breaks inside quotes. */
internal object Csv {
    /** Rows as header-name to value maps. Rows shorter than the header simply lack the trailing keys. */
    fun read(text: String): List<Map<String, String>> {
        val rows = parse(text.removePrefix("\uFEFF")).filter { row -> row.any { it.isNotEmpty() } }
        val header = rows.firstOrNull()?.map { it.trim() } ?: return emptyList()
        return rows.drop(1).map { row -> header.zip(row).toMap() }
    }

    private fun parse(text: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        fun endField() { row += field.toString(); field.setLength(0) }
        fun endRow() { endField(); rows += row; row = ArrayList() }
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && text.getOrNull(i + 1) == '"' -> { field.append('"'); i++ }
                c == '"' -> quoted = !quoted
                quoted -> field.append(c)
                c == ',' -> endField()
                c == '\n' -> endRow()
                c == '\r' -> {}
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) endRow()
        return rows
    }
}
