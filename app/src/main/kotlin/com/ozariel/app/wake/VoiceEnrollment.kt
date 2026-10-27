package com.ozariel.app.wake

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.ozariel.core.wake.VoiceModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext

/**
 * L'apprentissage du mot « Ozariel » sur la voix de l'utilisateur. Les enregistrements et le modèle
 * restent dans le stockage privé de l'app : rien n'est envoyé.
 */
object VoiceEnrollment {
    /** Les étapes : le mot plusieurs fois, quelques phrases ordinaires, puis du silence. */
    sealed class Step(val id: String, val instruction: String, val durationMs: Long, val positive: Boolean) {
        class Word(index: Int) : Step("pos_$index", "Dis « Ozariel », comme si tu l'appelais.", 2500, true)
        class Phrase(index: Int, text: String) : Step("neg_$index", "Lis cette phrase normalement :\n« $text »", 4000, false)
        object Silence : Step("silence", "Ne dis rien : je mesure le bruit de la pièce.", 4000, false)
    }

    val steps: List<Step> = List(WORD_TAKES) { Step.Word(it) } + listOf(
        "Tu peux mettre un peu de musique ?",
        "Je vais faire les courses cet après-midi.",
        "Rosalie arrive bientôt à la maison.",
        "Il fait beau aujourd'hui, on sort ?",
    ).mapIndexed { i, text -> Step.Phrase(i, text) } + Step.Silence

    private fun dir(context: Context) = File(context.filesDir, "wake").apply { mkdirs() }
    private fun clipFile(context: Context, step: Step) = File(dir(context), "${step.id}.pcm")
    private fun modelFile(context: Context) = File(dir(context), "model.json")

    fun isRecorded(context: Context, step: Step) = clipFile(context, step).exists()

    fun isTrained(context: Context) = modelFile(context).exists()

    /** Tous les enregistrements sont là : on peut recalculer le modèle sans tout réenregistrer. */
    fun hasAllRecordings(context: Context) = steps.all { isRecorded(context, it) }

    fun load(context: Context): VoiceModel? =
        runCatching { VoiceModel.fromJson(modelFile(context).readText()) }.getOrNull()

    fun forget(context: Context) {
        dir(context).deleteRecursively()
    }

    /**
     * Enregistre une étape au micro en donnant le niveau sonore en direct.
     * Renvoie le niveau maximal atteint, pour signaler un enregistrement trop faible.
     */
    @SuppressLint("MissingPermission") // vérifiée avant d'ouvrir l'écran d'apprentissage
    suspend fun record(context: Context, step: Step, onLevel: (Float) -> Unit): Float = withContext(Dispatchers.IO) {
        val total = (VoiceFeatures.SAMPLE_RATE * step.durationMs / 1000).toInt()
        val audio = ShortArray(total)
        val recorder = openMic()
        var peak = 0f
        try {
            recorder.startRecording()
            var read = 0
            val chunk = ShortArray(VoiceFeatures.CHUNK)
            while (read < total && coroutineContext.isActive) {
                val n = recorder.read(chunk, 0, minOf(chunk.size, total - read))
                if (n <= 0) break
                System.arraycopy(chunk, 0, audio, read, n)
                read += n
                val level = VoiceFeatures.rms(chunk.copyOf(n))
                peak = maxOf(peak, level)
                onLevel(level)
            }
        } finally {
            recorder.stop()
            recorder.release()
        }
        val bytes = ByteBuffer.allocate(audio.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        audio.forEach { bytes.putShort(it) }
        clipFile(context, step).writeBytes(bytes.array())
        peak
    }

    /** Calcule le modèle à partir de tous les enregistrements. */
    suspend fun train(context: Context): VoiceModel = withContext(Dispatchers.Default) {
        VoiceFeatures(context).use { features ->
            fun clips(positive: Boolean) = steps.filter { it.positive == positive && isRecorded(context, it) }
                .map { features.clip(readClip(context, it)) }
            val model = VoiceModel.train(clips(true), clips(false))
            modelFile(context).writeText(model.toJson())
            model
        }
    }

    private fun readClip(context: Context, step: Step): ShortArray {
        val bytes = ByteBuffer.wrap(clipFile(context, step).readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        return ShortArray(bytes.remaining() / 2) { bytes.getShort() }
    }

    /** Le micro, réglé pour la voix (16 kHz, mono). */
    @SuppressLint("MissingPermission")
    fun openMic(): AudioRecord {
        val minimum = AudioRecord.getMinBufferSize(
            VoiceFeatures.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            VoiceFeatures.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minimum, VoiceFeatures.CHUNK * 2 * 4),
        )
        check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Le micro n'est pas disponible." }
        return recorder
    }

    private const val WORD_TAKES = 8
}
