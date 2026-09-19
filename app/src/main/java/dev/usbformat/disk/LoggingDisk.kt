package dev.usbformat.disk

import dev.usbformat.log.AppLog

/**
 * Wraps a [Disk] and writes what happens to the on-screen log: the first calls, every 1000th call
 * after that, and every failure with the sector it happened at.
 */
class LoggingDisk(private val inner: Disk) : Disk {
    override val sectorSize: Int get() = inner.sectorSize
    override val sectorCount: Long get() = inner.sectorCount

    private var calls = 0

    override fun read(lba: Long, count: Int): ByteArray = traced("read", lba, count) { inner.read(lba, count) }

    override fun write(lba: Long, data: ByteArray, length: Int) {
        traced("write", lba, length / inner.sectorSize) { inner.write(lba, data, length) }
    }

    override fun flush() {
        traced("flush", 0L, 0) { inner.flush() }
    }

    private fun <T> traced(what: String, lba: Long, sectors: Int, block: () -> T): T {
        calls++
        val n = calls
        if (n <= 60 || n % 1000 == 0) AppLog.log("disk $what lba=$lba sectors=$sectors (call $n)")
        try {
            return block()
        } catch (e: Throwable) {
            AppLog.log("disk $what FAILED at lba=$lba sectors=$sectors (call $n): ${e.message}")
            throw e
        }
    }
}
