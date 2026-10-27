package com.ozariel.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ozariel.app.OzarielApp
import com.ozariel.app.services.WakeWordService
import com.ozariel.app.wake.VoiceEnrollment
import com.ozariel.core.wake.VoiceModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Réveil par la voix : apprendre « Ozariel » sur ta voix, puis l'appeler sans toucher le téléphone. */
@Composable
internal fun WakeWordSettings(micGranted: Boolean, askMic: () -> Unit) {
    val settings = OzarielApp.instance.settings
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(settings.wakeWord) }
    var screenOnly by remember { mutableStateOf(settings.wakeOnlyScreenOn) }
    var sensitivity by remember { mutableFloatStateOf(settings.wakeSensitivity) }
    var trained by remember { mutableStateOf(VoiceEnrollment.isTrained(context)) }
    var enrolling by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var retrainStatus by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val status by WakeWordService.status.collectAsState()

    Text("Réveil par la voix", style = MaterialTheme.typography.titleMedium)
    Text(
        "Dis « Ozariel » et la bulle t'écoute, sans toucher le téléphone. Ozariel apprend ta façon de dire son nom : " +
            "tout se passe sur le téléphone, rien n'est envoyé. Android affiche une notification tant que l'écoute est active.",
        style = MaterialTheme.typography.bodySmall,
    )
    if (!micGranted) PermissionRow("Micro", false, askMic)
    PermissionRow(if (trained) "Voix apprise ✓" else "Apprendre ma voix (2 minutes)", null) {
        if (micGranted) enrolling = true else askMic()
    }
    if (VoiceEnrollment.hasAllRecordings(context)) {
        TextButton(onClick = {
            retrainStatus = "Calcul en cours…"
            scope.launch {
                retrainStatus = runCatching { VoiceEnrollment.train(context) }.fold(
                    { trained = true; WakeWordService.restart(context); "Recalculé avec tes enregistrements." },
                    { "Échec du calcul : ${it.message}" },
                )
            }
        }) { Text("Recalculer avec les mêmes enregistrements") }
        if (retrainStatus.isNotEmpty()) Text(retrainStatus, style = MaterialTheme.typography.labelMedium)
    }
    SwitchRow("Réveil par la voix (« Ozariel »)", enabled) {
        enabled = it; settings.wakeWord = it; WakeWordService.sync(context)
    }
    SwitchRow("Seulement quand l'écran est allumé (économise la batterie)", screenOnly) {
        screenOnly = it; settings.wakeOnlyScreenOn = it; WakeWordService.restart(context)
    }
    Text("Sensibilité : ${sensitivityLabel(sensitivity)}", style = MaterialTheme.typography.bodyMedium)
    Slider(
        value = sensitivity,
        onValueChange = { sensitivity = it },
        onValueChangeFinished = { settings.wakeSensitivity = sensitivity },
    )
    Text(
        "Plus sensible : il t'entend de plus loin, mais peut se réveiller par erreur. Plus strict : moins d'erreurs, il faut parler clairement.",
        style = MaterialTheme.typography.labelSmall,
    )
    if (enabled) {
        Text(
            WakeWordService.missing(context) ?: status,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        if (trained) SwitchRow("Tester : afficher le score en direct", testing) { testing = it }
        if (testing) LiveScore(sensitivity)
    }

    if (enrolling) {
        EnrollmentDialog(
            onDone = { ok ->
                enrolling = false
                trained = VoiceEnrollment.isTrained(context)
                if (ok) WakeWordService.restart(context)
            },
        )
    }
}

private fun sensitivityLabel(value: Float) = when {
    value < 0.3f -> "stricte"
    value > 0.7f -> "élevée"
    else -> "normale"
}

