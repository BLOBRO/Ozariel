package com.ozariel.core.tools

/**
 * Une action que l'agent peut exécuter sur le téléphone (ouvrir une app, cliquer, mettre pause…).
 * Le module Android fournit les implémentations ; le cerveau ne connaît que cette interface.
 */
interface DeviceTool {
    val name: String
    val description: String
    val parameters: List<ToolParam>

    /** Une action sensible (envoyer un message, appeler…) demande une confirmation avant d'être exécutée. */
    val sensitive: Boolean get() = false

    suspend fun run(args: ToolArgs): ToolResult
}

data class ToolParam(
    val name: String,
    val type: String,
    val description: String,
    val required: Boolean = true,
    val enum: List<String>? = null,
) {
    fun toSchema(): Map<String, Any> = buildMap {
        put("type", type)
        put("description", description)
        enum?.let { put("enum", it) }
    }

    companion object {
        fun string(name: String, description: String, required: Boolean = true, enum: List<String>? = null) =
            ToolParam(name, "string", description, required, enum)

        fun integer(name: String, description: String, required: Boolean = true) =
            ToolParam(name, "integer", description, required)

        fun boolean(name: String, description: String, required: Boolean = true) =
            ToolParam(name, "boolean", description, required)
    }
}

class ToolArgs(private val values: Map<String, Any?>) {
    fun string(name: String): String =
        optString(name) ?: throw IllegalArgumentException("Paramètre manquant : $name")

    fun optString(name: String): String? = values[name]?.toString()?.takeIf { it.isNotBlank() }

    fun int(name: String): Int = optInt(name) ?: throw IllegalArgumentException("Paramètre manquant : $name")

    fun optInt(name: String): Int? = when (val v = values[name]) {
        is Number -> v.toInt()
        is String -> v.toIntOrNull()
        else -> null
    }

    fun optBoolean(name: String): Boolean? = when (val v = values[name]) {
        is Boolean -> v
        is String -> v.toBooleanStrictOrNull()
        else -> null
    }

    override fun toString(): String = values.toString()
}

data class ToolResult(val text: String, val isError: Boolean = false) {
    companion object {
        fun ok(text: String) = ToolResult(text)
        fun error(text: String) = ToolResult(text, isError = true)
    }
}
