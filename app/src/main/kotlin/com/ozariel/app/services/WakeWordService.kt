package com.ozariel.app.services

import ai.picovoice.porcupine.PorcupineException
import ai.picovoice.porcupine.PorcupineManager
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Écoute en permanence le mot « Ozariel », directement sur le téléphone (Picovoice Porcupine) :
 * rien ne quitte le téléphone tant que le mot n'est pas détecté. À la détection, le personnage
 * se réveille et la bulle écoute la demande ; l'écoute du mot reprend ensuite.
 * Android impose une notification permanente tant que le micro est utilisé.
 */
class WakeWordService : Service() {
    private val settings = OzarielApp.instance.settings
    private val handler = Handler(Looper.getMainLooper())
    private val audio by lazy { getSystemService(AudioManager::class.java) }

    private var manager: PorcupineManager? = null
    private var running = false

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
        runCatching { manager?.delete() }
        manager = null
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
        val engine = manager ?: try {
            PorcupineManager.Builder()
                .setAccessKey(settings.picovoiceKey)
                .setKeywordPaths(arrayOf(keywordFile(this).absolutePath))
                .setModelPath(MODEL_ASSET)
                .setSensitivities(floatArrayOf(SENSITIVITY))
                .setErrorCallback { e -> handler.post { fail(e) } }
                .build(applicationContext) { handler.post { onWakeWord() } }
                .also { manager = it }
        } catch (e: PorcupineException) {
            fail(e)
            return
        }
        try {
            engine.start()
            running = true
            showStatus("J'écoute « Ozariel ».")
        } catch (e: PorcupineException) {
            fail(e)
        }
    }

    private fun stopListening() {
        if (!running) return
        running = false
        runCatching { manager?.stop() }
    }

    private fun onWakeWord() {
        Log.d(TAG, "Mot d'éveil détecté")
        held = true
        stopListening()
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

    /** La clé, le fichier du mot ou le micro posent problème : on l'explique sans jamais afficher la clé. */
    private fun fail(e: PorcupineException) {
        Log.w(TAG, "Porcupine : ${e.javaClass.simpleName}")
        stopListening()
        runCatching { manager?.delete() }
        manager = null
        showStatus(
            when (e) {
                is ai.picovoice.porcupine.PorcupineActivationException,
                is ai.picovoice.porcupine.PorcupineInvalidArgumentException ->
                    "Clé Picovoice ou fichier du mot « Ozariel » invalide (le mot doit être créé en français). Vérifie-les dans les Réglages."
                is ai.picovoice.porcupine.PorcupineActivationLimitException ->
                    "La clé Picovoice a atteint sa limite d'appareils. Vérifie ton compte sur console.picovoice.ai."
                is ai.picovoice.porcupine.PorcupineActivationRefusedException ->
                    "Picovoice a refusé la clé. Vérifie-la sur console.picovoice.ai."
                else -> "Le réveil par la voix a rencontré un problème. Réessaie depuis les Réglages."
            }
        )
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
        private const val MODEL_ASSET = "porcupine_params_fr.pv"
        private const val SENSITIVITY = 0.6f

        @Volatile
        private var instance: WakeWordService? = null

        private val _status = MutableStateFlow("Réveil par la voix désactivé.")
        val status: StateFlow<String> = _status

        /** Le fichier du mot « Ozariel » créé sur console.picovoice.ai et importé dans les Réglages. */
        fun keywordFile(context: Context) = File(context.filesDir, "ozariel.ppn")

        /** Ce qui manque encore pour activer le réveil par la voix, ou null si tout est prêt. */
        fun missing(context: Context): String? {
            val settings = OzarielApp.instance.settings
            return when {
                settings.picovoiceKey.isBlank() -> "Colle ta clé d'accès Picovoice."
                !keywordFile(context).exists() -> "Importe le fichier du mot « Ozariel » (.ppn)."
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

        /** Réglages modifiés (clé, fichier du mot) : on recrée le moteur. */
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
