package dev.usbformat.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import dev.usbformat.log.AppLog
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Keeps one drive open for as long as the app works with it.
 *
 * Every close hands the drive back to Android, which mounts it and starts reading it; claiming it again a moment
 * later interrupts that in the middle and can leave the drive unresponsive. So the connection is opened once and
 * reused for reading the drive info, formatting and reading it again, until [release] is called or it is unplugged.
 */
object UsbSession {
    /** True while the app is holding a drive (Android cannot mount it during that time). */
    val isOpen = MutableStateFlow(false)

    private var disk: ScsiDisk? = null
    private var deviceName: String? = null

    /** Returns the open drive, opening it first if needed. May block for a long time; call from a worker thread. */
    @Synchronized
    fun acquire(manager: UsbManager, device: UsbDevice, ioTimeoutMs: Int): ScsiDisk {
        val current = disk
        if (current != null && deviceName == device.deviceName) {
            current.ioTimeoutMs = ioTimeoutMs
            return current
        }
        release()
        val fresh = UsbDisks.open(manager, device, ioTimeoutMs)
        disk = fresh
        deviceName = device.deviceName
        isOpen.value = true
        AppLog.log("session: drive claimed by the app")
        return fresh
    }

    /** Gives the drive back to Android. Call from a worker thread: it waits for any open in progress. */
    @Synchronized
    fun release() {
        val current = disk ?: return
        try {
            current.close()
        } catch (e: Exception) {
            AppLog.log("session: error while closing: ${e.message}")
        }
        disk = null
        deviceName = null
        isOpen.value = false
        AppLog.log("session: drive released")
    }
}
