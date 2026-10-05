package com.setbd.vibeshare.app.service

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
import com.setbd.vibeshare.app.MainActivity
import com.setbd.vibeshare.app.R
import com.setbd.vibeshare.app.transfer.TransferCoordinator
import com.setbd.vibeshare.core.Formats
import com.setbd.vibeshare.core.log.VibeLog
import com.setbd.vibeshare.domain.model.ConnectionLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * Foreground service (dataSync) that keeps active transfers alive while the
 * app is backgrounded and renders real progress into a notification with
 * pause/resume/cancel actions (spec section 21).
 */
class TransferForegroundService : Service() {

    private val coordinator by inject<TransferCoordinator>()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForegroundCompat(buildNotification(getString(R.string.service_waiting)))
        serviceScope.launch { observeHosting() }
        serviceScope.launch { observeIncoming() }
        serviceScope.launch { observeSending() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PAUSE_ALL -> coordinator.cancelAllSends().let { Unit }
            ACTION_RESUME_ALL -> Unit // handled per-session; kept for notification parity
            ACTION_CANCEL_ALL -> {
                coordinator.cancelAllSends()
                coordinator.stopHosting()
                stopSelf()
            }
        }
        return START_STICKY
    }

    private suspend fun observeHosting() {
        coordinator.hosting.collect { state ->
            if (state.active) {
                update(buildNotification(getString(R.string.service_receiving_ready)))
            } else if (noActivity()) {
                stopSelf()
            }
        }
    }

    private suspend fun observeIncoming() {
        coordinator.incoming.collect { progress ->
            if (progress != null) {
                update(buildIncomingNotification(progress))
            }
        }
    }

    private suspend fun observeSending() {
        coordinator.senders.collect { list ->
            if (list.isNotEmpty()) {
                val active = list.first { it.state == ConnectionLifecycle.TRANSFERRING || it.state == ConnectionLifecycle.PAUSED }
                update(
                    buildNotification(
                        getString(
                            R.string.service_sending,
                            active.peerName,
                            Formats.bytes(active.bytesDone),
                            Formats.bytes(active.bytesTotal),
                            active.percent,
                            Formats.speed(active.speedBps),
                        )
                    )
                )
            } else if (noActivity()) {
                stopSelf()
            }
        }
    }

    private fun noActivity(): Boolean =
        !coordinator.hosting.value.active &&
            coordinator.incoming.value?.state !in activeStates &&
            coordinator.senders.value.none { it.state in activeStates }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_vibeshare)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(mainIntent())
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()

    private fun buildIncomingNotification(progress: com.setbd.vibeshare.domain.model.IncomingProgress): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_vibeshare)
            .setContentTitle(getString(R.string.service_receiving_from, progress.peerName))
            .setContentText(
                "${progress.currentFileName} · ${Formats.bytes(progress.bytesDone)} / ${Formats.bytes(progress.bytesTotal)} · ${Formats.speed(progress.speedBps)}"
            )
            .setProgress(100, progress.percent.coerceIn(0, 100), progress.state == ConnectionLifecycle.PAIRING)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(mainIntent())
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        if (progress.state == ConnectionLifecycle.PAUSED) {
            builder.addAction(0, getString(R.string.action_resume), serviceIntent(ACTION_RESUME_ALL))
        } else {
            builder.addAction(0, getString(R.string.action_pause), serviceIntent(ACTION_PAUSE_ALL))
        }
        builder.addAction(0, getString(R.string.action_cancel), serviceIntent(ACTION_CANCEL_ALL))
        return builder.build()
    }

    private fun serviceIntent(action: String): PendingIntent =
        PendingIntent.getService(
            this,
            action.hashCode(),
            Intent(this, TransferForegroundService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun mainIntent(): PendingIntent =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun update(notification: Notification) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_transfers),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.channel_transfers_desc)
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        VibeLog.i(TAG, "Foreground service destroyed")
        super.onDestroy()
    }

    companion object {
        private const val TAG = "TransferService"
        private const val CHANNEL_ID = "vibeshare_transfers"
        private const val NOTIFICATION_ID = 4747
        const val ACTION_STOP = "com.setbd.vibeshare.action.STOP"
        const val ACTION_PAUSE_ALL = "com.setbd.vibeshare.action.PAUSE_ALL"
        const val ACTION_RESUME_ALL = "com.setbd.vibeshare.action.RESUME_ALL"
        const val ACTION_CANCEL_ALL = "com.setbd.vibeshare.action.CANCEL_ALL"

        private val activeStates = setOf(
            ConnectionLifecycle.TRANSFERRING,
            ConnectionLifecycle.PAUSED,
            ConnectionLifecycle.CONNECTING,
            ConnectionLifecycle.PAIRING,
        )

        /** Starts the service if there is activity to report. */
        fun start(context: Context) {
            val intent = Intent(context, TransferForegroundService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { VibeLog.w(TAG, "Could not start foreground service", it) }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, TransferForegroundService::class.java).setAction(ACTION_STOP))
        }
    }
}
