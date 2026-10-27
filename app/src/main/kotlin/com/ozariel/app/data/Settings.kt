package com.ozariel.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.ozariel.core.agent.AgentModel
import com.ozariel.core.agent.Provider

/** Réglages de l'app, chiffrés sur le téléphone (la clé API notamment). */
class Settings(context: Context) {
    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "ozariel_settings",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    var apiKey: String
        get() = prefs.getString("api_key", "").orEmpty()
        set(value) = prefs.edit { putString("api_key", value.trim()) }

    /** Clé gratuite créée sur aistudio.google.com/apikey. */
    var geminiKey: String
        get() = prefs.getString("gemini_key", "").orEmpty()
        set(value) = prefs.edit { putString("gemini_key", value.trim()) }

    /** Vrai si la clé du fournisseur du modèle choisi est renseignée. */
    val hasKeyForModel: Boolean
        get() = (if (model.provider == Provider.GEMINI) geminiKey else apiKey).isNotBlank()

    var model: AgentModel
        get() = AgentModel.fromId(prefs.getString("model", null))
        set(value) = prefs.edit { putString("model", value.id) }

    var ownerName: String
        get() = prefs.getString("owner_name", "Ariel").orEmpty()
        set(value) = prefs.edit { putString("owner_name", value.trim()) }

    /** Propose une réponse dans ton style dès qu'un message arrive. */
    var suggestReplies: Boolean
        get() = prefs.getBoolean("suggest_replies", true)
        set(value) = prefs.edit { putBoolean("suggest_replies", value) }

    /** Interrupteur général des réponses automatiques. */
    var autoReply: Boolean
        get() = prefs.getBoolean("auto_reply", false)
        set(value) = prefs.edit { putBoolean("auto_reply", value) }

    /** Contacts pour lesquels l'agent répond sans demander. */
    var autoReplyContacts: Set<String>
        get() = prefs.getStringSet("auto_reply_contacts", emptySet()).orEmpty()
        set(value) = prefs.edit { putStringSet("auto_reply_contacts", value) }

    /** Autorise les tâches programmées à envoyer des messages sans confirmation. */
    var allowSensitiveInTasks: Boolean
        get() = prefs.getBoolean("sensitive_in_tasks", false)
        set(value) = prefs.edit { putBoolean("sensitive_in_tasks", value) }

    /** Bulle flottante par-dessus les autres applications (nécessite l'accessibilité). */
    var floatingBubble: Boolean
        get() = prefs.getBoolean("floating_bubble", true)
        set(value) = prefs.edit { putBoolean("floating_bubble", value) }

    /** La bulle prend la forme d'un petit personnage animé (sinon, simple icône). */
    var animatedCharacter: Boolean
        get() = prefs.getBoolean("animated_character", true)
        set(value) = prefs.edit { putBoolean("animated_character", value) }

    /** Clé d'accès Picovoice (console.picovoice.ai), pour le mot d'éveil. */
    var picovoiceKey: String
        get() = prefs.getString("picovoice_key", "").orEmpty()
        set(value) = prefs.edit { putString("picovoice_key", value.trim()) }

    /** Ozariel se réveille quand on dit son nom. */
    var wakeWord: Boolean
        get() = prefs.getBoolean("wake_word", false)
        set(value) = prefs.edit { putBoolean("wake_word", value) }

    /** N'écouter le mot d'éveil que lorsque l'écran est allumé (économise la batterie). */
    var wakeOnlyScreenOn: Boolean
        get() = prefs.getBoolean("wake_screen_on", true)
        set(value) = prefs.edit { putBoolean("wake_screen_on", value) }

    /** La bulle lit ses réponses à voix haute. */
    var speakReplies: Boolean
        get() = prefs.getBoolean("speak_replies", true)
        set(value) = prefs.edit { putBoolean("speak_replies", value) }

    /** Position de la bulle : bord de l'écran (gauche ou droite) et hauteur. */
    var bubbleOnRight: Boolean
        get() = prefs.getBoolean("bubble_right", true)
        set(value) = prefs.edit { putBoolean("bubble_right", value) }

    var bubbleY: Int
        get() = prefs.getInt("bubble_y", -1)
        set(value) = prefs.edit { putInt("bubble_y", value) }

    fun isAutoReplyContact(contact: String): Boolean =
        autoReply && autoReplyContacts.any { it.equals(contact, ignoreCase = true) }
}
