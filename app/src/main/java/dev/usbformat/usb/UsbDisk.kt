package dev.usbformat.usb

import dev.usbformat.disk.Disk
import me.jahnen.libaums.core.UsbMassStorageDevice
import me.jahnen.libaums.core.driver.BlockDeviceDriver
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Raw sector access to a USB Mass Storage device through libaums (SCSI over Bulk-Only Transport).
 * libaums' file system layer is not used; only its block device driver.
 *
 * Requires that the user already granted USB permission for the device.
 */
class UsbDisk(private val device: UsbMassStorageDevice) : Disk, AutoCloseable {
    private val driver: BlockDeviceDriver

    init {
        // init() also tries to parse the partition table, which can fail on a blank or foreign drive.
        // By then the block device driver is already set up, and that is all that is needed here.
        try {
            device.init()
        } catch (e: SecurityException) {
            throw e
        } catch (e: Exception) {
            // ignored on purpose, see above
        }
        driver = try {
            device.blockDevice
        } catch (e: UninitializedPropertyAccessException) {
            throw IOException("Cannot open the USB drive", e)
        }
    }

    override val sectorSize: Int get() = driver.blockSize
    override val sectorCount: Long get() = driver.blocks

    override fun read(lba: Long, count: Int): ByteArray {
        val buf = ByteBuffer.allocate(count * sectorSize)
        driver.read(lba, buf)
        return buf.array()
    }

    override fun write(lba: Long, data: ByteArray, length: Int) {
        driver.write(lba, ByteBuffer.wrap(data, 0, length))
    }

    override fun close() {
        device.close()
    }
}
