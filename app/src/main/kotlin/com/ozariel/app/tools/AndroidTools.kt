package com.ozariel.app.tools

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaMetadata
import android.net.Uri
import android.provider.AlarmClock
import android.provider.MediaStore
import android.app.SearchManager
import android.view.KeyEvent
import com.ozariel.app.OzarielApp
import com.ozariel.app.services.AgentAccessibilityService
import com.ozariel.app.services.MessageListenerService
import com.ozariel.app.tasks.TaskScheduler
import com.ozariel.core.style.Draft
import com.ozariel.core.tools.DeviceTool
import com.ozariel.core.tools.ToolArgs
import com.ozariel.core.tools.ToolParam
import com.ozariel.core.tools.ToolParam.Companion.boolean
import com.ozariel.core.tools.ToolParam.Companion.integer
import com.ozariel.core.tools.ToolParam.Companion.string
import com.ozariel.core.tools.ToolResult
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.ZoneId

/** Petite aide pour déclarer un outil en quelques lignes. */
private fun tool(
    name: String,
    description: String,
    vararg params: ToolParam,
    sensitive: Boolean = false,
    block: suspend (ToolArgs) -> ToolResult,
): DeviceTool {
    val toolName = name
    val toolDescription = description
    val isSensitive = sensitive
    return object : DeviceTool {
        override val name = toolName
        override val description = toolDescription
        override val parameters = params.toList()
        override val sensitive = isSensitive
        override suspend fun run(args: ToolArgs) = block(args)
    }
}

/** Toutes les actions que l'agent peut faire sur le téléphone. */
object AndroidTools {
    private val MUSIC_APPS = mapOf(
        "spotify" to "com.spotify.music",
        "youtube music" to "com.google.android.apps.youtube.music",
        "deezer" to "deezer.android.app",
        "apple music" to "com.apple.android.music",
        "soundcloud" to "com.soundcloud.android",
    )

    fun all(context: Context): List<DeviceTool> = apps(context) + music(context) + screen() +
        utilities(context) + tasks(context) + messages(context) + mood()

    private fun launch(context: Context, intent: Intent) {
        val service = AgentAccessibilityService.instance
        if (service != null) service.launch(intent) else context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun installedApps(context: Context): Map<String, String> {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(launcher, PackageManager.MATCH_ALL)
            .associate { it.loadLabel(pm).toString() to it.activityInfo.packageName }
    }

    private fun apps(context: Context) = listOf(
        tool(
            "open_app",
            "Ouvre une application installée à partir de son nom (ex. « WhatsApp », « YouTube », « Paramètres »).",
            string("app_name", "Nom de l'application"),
        ) { args ->
            val wanted = args.string("app_name")
            val apps = installedApps(context)
            val match = apps.entries.firstOrNull { it.key.equals(wanted, ignoreCase = true) }
                ?: apps.entries.firstOrNull { it.key.contains(wanted, ignoreCase = true) }
                ?: return@tool ToolResult.error("Aucune application nommée « $wanted ». Utilise list_apps pour voir la liste.")
            val intent = context.packageManager.getLaunchIntentForPackage(match.value)
                ?: return@tool ToolResult.error("Impossible d'ouvrir ${match.key}.")
            launch(context, intent)
            delay(1500)
            ToolResult.ok("${match.key} est ouverte.")
        },
        tool("list_apps", "Liste les applications installées sur le téléphone.") {
            ToolResult.ok(installedApps(context).keys.sorted().joinToString(", "))
        },
    )

    private fun music(context: Context): List<DeviceTool> {
        val audio = context.getSystemService(AudioManager::class.java)
        return listOf(
            tool(
                "media_control",
                "Contrôle la musique ou la vidéo en cours de lecture, quelle que soit l'application.",
                string("action", "Action à effectuer", enum = listOf("play", "pause", "toggle", "next", "previous", "stop")),
            ) { args ->
                val code = when (args.string("action")) {
                    "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
                    "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
                    "toggle" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                    "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
                    "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                    else -> KeyEvent.KEYCODE_MEDIA_STOP
                }
                audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
                ToolResult.ok("Fait.")
            },
            tool("now_playing", "Indique ce qui est en cours de lecture (titre, artiste, application).") {
                val controllers = MessageListenerService.mediaControllers(context)
                if (controllers.isEmpty()) {
                    return@tool ToolResult.ok("Rien en lecture, ou l'accès aux notifications d'Ozariel n'est pas activé.")
                }
                ToolResult.ok(controllers.joinToString("\n") { c ->
                    val m = c.metadata
                    val title = m?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "?"
                    val artist = m?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "?"
                    val playing = c.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING
                    "${c.packageName} : $title, $artist (${if (playing) "en lecture" else "en pause"})"
                })
            },
            tool(
                "play_music",
                "Lance la lecture d'un morceau, d'un artiste, d'un album ou d'une playlist par recherche.",
                string("query", "Ce qu'il faut jouer (ex. « Aya Nakamura », « playlist chill »)"),
                string("app", "Application à utiliser", required = false, enum = MUSIC_APPS.keys.toList()),
            ) { args ->
                val query = args.string("query")
                val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                    .putExtra(SearchManager.QUERY, query)
                    .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                args.optString("app")?.let { MUSIC_APPS[it.lowercase()] }?.let(intent::setPackage)
                runCatching { launch(context, intent) }
                    .fold({ ToolResult.ok("Lecture de « $query » demandée.") }, { ToolResult.error("Aucun lecteur compatible : ${it.message}") })
            },
            tool(
                "set_volume",
                "Règle le volume de la musique, en pourcentage.",
                integer("percent", "Volume de 0 à 100"),
            ) { args ->
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val level = (args.int("percent").coerceIn(0, 100) * max) / 100
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, level, AudioManager.FLAG_SHOW_UI)
                ToolResult.ok("Volume réglé.")
            },
        )
    }

