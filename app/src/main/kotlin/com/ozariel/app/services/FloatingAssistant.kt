package com.ozariel.app.services

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.ozariel.app.OzarielApp
import com.ozariel.app.R
import com.ozariel.app.ui.AgentSession
import com.ozariel.app.ui.ChatEntry
import com.ozariel.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

/**
 * La bulle Ozariel, affichée par-dessus toutes les applications par le service d'accessibilité
 * (aucune autorisation supplémentaire). Appui : on parle ; appui long : on écrit.
 * Une carte affiche ce que l'agent fait, puis sa réponse, qui est aussi lue à voix haute.
 */
class FloatingAssistant(private val service: AccessibilityService) {
    private val settings = OzarielApp.instance.settings
    private val windows = service.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val density = service.resources.displayMetrics.density
    private val dark = (service.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private var scope: CoroutineScope? = null
    private var bubble: ImageView? = null
    private lateinit var bubbleParams: WindowManager.LayoutParams
    private var card: LinearLayout? = null
    private lateinit var cardParams: WindowManager.LayoutParams
    private lateinit var status: TextView
    private lateinit var input: EditText
    private lateinit var sendButton: Button
    private lateinit var allowButton: Button
    private lateinit var denyButton: Button

    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var collector: Job? = null
    private val hideCard = Runnable { setCardVisible(false) }

    /** Affiche ou retire la bulle selon les réglages. */
    fun refresh() {
        if (settings.floatingBubble) show() else hide()
    }

    fun destroy() {
        hide()
        tts?.shutdown()
        tts = null
    }

    private fun show() {
        if (bubble != null) return
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        createCard()
        createBubble()
        collector = scope?.launch {
            combine(AgentSession.busy, AgentSession.confirmation, AgentSession.chat) { busy, pending, chat ->
                Triple(busy, pending, chat.lastOrNull())
            }.collect { (busy, pending, last) -> render(busy, pending != null, last) }
        }
    }

    private fun hide() {
        stopListening()
        handler.removeCallbacks(hideCard)
        scope?.cancel()
        scope = null
        bubble?.let { runCatching { windows.removeView(it) } }
        card?.let { runCatching { windows.removeView(it) } }
        bubble = null
        card = null
    }

    // --- La bulle ---

    private fun createBubble() {
        val size = dp(56)
        val view = ImageView(service).apply {
            setImageResource(R.drawable.ic_launcher)
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
            elevation = dp(6).toFloat()
            clipToOutline = true
            contentDescription = "Ozariel : appuie pour parler, appui long pour écrire"
        }
        val metrics = service.resources.displayMetrics
        bubbleParams = overlayParams(size, size).apply {
            x = if (settings.bubbleOnRight) metrics.widthPixels - size else 0
            y = settings.bubbleY.takeIf { it >= 0 } ?: (metrics.heightPixels / 2)
        }
        view.setOnTouchListener(BubbleTouch())
        windows.addView(view, bubbleParams)
        bubble = view
    }

    /** Appui, appui long et glisser-déposer de la bulle, qui se range ensuite contre le bord le plus proche. */
    private inner class BubbleTouch : View.OnTouchListener {
        private val slop = ViewConfiguration.get(service).scaledTouchSlop
        private var startX = 0f
        private var startY = 0f
        private var originX = 0
        private var originY = 0
        private var dragging = false
        private var longPressed = false
        private val longPress = Runnable { longPressed = true; openTextInput() }

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            Log.d(TAG, "toucher bulle : ${event.actionMasked}")
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX; startY = event.rawY
                    originX = bubbleParams.x; originY = bubbleParams.y
                    dragging = false; longPressed = false
                    handler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startX
                    val dy = event.rawY - startY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                        dragging = true
                        handler.removeCallbacks(longPress)
                    }
                    if (dragging) {
                        bubbleParams.x = originX + dx.toInt()
                        bubbleParams.y = originY + dy.toInt()
                        windows.updateViewLayout(view, bubbleParams)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPress)
                    when {
                        dragging -> snapToEdge(view)
                        !longPressed && event.actionMasked == MotionEvent.ACTION_UP -> onBubbleTap()
                    }
                }
            }
            return true
        }
    }

    private fun snapToEdge(view: View) {
        val width = service.resources.displayMetrics.widthPixels
        val right = bubbleParams.x + view.width / 2 > width / 2
        bubbleParams.x = if (right) width - view.width else 0
        bubbleParams.y = bubbleParams.y.coerceAtLeast(0)
        windows.updateViewLayout(view, bubbleParams)
        settings.bubbleOnRight = right
        settings.bubbleY = bubbleParams.y
    }

    private fun onBubbleTap() {
        tts?.stop()
        when {
            listening -> stopListening()
            AgentSession.busy.value -> showCard()
            else -> startListening()
        }
    }

    // --- La carte ---

    private fun createCard() {
        val text = if (dark) Color.WHITE else Color.rgb(28, 27, 31)
        val layout = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(if (dark) Color.rgb(43, 41, 48) else Color.rgb(245, 242, 250))
            }
            elevation = dp(8).toFloat()
            visibility = View.GONE
        }
        layout.addView(TextView(service).apply {
            this.text = "Ozariel"
            setTextColor(Color.rgb(79, 70, 229))
            textSize = 13f
        })
        status = TextView(service).apply {
            setTextColor(text)
            textSize = 16f
            maxLines = 8
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(4), 0, dp(4))
        }
        layout.addView(status)
        input = EditText(service).apply {
            hint = "Ta demande…"
            setTextColor(text)
            setHintTextColor(Color.GRAY)
            imeOptions = EditorInfo.IME_ACTION_SEND
            isSingleLine = true
            visibility = View.GONE
            setOnEditorActionListener { _, action, _ ->
                (action == EditorInfo.IME_ACTION_SEND).also { if (it) submitTyped() }
            }
        }
        layout.addView(input)
        val buttons = LinearLayout(service).apply { gravity = Gravity.END }
        sendButton = textButton("Envoyer") { submitTyped() }.apply { visibility = View.GONE }
        allowButton = textButton("Autoriser") { AgentSession.answerConfirmation(true) }
        denyButton = textButton("Refuser") { AgentSession.answerConfirmation(false) }
        buttons.addView(textButton("Ouvrir l'app") { openApp() })
        buttons.addView(denyButton)
        buttons.addView(allowButton)
        buttons.addView(sendButton)
        buttons.addView(textButton("Fermer") { closeCard() })
        layout.addView(buttons)

        cardParams = overlayParams(
            (service.resources.displayMetrics.widthPixels - dp(32)).coerceAtMost(dp(420)),
            WindowManager.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(48)
        }
        windows.addView(layout, cardParams)
        card = layout
    }

    private fun textButton(label: String, onClick: () -> Unit) = Button(service, null, android.R.attr.borderlessButtonStyle).apply {
        text = label
        isAllCaps = false
        setTextColor(Color.rgb(79, 70, 229))
        setOnClickListener { onClick() }
    }

    /** Met la carte à jour selon l'état de l'agent (étape en cours, confirmation demandée…). */
    private fun render(busy: Boolean, confirming: Boolean, last: ChatEntry?) {
        if (card == null) return
        allowButton.visibility = if (confirming) View.VISIBLE else View.GONE
        denyButton.visibility = if (confirming) View.VISIBLE else View.GONE
        if (busy) {
            val pending = AgentSession.confirmation.value
            status.text = when {
                pending != null -> "${pending.tool.description}\n\n${pending.args}\n\nJe peux ?"
                last is ChatEntry.Action -> last.text
                last is ChatEntry.Assistant -> last.text
                else -> "Je réfléchis…"
            }
            if (confirming) showCard()
        }
        // Pendant le travail, la bulle et la carte laissent passer les touchers de l'agent,
        // sauf quand il faut répondre à une confirmation.
        setTouchable(!busy || confirming)
        bubble?.alpha = if (busy) 0.6f else 1f
    }

    private fun setTouchable(touchable: Boolean) {
        listOf(bubble to bubbleParams, card to cardParams).forEach { (view, params) ->
            if (view == null) return@forEach
            val flags = if (touchable) params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            else params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            if (flags != params.flags) {
                params.flags = flags
                windows.updateViewLayout(view, params)
            }
        }
    }

    private fun showCard(text: String? = null) {
        Log.d(TAG, "carte : $text")
        handler.removeCallbacks(hideCard)
        text?.let { status.text = it }
        setCardVisible(true)
    }

    private fun setCardVisible(visible: Boolean) {
        card?.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) setTextMode(false)
    }

    private fun closeCard() {
        tts?.stop()
        stopListening()
        setCardVisible(false)
    }

    private fun openTextInput() {
        tts?.stop()
        stopListening()
        showCard("Écris ta demande :")
        setTextMode(true)
    }

    /** Le champ de saisie a besoin d'une fenêtre qui peut recevoir le clavier. */
    private fun setTextMode(on: Boolean) {
        val view = card ?: return
        input.visibility = if (on) View.VISIBLE else View.GONE
        sendButton.visibility = if (on) View.VISIBLE else View.GONE
        cardParams.flags = if (on) cardParams.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        else cardParams.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        windows.updateViewLayout(view, cardParams)
        val keyboard = service.getSystemService(InputMethodManager::class.java)
        if (on) {
            input.setText("")
            input.requestFocus()
            input.post { keyboard.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT) }
        } else {
            keyboard.hideSoftInputFromWindow(input.windowToken, 0)
        }
    }

    private fun submitTyped() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        setTextMode(false)
        submit(text)
    }

    private fun submit(text: String) {
        showCard("« $text »\n\nJe réfléchis…")
        val accepted = AgentSession.send(service, text) { answer ->
            val reply = answer.ifBlank { "C'est fait." }
            showCard(reply)
            speak(reply)
            handler.postDelayed(hideCard, CARD_DELAY_MS)
        }
        if (!accepted) showCard("Je suis encore sur ta demande précédente, un instant…")
    }

    private fun openApp() {
        closeCard()
        service.startActivity(Intent(service, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // --- La voix ---

    private fun startListening() {
        if (ContextCompat.checkSelfPermission(service, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            showCard("J'ai besoin du micro pour t'écouter. Autorise-le dans Ozariel > Réglages, ou fais un appui long sur la bulle pour écrire.")
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(service)) {
            showCard("La reconnaissance vocale n'est pas disponible sur ce téléphone. Fais un appui long sur la bulle pour écrire.")
            return
        }
        val recognizer = recognizer ?: SpeechRecognizer.createSpeechRecognizer(service).also {
            it.setRecognitionListener(Listener())
            recognizer = it
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        listening = true
        bubble?.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.rgb(239, 68, 68)) }
        showCard("Je t'écoute…")
        recognizer.startListening(intent)
    }

    private fun stopListening() {
        if (!listening) return
        listening = false
        recognizer?.cancel()
        bubble?.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
    }

    private inner class Listener : RecognitionListener {
        override fun onPartialResults(partialResults: Bundle?) {
            partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                ?.takeIf { it.isNotBlank() }?.let { status.text = "« $it »" }
        }

        override fun onResults(results: Bundle?) {
            stopListening()
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (text.isBlank()) showCard("Je n'ai rien entendu. Appuie sur la bulle pour réessayer.") else submit(text)
        }

        override fun onError(error: Int) {
            stopListening()
            showCard(
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Je n'ai rien entendu. Appuie sur la bulle pour réessayer."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "La reconnaissance vocale a besoin d'internet."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Autorise le micro dans Ozariel > Réglages."
                    else -> "La reconnaissance vocale a échoué (erreur $error). Réessaie ou fais un appui long pour écrire."
                }
            )
            handler.postDelayed(hideCard, CARD_DELAY_MS)
        }

        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun speak(text: String) {
        if (!settings.speakReplies) return
        val engine = tts
        if (engine != null && ttsReady) {
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ozariel")
            return
        }
        if (engine == null) {
            tts = TextToSpeech(service) { result ->
                ttsReady = result == TextToSpeech.SUCCESS
                if (ttsReady) {
                    tts?.language = Locale.FRANCE
                    tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ozariel")
                }
            }
        }
    }

    // --- Outils ---

    private fun overlayParams(width: Int, height: Int) = WindowManager.LayoutParams(
        width,
        height,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun dp(value: Int) = (value * density).toInt()

    companion object {
        private const val CARD_DELAY_MS = 10_000L
        private const val TAG = "Ozariel/Bulle"
    }
}
