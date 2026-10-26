package com.ozariel.core.agent

import com.ozariel.core.tools.DeviceTool
import com.ozariel.core.tools.ToolArgs
import com.ozariel.core.tools.ToolResult
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Ce que l'agent montre à l'utilisateur pendant qu'il travaille. */
sealed interface AgentEvent {
    data class Said(val text: String) : AgentEvent
    data class ToolStarted(val tool: String, val args: String) : AgentEvent
    data class ToolFinished(val tool: String, val result: ToolResult) : AgentEvent
    data class Failed(val message: String) : AgentEvent
}

/** Demande à l'utilisateur s'il accepte une action sensible. Renvoie true pour continuer. */
fun interface ConfirmationGate {
    suspend fun confirm(tool: DeviceTool, args: ToolArgs): Boolean
}

/**
 * Le cerveau : envoie la demande au modèle avec la liste des actions possibles,
 * exécute les actions choisies sur le téléphone et renvoie les résultats, jusqu'à ce que la tâche soit finie.
 * Une implémentation par fournisseur (Claude, Gemini).
 */
interface Assistant {
    suspend fun send(userText: String, onEvent: (AgentEvent) -> Unit = {}): String
    fun reset()
}

/** Exécute un outil demandé par le modèle, avec confirmation pour les actions sensibles. */
internal suspend fun executeTool(
    tools: Map<String, DeviceTool>,
    gate: ConfirmationGate,
    name: String,
    rawArgs: Map<String, Any?>,
    onEvent: (AgentEvent) -> Unit,
): ToolResult {
    val tool = tools[name] ?: return ToolResult.error("Action inconnue : $name")
    val args = ToolArgs(rawArgs)
    onEvent(AgentEvent.ToolStarted(tool.name, args.toString()))
    val result = try {
        if (tool.sensitive && !gate.confirm(tool, args)) {
            ToolResult.error("L'utilisateur a refusé cette action.")
        } else {
            tool.run(args)
        }
    } catch (e: Exception) {
        ToolResult.error("Erreur : ${e.message}")
    }
    onEvent(AgentEvent.ToolFinished(tool.name, result))
    return result
}

internal fun userTurn(text: String): String {
    val now = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm", Locale.FRENCH))
    return "[$now]\n$text"
}