    private fun screen() = listOf(
        tool(
            "read_screen",
            "Lit ce qui est affiché à l'écran : la liste numérotée des textes, boutons et champs visibles.",
        ) { ToolResult.ok(AgentAccessibilityService.require().readScreen()) },
        tool(
            "tap_element",
            "Touche un élément de l'écran par son numéro, tel que donné par le dernier read_screen.",
            integer("index", "Numéro de l'élément"),
            boolean("long_press", "Appui long au lieu d'un toucher simple", required = false),
        ) { args ->
            val service = AgentAccessibilityService.require()
            val result = if (args.optBoolean("long_press") == true) service.longPress(args.int("index")) else service.tap(args.int("index"))
            delay(1000)
            ToolResult.ok(result)
        },
        tool(
            "type_text",
            "Écrit du texte dans un champ de saisie (celui indiqué, sinon celui qui est sélectionné). Remplace le texte existant.",
            string("text", "Texte à écrire"),
            integer("index", "Numéro du champ, d'après read_screen", required = false),
            boolean("submit", "Valider avec la touche Entrée du clavier après la saisie", required = false),
        ) { args ->
            val service = AgentAccessibilityService.require()
            ToolResult.ok(service.typeText(args.string("text"), args.optInt("index"), args.optBoolean("submit") == true))
        },
        tool(
            "scroll",
            "Fait défiler l'écran.",
            string("direction", "Sens du défilement", enum = listOf("up", "down", "left", "right")),
        ) { args ->
            val result = AgentAccessibilityService.require().scroll(args.string("direction"))
            delay(700)
            ToolResult.ok(result)
        },
        tool(
            "global_action",
            "Action système : retour, accueil, applications récentes, volet de notifications, réglages rapides, verrouiller.",
            string("action", "Action", enum = listOf("back", "home", "recents", "notifications", "quick_settings", "lock_screen")),
        ) { args ->
            val result = AgentAccessibilityService.require().global(args.string("action"))
            delay(700)
            ToolResult.ok(result)
        },
        tool(
            "wait",
            "Attend quelques secondes, par exemple qu'une page se charge.",
            integer("seconds", "Durée, de 1 à 10 secondes"),
        ) { args ->
            delay(args.int("seconds").coerceIn(1, 10) * 1000L)
            ToolResult.ok("Attendu.")
        },
    )

