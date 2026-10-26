package com.ozariel.core.agent

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.messages.OutputConfig

enum class Provider { CLAUDE, GEMINI }

/** Modèles proposés dans les réglages de l'app. */
enum class AgentModel(val id: String, val label: String, val provider: Provider) {
    OPUS("claude-opus-5-5", "Claude Opus 5.5 (le plus capable, payant)", Provider.CLAUDE),
    SONNET("claude-sonnet-5-5", "Claude Sonnet 5.5 (équilibré, payant)", Provider.CLAUDE),
    HAIKU("claude-haiku-5-5", "Claude Haiku 5.5 (économique, payant)", Provider.CLAUDE),
    GEMINI_FLASH("gemini-flash-latest", "Gemini Flash (gratuit avec limites, Google)", Provider.GEMINI),
    GEMINI_FLASH_LITE("gemini-flash-lite-latest", "Gemini Flash-Lite (gratuit, plus rapide, moins fiable)", Provider.GEMINI);

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
