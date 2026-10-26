package com.ozariel.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.anthropic.client.AnthropicClient
import com.ozariel.app.data.MessageStore
import com.ozariel.app.data.Settings
import com.ozariel.app.data.TaskStore
import com.ozariel.core.agent.ClaudeClients
import com.ozariel.core.style.ReplyWriter
import com.ozariel.core.style.StyleMemory
import java.io.File

class OzarielApp : Application() {
    lateinit var settings: Settings
        private set
    lateinit var styleMemory: StyleMemory
        private set
    lateinit var messages: MessageStore
        private set
    lateinit var tasks: TaskStore
        private set

    private var cachedKey: String? = null
    private var cachedClient: AnthropicClient? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = Settings(this)
        styleMemory = StyleMemory(File(filesDir, "style.json"))
        messages = MessageStore()
        tasks = TaskStore(this)
        createChannels()
    }

    /** Client Claude, recréé si la clé API change. Null tant qu'aucune clé n'est saisie. */
    @Synchronized
    fun claude(): AnthropicClient? {
        val key = settings.apiKey.takeIf { it.isNotBlank() } ?: return null
        if (key != cachedKey) {
            cachedClient?.close()
            cachedClient = ClaudeClients.create(key)
            cachedKey = key
        }
        return cachedClient
    }

    fun replyWriter(): ReplyWriter? = claude()?.let { ReplyWriter(it, styleMemory, settings.model) }

    private fun createChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_SUGGESTIONS, "Réponses suggérées", NotificationManager.IMPORTANCE_HIGH)
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_TASKS, "Tâches programmées", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    companion object {
        const val CHANNEL_SUGGESTIONS = "suggestions"
        const val CHANNEL_TASKS = "tasks"

        lateinit var instance: OzarielApp
            private set
    }
}
