package com.ozariel.app.ui

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.ozariel.app.OzarielApp
import com.ozariel.app.services.AgentAccessibilityService
import com.ozariel.app.services.MessageListenerService
import com.ozariel.core.agent.AgentModel
import com.ozariel.core.agent.Provider
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: AgentViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OzarielTheme { OzarielScreen(viewModel) }
        }
    }
}

@Composable
private fun OzarielTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = Color(0xFFA5B4FC), secondary = Color(0xFFFDE68A))
    } else {
        lightColorScheme(primary = Color(0xFF4F46E5), secondary = Color(0xFFB45309))
    }
    MaterialTheme(colorScheme = colors, content = content)
}

private enum class Tab(val label: String) { AGENT("Agent"), MESSAGES("Messages"), STYLE("Mon style"), SETTINGS("Réglages") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OzarielScreen(viewModel: AgentViewModel) {
    var tab by remember { mutableStateOf(if (OzarielApp.instance.settings.hasKeyForModel) Tab.AGENT else Tab.SETTINGS) }
    val confirmation by viewModel.confirmation.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ozariel") },
                actions = {
                    if (tab == Tab.AGENT) {
                        IconButton(onClick = viewModel::newConversation) { Icon(Icons.Default.Refresh, "Nouvelle conversation") }
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        label = { Text(t.label) },
                        icon = {
                            Icon(
                                when (t) {
                                    Tab.AGENT -> Icons.Default.Chat
                                    Tab.MESSAGES -> Icons.Default.Forum
                                    Tab.STYLE -> Icons.Default.AutoAwesome
                                    Tab.SETTINGS -> Icons.Default.Settings
                                },
                                contentDescription = null,
                            )
                        },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                Tab.AGENT -> ChatTab(viewModel)
                Tab.MESSAGES -> MessagesTab(viewModel)
                Tab.STYLE -> StyleTab(viewModel)
                Tab.SETTINGS -> SettingsTab(viewModel)
            }
        }
    }

    confirmation?.let { pending ->
        AlertDialog(
            onDismissRequest = { viewModel.answerConfirmation(false) },
            title = { Text("Autoriser cette action ?") },
            text = { Text("${pending.tool.description}\n\n${pending.args}") },
            confirmButton = { TextButton(onClick = { viewModel.answerConfirmation(true) }) { Text("Autoriser") } },
            dismissButton = { TextButton(onClick = { viewModel.answerConfirmation(false) }) { Text("Refuser") } },
        )
    }
}

@Composable
private fun ChatTab(viewModel: AgentViewModel) {
    val chat by viewModel.chat.collectAsState()
    val busy by viewModel.busy.collectAsState()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(chat.size) { if (chat.isNotEmpty()) listState.animateScrollToItem(chat.size - 1) }

    Column(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (chat.isEmpty()) {
                item {
                    Text(
                        "Demande-moi quelque chose : « Mets du lo-fi sur Spotify », « Ouvre YouTube et cherche une recette de crêpes », " +
                            "« Réveille-moi à 7h », « Qu'est-ce que Léo m'a écrit ? », « Tous les soirs à 22h, mets le volume à 20 % ».",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            }
            items(chat) { entry -> ChatBubble(entry) }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ta demande…") },
                maxLines = 4,
            )
            Spacer(Modifier.size(8.dp))
            if (busy) {
                CircularProgressIndicator(Modifier.size(32.dp))
            } else {
                IconButton(onClick = { viewModel.send(input); input = "" }) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Envoyer")
                }
            }
        }
    }
}

