package dev.usbformat.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.IOException

private class AndroidUsbTransport(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val inEndpoint: UsbEndpoint,
    private val outEndpoint: UsbEndpoint,
) : UsbTransport {

    override fun bulkOut(data: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int =
        connection.bulkTransfer(outEndpoint, data, offset, length, timeoutMs)

    override fun bulkIn(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int =
        connection.bulkTransfer(inEndpoint, buffer, offset, length, timeoutMs)

    override fun clearHalt(inEndpoint: Boolean) {
        val endpoint = if (inEndpoint) this.inEndpoint else outEndpoint
        // CLEAR_FEATURE(ENDPOINT_HALT) addressed to the endpoint.
        connection.controlTransfer(0x02, 0x01, 0, endpoint.address, null, 0, 1000)
    }

    override fun reset() {
        // Bulk-Only Mass Storage Reset, class request 0xFF addressed to the interface.
        connection.controlTransfer(0x21, 0xFF, 0, usbInterface.id, null, 0, 1000)
        clearHalt(true)
        clearHalt(false)
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
    fun open(manager: UsbManager, device: UsbDevice): ScsiDisk {
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
        connection.setInterface(usbInterface)

        val transport = AndroidUsbTransport(connection, usbInterface, inEndpoint, outEndpoint)
        try {
            return ScsiDisk(transport)
        } catch (e: Throwable) {
            transport.close()
            throw e
        }
    }
}
