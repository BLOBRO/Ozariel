package com.ozariel.core.wake

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Le détecteur du mot « Ozariel », appris à partir des enregistrements de l'utilisateur.
 *
 * Chaque enregistrement est transformé en une suite d'empreintes vocales (une toutes les 80 ms,
 * calculées sur le téléphone par le modèle d'embedding d'openWakeWord). Un « modèle » est la fenêtre
 * de [WINDOW] empreintes qui se termine juste après le mot. En écoute, la fenêtre courante est comparée
 * aux modèles (similarité cosinus, une fois retiré le « fond » moyen des phrases ordinaires) :
 * le score est la moyenne des [TOP] meilleures similarités.
 */
class VoiceModel(
    val templates: List<Array<FloatArray>>,
    val background: Array<FloatArray>,
    /** Seuil calibré entre les « Ozariel » enregistrés et les phrases ordinaires. */
    val threshold: Float,
    /** Plus mauvais score d'un « Ozariel » enregistré, et meilleur score d'une phrase ordinaire (pour l'affichage). */
    val positiveFloor: Float,
    val negativeCeiling: Float,
) {
    /** Score de la fenêtre des [WINDOW] dernières empreintes (de la plus ancienne à la plus récente). */
    fun score(window: List<FloatArray>): Float {
        require(window.size == WINDOW)
        val centered = Array(WINDOW) { unit(minus(window[it], background[it])) }
        val sims = templates.map { similarity(centered, it) }.sortedDescending()
        return sims.take(TOP).average().toFloat()
    }

    /** Pour l'enregistrer sur le téléphone. */
    fun toJson(): String = mapper.writeValueAsString(
        Saved(templates.map { t -> t.map { it.toList() } }, background.map { it.toList() }, threshold, positiveFloor, negativeCeiling)
    )

    /** Seuil effectif selon la sensibilité choisie (0 = strict, 1 = très sensible). */
    fun thresholdFor(sensitivity: Float): Float = threshold + (0.5f - sensitivity.coerceIn(0f, 1f)) * SENSITIVITY_RANGE

    companion object {
        /** 12 empreintes de 80 ms : un peu moins d'une seconde, la durée de « Ozariel ». */
        const val WINDOW = 12
        const val TOP = 3
        const val DIM = 96
        private const val SENSITIVITY_RANGE = 0.16f
        /** Écart à la ressemblance médiane au-delà duquel une prise est jugée ratée. */
        private const val OUTLIER_GAP = 0.25f
        /** En dessous de ce niveau sonore (RMS sur 16 bits), un bloc de 80 ms est considéré silencieux. */
        const val SPEECH_RMS = 300f

        private val mapper = jacksonObjectMapper()

        fun fromJson(json: String): VoiceModel {
            val s = mapper.readValue<Saved>(json)
            return VoiceModel(
                s.templates.map { t -> t.map { it.toFloatArray() }.toTypedArray() },
                s.background.map { it.toFloatArray() }.toTypedArray(),
                s.threshold, s.positiveFloor, s.negativeCeiling,
            )
        }

        /**
         * Construit le modèle à partir des enregistrements : [positives] contient les « Ozariel »,
         * [negatives] des phrases ordinaires et du silence. Chaque clip est la suite de ses empreintes
         * accompagnée du niveau sonore de chaque bloc de 80 ms.
         */
        fun train(positives: List<Clip>, negatives: List<Clip>): VoiceModel {
            require(positives.size >= TOP + 1) { "Il faut au moins ${TOP + 1} enregistrements du mot." }
            require(negatives.isNotEmpty()) { "Il faut au moins une phrase ordinaire." }

            val negativeWindows = negatives.flatMap { windows(it.embeddings) }
            require(negativeWindows.isNotEmpty()) { "Les phrases ordinaires sont trop courtes." }
            val background = Array(WINDOW) { t ->
                FloatArray(DIM) { d -> negativeWindows.sumOf { it[t][d].toDouble() }.toFloat() / negativeWindows.size }
            }
            // Un enregistrement sans parole (micro trop loin, mot dit trop tard) ne sert à rien.
            val candidates = positives.filter { clip -> clip.levels.any { it > SPEECH_RMS } }.mapNotNull { clip ->
                val end = wordEnd(clip)
                if (end < WINDOW) null else Array(WINDOW) { unit(minus(clip.embeddings[end - WINDOW + it], background[it])) }
            }
            // Une prise ratée (autre mot, bruit) ne ressemble pas aux autres : on l'écarte.
            val agreement = candidates.indices.map { i -> looScore(candidates, i) }
            val median = agreement.sorted().getOrElse(agreement.size / 2) { 0f }
            val templates = candidates.filterIndexed { i, _ -> agreement[i] >= median - OUTLIER_GAP }
            require(templates.size >= TOP + 1) { "Je n'ai pas bien entendu le mot dans assez d'enregistrements. Parle un peu plus fort." }

            // Calibration : chaque modèle comparé aux autres (comme un nouvel « Ozariel »)…
            val positiveScores = templates.indices.map { looScore(templates, it) }.sorted()
            // … et la pire phrase ordinaire.
            val draft = VoiceModel(templates, background, 0f, 0f, 0f)
            val negativeCeiling = negativeWindows.maxOf { draft.score(it.toList()) }
            // Le seuil se place aux deux tiers du chemin, côté phrases ordinaires, et sous presque tous les « Ozariel ».
            val positiveFloor = positiveScores[(positiveScores.size * 0.2).toInt()]
            val threshold = if (positiveFloor > negativeCeiling) {
                negativeCeiling + (positiveFloor - negativeCeiling) * 0.4f
            } else {
                // Recouvrement : on reste juste au-dessus des phrases ordinaires.
                negativeCeiling + 0.02f
            }
            return VoiceModel(templates, background, threshold, positiveFloor, negativeCeiling)
        }

        /** Index (exclu) de la fin du mot : dernier bloc sonore, plus 3 blocs de marge. */
        fun wordEnd(clip: Clip): Int {
            val loud = max(SPEECH_RMS, (clip.levels.maxOrNull() ?: 0f) * 0.15f)
            val last = clip.levels.indexOfLast { it > loud }
            return if (last < 0) clip.embeddings.size else (last + 3).coerceAtMost(clip.embeddings.size)
        }

        /** Ressemblance d'un modèle avec les autres, comme s'il s'agissait d'un nouvel « Ozariel ». */
        private fun looScore(templates: List<Array<FloatArray>>, i: Int): Float =
            templates.filterIndexed { j, _ -> j != i }.map { similarity(templates[i], it) }
                .sortedDescending().take(TOP).average().toFloat()

        private fun windows(e: List<FloatArray>): List<Array<FloatArray>> =
            (WINDOW..e.size).map { end -> Array(WINDOW) { e[end - WINDOW + it] } }

        private fun similarity(a: Array<FloatArray>, b: Array<FloatArray>): Float {
            var sum = 0f
            for (t in 0 until WINDOW) sum += dot(a[t], b[t])
            return sum / WINDOW
        }

        private fun dot(a: FloatArray, b: FloatArray): Float {
            var s = 0f
            for (i in a.indices) s += a[i] * b[i]
            return s
        }

        private fun minus(a: FloatArray, b: FloatArray) = FloatArray(a.size) { a[it] - b[it] }

        private fun unit(a: FloatArray): FloatArray {
            val n = sqrt(dot(a, a)) + 1e-8f
            return FloatArray(a.size) { a[it] / n }
        }
    }
}

private data class Saved(
    val templates: List<List<List<Float>>>,
    val background: List<List<Float>>,
    val threshold: Float,
    val positiveFloor: Float,
    val negativeCeiling: Float,
)

/** Un enregistrement : ses empreintes (une toutes les 80 ms) et le niveau sonore de chaque bloc. */
class Clip(val embeddings: List<FloatArray>, val levels: List<Float>)
