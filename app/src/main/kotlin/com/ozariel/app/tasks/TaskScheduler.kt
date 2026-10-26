package com.ozariel.app.tasks

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.ozariel.app.OzarielApp
import com.ozariel.app.R
import com.ozariel.app.data.ScheduledTask
import com.ozariel.app.services.SuggestionNotifier
import com.ozariel.app.ui.AgentFactory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Programme les tâches de l'agent avec WorkManager, qui survit aux redémarrages du téléphone. */
object TaskScheduler {
    internal const val KEY_ID = "task_id"

    fun schedule(context: Context, instruction: String, firstRunAt: Long, repeatMinutes: Long?): ScheduledTask {
        val task = ScheduledTask(UUID.randomUUID().toString().take(8), instruction, firstRunAt, repeatMinutes)
        OzarielApp.instance.tasks.add(task)
        val delay = (firstRunAt - System.currentTimeMillis()).coerceAtLeast(0)
        val data = workDataOf(KEY_ID to task.id)
        val manager = WorkManager.getInstance(context)
        if (repeatMinutes == null) {
            val request = OneTimeWorkRequestBuilder<AgentTaskWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setInputData(data)
                .build()
            manager.enqueueUniqueWork(task.id, ExistingWorkPolicy.REPLACE, request)
        } else {
            val request = PeriodicWorkRequestBuilder<AgentTaskWorker>(repeatMinutes, TimeUnit.MINUTES)
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setInputData(data)
                .build()
            manager.enqueueUniquePeriodicWork(task.id, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
        return task
    }

    fun cancel(context: Context, id: String) {
        WorkManager.getInstance(context).cancelUniqueWork(id)
        OzarielApp.instance.tasks.remove(id)
    }

    fun describe(task: ScheduledTask): String {
        val format = SimpleDateFormat("EEE d MMM HH:mm", Locale.FRENCH)
        val repeat = task.repeatMinutes?.let { m ->
            when {
                m % 1440 == 0L -> ", tous les ${m / 1440} jour(s)"
                m % 60 == 0L -> ", toutes les ${m / 60} h"
                else -> ", toutes les $m min"
            }
        }.orEmpty()
        return "${task.id} : « ${task.instruction} » (${format.format(Date(task.firstRunAt))}$repeat)"
    }
}

class AgentTaskWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = OzarielApp.instance
        val id = inputData.getString(TaskScheduler.KEY_ID) ?: return Result.failure()
        val task = app.tasks.get(id) ?: return Result.success()
        val agent = AgentFactory.create(applicationContext, background = true) ?: run {
            notify(id, "Tâche non exécutée", "Clé API manquante : « ${task.instruction} »")
            return Result.success()
        }
        val answer = agent.send("Tâche programmée à exécuter maintenant : ${task.instruction}")
        notify(id, "Tâche exécutée", answer.ifBlank { task.instruction })
        if (task.repeatMinutes == null) app.tasks.remove(id)
        return Result.success()
    }

    private fun notify(id: String, title: String, text: String) = SuggestionNotifier.notify(
        applicationContext,
        id.hashCode(),
        NotificationCompat.Builder(applicationContext, OzarielApp.CHANNEL_TASKS)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .build(),
    )
}
