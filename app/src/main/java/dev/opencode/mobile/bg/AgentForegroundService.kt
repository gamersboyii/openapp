package dev.opencode.mobile.bg

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.opencode.mobile.MainActivity
import dev.opencode.mobile.OpenCodeApp
import dev.opencode.mobile.R
import dev.opencode.mobile.agent.AgentEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Feature 12 — Background Agent Mode.
 *
 * A foreground service that keeps a running agent turn alive while the app sits
 * in the background, and mirrors its progress into a notification:
 *
 *   OpenCode Mobile · Working on project…
 *   ✓ analysing project…
 *   ✓ write_file · MainScreen.kt
 *   ✗ build_project · test
 *
 * The service holds no work of its own: the [AgentEngine] keeps running in the
 * app's application scope either way. This component only (a) holds the process
 * at foreground priority so Android does not kill it mid-turn and (b) renders
 * status plus pause/resume/stop/retry actions. It stops itself whenever the
 * agent is idle, so no unnecessary service stays alive.
 */
class AgentForegroundService : Service() {

    private val engine: AgentEngine by lazy { (application as OpenCodeApp).container.agent }

    private var watchScope: CoroutineScope? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        watchScope = scope
        scope.launch {
            combine(engine.isRunning, engine.status, engine.progress, engine.paused) { _, _, _, _ -> }
                .collect { render() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> engine.pause()
            ACTION_RESUME -> engine.resume()
            ACTION_RETRY -> engine.retryLastTurn()

            ACTION_STOP -> {
                engine.cancel()
                stopSelf()
                return START_NOT_STICKY
            }

            else -> if (!startAsForeground()) return START_NOT_STICKY
        }

        // Idle (and nobody waiting on an approval sheet): nothing left to guard.
        if (!engine.isRunning.value && engine.pendingApproval.value == null) stopSelf()
        render()
        return START_NOT_STICKY
    }

    /** Returns false when Android refuses the foreground promotion (app in bg). */
    private fun startAsForeground(): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
    }.isSuccess

    override fun onDestroy() {
        watchScope?.cancel()
        watchScope = null
        super.onDestroy()
    }

    // ---- notification ------------------------------------------------------

    private fun render() {
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, buildNotification())
    }

    @Suppress("DEPRECATION")
    private fun buildNotification(): Notification {
        val running = engine.isRunning.value
        val paused = engine.paused.value
        val approvalPending = engine.pendingApproval.value != null
        val filesTouched = engine.turnFilesChanged.value

        val headline = when {
            approvalPending -> "Waiting for your approval"
            paused -> "Paused"
            running -> engine.status.value.ifBlank { "Working on project…" }
            else -> "Agent stopped"
        }

        val lines = ArrayList<String>(engine.progress.value)
        if (filesTouched > 0 && lines.none { it.startsWith("•") }) {
            lines.add(0, "✓ modified $filesTouched file(s)")
        }
        if (approvalPending) lines += "⚠ open the app to approve"

        val bigText = NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n"))

        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("OpenCode Mobile")
            .setContentText(headline)
            .setStyle(bigText)
            .setContentIntent(openIntent)
            .setOngoing(running || approvalPending)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)

        if (running && !approvalPending) {
            builder.addAction(if (paused) action("Resume", ACTION_RESUME) else action("Pause", ACTION_PAUSE))
            builder.addAction(action("Stop", ACTION_STOP))
        } else {
            builder.addAction(action("Stop", ACTION_STOP))
            if (!running && engine.lastPrompt.isNotBlank()) builder.addAction(action("Retry", ACTION_RETRY))
        }
        return builder.build()
    }

    private fun action(label: String, action: String): NotificationCompat.Action {
        val pending = PendingIntent.getService(
            this,
            action.hashCode(),
            Intent(this, AgentForegroundService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action.Builder(0, label, pending).build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Background agent",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows what the coding agent is doing while you use other apps"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "opencode_agent"
        const val NOTIFICATION_ID = 41
        const val ACTION_PAUSE = "dev.opencode.mobile.bg.PAUSE"
        const val ACTION_RESUME = "dev.opencode.mobile.bg.RESUME"
        const val ACTION_STOP = "dev.opencode.mobile.bg.STOP"
        const val ACTION_RETRY = "dev.opencode.mobile.bg.RETRY"

        /** Called from the chat composer while a turn runs (or shortly before). */
        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, AgentForegroundService::class.java),
                )
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AgentForegroundService::class.java))
        }
    }
}
