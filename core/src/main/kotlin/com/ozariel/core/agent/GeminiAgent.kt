package com.ozariel.core.agent

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.convertValue
import com.ozariel.core.llm.GeminiClient
import com.ozariel.core.tools.DeviceTool

/**
 * L'agent avec Gemini. Les réponses du modèle sont gardées telles quelles dans l'historique :
 * Gemini y place des « signatures de réflexion » qu'il faut lui renvoyer intactes.
 */
class GeminiAgent(
    private val client: GeminiClient,
    private val config: AgentConfig,
    tools: List<DeviceTool>,
    private val gate: ConfirmationGate,
    private val extraInstructions: String = "",
) : Assistant {
    private val mapper = client.mapper
    private val history = mutableListOf<JsonNode>()
    private val toolsByName = tools.associateBy { it.name }
    private val declarations = mapper.valueToTree<JsonNode>(
        listOf(mapOf("functionDeclarations" to tools.map { it.toDeclaration() }))
    )

    override fun reset() = history.clear()

    override suspend fun send(userText: String, onEvent: (AgentEvent) -> Unit): String {
        history.add(textContent("user", userTurn(userText)))

        val said = StringBuilder()
        repeat(config.maxSteps) {
            val response = try {
                client.generate(config.model.id, buildBody())
            } catch (e: Exception) {
                val message = e.message ?: "Impossible de joindre Gemini."
                onEvent(AgentEvent.Failed(message))
                return message
            }

            val candidate = response.path("candidates").path(0)
            val content = candidate.path("content")
            if (!content.has("parts")) {
                val reason = candidate.path("finishReason").asText(response.path("promptFeedback").path("blockReason").asText("inconnue"))
                val message = "Gemini n'a pas répondu (raison : $reason)."
                onEvent(AgentEvent.Failed(message))
                return message
            }
            history.add((content.deepCopy<JsonNode>() as ObjectNode).put("role", "model"))

            val parts = content.path("parts").toList()
            parts.filter { it.has("text") && !it.path("thought").asBoolean(false) }
                .map { it.path("text").asText().trim() }
                .filter { it.isNotEmpty() }
                .forEach {
                    said.appendLine(it)
                    onEvent(AgentEvent.Said(it))
                }

            val calls = parts.filter { it.has("functionCall") }.map { it.path("functionCall") }
            if (calls.isEmpty()) return said.toString().trim()
            history.add(functionResponses(calls, onEvent))
        }
        val message = "J'ai atteint la limite de ${config.maxSteps} étapes, je m'arrête là."
        onEvent(AgentEvent.Failed(message))
        return message
    }

    private fun buildBody(): ObjectNode = client.newObject().apply {
        set<JsonNode>("systemInstruction", textContent(null, systemPrompt(config.ownerName) + extraInstructions))
        set<JsonNode>("contents", mapper.valueToTree(history))
        set<JsonNode>("tools", declarations)
    }

    private suspend fun functionResponses(calls: List<JsonNode>, onEvent: (AgentEvent) -> Unit): JsonNode {
        val parts = calls.map { call ->
            val name = call.path("name").asText()
            val args: Map<String, Any?> = if (call.has("args")) mapper.convertValue(call.path("args")) else emptyMap()
            val result = executeTool(toolsByName, gate, name, args, onEvent)
            val response = buildMap<String, Any> {
                put("name", name)
                if (call.has("id")) put("id", call.path("id").asText())
                put("response", if (result.isError) mapOf("error" to result.text) else mapOf("result" to result.text))
            }
            mapOf("functionResponse" to response)
        }
        return mapper.valueToTree(mapOf("role" to "user", "parts" to parts))
    }

    private fun textContent(role: String?, text: String): JsonNode = mapper.valueToTree(
        buildMap {
            role?.let { put("role", it) }
            put("parts", listOf(mapOf("text" to text)))
        }
    )

    private fun DeviceTool.toDeclaration(): Map<String, Any> = mapOf(
        "name" to name,
        "description" to description,
        "parameters" to mapOf(
            "type" to "object",
            "properties" to parameters.associate { it.name to it.toSchema() },
            "required" to parameters.filter { it.required }.map { it.name },
        ),
    )
}
