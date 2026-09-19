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
        driver = findBlockDriver(device)
            ?: throw IOException("Cannot open the USB drive: no block device in ${device.javaClass.name}")
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

/**
 * libaums keeps the raw block device in a private field and exposes only the parsed partitions.
 * The field is found by its type rather than by name, so a rename between versions does not break this.
 */
private fun findBlockDriver(device: UsbMassStorageDevice): BlockDeviceDriver? {
    var cls: Class<*>? = device.javaClass
    while (cls != null && cls != Any::class.java) {
        for (field in cls.declaredFields) {
            if (BlockDeviceDriver::class.java.isAssignableFrom(field.type)) {
                field.isAccessible = true
                val value = field.get(device)
                if (value is BlockDeviceDriver) return value
            }
        }
        cls = cls.superclass
    }
    return null
}
