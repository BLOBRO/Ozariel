package com.ozariel.core.agent

import com.anthropic.client.AnthropicClient
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolResultBlockParam
import com.anthropic.models.messages.ToolUseBlock
import com.ozariel.core.tools.DeviceTool
import com.ozariel.core.tools.ToolArgs
import com.ozariel.core.tools.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 * Le cerveau : envoie la demande à Claude avec la liste des actions possibles,
 * exécute les actions choisies sur le téléphone et renvoie les résultats, jusqu'à ce que la tâche soit finie.
 *
 * L'historique n'est jamais modifié, seulement complété : c'est ce que demandent les modèles récents
 * (et ça permet la mise en cache du début de la conversation).
 */
class Agent(
    private val client: AnthropicClient,
    private val config: AgentConfig,
    private val tools: List<DeviceTool>,
    private val gate: ConfirmationGate,
    private val extraInstructions: String = "",
) {
    private val history = mutableListOf<MessageParam>()
    private val toolsByName = tools.associateBy { it.name }
    private val sdkTools: List<Tool> = tools.map { it.toSdkTool() }

    fun reset() = history.clear()

    suspend fun send(userText: String, onEvent: (AgentEvent) -> Unit = {}): String {
        history += MessageParam.ofUser("[${now()}]\n$userText")

        val said = StringBuilder()
        repeat(config.maxSteps) {
            val response = try {
                withContext(Dispatchers.IO) { client.messages().create(buildParams()) }
            } catch (e: Exception) {
                val message = "Impossible de joindre Claude : ${e.message}"
                onEvent(AgentEvent.Failed(message))
                return message
            }
            history += response.toParam()

            response.texts().forEach {
                said.appendLine(it)
                onEvent(AgentEvent.Said(it))
            }

            when (response.stopReason().orElse(null)) {
                StopReason.TOOL_USE -> history += runTools(response, onEvent)
                StopReason.REFUSAL -> {
                    val message = "Claude a refusé cette demande."
                    onEvent(AgentEvent.Failed(message))
                    return message
                }
                else -> return said.toString().trim()
            }
        }
        val message = "J'ai atteint la limite de ${config.maxSteps} étapes, je m'arrête là."
        onEvent(AgentEvent.Failed(message))
        return message
    }

    private fun buildParams(): MessageCreateParams {
        val builder = MessageCreateParams.builder()
            .model(config.model.id)
            .maxTokens(16000L)
            .system(systemPrompt(config.ownerName) + extraInstructions)
            .messages(history)
            .outputConfig(OutputConfig.builder().effort(config.effort).build())
        sdkTools.forEach { builder.addTool(it) }
        if (config.model != AgentModel.HAIKU) {
            // Si un filtre de sécurité refuse la demande par erreur, l'API réessaie avec un autre modèle.
            builder.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
            builder.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        }
        return builder.build()
    }

    private suspend fun runTools(response: Message, onEvent: (AgentEvent) -> Unit): MessageParam {
        val results = response.content().mapNotNull { it.toolUse().orElse(null) }.map { use ->
            val result = runTool(use, onEvent)
            ContentBlockParam.ofToolResult(
                ToolResultBlockParam.builder()
                    .toolUseId(use.id())
                    .content(result.text)
                    .isError(result.isError)
                    .build()
            )
        }
        return MessageParam.ofUserOfContentBlockParams(results)
    }

    private suspend fun runTool(use: ToolUseBlock, onEvent: (AgentEvent) -> Unit): ToolResult {
        val tool = toolsByName[use.name()] ?: return ToolResult.error("Action inconnue : ${use.name()}")
        val args = ToolArgs(use.argsMap())
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

    private fun now(): String = ZonedDateTime.now()
        .format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm", Locale.FRENCH))
}

internal fun Message.texts(): List<String> =
    content().mapNotNull { it.text().orElse(null)?.text() }.filter { it.isNotBlank() }

@Suppress("UNCHECKED_CAST")
private fun ToolUseBlock.argsMap(): Map<String, Any?> =
    _input().convert(Map::class.java) as? Map<String, Any?> ?: emptyMap()

private fun DeviceTool.toSdkTool(): Tool {
    val properties = Tool.InputSchema.Properties.builder()
    parameters.forEach { properties.putAdditionalProperty(it.name, JsonValue.from(it.toSchema())) }
    return Tool.builder()
        .name(name)
        .description(description)
        .inputSchema(
            Tool.InputSchema.builder()
                .properties(properties.build())
                .required(parameters.filter { it.required }.map { it.name })
                .build()
        )
        .build()
}