/** Le score de ressemblance en direct, comparé au seuil : pratique pour régler la sensibilité. */
@Composable
private fun LiveScore(sensitivity: Float) {
    val context = LocalContext.current
    val score by WakeWordService.score.collectAsState()
    val model = remember { VoiceEnrollment.load(context) } ?: return
    val threshold = model.thresholdFor(sensitivity)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Dis « Ozariel », puis d'autres mots : la barre doit dépasser le trait seulement pour ton nom.", style = MaterialTheme.typography.labelSmall)
        // On affiche la zone utile, de 0,2 sous le seuil à 0,2 au-dessus.
        LinearProgressIndicator(
            progress = { ((score - threshold + 0.2f) / 0.4f).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(10.dp),
            color = if (score >= threshold) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
        )
        Text(
            "Score %.2f — seuil %.2f %s".format(score, threshold, if (score >= threshold) "✓ reconnu" else ""),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

/** L'apprentissage guidé : le mot plusieurs fois, quelques phrases ordinaires, puis du silence. */
@Composable
private fun EnrollmentDialog(onDone: (Boolean) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val steps = VoiceEnrollment.steps
    var index by remember { mutableIntStateOf(0) }
    var level by remember { mutableFloatStateOf(0f) }
    var recording by remember { mutableStateOf<Job?>(null) }
    var recorded by remember { mutableStateOf(false) }
    var warning by remember { mutableStateOf("") }
    var training by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var success by remember { mutableStateOf(false) }

    // Le micro ne peut servir qu'à une écoute à la fois.
    DisposableEffect(Unit) {
        WakeWordService.hold()
        onDispose { WakeWordService.release() }
    }

    Dialog(onDismissRequest = { if (recording == null && !training) onDone(false) }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when {
                    training -> {
                        CircularProgressIndicator()
                        Text("J'apprends ta voix…")
                    }
                    result != null -> {
                        Text(result!!, textAlign = TextAlign.Center)
                        Button(onClick = { onDone(success) }) { Text(if (success) "Terminé" else "Fermer") }
                        if (!success) TextButton(onClick = { result = null; index = 0; recorded = false }) { Text("Recommencer") }
                    }
                    else -> {
                        val step = steps[index]
                        Text("Étape ${index + 1} sur ${steps.size}", style = MaterialTheme.typography.labelLarge)
                        Text(step.instruction, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                        LinearProgressIndicator(
                            progress = { (level / 6000f).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(12.dp),
                        )
                        if (warning.isNotEmpty()) Text(warning, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (recording != null) {
                                Text("J'enregistre…")
                            } else if (!recorded) {
                                Button(onClick = {
                                    warning = ""
                                    recording = scope.launch {
                                        val peak = runCatching { VoiceEnrollment.record(context, step) { level = it } }
                                            .getOrElse { warning = it.message ?: "Le micro n'est pas disponible."; -1f }
                                        level = 0f
                                        recording = null
                                        if (peak >= 0) {
                                            recorded = true
                                            if (step.positive && peak < VoiceModel.SPEECH_RMS * 2) {
                                                warning = "C'était très faible. Rapproche-toi un peu ou parle plus fort, puis refais-le."
                                            }
                                        }
                                    }
                                }) { Text("Enregistrer") }
                            } else {
                                OutlinedButton(onClick = { recorded = false; warning = "" }) { Text("Refaire") }
                                Button(onClick = {
                                    recorded = false
                                    warning = ""
                                    if (index < steps.lastIndex) {
                                        index++
                                    } else {
                                        training = true
                                        scope.launch {
                                            val outcome = runCatching { VoiceEnrollment.train(context) }
                                            training = false
                                            val model = outcome.getOrNull()
                                            success = model != null
                                            result = when {
                                                model == null -> "Je n'ai pas réussi à apprendre : ${outcome.exceptionOrNull()?.message}"
                                                model.positiveFloor > model.negativeCeiling ->
                                                    "C'est appris ! Ton « Ozariel » se distingue bien des phrases ordinaires. " +
                                                        "Active le réveil par la voix et essaie avec le mode « Tester »."
                                                else ->
                                                    "C'est appris, mais certaines phrases ressemblent à ton « Ozariel ». " +
                                                        "Ça peut marcher ; sinon recommence dans un endroit calme en articulant bien le mot."
                                            }
                                        }
                                    }
                                }) { Text(if (index < steps.lastIndex) "Suivant" else "Terminer") }
                            }
                        }
                        if (recording == null) TextButton(onClick = { onDone(false) }) { Text("Annuler") }
                    }
                }
            }
        }
    }
}
