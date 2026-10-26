package com.ozariel.app.services

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.ozariel.app.OzarielApp
import com.ozariel.app.R
import com.ozariel.app.data.Conversation

/** Notifications d'Ozariel : réponses suggérées, réponses envoyées, messages qui demandent ton avis. */
object SuggestionNotifier {
    private const val EXTRA_KEY = "key"
    private const val EXTRA_TEXT = "text"
    private const val EXTRA_ID = "id"
    const val ACTION_SEND = "com.ozariel.app.SEND_SUGGESTION"

    fun suggest(context: Context, conversation: Conversation, text: String) {
        val id = conversation.key.hashCode()
        val send = PendingIntent.getBroadcast(
            context,
            id,
            Intent(context, ReplyActionReceiver::class.java)
                .setAction(ACTION_SEND)
                .putExtra(EXTRA_KEY, conversation.key)
                .putExtra(EXTRA_TEXT, text)
                .putExtra(EXTRA_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        notify(
            context,
            id,
            NotificationCompat.Builder(context, OzarielApp.CHANNEL_SUGGESTIONS)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("Réponse proposée pour ${conversation.contact}")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .addAction(0, "Envoyer", send)
                .setAutoCancel(true)
                .build(),
        )
    }

    fun autoReplied(context: Context, conversation: Conversation, text: String) = notify(
        context,
        conversation.key.hashCode(),
        NotificationCompat.Builder(context, OzarielApp.CHANNEL_SUGGESTIONS)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("J'ai répondu à ${conversation.contact}")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build(),
    )

    fun needsOwner(context: Context, conversation: Conversation, reason: String) = notify(
        context,
        conversation.key.hashCode(),
        NotificationCompat.Builder(context, OzarielApp.CHANNEL_SUGGESTIONS)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("${conversation.contact} attend ta réponse")
            .setContentText("Je préfère te laisser répondre : $reason")
            .build(),
    )

    fun notify(context: Context, id: Int, notification: android.app.Notification) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) NotificationManagerCompat.from(context).notify(id, notification)
    }

    internal fun handle(context: Context, intent: Intent) {
        if (intent.action != ACTION_SEND) return
        val key = intent.getStringExtra(EXTRA_KEY) ?: return
        val text = intent.getStringExtra(EXTRA_TEXT) ?: return
        MessageListenerService.sendReply(context, key, text)
        NotificationManagerCompat.from(context).cancel(intent.getIntExtra(EXTRA_ID, 0))
    }
}

class ReplyActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = SuggestionNotifier.handle(context, intent)
}
