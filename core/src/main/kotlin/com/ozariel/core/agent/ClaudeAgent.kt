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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * L'agent avec Claude. L'historique n'est jamais modifié, seulement complété : c'est ce que demandent
 * les modèles récents (et ça permet la mise en cache du début de la conversation).
 */
class ClaudeAgent(
    private val client: AnthropicClient,
    private val config: AgentConfig,
    tools: List<DeviceTool>,
    private val gate: ConfirmationGate,
    private val extraInstructions: String = "",
) : Assistant {
    private val history = mutableListOf<MessageParam>()
    private val toolsByName = tools.associateBy { it.name }
    private val sdkTools: List<Tool> = tools.map { it.toSdkTool() }

    override fun reset() = history.clear()

    override suspend fun send(userText: String, onEvent: (AgentEvent) -> Unit): String {
        history += MessageParam.ofUser(userTurn(userText))

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
            val result = executeTool(toolsByName, gate, use.name(), use.argsMap(), onEvent)
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
