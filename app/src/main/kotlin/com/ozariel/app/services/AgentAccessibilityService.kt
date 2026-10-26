package com.ozariel.app.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Les « yeux et les mains » de l'agent dans les autres applications.
 * Android ne l'active qu'après accord explicite dans Réglages > Accessibilité.
 */
class AgentAccessibilityService : AccessibilityService() {

    /** Éléments numérotés lors du dernier read_screen, pour que l'agent puisse les désigner. */
    private var lastElements: List<AccessibilityNodeInfo> = emptyList()

    override fun onServiceConnected() {
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    /** Démarre une activité depuis le service : Android l'autorise même quand l'app est en arrière-plan. */
    fun launch(intent: Intent) {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun readScreen(maxElements: Int = 150): String {
        val root = rootInActiveWindow ?: return "Impossible de lire l'écran (écran verrouillé ou fenêtre protégée)."
        val elements = mutableListOf<AccessibilityNodeInfo>()
        val lines = mutableListOf("Application : ${root.packageName}")
        val queue = ArrayDeque(listOf(root))
        while (queue.isNotEmpty() && elements.size < maxElements) {
            val node = queue.removeFirst()
            if (!node.isVisibleToUser) continue
            describe(node)?.let { description ->
                elements += node
                lines += "[${elements.size - 1}] $description"
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
        }
        lastElements = elements
        if (elements.isEmpty()) lines += "(aucun élément lisible)"
        return lines.joinToString("\n")
    }

    fun tap(index: Int): String {
        val node = element(index)
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return "Touché."
        val bounds = Rect().also(node::getBoundsInScreen)
        return if (gesture(bounds.exactCenterX(), bounds.exactCenterY())) "Touché (geste)." else "Le toucher a échoué."
    }

    fun longPress(index: Int): String {
        val node = element(index)
        if (node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) return "Appui long effectué."
        val bounds = Rect().also(node::getBoundsInScreen)
        return if (gesture(bounds.exactCenterX(), bounds.exactCenterY(), duration = 800)) "Appui long effectué (geste)." else "Échec."
    }

    fun typeText(text: String, index: Int?, submit: Boolean): String {
        val node = if (index != null) element(index) else focusedInput()
            ?: return "Aucun champ de saisie sélectionné. Touche d'abord le champ, puis réessaie."
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return "Impossible d'écrire dans ce champ."
        if (submit && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }
        return "Texte saisi."
    }

    fun scroll(direction: String): String {
        val root = rootInActiveWindow ?: return "Écran illisible."
        val scrollable = findScrollable(root)
        val forward = direction == "down" || direction == "right"
        if (scrollable != null) {
            val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            if (scrollable.performAction(action)) return "Défilement effectué."
        }
        // Repli : glisser le doigt sur l'écran.
        val metrics = resources.displayMetrics
        val w = metrics.widthPixels.toFloat()
        val h = metrics.heightPixels.toFloat()
        val ok = when (direction) {
            "down" -> swipe(w / 2, h * 0.75f, w / 2, h * 0.25f)
            "up" -> swipe(w / 2, h * 0.25f, w / 2, h * 0.75f)
            "right" -> swipe(w * 0.8f, h / 2, w * 0.2f, h / 2)
            else -> swipe(w * 0.2f, h / 2, w * 0.8f, h / 2)
        }
        return if (ok) "Défilement effectué (geste)." else "Défilement impossible."
    }

    fun global(action: String): String {
        val code = when (action) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            "lock_screen" -> GLOBAL_ACTION_LOCK_SCREEN
            else -> return "Action inconnue : $action"
        }
        return if (performGlobalAction(code)) "Fait." else "Échec."
    }

    private fun element(index: Int): AccessibilityNodeInfo =
        lastElements.getOrNull(index)?.takeIf { it.refresh() }
            ?: throw IllegalStateException("Élément $index introuvable. Relis l'écran avec read_screen.")

    private fun focusedInput(): AccessibilityNodeInfo? =
        rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }

    private fun findScrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable && node.isVisibleToUser) return node
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child -> findScrollable(child)?.let { return it } }
        }
        return null
    }

    private fun describe(node: AccessibilityNodeInfo): String? {
        val text = node.text?.toString()?.trim().orEmpty()
        val label = node.contentDescription?.toString()?.trim().orEmpty()
        val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) node.hintText?.toString().orEmpty() else ""
        val interactive = node.isClickable || node.isEditable || node.isCheckable || node.isScrollable
        if (text.isEmpty() && label.isEmpty() && !interactive) return null
        if (text.isEmpty() && label.isEmpty() && hint.isEmpty() && !node.isEditable && !node.isScrollable) return null

        val kind = when {
            node.isEditable -> "Champ de saisie"
            node.isCheckable -> if (node.isChecked) "Case cochée" else "Case non cochée"
            node.isScrollable -> "Liste défilante"
            node.className?.contains("Button") == true -> "Bouton"
            node.className?.contains("Image") == true -> "Image"
            else -> "Texte"
        }
        val parts = mutableListOf(kind)
        if (text.isNotEmpty()) parts += "« ${text.take(120)} »"
        if (label.isNotEmpty() && label != text) parts += "(${label.take(80)})"
        if (hint.isNotEmpty() && text.isEmpty()) parts += "indice : $hint"
        if (node.isClickable && kind != "Bouton") parts += "cliquable"
        if (node.isFocused) parts += "sélectionné"
        return parts.joinToString(" ")
    }

    private fun gesture(x: Float, y: Float, duration: Long = 60): Boolean =
        dispatch(Path().apply { moveTo(x, y) }, duration)

    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float): Boolean =
        dispatch(Path().apply { moveTo(x1, y1); lineTo(x2, y2) }, 350)

    private fun dispatch(path: Path, duration: Long): Boolean = dispatchGesture(
        GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build(),
        null,
        null,
    )

    companion object {
        @Volatile
        var instance: AgentAccessibilityService? = null
            private set

        fun require(): AgentAccessibilityService = instance ?: throw IllegalStateException(
            "Le service d'accessibilité d'Ozariel n'est pas activé. Active-le dans Réglages > Accessibilité > Ozariel."
        )
    }
}
