package com.ozariel.core.style

import com.ozariel.core.llm.TextModel

/** Un message d'une conversation en cours, tel que vu dans les notifications. */
data class ConversationLine(val author: String, val text: String)

sealed interface Draft {
    data class Reply(val text: String) : Draft

    /** L'agent estime que seul le propriétaire peut répondre (rendez-vous, argent, sujet sensible…). */
    data class NeedsOwner(val reason: String) : Draft
}

/**
 * Écrit des réponses dans le style du propriétaire, à partir de son profil de style
 * et de ses vraies réponses passées les plus proches.
 */
class ReplyWriter(
    private val model: TextModel,
    private val memory: StyleMemory,
) {
    /** Analyse un échantillon de vraies réponses et en tire une description du style d'écriture. */
    suspend fun buildProfile(): String {
        val data = memory.data
        require(data.pairs.isNotEmpty()) { "Importe d'abord des discussions." }
        val sample = data.pairs.shuffled(kotlin.random.Random(42)).take(400)
        val examples = sample.joinToString("\n\n") { "${it.contact} : ${it.incoming}\n${data.owner} : ${it.reply}" }
        val prompt = """
            Voici des extraits de vraies conversations de ${data.owner}.
            Décris précisément sa façon d'écrire pour qu'un assistant puisse répondre exactement comme ${data.owner}.
            Couvre : longueur des messages, ponctuation, majuscules, emojis (lesquels, quand), abréviations et fautes habituelles,
            expressions récurrentes, salutations, ton (humour, chaleur, sécheresse), langues mélangées,
            et les différences selon les personnes (famille, amis, travail).
            Donne des exemples courts tirés des extraits. Réponds en français, en liste concise.

            $examples
        """.trimIndent()
        val profile = model.complete(prompt, thorough = true)
        memory.setProfile(profile)
        return profile
    }

    suspend fun draft(contact: String, conversation: List<ConversationLine>): Draft {
        val data = memory.data
        val owner = data.owner.ifBlank { "le propriétaire" }
        val incoming = conversation.lastOrNull { it.author != owner }?.text.orEmpty()
        val examples = memory.similarExamples(contact, incoming)
            .joinToString("\n\n") { "${it.contact} : ${it.incoming}\n$owner : ${it.reply}" }
        val thread = conversation.takeLast(15).joinToString("\n") { "${it.author} : ${it.text}" }

        val prompt = """
            Tu écris à la place de $owner, qui doit recevoir une réponse impossible à distinguer de ses vrais messages.

            Profil de style de $owner :
            ${data.profile.ifBlank { "(pas encore de profil, inspire-toi des exemples)" }}

            Vraies réponses passées de $owner dans des situations proches :
            ${examples.ifBlank { "(aucun exemple)" }}

            Conversation en cours avec $contact :
            $thread

            Écris la prochaine réponse de $owner à $contact.
            - Respecte son style : longueur, emojis, ponctuation, ton, façon de s'adresser à cette personne.
            - N'invente aucun fait (horaire, lieu, engagement, information personnelle) que $owner n'a pas déjà donné.
            - Si la réponse demande une décision ou une information que seul $owner connaît
              (accepter un rendez-vous, argent, sujet grave ou intime), réponds uniquement : PASSER: <raison en quelques mots>
            - Sinon, réponds uniquement avec le texte du message, sans guillemets ni commentaire.
        """.trimIndent()

        val answer = model.complete(prompt).trim()
        return if (answer.isBlank()) {
            Draft.NeedsOwner("aucune réponse générée")
        } else if (answer.startsWith("PASSER")) {
            Draft.NeedsOwner(answer.removePrefix("PASSER").trimStart(':', ' '))
        } else {
            Draft.Reply(answer.removeSurrounding("\""))
        }
    }
}
