package com.ozariel.app.data

import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.ozariel.core.style.ConversationLine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** Une conversation vue dans les notifications d'une messagerie (WhatsApp, SMS, Telegram…). */
data class Conversation(
    val key: String,
    val app: String,
    val contact: String,
    val isGroup: Boolean,
    val lines: List<ConversationLine>,
    val lastIncomingAt: Long,
    val replyAction: ReplyAction?,
)

/** Le bouton « Répondre » de la notification, qui permet d'envoyer un message sans ouvrir l'app. */
data class ReplyAction(val intent: PendingIntent, val remoteInputs: Array<RemoteInput>) {
    fun send(context: Context, text: String) {
        val fill = Intent()
        val results = Bundle()
        remoteInputs.forEach { results.putCharSequence(it.resultKey, text) }
        RemoteInput.addResultsToIntent(remoteInputs, fill, results)
        intent.send(context, 0, fill)
    }

    override fun equals(other: Any?) = other is ReplyAction && other.intent == intent
    override fun hashCode() = intent.hashCode()
}

/** Messages reçus pendant que l'app tourne. Gardés en mémoire seulement, jamais envoyés ailleurs. */
class MessageStore {
    private val _conversations = MutableStateFlow<Map<String, Conversation>>(emptyMap())
    val conversations: StateFlow<Map<String, Conversation>> = _conversations

    fun upsert(conversation: Conversation) = _conversations.update { it + (conversation.key to conversation) }

    fun appendOwnReply(key: String, owner: String, text: String) = _conversations.update { all ->
        val c = all[key] ?: return@update all
        all + (key to c.copy(lines = c.lines + ConversationLine(owner, text)))
    }

    fun find(contact: String): Conversation? {
        val all = _conversations.value.values.sortedByDescending { it.lastIncomingAt }
        return all.firstOrNull { it.contact.equals(contact, ignoreCase = true) }
            ?: all.firstOrNull { it.contact.contains(contact, ignoreCase = true) }
    }

    fun recent(limit: Int = 10): List<Conversation> =
        _conversations.value.values.sortedByDescending { it.lastIncomingAt }.take(limit)
}
