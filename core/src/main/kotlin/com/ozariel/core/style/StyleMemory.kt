package com.ozariel.core.style

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.File
import java.text.Normalizer

/** Tout ce que l'agent sait de la façon d'écrire du propriétaire. Stocké uniquement sur le téléphone. */
data class StyleData(
    val owner: String = "",
    val profile: String = "",
    val pairs: List<ReplyPair> = emptyList(),
)

class StyleMemory(private val file: File) {
    private val mapper = jacksonObjectMapper()

    @Volatile
    var data: StyleData = load()
        private set

    fun addPairs(owner: String, newPairs: List<ReplyPair>) {
        val merged = (data.pairs + newPairs).distinct()
        save(data.copy(owner = owner, pairs = merged))
    }

    fun setProfile(profile: String) = save(data.copy(profile = profile))

    fun clear() = save(StyleData())

    /** Les exemples passés les plus proches du message reçu, en privilégiant la même personne. */
    fun similarExamples(contact: String, incoming: String, k: Int = 8): List<ReplyPair> =
        rank(data.pairs, contact, incoming, k)

    private fun load(): StyleData =
        if (file.exists()) runCatching { mapper.readValue<StyleData>(file) }.getOrDefault(StyleData()) else StyleData()

    private fun save(newData: StyleData) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        mapper.writeValue(tmp, newData)
        tmp.renameTo(file)
        data = newData
    }

    companion object {
        fun rank(pairs: List<ReplyPair>, contact: String, incoming: String, k: Int): List<ReplyPair> {
            val query = words(incoming)
            return pairs
                .map { pair ->
                    val overlap = jaccard(query, words(pair.incoming))
                    val sameContact = if (ReplyPairExtractor.sameName(pair.contact, contact)) 0.3 else 0.0
                    pair to overlap + sameContact
                }
                .sortedByDescending { it.second }
                .take(k)
                .map { it.first }
        }

        internal fun words(text: String): Set<String> =
            Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
                .replace(Regex("\\p{M}"), "")
                .split(Regex("[^\\p{L}\\p{N}]+"))
                .filter { it.length > 1 }
                .toSet()

        private fun jaccard(a: Set<String>, b: Set<String>): Double {
            if (a.isEmpty() || b.isEmpty()) return 0.0
            return a.intersect(b).size.toDouble() / a.union(b).size
        }
    }
}
