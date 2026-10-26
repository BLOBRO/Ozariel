package com.ozariel.core.agent

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.messages.OutputConfig

/** Modèles proposés dans les réglages de l'app. Opus est le plus capable, Haiku le moins cher. */
enum class AgentModel(val id: String, val label: String) {
    OPUS("claude-opus-5-5", "Claude Opus 5.5 (le plus capable)"),
    SONNET("claude-sonnet-5-5", "Claude Sonnet 5.5 (équilibré)"),
    HAIKU("claude-haiku-5-5", "Claude Haiku 5.5 (rapide et économique)");

    companion object {
        fun fromId(id: String?): AgentModel = entries.firstOrNull { it.id == id } ?: OPUS
    }
}

data class AgentConfig(
    val model: AgentModel = AgentModel.OPUS,
    val effort: OutputConfig.Effort = OutputConfig.Effort.MEDIUM,
    val maxSteps: Int = 30,
    val ownerName: String = "Ariel",
)

object ClaudeClients {
    fun create(apiKey: String): AnthropicClient =
        AnthropicOkHttpClient.builder().apiKey(apiKey).build()
}
