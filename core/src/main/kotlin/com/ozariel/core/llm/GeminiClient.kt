package com.ozariel.core.llm

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class GeminiException(message: String) : IOException(message)

/** Appels à l'API Gemini de Google (offre gratuite disponible sur aistudio.google.com). */
class GeminiClient(private val apiKey: String) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()
    val mapper = jacksonObjectMapper()

    fun newObject(): ObjectNode = mapper.createObjectNode()

    /**
     * Envoie une requête generateContent. L'offre gratuite limite le nombre de requêtes par minute :
     * en cas de dépassement, on attend et on réessaie quelques fois avant d'abandonner.
     */
    suspend fun generate(model: String, body: ObjectNode): JsonNode {
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .header("x-goog-api-key", apiKey)
            .post(mapper.writeValueAsString(body).toRequestBody("application/json".toMediaType()))
            .build()

        repeat(MAX_RETRIES) { attempt ->
            val (code, text) = withContext(Dispatchers.IO) {
                http.newCall(request).execute().use { it.code to it.body?.string().orEmpty() }
            }
            when {
                code in 200..299 -> return mapper.readTree(text)
                (code == 429 || code >= 500) && attempt < MAX_RETRIES - 1 -> delay(retryDelayMs(text, attempt))
                code == 429 -> throw GeminiException(
                    "Limite gratuite de Gemini atteinte. Réessaie dans une minute (ou demain si c'est la limite du jour)."
                )
                code == 400 || code == 403 -> throw GeminiException("Gemini a refusé la requête : ${errorMessage(text)}")
                code == 404 -> throw GeminiException("Modèle Gemini « $model » introuvable : ${errorMessage(text)}")
                else -> throw GeminiException("Erreur Gemini $code : ${errorMessage(text)}")
            }
        }
        throw GeminiException("Gemini ne répond pas.")
    }

    private fun errorMessage(text: String): String =
        runCatching { mapper.readTree(text).path("error").path("message").asText() }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: text.take(300)

    /** Gemini indique parfois le délai à attendre (« retryDelay »: "17s"). */
    private fun retryDelayMs(text: String, attempt: Int): Long {
        val hinted = Regex("\"retryDelay\"\\s*:\\s*\"(\\d+)(?:\\.\\d+)?s\"").find(text)?.groupValues?.get(1)?.toLongOrNull()
        return ((hinted ?: (5L shl attempt)).coerceIn(2, 60)) * 1000
    }

    companion object {
        private const val MAX_RETRIES = 4
    }
}