    private fun utilities(context: Context) = listOf(
        tool("open_url", "Ouvre un lien web ou un lien d'application.", string("url", "Adresse à ouvrir")) { args ->
            launch(context, Intent(Intent.ACTION_VIEW, Uri.parse(args.string("url"))))
            ToolResult.ok("Lien ouvert.")
        },
        tool("web_search", "Lance une recherche web sur le téléphone.", string("query", "Recherche")) { args ->
            launch(context, Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, args.string("query")))
            ToolResult.ok("Recherche lancée.")
        },
        tool(
            "set_alarm",
            "Crée une alarme dans l'application Horloge.",
            integer("hour", "Heure (0-23)"),
            integer("minute", "Minutes (0-59)"),
            string("label", "Nom de l'alarme", required = false),
        ) { args ->
            launch(
                context,
                Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, args.int("hour"))
                    .putExtra(AlarmClock.EXTRA_MINUTES, args.int("minute"))
                    .putExtra(AlarmClock.EXTRA_MESSAGE, args.optString("label") ?: "Ozariel")
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true),
            )
            ToolResult.ok("Alarme créée.")
        },
        tool(
            "set_timer",
            "Lance un minuteur.",
            integer("seconds", "Durée en secondes"),
            string("label", "Nom du minuteur", required = false),
        ) { args ->
            launch(
                context,
                Intent(AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_LENGTH, args.int("seconds"))
                    .putExtra(AlarmClock.EXTRA_MESSAGE, args.optString("label") ?: "Ozariel")
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true),
            )
            ToolResult.ok("Minuteur lancé.")
        },
    )

    private fun mood() = listOf(
        tool(
            "set_mood",
            "Montre une émotion sur le visage de la bulle Ozariel, quand c'est naturel (blague, bonne nouvelle, excuse…).",
            string("mood", "Émotion", enum = listOf("happy", "sad", "surprised", "thinking", "love", "wink")),
        ) { args ->
            val shown = AgentAccessibilityService.instance?.floating?.showMood(args.string("mood")) == true
            ToolResult.ok(if (shown) "Émotion affichée." else "La bulle n'est pas affichée, continue normalement.")
        },
    )

    private fun tasks(context: Context) = listOf(
        tool(
            "schedule_task",
            "Programme une tâche que tu exécuteras toi-même plus tard (une fois ou de façon répétée). " +
                "La consigne doit être complète et compréhensible sans le contexte de la conversation.",
            string("instruction", "Ce qu'il faudra faire, par exemple « Mets la playlist Réveil sur Spotify »"),
            string("at", "Date et heure de la première exécution, format AAAA-MM-JJTHH:MM (heure locale)", required = false),
            integer("in_minutes", "Ou bien : dans combien de minutes", required = false),
            integer("repeat_every_minutes", "Répéter toutes les N minutes (1440 = chaque jour, minimum 15)", required = false),
        ) { args ->
            val now = System.currentTimeMillis()
            val firstRun = args.optString("at")?.let {
                LocalDateTime.parse(it).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            } ?: args.optInt("in_minutes")?.let { now + it * 60_000L }
                ?: return@tool ToolResult.error("Indique « at » ou « in_minutes ».")
            val repeat = args.optInt("repeat_every_minutes")?.toLong()?.coerceAtLeast(15)
            val task = TaskScheduler.schedule(context, args.string("instruction"), firstRun, repeat)
            ToolResult.ok("Tâche ${task.id} programmée.")
        },
        tool("list_scheduled_tasks", "Liste les tâches programmées.") {
            val tasks = OzarielApp.instance.tasks.all()
            if (tasks.isEmpty()) ToolResult.ok("Aucune tâche programmée.")
            else ToolResult.ok(tasks.joinToString("\n") { TaskScheduler.describe(it) })
        },
        tool("cancel_scheduled_task", "Annule une tâche programmée.", string("id", "Identifiant de la tâche")) { args ->
            TaskScheduler.cancel(context, args.string("id"))
            ToolResult.ok("Tâche annulée.")
        },
    )

    private fun messages(context: Context) = listOf(
        tool(
            "read_messages",
            "Lit les derniers messages reçus (WhatsApp, SMS, Telegram…) depuis le démarrage de l'app.",
            string("contact", "Nom du contact ou du groupe ; sinon toutes les conversations récentes", required = false),
        ) { args ->
            val store = OzarielApp.instance.messages
            val conversations = args.optString("contact")?.let { listOfNotNull(store.find(it)) } ?: store.recent()
            if (conversations.isEmpty()) {
                return@tool ToolResult.ok("Aucun message reçu pour l'instant (ou l'accès aux notifications n'est pas activé).")
            }
            ToolResult.ok(conversations.joinToString("\n\n") { c ->
                "${c.contact} (${c.app})${if (c.replyAction == null) " [réponse directe impossible]" else ""} :\n" +
                    c.lines.takeLast(10).joinToString("\n") { "  ${it.author} : ${it.text}" }
            })
        },
        tool(
            "draft_reply",
            "Rédige une réponse au dernier message d'un contact, dans le style de l'utilisateur (sans l'envoyer).",
            string("contact", "Nom du contact"),
        ) { args ->
            val app = OzarielApp.instance
            val conversation = app.messages.find(args.string("contact"))
                ?: return@tool ToolResult.error("Pas de conversation récente avec ce contact.")
            val writer = app.replyWriter() ?: return@tool ToolResult.error("Clé API manquante.")
            when (val draft = writer.draft(conversation.contact, conversation.lines)) {
                is Draft.Reply -> ToolResult.ok(draft.text)
                is Draft.NeedsOwner -> ToolResult.ok("Mieux vaut que l'utilisateur réponde directement : ${draft.reason}")
            }
        },
        tool(
            "send_reply",
            "Envoie un message à un contact qui a écrit récemment, via le bouton Répondre de sa notification.",
            string("contact", "Nom du contact"),
            string("text", "Message à envoyer"),
            sensitive = true,
        ) { args ->
            val conversation = OzarielApp.instance.messages.find(args.string("contact"))
                ?: return@tool ToolResult.error("Pas de conversation récente avec ce contact. Ouvre l'application de messagerie pour lui écrire.")
            if (MessageListenerService.sendReply(context, conversation.key, args.string("text"))) {
                ToolResult.ok("Message envoyé à ${conversation.contact}.")
            } else {
                ToolResult.error("Envoi impossible depuis la notification. Ouvre l'application de messagerie pour lui écrire.")
            }
        },
    )
}
