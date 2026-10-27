package com.ozariel.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.anthropic.client.AnthropicClient
import com.ozariel.app.data.MessageStore
import com.ozariel.app.data.Settings
import com.ozariel.app.data.TaskStore
import com.ozariel.core.agent.ClaudeClients
import com.ozariel.core.agent.Provider
import com.ozariel.core.llm.ClaudeText
import com.ozariel.core.llm.GeminiClient
import com.ozariel.core.llm.GeminiText
import com.ozariel.core.llm.TextModel
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
    private var cachedGeminiKey: String? = null
    private var cachedGemini: GeminiClient? = null

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

    @Synchronized
    fun gemini(): GeminiClient? {
        val key = settings.geminiKey.takeIf { it.isNotBlank() } ?: return null
        if (key != cachedGeminiKey) {
            cachedGemini = GeminiClient(key)
            cachedGeminiKey = key
        }
        return cachedGemini
    }

    /** Le modèle de texte du fournisseur choisi dans les réglages, ou null si sa clé manque. */
    fun textModel(): TextModel? {
        val model = settings.model
        return when (model.provider) {
            Provider.CLAUDE -> claude()?.let { ClaudeText(it, model) }
            Provider.GEMINI -> gemini()?.let { GeminiText(it, model) }
        }
    }

    fun replyWriter(): ReplyWriter? = textModel()?.let { ReplyWriter(it, styleMemory) }

    private fun createChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_SUGGESTIONS, "Réponses suggérées", NotificationManager.IMPORTANCE_HIGH)
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_TASKS, "Tâches programmées", NotificationManager.IMPORTANCE_DEFAULT)
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_WAKE, "Réveil par la voix", NotificationManager.IMPORTANCE_LOW)
        )
    }

    companion object {
        const val CHANNEL_SUGGESTIONS = "suggestions"
        const val CHANNEL_TASKS = "tasks"
        const val CHANNEL_WAKE = "wake_word"

        lateinit var instance: OzarielApp
            private set
    }
}