@Composable
private fun ChatBubble(entry: ChatEntry) {
    when (entry) {
        is ChatEntry.User -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text(
                entry.text,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp))
                    .padding(12.dp),
            )
        }
        is ChatEntry.Assistant -> Text(
            entry.text,
            modifier = Modifier
                .widthIn(max = 320.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
                .padding(12.dp),
        )
        is ChatEntry.Action -> Text(
            entry.text,
            style = MaterialTheme.typography.labelMedium,
            color = if (entry.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MessagesTab(viewModel: AgentViewModel) {
    val app = OzarielApp.instance
    val conversations by app.messages.conversations.collectAsState()
    var autoContacts by remember { mutableStateOf(app.settings.autoReplyContacts) }
    val scope = rememberCoroutineScope()

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(
                "Les messages reçus depuis le démarrage de l'app apparaissent ici. Active la réponse automatique contact par contact " +
                    "(et l'interrupteur général dans les Réglages).",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        if (conversations.isEmpty()) {
            item { Text("Aucun message pour l'instant. Vérifie que l'accès aux notifications est activé dans les Réglages.") }
        }
        items(conversations.values.sortedByDescending { it.lastIncomingAt }) { c ->
            var draft by remember(c.key) { mutableStateOf("") }
            var status by remember(c.key) { mutableStateOf("") }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${c.contact} · ${c.app}", style = MaterialTheme.typography.titleSmall)
                    c.lines.takeLast(4).forEach { Text("${it.author} : ${it.text}", style = MaterialTheme.typography.bodySmall) }
                    if (!c.isGroup) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Réponse automatique", Modifier.weight(1f))
                            Switch(
                                checked = autoContacts.any { it.equals(c.contact, ignoreCase = true) },
                                onCheckedChange = { on ->
                                    autoContacts = if (on) autoContacts + c.contact else autoContacts.filterNot { it.equals(c.contact, true) }.toSet()
                                    app.settings.autoReplyContacts = autoContacts
                                },
                            )
                        }
                    }
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Ta réponse") },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            status = "Rédaction…"
                            scope.launch {
                                draft = viewModel.draftFor(c.key)
                                status = if (draft.isBlank()) "Je préfère te laisser répondre à celui-là." else ""
                            }
                        }) { Text("Suggérer") }
                        Button(
                            enabled = draft.isNotBlank() && c.replyAction != null,
                            onClick = {
                                status = if (viewModel.sendReply(c.key, draft)) "Envoyé." else "Échec de l'envoi."
                                draft = ""
                            },
                        ) { Text("Envoyer") }
                    }
                    if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun StyleTab(viewModel: AgentViewModel) {
    val app = OzarielApp.instance
    val context = LocalContext.current
    val status by viewModel.styleStatus.collectAsState()
    val data = remember(status) { app.styleMemory.data }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) viewModel.importChats(context, uris)
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Apprendre ta façon d'écrire", style = MaterialTheme.typography.titleMedium)
        Text(
            "1. Dans WhatsApp, ouvre une discussion, menu ⋮ > Plus > Exporter la discussion > Sans médias, et enregistre le fichier.\n" +
                "2. Recommence avec plusieurs personnes différentes (famille, amis, travail) : plus il y a d'exemples, mieux c'est.\n" +
                "3. Importe les fichiers ici, puis lance l'analyse.\n\nTout reste sur ton téléphone ; seuls des extraits sont envoyés à Claude pour rédiger.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = { picker.launch(arrayOf("text/plain", "application/zip")) }, modifier = Modifier.fillMaxWidth()) {
            Text("Importer des discussions (.txt ou .zip)")
        }
        OutlinedButton(onClick = viewModel::buildProfile, enabled = data.pairs.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
            Text("Analyser mon style (${data.pairs.size} réponses)")
        }
        if (status.isNotEmpty()) Text(status, color = MaterialTheme.colorScheme.primary)
        if (data.profile.isNotBlank()) {
            Card { Text(data.profile, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall) }
        }
        TextButton(onClick = viewModel::clearStyle) { Text("Effacer la mémoire de style") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsTab(viewModel: AgentViewModel) {
    val settings = OzarielApp.instance.settings
    val context = LocalContext.current
    var apiKey by remember { mutableStateOf(settings.apiKey) }
    var geminiKey by remember { mutableStateOf(settings.geminiKey) }
    var name by remember { mutableStateOf(settings.ownerName) }
    var model by remember { mutableStateOf(settings.model) }
    var modelMenu by remember { mutableStateOf(false) }
    var suggest by remember { mutableStateOf(settings.suggestReplies) }
    var auto by remember { mutableStateOf(settings.autoReply) }
    var sensitiveInTasks by remember { mutableStateOf(settings.allowSensitiveInTasks) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    var bubble by remember { mutableStateOf(settings.floatingBubble) }
    var speak by remember { mutableStateOf(settings.speakReplies) }
    var micGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { micGranted = it }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Cerveau de l'agent", style = MaterialTheme.typography.titleMedium)
        Text(
            "Gemini est gratuit (avec des limites par minute et par jour, et Google peut utiliser les données envoyées). " +
                "Claude est payant mais plus fiable.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = geminiKey,
            onValueChange = {
                geminiKey = it
                settings.geminiKey = it
                // Première clé saisie : on passe directement sur Gemini.
                if (it.isNotBlank() && settings.apiKey.isBlank() && model.provider == Provider.CLAUDE) {
                    model = AgentModel.GEMINI_FLASH
                    settings.model = model
                }
                viewModel.settingsChanged()
            },
            label = { Text("Clé API Gemini, gratuite (aistudio.google.com/apikey)") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it; settings.apiKey = it; viewModel.settingsChanged() },
            label = { Text("Clé API Claude, payante (console.anthropic.com)") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        ExposedDropdownMenuBox(expanded = modelMenu, onExpandedChange = { modelMenu = it }) {
            OutlinedTextField(
                value = model.label,
                onValueChange = {},
                readOnly = true,
                label = { Text("Modèle") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelMenu) },
                modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                AgentModel.entries.forEach { m ->
                    DropdownMenuItem(text = { Text(m.label) }, onClick = {
                        model = m; settings.model = m; modelMenu = false; viewModel.settingsChanged()
                    })
                }
            }
        }
        OutlinedTextField(
            value = name,
            onValueChange = { name = it; settings.ownerName = it; viewModel.settingsChanged() },
            label = { Text("Ton nom (tel qu'il apparaît dans WhatsApp)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Text("Permissions", style = MaterialTheme.typography.titleMedium)
        PermissionRow(
            "Pilote d'applications (accessibilité)",
            AgentAccessibilityService.instance != null,
        ) { context.startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)) }
        PermissionRow(
            "Messages et musique (accès aux notifications)",
            MessageListenerService.instance != null,
        ) {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                    AndroidSettings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    ComponentName(context, MessageListenerService::class.java).flattenToString(),
                )
            } else {
                Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            }
            context.startActivity(intent)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PermissionRow("Afficher des notifications", null) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        Text("Bulle flottante", style = MaterialTheme.typography.titleMedium)
        Text(
            "Une bulle Ozariel reste par-dessus toutes les applications : appuie dessus pour parler, " +
                "appui long pour écrire. Elle a besoin du pilote d'applications (accessibilité).",
            style = MaterialTheme.typography.bodySmall,
        )
        SwitchRow("Afficher la bulle flottante", bubble) {
            bubble = it; settings.floatingBubble = it
            AgentAccessibilityService.instance?.floating?.refresh()
        }
        SwitchRow("Lire les réponses à voix haute", speak) { speak = it; settings.speakReplies = it }
        PermissionRow("Micro (pour parler à la bulle)", micGranted) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }

        Text("Réponses à tes messages", style = MaterialTheme.typography.titleMedium)
        SwitchRow("Proposer une réponse dans mon style à chaque message", suggest) { suggest = it; settings.suggestReplies = it }
        SwitchRow("Activer les réponses automatiques (pour les contacts choisis)", auto) { auto = it; settings.autoReply = it }
        SwitchRow("Les tâches programmées peuvent envoyer des messages sans confirmation", sensitiveInTasks) {
            sensitiveInTasks = it; settings.allowSensitiveInTasks = it
        }
    }
}

@Composable
private fun PermissionRow(label: String, enabled: Boolean?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label + when (enabled) {
                true -> " ✓"
                false -> " (désactivé)"
                null -> ""
            },
            Modifier.weight(1f),
        )
        OutlinedButton(onClick = onClick) { Text("Ouvrir") }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
