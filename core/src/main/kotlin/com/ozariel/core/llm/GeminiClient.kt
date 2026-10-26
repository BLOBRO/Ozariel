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
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

open class GeminiException(message: String) : IOException(message)

/** Panne passagère (surcharge, quota, modèle absent) : un autre modèle peut prendre le relais. */
class GeminiUnavailable(message: String) : GeminiException(message)

/** Appels à l'API Gemini de Google (offre gratuite disponible sur aistudio.google.com). */
class GeminiClient(private val apiKey: String) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    val mapper = jacksonObjectMapper()

    /** Modèle de secours en service, et jusqu'à quand le garder. */
    @Volatile private var fallback: Pair<String, Long>? = null

    fun newObject(): ObjectNode = mapper.createObjectNode()

    /**
     * Envoie une requête generateContent. L'offre gratuite limite le nombre de requêtes par minute,
     * et les serveurs de Google sont parfois surchargés : on réessaie, puis on se rabat sur un autre
     * modèle Gemini avant d'abandonner.
     */
    suspend fun generate(model: String, body: ObjectNode): JsonNode {
        val json = mapper.writeValueAsString(body)
        var lastError: GeminiException? = null
        val candidates = (listOf(model) + FALLBACK_MODELS).distinct()
        // Si le modèle choisi vient d'être indisponible, on commence directement par celui qui a pris le relais.
        val relay = fallback?.takeIf { (_, until) -> System.currentTimeMillis() < until }?.first
        val ordered = relay?.let { listOf(it) + (candidates - it) } ?: candidates
        for (candidate in ordered) {
            try {
                return generateWith(candidate, json).also {
                    fallback = if (candidate == model) null else candidate to System.currentTimeMillis() + FALLBACK_DURATION_MS
                }
            } catch (e: GeminiUnavailable) {
                log("$candidate indisponible : ${e.message}")
                if (lastError == null) lastError = e
            }
        }
        throw lastError ?: GeminiException("Gemini ne répond pas.")
    }

    private suspend fun generateWith(model: String, json: String): JsonNode {
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .header("x-goog-api-key", apiKey)
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()

        repeat(ATTEMPTS_PER_MODEL) { attempt ->
            val last = attempt == ATTEMPTS_PER_MODEL - 1
            val started = System.currentTimeMillis()
            val (code, text) = try {
                withContext(Dispatchers.IO) {
                    http.newCall(request).execute().use { it.code to it.body?.string().orEmpty() }
                }
            } catch (e: InterruptedIOException) {
                log("$model : pas de réponse après ${elapsed(started)} (essai ${attempt + 1})")
                if (!last) return@repeat
                throw GeminiUnavailable("Gemini met trop de temps à répondre. Réessaie dans un instant.")
            }
            log("$model : HTTP $code en ${elapsed(started)} (essai ${attempt + 1})")
            when {
                code in 200..299 -> return mapper.readTree(text).also { log("modèle utilisé : ${it.path("modelVersion").asText()}") }
                (code == 429 || code >= 500) && !last -> delay(retryDelayMs(text, attempt))
                code == 429 -> throw GeminiUnavailable(
                    "Limite gratuite de Gemini atteinte. Réessaie dans une minute (ou demain si c'est la limite du jour)."
                )
                code >= 500 -> throw GeminiUnavailable("Les serveurs de Gemini sont surchargés. Réessaie dans quelques minutes.")
                code == 404 -> throw GeminiUnavailable("Modèle Gemini « $model » introuvable : ${errorMessage(text)}")
                code == 400 || code == 403 -> throw GeminiException("Gemini a refusé la requête : ${errorMessage(text)}")
                else -> throw GeminiException("Erreur Gemini $code : ${errorMessage(text)}")
            }
        }
        throw GeminiUnavailable("Gemini ne répond pas.")
    }

    private fun errorMessage(text: String): String =
        runCatching { mapper.readTree(text).path("error").path("message").asText() }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: text.take(300)

    /** Gemini indique parfois le délai à attendre (« retryDelay »: "17s"). */
    private fun retryDelayMs(text: String, attempt: Int): Long {
        val hinted = Regex("\"retryDelay\"\\s*:\\s*\"(\\d+)(?:\\.\\d+)?s\"").find(text)?.groupValues?.get(1)?.toLongOrNull()
        return ((hinted ?: (5L shl attempt)).coerceIn(2, 60)) * 1000
    }

    private fun elapsed(started: Long) = "${(System.currentTimeMillis() - started) / 1000.0} s"

    /** Visible dans logcat (balise System.out) pour diagnostiquer les lenteurs. */
    private fun log(message: String) = println("Ozariel/Gemini: $message")

    companion object {
        private const val ATTEMPTS_PER_MODEL = 2
        private const val FALLBACK_DURATION_MS = 10 * 60_000L

        /** Modèles de secours, essayés dans l'ordre si celui choisi est indisponible. */
        private val FALLBACK_MODELS = listOf("gemini-flash-lite-latest", "gemini-2.5-flash", "gemini-2.5-flash-lite")
    }
}
