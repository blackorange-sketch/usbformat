package dev.usbformat.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import dev.usbformat.log.AppLog
import java.io.IOException

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

    override fun bulkOut(data: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
        lastOut = connection.bulkTransfer(outEndpoint, data, offset, length, timeoutMs)
        return lastOut
    }

    override fun bulkIn(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
        lastIn = connection.bulkTransfer(inEndpoint, buffer, offset, length, timeoutMs)
        return lastIn
    }

    override fun describe(): String = "$openLog; out=$lastOut in=$lastIn ctl=$lastControl"

    /**
     * CLEAR_FEATURE(ENDPOINT_HALT) on one endpoint of the drive. It does not touch the host's state: SET_INTERFACE would,
     * but it resets BOTH host endpoints, so calling it after clearing only one of them left the other one out of step
     * (an endless cycle of "not accepted" errors in the logs). Use [resync] when both sides need to be reset.
     */
    override fun clearHalt(inEndpoint: Boolean) {
        clearHaltOnDrive(if (inEndpoint) this.inEndpoint else outEndpoint)
    }

    override fun resync() {
        connection.setInterface(usbInterface)
        clearHaltOnDrive(inEndpoint)
        clearHaltOnDrive(outEndpoint)
    }

    private fun clearHaltOnDrive(endpoint: UsbEndpoint) {
        lastControl = connection.controlTransfer(0x02, 0x01, 0, endpoint.address, null, 0, 1000)
    }

    override fun reset() {
        // Bulk-Only Mass Storage Reset, class request 0xFF addressed to the interface, then clear both halts.
        lastControl = connection.controlTransfer(0x21, 0xFF, 0, usbInterface.id, null, 0, 1000)
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
            // SET_INTERFACE above reset the host's endpoint state; make the drive's match it.
            transport.clearHalt(true)
            transport.clearHalt(false)
            return ScsiDisk(transport, ioTimeoutMs)
        } catch (e: Throwable) {
            transport.close()
            throw e
        }
    }
}
