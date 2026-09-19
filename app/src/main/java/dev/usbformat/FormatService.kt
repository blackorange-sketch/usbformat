package dev.usbformat

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import dev.usbformat.fmt.Cancel
import dev.usbformat.fmt.EraseMode
import dev.usbformat.fmt.FormatJob
import dev.usbformat.fmt.Fs
import dev.usbformat.fmt.Options
import dev.usbformat.fmt.Phase
import dev.usbformat.fmt.Scheme
import android.hardware.usb.UsbManager
import dev.usbformat.disk.LoggingDisk
import dev.usbformat.log.AppLog
import dev.usbformat.usb.UsbSession
import java.io.IOException
import java.util.concurrent.CancellationException
import kotlin.concurrent.thread

/**
 * Runs the format job in a foreground service so that a multi-hour full erase survives
 * the screen turning off or the app going to the background.
 */
class FormatService : Service() {

    companion object {
        const val EXTRA_DEVICE = "device"
        const val EXTRA_SCHEME = "scheme"
        const val EXTRA_FS = "fs"
        const val EXTRA_MODE = "mode"
        const val EXTRA_LABEL = "label"
        private const val CHANNEL = "format"
        private const val NOTIFICATION_ID = 1
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        NotificationManagerCompat.from(this).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(getString(R.string.notification_channel))
                .build(),
        )
        // Must be called promptly after startForegroundService().
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(getString(R.string.phase_partitioning), 0, 0),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )

        if (FormatState.status.value is FormatStatus.Running) return START_NOT_STICKY

        val deviceName = intent?.getStringExtra(EXTRA_DEVICE)
        val options = try {
            Options(
                scheme = Scheme.valueOf(intent?.getStringExtra(EXTRA_SCHEME).orEmpty()),
                fs = Fs.valueOf(intent?.getStringExtra(EXTRA_FS).orEmpty()),
                label = intent?.getStringExtra(EXTRA_LABEL).orEmpty(),
                mode = EraseMode.valueOf(intent?.getStringExtra(EXTRA_MODE).orEmpty()),
            )
        } catch (e: IllegalArgumentException) {
            null
        }
        if (deviceName == null || options == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        FormatState.status.value = FormatStatus.Running(Phase.PARTITIONING, 0, 0, 0, 0)
        thread(name = "usb-format") { runJob(deviceName, options) }
        return START_NOT_STICKY
    }

    private fun runJob(deviceName: String, options: Options) {
        val cancel = Cancel()
        FormatState.cancel = cancel
        AppLog.log("job started: device=$deviceName $options")
        val wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "usbformat:job")
        wakeLock.setReferenceCounted(false)
        wakeLock.acquire(12L * 60 * 60 * 1000)

        try {
            val manager = getSystemService(USB_SERVICE) as UsbManager
            val device = manager.deviceList[deviceName]
                ?: throw IOException(getString(R.string.error_not_found))

            run {
                val usbDisk = UsbSession.acquire(manager, device, 30_000)
                if (!usbDisk.ping()) AppLog.log("the drive did not answer the wake-up ping")
                val disk = LoggingDisk(usbDisk)
                var currentPhase: Phase? = null
                var phaseStart = 0L
                var phaseStartDone = 0L
                var lastPost = 0L

                FormatJob.run(disk, options, cancel) { phase, done, total ->
                    val now = SystemClock.elapsedRealtime()
                    if (phase != currentPhase) {
                        currentPhase = phase
                        phaseStart = now
                        phaseStartDone = done
                        lastPost = 0L
                    }
                    if (now - lastPost >= 500 || (total > 0 && done >= total)) {
                        lastPost = now
                        val elapsedMs = maxOf(1L, now - phaseStart)
                        val bytesPerSec = (done - phaseStartDone) * disk.sectorSize * 1000 / elapsedMs
                        val eta = if (bytesPerSec > 0 && total > 0) {
                            (total - done) * disk.sectorSize / bytesPerSec
                        } else {
                            0L
                        }
                        post(FormatStatus.Running(phase, done, total, bytesPerSec, eta))
                    }
                }
            }
            AppLog.log("job finished OK (the drive stays claimed by the app until released or unplugged)")
            FormatState.status.value = FormatStatus.Done
        } catch (e: CancellationException) {
            AppLog.log("job cancelled")
            FormatState.status.value = FormatStatus.Cancelled
        } catch (e: Throwable) {
            AppLog.log(
                "job FAILED: ${e.javaClass.simpleName}: ${e.message}\n" +
                    e.stackTrace.take(6).joinToString("\n") { "  at $it" },
            )
            FormatState.status.value = FormatStatus.Failed(e.message ?: e.javaClass.simpleName)
            UsbSession.release() // start from a clean connection next time
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun post(status: FormatStatus.Running) {
        FormatState.status.value = status
        notify(notification(getString(status.phase.labelRes()), status.done, status.total))
    }

    private fun notification(text: String, done: Long, total: Long): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val determinate = total > 0
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(if (determinate) 100 else 0, if (determinate) (done * 100 / total).toInt() else 0, !determinate)
            .build()
    }

    @SuppressLint("MissingPermission")
    private fun notify(notification: Notification) {
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // Notifications were denied; the job itself is not affected.
        }
    }
}
