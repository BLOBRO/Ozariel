package com.ozariel.app.ui

import android.content.Context
import com.ozariel.core.agent.AgentEvent
import com.ozariel.core.agent.Assistant
import com.ozariel.core.agent.ConfirmationGate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * La conversation avec l'agent, partagée entre l'onglet Agent et la bulle flottante :
 * même historique, même agent et mêmes demandes de confirmation.
 */
object AgentSession {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _chat = MutableStateFlow<List<ChatEntry>>(emptyList())
    val chat: StateFlow<List<ChatEntry>> = _chat

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val _confirmation = MutableStateFlow<PendingConfirmation?>(null)
    val confirmation: StateFlow<PendingConfirmation?> = _confirmation

    private var agent: Assistant? = null

    private val gate = ConfirmationGate { tool, args ->
        val pending = PendingConfirmation(tool, args, CompletableDeferred())
        _confirmation.value = pending
        pending.answer.await().also { _confirmation.value = null }
    }

    /**
     * Envoie une demande à l'agent. [onFinished] reçoit sa réponse finale (ou le message d'erreur).
     * Renvoie false si une demande est déjà en cours.
     */
    fun send(context: Context, text: String, onFinished: (String) -> Unit = {}): Boolean {
        if (text.isBlank() || _busy.value) return false
        _chat.update { it + ChatEntry.User(text) }
        val current = agent ?: AgentFactory.create(context, background = false, gate = gate)?.also { agent = it }
        if (current == null) {
            val message = "Ajoute la clé API du modèle choisi dans l'onglet Réglages."
            _chat.update { it + ChatEntry.Action(message, failed = true) }
            onFinished(message)
            return true
        }
        _busy.value = true
        scope.launch {
            val answer = try {
                current.send(text) { event ->
                    val entry = when (event) {
                        is AgentEvent.Said -> ChatEntry.Assistant(event.text)
                        is AgentEvent.ToolStarted -> if (event.tool == "set_mood") null else ChatEntry.Action("${labelFor(event.tool)}…")
                        is AgentEvent.ToolFinished -> if (event.result.isError) ChatEntry.Action(event.result.text, failed = true) else null
                        is AgentEvent.Failed -> ChatEntry.Action(event.message, failed = true)
                    }
                    entry?.let { e -> _chat.update { it + e } }
                }
            } catch (e: Exception) {
                (e.message ?: "Erreur inattendue.").also { message -> _chat.update { it + ChatEntry.Action(message, failed = true) } }
            } finally {
                _busy.value = false
            }
            onFinished(answer)
        }
        return true
    }

    fun answerConfirmation(accepted: Boolean) {
        _confirmation.value?.answer?.complete(accepted)
    }

    fun newConversation() {
        agent = null
        _chat.value = emptyList()
    }

    /** Réglages modifiés (clé, modèle…) : le prochain message recréera l'agent. */
    fun settingsChanged() {
        agent = null
    }

    fun labelFor(tool: String) = when (tool) {
        "open_app" -> "J'ouvre l'application"
        "read_screen" -> "Je regarde l'écran"
        "tap_element" -> "Je touche l'écran"
        "type_text" -> "J'écris"
        "scroll" -> "Je fais défiler"
        "media_control", "play_music", "set_volume", "now_playing" -> "Je m'occupe de la musique"
        "set_alarm" -> "Je règle l'alarme"
        "set_timer" -> "Je lance le minuteur"
        "list_apps" -> "Je regarde tes applications"
        "open_url" -> "J'ouvre le lien"
        "web_search" -> "Je lance la recherche"
        "global_action" -> "J'utilise les boutons du téléphone"
        "wait" -> "J'attends un instant"
        "schedule_task" -> "Je programme la tâche"
        "list_scheduled_tasks" -> "Je regarde les tâches programmées"
        "cancel_scheduled_task" -> "J'annule la tâche"
        "read_messages" -> "Je lis tes messages"
        "draft_reply" -> "Je rédige une réponse"
        "send_reply" -> "J'envoie le message"
        else -> "Action : $tool"
    }
}
