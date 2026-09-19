package dev.usbformat.disk

import java.io.RandomAccessFile

const val MIB = 1024 * 1024

/**
 * Minimal sector-addressed block device.
 * Everything above this interface is plain JVM code, so it can be tested on a PC with [FileDisk].
 */
interface Disk {
    val sectorSize: Int
    val sectorCount: Long

    /** Reads [count] sectors starting at [lba]. */
    fun read(lba: Long, count: Int): ByteArray

    /** Writes the first [length] bytes of [data] at [lba]. [length] must be a multiple of [sectorSize]. */
    fun write(lba: Long, data: ByteArray, length: Int = data.size)

    fun flush() {}
}

/** Disk backed by a regular file: used by unit tests and for checking the output on a PC. */
class FileDisk(path: String, override val sectorSize: Int = 512) : Disk, AutoCloseable {
    private val raf = RandomAccessFile(path, "rw")
    override val sectorCount: Long = raf.length() / sectorSize

    override fun read(lba: Long, count: Int): ByteArray {
        val buf = ByteArray(count * sectorSize)
        raf.seek(lba * sectorSize)
        raf.readFully(buf)
        return buf
    }

    override fun write(lba: Long, data: ByteArray, length: Int) {
        require(length % sectorSize == 0) { "Length must be a multiple of the sector size" }
        raf.seek(lba * sectorSize)
        raf.write(data, 0, length)
    }

    override fun close() = raf.close()
}
