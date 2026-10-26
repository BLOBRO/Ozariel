package com.ozariel.core.style

/** Un message lu dans un export de discussion. */
data class ChatMessage(val timestamp: String, val author: String, val text: String)

/** Un message reçu et la réponse que le propriétaire du téléphone y a donnée. */
data class ReplyPair(val contact: String, val incoming: String, val reply: String)

/**
 * Lit les exports texte de WhatsApp (menu de la discussion, Plus, Exporter la discussion, Sans médias).
 * Gère les formats Android (« 26/10/2026 10:23 - Nom: texte ») et iPhone (« [26/10/2026 10:23:45] Nom: texte »).
 */
object WhatsAppChatParser {
    private val line = Regex(
        """^‎?\[?(\d{1,2}[/.\-]\d{1,2}[/.\-]\d{2,4}),?\s(\d{1,2}[:h]\d{2}(?::\d{2})?(?:\s?[APap]\.?[Mm]\.?)?)\]?\s?(?:-\s)?([^:]{1,60}?):\s(.*)$"""
    )

    private val ignored = listOf(
        "<médias omis>", "<media omitted>", "<pièce jointe", "<attached:",
        "ce message a été supprimé", "vous avez supprimé ce message", "this message was deleted",
        "you deleted this message", "message modifié", "<ce message a été modifié>",
        "image absente", "vidéo absente", "audio omis", "sticker omis", "gif omis",
    )

    fun parse(text: String): List<ChatMessage> {
        val messages = mutableListOf<ChatMessage>()
        var current: ChatMessage? = null
        for (raw in text.lineSequence()) {
            val match = line.find(raw.trimEnd())
            if (match != null) {
                current?.let { messages += it }
                val (date, time, author, body) = match.destructured
                current = ChatMessage("$date $time", author.trim().trimStart('‎', '~', ' '), body)
            } else if (current != null && raw.isNotBlank()) {
                // Message sur plusieurs lignes.
                current = current.copy(text = current.text + "\n" + raw)
            }
        }
        current?.let { messages += it }
        return messages.filterNot { isNoise(it.text) }
    }

    fun authors(messages: List<ChatMessage>): List<String> =
        messages.groupingBy { it.author }.eachCount().entries.sortedByDescending { it.value }.map { it.key }

    private fun isNoise(text: String): Boolean {
        val t = text.trim().trimStart('‎').lowercase()
        return t.isEmpty() || ignored.any { t.contains(it) }
    }
}

/**
 * Transforme une discussion en paires « ce qu'on m'a écrit → ce que j'ai répondu ».
 * Les messages consécutifs d'une même personne sont regroupés en un seul tour de parole.
 */
object ReplyPairExtractor {
    fun extract(messages: List<ChatMessage>, owner: String): List<ReplyPair> {
        val turns = mutableListOf<Pair<String, MutableList<String>>>()
        for (m in messages) {
            val last = turns.lastOrNull()
            if (last != null && last.first == m.author) last.second += m.text else turns += m.author to mutableListOf(m.text)
        }
        val pairs = mutableListOf<ReplyPair>()
        for (i in 1 until turns.size) {
            val (author, texts) = turns[i]
            val (previousAuthor, previousTexts) = turns[i - 1]
            if (sameName(author, owner) && !sameName(previousAuthor, owner)) {
                pairs += ReplyPair(
                    contact = previousAuthor,
                    incoming = previousTexts.joinToString("\n"),
                    reply = texts.joinToString("\n"),
                )
            }
        }
        return pairs
    }

    fun sameName(a: String, b: String): Boolean = a.trim().equals(b.trim(), ignoreCase = true)
}
