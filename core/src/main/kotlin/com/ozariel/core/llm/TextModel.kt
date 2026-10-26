package com.ozariel.core.llm

import com.anthropic.client.AnthropicClient
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.ozariel.core.agent.AgentModel
import com.ozariel.core.agent.texts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Un modèle qui répond à une consigne par du texte (rédaction des réponses, analyse du style). */
interface TextModel {
    /** [thorough] : réfléchir davantage (analyse du style) plutôt que répondre vite (rédaction d'un message). */
    suspend fun complete(prompt: String, thorough: Boolean = false): String
}

class ClaudeText(private val client: AnthropicClient, private val model: AgentModel) : TextModel {
    override suspend fun complete(prompt: String, thorough: Boolean): String {
        val effort = if (thorough) OutputConfig.Effort.HIGH else OutputConfig.Effort.LOW
        val params = MessageCreateParams.builder()
            .model(model.id)
            .maxTokens(16000L)
            .outputConfig(OutputConfig.builder().effort(effort).build())
            .addUserMessage(prompt)
            .build()
        val response = withContext(Dispatchers.IO) { client.messages().create(params) }
        return response.texts().joinToString("\n")
    }
}

class GeminiText(private val client: GeminiClient, private val model: AgentModel) : TextModel {
    override suspend fun complete(prompt: String, thorough: Boolean): String {
        val body = client.newObject()
        body.set<com.fasterxml.jackson.databind.JsonNode>(
            "contents",
            client.mapper.valueToTree(listOf(mapOf("role" to "user", "parts" to listOf(mapOf("text" to prompt))))),
        )
        val response = client.generate(model.id, body)
        return response.path("candidates").path(0).path("content").path("parts")
            .filter { it.has("text") && !it.path("thought").asBoolean(false) }
            .joinToString("") { it.path("text").asText() }
    }
}
