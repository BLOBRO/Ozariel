package com.ozariel.app.services

import android.app.Notification
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import com.ozariel.app.OzarielApp
import com.ozariel.app.data.Conversation
import com.ozariel.app.data.ReplyAction
import com.ozariel.core.style.ConversationLine
import com.ozariel.core.style.Draft
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Reçoit les notifications des messageries. Pour chaque nouveau message :
 * - le garde en mémoire (l'agent peut le lire et y répondre),
 * - répond tout seul si le contact fait partie des réponses automatiques,
 * - sinon propose une réponse dans ton style, à valider d'un tap.
 */
class MessageListenerService : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lastHandled = mutableMapOf<String, Long>()

    override fun onListenerConnected() {
        instance = this
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    override fun onDestroy() {
        scope.cancel()
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in MESSAGING_APPS) return
        val notification = sbn.notification
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification) ?: return
        val messages = style.messages.ifEmpty { return }
        val last = messages.last()
        // Message envoyé par toi (person absente) : rien à faire.
        val lastSender = last.person?.name?.toString() ?: return
        if (lastSender == style.user.name?.toString()) return

        val isGroup = style.isGroupConversation
        val contact = (if (isGroup) style.conversationTitle?.toString() else null) ?: lastSender
        val key = "${sbn.packageName}|$contact"
        val owner = OzarielApp.instance.settings.ownerName
        val userName = style.user.name?.toString()
        val lines = messages.map {
            val author = it.person?.name?.toString()?.takeIf { name -> name != userName } ?: owner
            ConversationLine(author, it.text?.toString().orEmpty())
        }

        val conversation = Conversation(
            key = key,
            app = appLabel(sbn.packageName),
            contact = contact,
            isGroup = isGroup,
            lines = lines,
            lastIncomingAt = last.timestamp,
            replyAction = findReplyAction(notification),
        )
        val app = OzarielApp.instance
        app.messages.upsert(conversation)

        if ((lastHandled[key] ?: 0L) >= last.timestamp) return
        lastHandled[key] = last.timestamp
        scope.launch { react(conversation) }
    }

    private suspend fun react(conversation: Conversation) {
        val app = OzarielApp.instance
        val settings = app.settings
        if (conversation.replyAction == null || conversation.isGroup) return
        val autoReply = settings.isAutoReplyContact(conversation.contact)
        if (!autoReply && !settings.suggestReplies) return
        if (app.styleMemory.data.pairs.isEmpty()) return
        val writer = app.replyWriter() ?: return

        val draft = runCatching { writer.draft(conversation.contact, conversation.lines) }.getOrNull() ?: return
        when (draft) {
            is Draft.Reply -> if (autoReply) {
                // Petit délai pour ne pas répondre de façon instantanée, ce qui paraîtrait suspect.
                delay((20_000L..60_000L).random())
                sendReply(this, conversation.key, draft.text)
                SuggestionNotifier.autoReplied(this, conversation, draft.text)
            } else {
                SuggestionNotifier.suggest(this, conversation, draft.text)
            }
            is Draft.NeedsOwner -> SuggestionNotifier.needsOwner(this, conversation, draft.reason)
        }
    }

    private fun findReplyAction(notification: Notification): ReplyAction? {
        val actions = notification.actions ?: return null
        val candidates = actions.filter { !it.remoteInputs.isNullOrEmpty() }
        val action = candidates.firstOrNull { it.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY }
            ?: candidates.firstOrNull()
            ?: return null
        val inputs: Array<RemoteInput> = action.remoteInputs.filter { it.allowFreeFormInput }.toTypedArray()
        if (inputs.isEmpty()) return null
        return ReplyAction(action.actionIntent, inputs)
    }

    private fun appLabel(packageName: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    companion object {
        @Volatile
        var instance: MessageListenerService? = null
            private set

        val MESSAGING_APPS = setOf(
            "com.whatsapp",
            "com.whatsapp.w4b",
            "org.telegram.messenger",
            "org.thoughtcrime.securesms",
            "com.facebook.orca",
            "com.instagram.android",
            "com.google.android.apps.messaging",
            "com.samsung.android.messaging",
            "com.discord",
            "com.snapchat.android",
        )

        /** Envoie un message via le bouton « Répondre » de la notification. */
        fun sendReply(context: Context, key: String, text: String): Boolean {
            val app = OzarielApp.instance
            val conversation = app.messages.conversations.value[key] ?: return false
            val action = conversation.replyAction ?: return false
            return runCatching {
                action.send(context, text)
                app.messages.appendOwnReply(key, app.settings.ownerName, text)
            }.isSuccess
        }

        /** Lecteurs de musique actifs (Spotify, YouTube Music…). Nécessite l'accès aux notifications. */
        fun mediaControllers(context: Context): List<MediaController> {
            val manager = context.getSystemService(MediaSessionManager::class.java)
            val component = ComponentName(context, MessageListenerService::class.java)
            return runCatching { manager.getActiveSessions(component) }.getOrDefault(emptyList())
        }
    }
}
