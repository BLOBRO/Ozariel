package com.ozariel.core.wake

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VoiceModelTest {
    private val random = Random(42)
    private val dim = VoiceModel.DIM
    private val word = List(VoiceModel.WINDOW) { FloatArray(dim) { random.nextFloat() * 2 - 1 } }

    private fun noise(scale: Float) = FloatArray(dim) { (random.nextFloat() * 2 - 1) * scale }

    /** Un clip : du silence, puis le « mot » (motif fixe + bruit), puis un peu de silence. */
    private fun wordClip(): Clip {
        val silence = List(8) { noise(1f) }
        val spoken = word.map { w -> FloatArray(dim) { w[it] + noise(0.3f)[it] } }
        val after = List(3) { noise(1f) }
        val levels = List(8) { 50f } + List(spoken.size) { 3000f } + List(3) { 50f }
        return Clip(silence + spoken + after, levels)
    }

    private fun otherClip(length: Int = 30) = Clip(List(length) { noise(1f) }, List(length) { 2000f })

    private fun lastWindow(clip: Clip): List<FloatArray> {
        val end = VoiceModel.wordEnd(clip)
        return clip.embeddings.subList(end - VoiceModel.WINDOW, end)
    }

    @Test
    fun `reconnaît le mot appris et pas les autres sons`() {
        val model = VoiceModel.train(List(8) { wordClip() }, List(4) { otherClip() })
        val threshold = model.thresholdFor(0.5f)

        repeat(5) { assertTrue(model.score(lastWindow(wordClip())) > threshold, "un nouvel « Ozariel » doit être reconnu") }
        repeat(5) {
            val other = otherClip()
            assertTrue(model.score(other.embeddings.takeLast(VoiceModel.WINDOW)) < threshold, "un autre son ne doit pas déclencher")
        }
        assertTrue(model.positiveFloor > model.negativeCeiling)
    }

    @Test
    fun `ignore une prise silencieuse et une prise ratée`() {
        val silent = Clip(List(25) { noise(1f) }, List(25) { 20f })
        val wrong = otherClip(25)
        val model = VoiceModel.train(List(6) { wordClip() } + silent + wrong, List(4) { otherClip() })

        assertTrue(model.positiveFloor > model.negativeCeiling, "les mauvaises prises ne doivent pas fausser la calibration")
        assertTrue(model.score(lastWindow(wordClip())) > model.thresholdFor(0.5f))
    }

    @Test
    fun `la sensibilité déplace le seuil`() {
        val model = VoiceModel.train(List(6) { wordClip() }, List(3) { otherClip() })
        assertTrue(model.thresholdFor(1f) < model.thresholdFor(0.5f))
        assertTrue(model.thresholdFor(0f) > model.thresholdFor(0.5f))
    }

    @Test
    fun `se sauvegarde et se recharge à l'identique`() {
        val model = VoiceModel.train(List(6) { wordClip() }, List(3) { otherClip() })
        val reloaded = VoiceModel.fromJson(model.toJson())
        val window = lastWindow(wordClip())
        assertEquals(model.score(window), reloaded.score(window), 1e-5f)
        assertEquals(model.threshold, reloaded.threshold)
    }
}
