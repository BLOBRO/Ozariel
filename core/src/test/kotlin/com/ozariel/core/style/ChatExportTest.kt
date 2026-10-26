package com.ozariel.core.style

import kotlin.test.Test
import kotlin.test.assertEquals

class ChatExportTest {
    private val androidExport = """
        26/10/2026 09:58 - Les messages et les appels sont chiffrés de bout en bout.
        26/10/2026 10:00 - Maman: Coucou mon chéri
        26/10/2026 10:00 - Maman: Tu viens dimanche ?
        26/10/2026 10:05 - Ariel: Coucou ! Oui je pense 😊
        on mange à quelle heure ?
        26/10/2026 10:06 - Maman: <Médias omis>
        26/10/2026 10:07 - Maman: Vers 13h
        26/10/2026 10:09 - Ariel: Top à dimanche
    """.trimIndent()

    private val iosExport = """
        [26/10/2026 10:00:12] Léo: t'es chaud ce soir ?
        [26/10/2026 10:01:40] Ariel: grave
    """.trimIndent()

    @Test
    fun parsesAndroidExportWithMultilineMessages() {
        val messages = WhatsAppChatParser.parse(androidExport)
        assertEquals(5, messages.size)
        assertEquals("Maman", messages[0].author)
        assertEquals("Coucou ! Oui je pense 😊\non mange à quelle heure ?", messages[2].text)
        assertEquals(listOf("Maman", "Ariel"), WhatsAppChatParser.authors(messages))
    }

    @Test
    fun parsesIosExport() {
        val messages = WhatsAppChatParser.parse(iosExport)
        assertEquals(listOf("Léo", "Ariel"), messages.map { it.author })
        assertEquals("grave", messages[1].text)
    }

    @Test
    fun extractsPairsGroupingConsecutiveMessages() {
        val pairs = ReplyPairExtractor.extract(WhatsAppChatParser.parse(androidExport), "ariel")
        assertEquals(2, pairs.size)
        assertEquals("Coucou mon chéri\nTu viens dimanche ?", pairs[0].incoming)
        assertEquals("Maman", pairs[0].contact)
        assertEquals("Top à dimanche", pairs[1].reply)
    }

    @Test
    fun ranksSimilarExamplesFirst() {
        val pairs = listOf(
            ReplyPair("Léo", "on se fait un foot demain ?", "carrément"),
            ReplyPair("Maman", "Tu viens manger dimanche ?", "oui !"),
            ReplyPair("Boss", "Le rapport est prêt ?", "Je vous l'envoie ce soir."),
        )
        val best = StyleMemory.rank(pairs, "Maman", "tu viens dimanche", 1).single()
        assertEquals("oui !", best.reply)
    }
}
