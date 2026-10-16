package br.com.wanotifkeeper

import java.text.Normalizer
import java.util.Locale

/** Normalização apenas para busca; nunca altera identidade, nome ou vínculo persistido. */
object EntitySearch {
    fun searchable(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT)

    fun matches(query: String, vararg fields: String): Boolean {
        val needle = searchable(query.trim())
        return needle.isEmpty() || fields.any { searchable(it).contains(needle) }
    }
}
