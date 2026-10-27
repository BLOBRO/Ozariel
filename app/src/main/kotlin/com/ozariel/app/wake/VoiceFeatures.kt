package com.ozariel.app.wake

import android.content.Context
import com.ozariel.core.wake.Clip
import com.ozariel.core.wake.VoiceModel
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Calcule les empreintes vocales sur le téléphone, avec les deux petits modèles d'openWakeWord
 * (licence Apache 2.0) : un mel-spectrogramme, puis le modèle d'embedding de Google.
 * On lui donne l'audio par blocs de 80 ms ; chaque bloc produit une empreinte de 96 nombres.
 * Même traitement à l'enregistrement et en écoute, pour que les empreintes soient comparables.
 */
class VoiceFeatures(context: Context) : Closeable {
    private val mel = Interpreter(asset(context, "wake/melspectrogram.tflite"), Interpreter.Options().setNumThreads(1))
    private val embedding = Interpreter(asset(context, "wake/embedding_model.tflite"), Interpreter.Options().setNumThreads(1))
    private val melInput = floats(MEL_SAMPLES)
    private val melOutput: ByteBuffer
    private val melFramesPerChunk: Int
    private val embeddingInput = floats(MEL_WINDOW * MEL_BINS)
    private val embeddingOutput = floats(VoiceModel.DIM)

    /** Les 30 ms d'audio précédant le bloc, dont le mel-spectrogramme a besoin. */
    private val history = ShortArray(CONTEXT_SAMPLES)
    private val melFrames = ArrayDeque<FloatArray>()
    /** Spectre du dernier bloc calculé, réutilisé tant que la pièce reste calme. */
    private var lastMel: List<FloatArray>? = null
    private var lastWasQuiet = false

    init {
        mel.resizeInput(0, intArrayOf(1, MEL_SAMPLES))
        mel.allocateTensors()
        // Taille d'entrée explicite : le modèle d'embedding est enregistré avec un lot de taille variable.
        embedding.resizeInput(0, intArrayOf(1, MEL_WINDOW, MEL_BINS, 1))
        embedding.allocateTensors()
        val outputs = mel.getOutputTensor(0).numElements()
        melOutput = floats(outputs)
        melFramesPerChunk = outputs / MEL_BINS
        reset()
    }

    fun reset() {
        history.fill(0)
        melFrames.clear()
        lastMel = null
        lastWasQuiet = false
        repeat(MEL_HISTORY) { melFrames.addLast(FloatArray(MEL_BINS) { 1f }) }
    }

    /** Ajoute 80 ms d'audio ([CHUNK] échantillons à 16 kHz) et renvoie la nouvelle empreinte. */
    fun push(chunk: ShortArray): FloatArray {
        pushMel(chunk)
        return embeddingAt(0)
    }

    /**
     * Ajoute 80 ms d'audio au mel-spectrogramme seulement (peu coûteux). L'empreinte, plus chère,
     * peut être calculée ensuite avec [embeddingAt], uniquement quand quelqu'un parle.
     */
    fun pushMel(chunk: ShortArray) {
        // Pièce calme : le spectre ne change presque pas, on réutilise le dernier au lieu de le recalculer.
        // (Même règle à l'apprentissage et en écoute, pour que les empreintes restent comparables.)
        val quiet = rms(chunk) < QUIET_RMS
        val cached = lastMel
        if (quiet && lastWasQuiet && cached != null) {
            System.arraycopy(chunk, CHUNK - CONTEXT_SAMPLES, history, 0, CONTEXT_SAMPLES)
            for (frame in cached) {
                melFrames.addLast(frame.copyOf())
                if (melFrames.size > MEL_HISTORY) melFrames.removeFirst()
            }
            return
        }
        lastWasQuiet = quiet
        melInput.rewind()
        for (s in history) melInput.putFloat(s.toFloat())
        for (s in chunk) melInput.putFloat(s.toFloat())
        melInput.rewind()
        System.arraycopy(chunk, CHUNK - CONTEXT_SAMPLES, history, 0, CONTEXT_SAMPLES)

        melOutput.rewind()
        mel.run(melInput, melOutput)
        melOutput.rewind()
        // Même transformation qu'openWakeWord, pour se rapprocher du modèle d'origine de Google.
        val frames = List(melFramesPerChunk) { FloatArray(MEL_BINS) { melOutput.getFloat() / 10f + 2f } }
        for (frame in frames) {
            melFrames.addLast(frame)
            if (melFrames.size > MEL_HISTORY) melFrames.removeFirst()
        }
        lastMel = frames.map { it.copyOf() }
    }

    /** Empreinte du bloc ajouté il y a [chunksAgo] blocs (0 = le dernier), jusqu'à [MAX_CHUNKS_AGO]. */
    fun embeddingAt(chunksAgo: Int): FloatArray {
        require(chunksAgo in 0..MAX_CHUNKS_AGO)
        val end = melFrames.size - chunksAgo * melFramesPerChunk
        embeddingInput.rewind()
        for (i in end - MEL_WINDOW until end) for (v in melFrames[i]) embeddingInput.putFloat(v)
        embeddingInput.rewind()
        embeddingOutput.rewind()
        embedding.run(embeddingInput, embeddingOutput)
        embeddingOutput.rewind()
        return FloatArray(VoiceModel.DIM) { embeddingOutput.getFloat() }
    }

    /** Empreintes d'un enregistrement, encadré de silence comme s'il arrivait pendant l'écoute continue. */
    fun clip(audio: ShortArray): Clip {
        reset()
        val padded = ShortArray(SAMPLE_RATE) + audio + ShortArray(SAMPLE_RATE / 2)
        val embeddings = mutableListOf<FloatArray>()
        val levels = mutableListOf<Float>()
        var start = 0
        while (start + CHUNK <= padded.size) {
            val chunk = padded.copyOfRange(start, start + CHUNK)
            embeddings += push(chunk)
            levels += rms(chunk)
            start += CHUNK
        }
        return Clip(embeddings, levels)
    }

    override fun close() {
        mel.close()
        embedding.close()
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        /** 80 ms d'audio. */
        const val CHUNK = 1280
        private const val CONTEXT_SAMPLES = 480
        private const val MEL_SAMPLES = CHUNK + CONTEXT_SAMPLES
        private const val MEL_BINS = 32
        private const val MEL_WINDOW = 76
        /** En dessous de ce niveau, un bloc de 80 ms est du calme (bien en dessous de la parole). */
        private const val QUIET_RMS = 150f
        /** On peut revenir jusqu'à une fenêtre de détection en arrière. */
        const val MAX_CHUNKS_AGO = VoiceModel.WINDOW - 1
        /** Trames mel gardées : la fenêtre du modèle d'embedding, plus de quoi remonter [MAX_CHUNKS_AGO] blocs (8 par bloc). */
        private const val MEL_HISTORY = MEL_WINDOW + MAX_CHUNKS_AGO * 8

        fun rms(chunk: ShortArray): Float {
            var sum = 0.0
            for (s in chunk) sum += s.toDouble() * s
            return sqrt(sum / chunk.size).toFloat()
        }

        private fun floats(count: Int): ByteBuffer = ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder())

        private fun asset(context: Context, path: String): ByteBuffer {
            val bytes = context.assets.open(path).use { it.readBytes() }
            return ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply { put(bytes); rewind() }
        }
    }
}
