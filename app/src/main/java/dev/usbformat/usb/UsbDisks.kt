package dev.usbformat.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import android.os.Build
import dev.usbformat.log.AppLog
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.TimeoutException

/** Remembered for the whole process: once the alternative transfer method was needed, later connections start with it. */
object AlternateTransfers {
    @Volatile
    var enabled = false
}

private class AndroidUsbTransport(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val inEndpoint: UsbEndpoint,
    private val outEndpoint: UsbEndpoint,
    private val openLog: String,
) : UsbTransport {

    private var lastOut = 0
    private var lastIn = 0
    private var lastControl = 0
    private var useRequests = AlternateTransfers.enabled

    override fun bulkOut(data: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
        lastOut = if (useRequests) {
            viaRequest(outEndpoint, data, offset, length, timeoutMs)
        } else {
            connection.bulkTransfer(outEndpoint, data, offset, length, timeoutMs)
        }
        return lastOut
    }

    override fun bulkIn(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
        lastIn = if (useRequests) {
            viaRequest(inEndpoint, buffer, offset, length, timeoutMs)
        } else {
            connection.bulkTransfer(inEndpoint, buffer, offset, length, timeoutMs)
        }
        return lastIn
    }

    /**
     * The other way to move bulk data: queued UsbRequests instead of blocking bulkTransfer(). It goes through a
     * different path in the kernel and works on some phones where the plain one does not.
     */
    private fun viaRequest(endpoint: UsbEndpoint, data: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
        if (Build.VERSION.SDK_INT < 26) return -1
        val request = UsbRequest()
        try {
            if (!request.initialize(connection, endpoint)) return -1
            val buffer = ByteBuffer.wrap(data, offset, length)
            if (!request.queue(buffer)) return -1
            val finished = try {
                connection.requestWait(timeoutMs.toLong())
            } catch (e: TimeoutException) {
                request.cancel()
                return -1
            }
            if (finished !== request) return -1
            val moved = buffer.position() - offset
            return if (moved == 0) length else moved
        } finally {
            request.close()
        }
    }

    override fun useAlternateTransfers(): Boolean {
        if (useRequests || Build.VERSION.SDK_INT < 26) return false
        useRequests = true
        AlternateTransfers.enabled = true
        return true
    }

    override fun describe(): String = "$openLog; out=$lastOut in=$lastIn ctl=$lastControl"

    /**
     * CLEAR_FEATURE(ENDPOINT_HALT) only resets the drive's side. Selecting the interface again (SET_INTERFACE)
     * makes the host controller reset its endpoint state too, which is what a proper clear-halt does.
     */
    override fun clearHalt(inEndpoint: Boolean) {
        clearHaltOnDrive(if (inEndpoint) this.inEndpoint else outEndpoint)
        connection.setInterface(usbInterface)
    }

    private fun clearHaltOnDrive(endpoint: UsbEndpoint) {
        lastControl = connection.controlTransfer(0x02, 0x01, 0, endpoint.address, null, 0, 1000)
    }

    override fun reset() {
        // Bulk-Only Mass Storage Reset, class request 0xFF addressed to the interface.
        lastControl = connection.controlTransfer(0x21, 0xFF, 0, usbInterface.id, null, 0, 1000)
        connection.setInterface(usbInterface)
        clearHaltOnDrive(inEndpoint)
        clearHaltOnDrive(outEndpoint)
    }

    override fun endpointStatus(inEndpoint: Boolean): Int {
        val endpoint = if (inEndpoint) this.inEndpoint else outEndpoint
        val buffer = ByteArray(2)
        val n = connection.controlTransfer(0x82, 0x00, 0, endpoint.address, buffer, 2, 1000)
        return if (n == 2) buffer[0].toInt() and 0xFF else -1
    }

    /** UsbDeviceConnection.resetDevice() is looked up by name so a missing method cannot break the build. */
    override fun hardReset(): Boolean {
        val reset = try {
            connection.javaClass.getMethod("resetDevice").invoke(connection) as? Boolean ?: false
        } catch (e: Exception) {
            false
        }
        if (!reset) return false
        Thread.sleep(1500)
        val claimed = connection.claimInterface(usbInterface, true)
        connection.setInterface(usbInterface)
        return claimed
    }

    override fun close() {
        connection.releaseInterface(usbInterface)
        connection.close()
    }
}

object UsbDisks {
    /**
     * Opens a USB Mass Storage device for raw sector access. USB permission must already be granted.
     * Claiming the interface makes Android unmount the drive until it is closed again.
     */
    fun open(manager: UsbManager, device: UsbDevice, ioTimeoutMs: Int = 30_000): ScsiDisk {
        var chosen: UsbInterface? = null
        for (i in 0 until device.interfaceCount) {
            val candidate = device.getInterface(i)
            // Bulk-Only Transport. UAS-capable drives list it as a separate alternate setting.
            if (candidate.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE && candidate.interfaceProtocol == 0x50) {
                chosen = candidate
                break
            }
        }
        val usbInterface = chosen ?: throw IOException("This USB device has no Bulk-Only mass storage interface")

        var inEndpoint: UsbEndpoint? = null
        var outEndpoint: UsbEndpoint? = null
        for (i in 0 until usbInterface.endpointCount) {
            val endpoint = usbInterface.getEndpoint(i)
            if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
            if (endpoint.direction == UsbConstants.USB_DIR_IN) inEndpoint = endpoint else outEndpoint = endpoint
        }
        if (inEndpoint == null || outEndpoint == null) throw IOException("The USB interface has no bulk endpoints")

        val connection = manager.openDevice(device) ?: throw IOException("Cannot open the USB device (no permission?)")
        if (!connection.claimInterface(usbInterface, true)) {
            connection.close()
            throw IOException("Cannot claim the USB interface")
        }
        // Select the Bulk-Only alternate setting (drives that also speak UAS may be in another one).
        val setInterfaceOk = connection.setInterface(usbInterface)
        // Get Max LUN: drives with a single LUN may stall this, which is fine.
        val lunBuffer = ByteArray(1)
        val lunResult = connection.controlTransfer(0xA1, 0xFE, 0, usbInterface.id, lunBuffer, 1, 1000)

        val interfaces = (0 until device.interfaceCount).joinToString(",") {
            val i = device.getInterface(it)
            "${i.id}/${i.alternateSetting}:${i.interfaceClass}:${i.interfaceSubclass}:${i.interfaceProtocol}"
        }
        val openLog = "interfaces=$interfaces using ${usbInterface.id}/${usbInterface.alternateSetting}, " +
            "out=0x%02x/%d in=0x%02x/%d, setIf=%b maxLun=%d/%d".format(
                outEndpoint.address, outEndpoint.maxPacketSize,
                inEndpoint.address, inEndpoint.maxPacketSize,
                setInterfaceOk, lunResult, lunBuffer[0].toInt(),
            )

        AppLog.log("usb: $openLog")
        val transport = AndroidUsbTransport(connection, usbInterface, inEndpoint, outEndpoint, openLog)
        try {
            // Start from a known state: Bulk-Only reset, then clear both endpoints.
            transport.reset()
            AppLog.log("usb: reset done (${transport.describe()})")
            Thread.sleep(100)
            return ScsiDisk(transport, ioTimeoutMs)
        } catch (e: Throwable) {
            transport.close()
            throw e
        }
    }
}
