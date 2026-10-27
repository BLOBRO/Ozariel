package com.ozariel.app.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ozariel.app.OzarielApp
import com.ozariel.app.services.MessageListenerService
import com.ozariel.app.tools.AndroidTools
import com.ozariel.core.agent.Assistant
import com.ozariel.core.agent.ClaudeAgent
import com.ozariel.core.agent.GeminiAgent
import com.ozariel.core.agent.Provider
import com.ozariel.core.agent.AgentConfig
import com.ozariel.core.agent.ConfirmationGate
import com.ozariel.core.style.Draft
import com.ozariel.core.style.ReplyPairExtractor
import com.ozariel.core.style.WhatsAppChatParser
import com.ozariel.core.tools.DeviceTool
import com.ozariel.core.tools.ToolArgs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.zip.ZipInputStream

object AgentFactory {
    /**
     * Crée un agent avec toutes les actions du téléphone.
     * En arrière-plan (tâches programmées), personne ne peut confirmer : les actions sensibles
     * sont refusées sauf si l'utilisateur l'a autorisé dans les réglages.
     */
    fun create(context: Context, background: Boolean, gate: ConfirmationGate? = null): Assistant? {
        val app = OzarielApp.instance
        val settings = app.settings
        val effectiveGate = gate ?: ConfirmationGate { _, _ -> background && settings.allowSensitiveInTasks }
        val config = AgentConfig(model = settings.model, ownerName = settings.ownerName)
        val tools = AndroidTools.all(context.applicationContext)
        return when (settings.model.provider) {
            Provider.CLAUDE -> app.claude()?.let { ClaudeAgent(it, config, tools, effectiveGate) }
            Provider.GEMINI -> app.gemini()?.let { GeminiAgent(it, config, tools, effectiveGate) }
        }
    }
}

sealed interface ChatEntry {
    data class User(val text: String) : ChatEntry
    data class Assistant(val text: String) : ChatEntry
    data class Action(val text: String, val failed: Boolean = false) : ChatEntry
}

data class PendingConfirmation(val tool: DeviceTool, val args: ToolArgs, val answer: CompletableDeferred<Boolean>)

class AgentViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as OzarielApp

    val chat: StateFlow<List<ChatEntry>> = AgentSession.chat
    val busy: StateFlow<Boolean> = AgentSession.busy
    val confirmation: StateFlow<PendingConfirmation?> = AgentSession.confirmation

    private val _styleStatus = MutableStateFlow("")
    val styleStatus: StateFlow<String> = _styleStatus

    fun send(text: String) {
        AgentSession.send(app, text)
    }

    fun answerConfirmation(accepted: Boolean) = AgentSession.answerConfirmation(accepted)

    fun newConversation() = AgentSession.newConversation()

    /** Réglages modifiés (clé, modèle…) : le prochain message recréera l'agent. */
    fun settingsChanged() = AgentSession.settingsChanged()

    fun importChats(context: Context, uris: List<Uri>) {
        viewModelScope.launch {
            _styleStatus.value = "Lecture des discussions…"
            val texts = withContext(Dispatchers.IO) { uris.flatMap { readTexts(context, it) } }
            val chats = texts.map(WhatsAppChatParser::parse).filter { it.isNotEmpty() }
            if (chats.isEmpty()) {
                _styleStatus.value = "Aucun message trouvé. Exporte la discussion depuis WhatsApp (sans médias) et choisis le fichier .txt ou .zip."
                return@launch
            }
            val authorsPerChat = chats.map { chat -> WhatsAppChatParser.authors(chat) }
            val owner = guessOwner(authorsPerChat) ?: run {
                val names = authorsPerChat.flatten().distinct().joinToString(", ")
                _styleStatus.value = "Je ne sais pas lequel de ces noms est le tien : $names. " +
                    "Écris ton nom exactement comme dans la discussion dans les Réglages, puis réimporte."
                return@launch
            }
            app.settings.ownerName = owner
            val pairs = chats.flatMap { ReplyPairExtractor.extract(it, owner) }
            app.styleMemory.addPairs(owner, pairs)
            _styleStatus.value = "${pairs.size} réponses de $owner ajoutées (${app.styleMemory.data.pairs.size} au total). " +
                "Appuie sur « Analyser mon style » pour mettre à jour ton profil."
        }
    }

    fun buildProfile() {
        val writer = app.replyWriter() ?: run {
            _styleStatus.value = "Ajoute d'abord ta clé API dans les Réglages."
            return
        }
        viewModelScope.launch {
            _styleStatus.value = "Analyse de ton style en cours…"
            _styleStatus.value = runCatching { writer.buildProfile() }
                .fold({ "Profil de style mis à jour." }, { "Échec de l'analyse : ${it.message}" })
        }
    }

    fun clearStyle() {
        app.styleMemory.clear()
        _styleStatus.value = "Mémoire de style effacée."
    }

    suspend fun draftFor(key: String): String {
        val conversation = app.messages.conversations.value[key] ?: return ""
        val writer = app.replyWriter() ?: return "Ajoute ta clé API dans les Réglages."
        return when (val d = runCatching { writer.draft(conversation.contact, conversation.lines) }.getOrElse { return "Erreur : ${it.message}" }) {
            is Draft.Reply -> d.text
            is Draft.NeedsOwner -> ""
        }
    }

    fun sendReply(key: String, text: String): Boolean = MessageListenerService.sendReply(app, key, text)

    private fun readTexts(context: Context, uri: Uri): List<String> {
        val resolver = context.contentResolver
        val type = resolver.getType(uri).orEmpty()
        val name = uri.lastPathSegment.orEmpty()
        return if (type.contains("zip") || name.endsWith(".zip")) {
            resolver.openInputStream(uri)?.use { input ->
                val zip = ZipInputStream(input)
                generateSequence { zip.nextEntry }
                    .filter { it.name.endsWith(".txt") }
                    .map { zip.readBytes().toString(Charsets.UTF_8) }
                    .toList()
            }.orEmpty()
        } else {
            listOfNotNull(resolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) })
        }
    }

    /**
     * Le propriétaire du téléphone : le nom indiqué dans les réglages s'il apparaît,
     * sinon la seule personne présente dans toutes les discussions importées.
     */
    private fun guessOwner(authorsPerChat: List<List<String>>): String? {
        val configured = app.settings.ownerName
        authorsPerChat.flatten().firstOrNull { ReplyPairExtractor.sameName(it, configured) }?.let { return it }
        if (authorsPerChat.size < 2) return null
        val common = authorsPerChat.map { it.toSet() }.reduce { a, b -> a intersect b }
        return common.singleOrNull()
    }
}
