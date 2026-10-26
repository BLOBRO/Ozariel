package com.ozariel.app.data

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/** Une tâche que l'agent exécutera plus tard, une fois ou régulièrement. */
data class ScheduledTask(
    val id: String,
    val instruction: String,
    val firstRunAt: Long,
    val repeatMinutes: Long?,
)

class TaskStore(context: Context) {
    private val prefs = context.getSharedPreferences("ozariel_tasks", Context.MODE_PRIVATE)

    fun all(): List<ScheduledTask> {
        val array = JSONArray(prefs.getString("tasks", "[]"))
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            ScheduledTask(
                id = o.getString("id"),
                instruction = o.getString("instruction"),
                firstRunAt = o.getLong("firstRunAt"),
                repeatMinutes = if (o.has("repeatMinutes")) o.getLong("repeatMinutes") else null,
            )
        }
    }

    fun get(id: String): ScheduledTask? = all().firstOrNull { it.id == id }

    fun add(task: ScheduledTask) = save(all().filterNot { it.id == task.id } + task)

    fun remove(id: String) = save(all().filterNot { it.id == id })

    private fun save(tasks: List<ScheduledTask>) {
        val array = JSONArray()
        tasks.forEach { t ->
            array.put(JSONObject().apply {
                put("id", t.id)
                put("instruction", t.instruction)
                put("firstRunAt", t.firstRunAt)
                t.repeatMinutes?.let { put("repeatMinutes", it) }
            })
        }
        prefs.edit { putString("tasks", array.toString()) }
    }
}
