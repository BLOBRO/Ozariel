package com.ozariel.app.services

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.ozariel.app.OzarielApp
import com.ozariel.app.R
import com.ozariel.app.ui.MainActivity
import com.ozariel.app.wake.VoiceEnrollment
import com.ozariel.app.wake.VoiceFeatures
import com.ozariel.core.wake.VoiceModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.concurrent.thread

/**
 * Écoute en permanence le mot « Ozariel », directement sur le téléphone : la voix est comparée au modèle
 * appris lors de l'enregistrement (voir [VoiceEnrollment]) et rien ne quitte le téléphone. À la détection, le personnage
 * se réveille et la bulle écoute la demande ; l'écoute du mot reprend ensuite.
 * Android impose une notification permanente tant que le micro est utilisé.
 */
class WakeWordService : Service() {
    private val settings = OzarielApp.instance.settings
    private val handler = Handler(Looper.getMainLooper())
    private val audio by lazy { getSystemService(AudioManager::class.java) }

    @Volatile private var running = false
    private var listener: Thread? = null
    private var features: VoiceFeatures? = null
    private var lastWake = 0L

    /** La bulle utilise le micro pour écouter la demande. */
    private var held = false
    /** Mis en pause depuis la notification. */
    private var userPaused = false
    private var screenOn = true
    /** Appel en cours ou autre application qui enregistre. */
    private var micBusy = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            screenOn = intent.action != Intent.ACTION_SCREEN_OFF
            update()
        }
    }

    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
            val others = configs.size - (if (running) 1 else 0)
            micBusy = others > 0 || audio.mode == AudioManager.MODE_IN_CALL || audio.mode == AudioManager.MODE_IN_COMMUNICATION
            update()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        screenOn = getSystemService(PowerManager::class.java).isInteractive
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification("Démarrage…"),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0,
        )
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        })
        audio.registerAudioRecordingCallback(recordingCallback, handler)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> userPaused = true
            ACTION_RESUME -> userPaused = false
        }
        update()
        return START_STICKY
    }

    override fun onDestroy() {
        stopListening()
        features?.close()
        features = null
        runCatching { unregisterReceiver(screenReceiver) }
        audio.unregisterAudioRecordingCallback(recordingCallback)
        if (instance === this) instance = null
        _status.value = "Réveil par la voix désactivé."
        super.onDestroy()
    }

    /** Écoute ou non selon la situation (écran, appel, bulle qui écoute, pause). */
    private fun update() {
        val reason = when {
            userPaused -> "En pause."
            held -> "Je t'écoute…"
            micBusy -> "En pause pendant l'appel ou l'enregistrement en cours."
            !screenOn && settings.wakeOnlyScreenOn -> "En pause, écran éteint."
            else -> null
        }
        if (reason == null) startListening() else {
            stopListening()
            showStatus(reason)
        }
    }

    private fun startListening() {
        if (running) return
        val model = VoiceEnrollment.load(this) ?: run {
            showStatus("Apprends-moi d'abord ton « Ozariel » dans les Réglages.")
            return
        }
        running = true
        showStatus("J'écoute « Ozariel ».")
        listener = thread(name = "ozariel-eveil") { listen(model) }
    }

    private fun stopListening() {
        if (!running) return
        running = false
        // Attend que le micro soit libéré avant de le confier à la bulle (une lecture dure 80 ms).
        listener?.join(500)
        listener = null
        _score.value = 0f
    }

    /** La boucle d'écoute : empreinte toutes les 80 ms, comparée au modèle appris. */
    private fun listen(model: VoiceModel) {
        val recorder = try {
            VoiceEnrollment.openMic()
        } catch (e: Exception) {
            handler.post { running = false; showStatus("Le micro n'est pas disponible pour l'instant.") }
            return
        }
        val features = this.features ?: VoiceFeatures(this).also { this.features = it }
        features.reset()
        // Empreintes des derniers blocs, calculées seulement quand il y a du son (null = pas encore calculée).
        val window = ArrayDeque<FloatArray?>()
        val levels = ArrayDeque<Float>()
        val chunk = ShortArray(VoiceFeatures.CHUNK)
        try {
            recorder.startRecording()
            while (running) {
                var read = 0
                while (read < chunk.size && running) {
                    val n = recorder.read(chunk, read, chunk.size - read)
                    if (n <= 0) break
                    read += n
                }
                if (read < chunk.size) break
                features.pushMel(chunk)
                window.addLast(null)
                levels.addLast(VoiceFeatures.rms(chunk))
                if (window.size > VoiceModel.WINDOW) { window.removeFirst(); levels.removeFirst() }
                if (window.size < VoiceModel.WINDOW) continue

                // Dans le silence, rien de plus à calculer : c'est ce qui économise la batterie.
                if (levels.max() <= VoiceModel.SPEECH_RMS) {
                    _score.value = 0f
                    continue
                }
                for (i in window.indices) {
                    if (window[i] == null) window[i] = features.embeddingAt(window.size - 1 - i)
                }
                val score = model.score(window.map { it!! })
                _score.value = score
                val now = System.currentTimeMillis()
                if (score >= model.thresholdFor(settings.wakeSensitivity) && now - lastWake > REARM_MS) {
                    lastWake = now
                    Log.d(TAG, "Mot d'éveil détecté (score $score)")
                    handler.post { onWakeWord() }
                    break
                }
            }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }
    }

    private fun onWakeWord() {
        held = true
        running = false
        vibrate()
        val floating = AgentAccessibilityService.instance?.floating
        if (floating == null || !floating.wakeUp()) {
            // Pas de bulle pour écouter la demande : on ouvre l'app.
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            held = false
            update()
        } else {
            showStatus("Je t'écoute…")
        }
    }

    private fun vibrate() {
        val vibrator = getSystemService(Vibrator::class.java) ?: return
        vibrator.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun showStatus(text: String) {
        _status.value = text
        ContextCompat.getSystemService(this, android.app.NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, notification(text))
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val toggle = PendingIntent.getService(
            this,
            1,
            Intent(this, WakeWordService::class.java).setAction(if (userPaused) ACTION_RESUME else ACTION_PAUSE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, OzarielApp.CHANNEL_WAKE)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Ozariel t'écoute")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, if (userPaused) "Reprendre" else "Pause", toggle)
            .build()
    }

    companion object {
        private const val TAG = "Ozariel/Eveil"
        private const val NOTIFICATION_ID = 4242
        private const val ACTION_PAUSE = "com.ozariel.app.WAKE_PAUSE"
        private const val ACTION_RESUME = "com.ozariel.app.WAKE_RESUME"
        /** Délai avant de pouvoir se réveiller à nouveau. */
        private const val REARM_MS = 2000L

        @Volatile
        private var instance: WakeWordService? = null

        private val _status = MutableStateFlow("Réveil par la voix désactivé.")
        val status: StateFlow<String> = _status

        /** Score de ressemblance en direct, pour le mode « Tester » des Réglages. */
        private val _score = MutableStateFlow(0f)
        val score: StateFlow<Float> = _score

        /** Ce qui manque encore pour activer le réveil par la voix, ou null si tout est prêt. */
        fun missing(context: Context): String? {
            return when {
                !VoiceEnrollment.isTrained(context) -> "Apprends-moi d'abord ton « Ozariel » (bouton ci-dessus)."
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ->
                    "Autorise le micro."
                else -> null
            }
        }

        /** Démarre ou arrête le service selon les réglages. */
        fun sync(context: Context) {
            val settings = OzarielApp.instance.settings
            if (settings.wakeWord && missing(context) == null) {
                runCatching { ContextCompat.startForegroundService(context, Intent(context, WakeWordService::class.java)) }
                    .onFailure { Log.w(TAG, "Démarrage impossible en arrière-plan : ${it.javaClass.simpleName}") }
            } else {
                context.stopService(Intent(context, WakeWordService::class.java))
            }
        }

        /** Réglages modifiés (nouvel apprentissage…) : on recrée l'écoute. */
        fun restart(context: Context) {
            context.stopService(Intent(context, WakeWordService::class.java))
            sync(context)
        }

        /** La bulle prend le micro. */
        fun hold() {
            instance?.let { it.held = true; it.update() }
        }

        /** La bulle a fini d'écouter : on reprend l'écoute du mot. */
        fun release() {
            instance?.let { it.held = false; it.update() }
        }
    }
}
