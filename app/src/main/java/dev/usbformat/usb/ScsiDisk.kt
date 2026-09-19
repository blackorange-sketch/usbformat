package dev.usbformat.usb

import dev.usbformat.disk.Disk
import java.io.IOException

/** The few USB operations SCSI-over-Bulk-Only needs. Implemented on Android by [AndroidUsbTransport]. */
interface UsbTransport {
    /** Returns the number of bytes moved, or a negative value on error, timeout or stall. */
    fun bulkOut(data: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int

    fun bulkIn(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int

    /** CLEAR_FEATURE(ENDPOINT_HALT) on the bulk-in or bulk-out endpoint. */
    fun clearHalt(inEndpoint: Boolean)

    /** Bulk-Only Mass Storage Reset followed by clearing both endpoints. */
    fun reset()

    fun close()
}

/**
 * A USB flash drive as a [Disk]: SCSI commands (READ/WRITE, READ CAPACITY, SYNCHRONIZE CACHE)
 * wrapped in Bulk-Only Transport command/status blocks. Plain JVM code, so it can be tested with a fake transport.
 */
class ScsiDisk(private val transport: UsbTransport) : Disk, AutoCloseable {

    private companion object {
        const val CBW_SIGNATURE = 0x43425355L // "USBC"
        const val CSW_SIGNATURE = 0x53425355L // "USBS"
        const val TIMEOUT_MS = 30_000
        const val CHUNK = 16 * 1024 // per bulk transfer; multiple of every bulk packet size
        const val MAX_COMMAND_BYTES = 64 * 1024 // per SCSI command; cheap controllers dislike more
    }

    private class Result(val status: Int, val transferred: Int)

    private class Sense(val key: Int, val asc: Int, val ascq: Int)

    private var tag = 1L

    override val sectorSize: Int
    override val sectorCount: Long

    init {
        waitUntilReady()
        val (blockSize, blocks) = readCapacity()
        if (blockSize != 512 && blockSize != 4096) {
            throw IOException("Unsupported sector size: $blockSize bytes")
        }
        sectorSize = blockSize
        sectorCount = blocks
    }

    override fun read(lba: Long, count: Int): ByteArray {
        val out = ByteArray(count * sectorSize)
        var done = 0
        while (done < count) {
            val n = minOf(count - done, maxSectors())
            transferSectors(false, lba + done, n, out, done * sectorSize)
            done += n
        }
        return out
    }

    override fun write(lba: Long, data: ByteArray, length: Int) {
        require(length % sectorSize == 0) { "Length must be a multiple of the sector size" }
        val count = length / sectorSize
        var done = 0
        while (done < count) {
            val n = minOf(count - done, maxSectors())
            transferSectors(true, lba + done, n, data, done * sectorSize)
            done += n
        }
    }

    /** Asks the drive to commit its write cache. Not every drive supports it, so failure is not an error. */
    override fun flush() {
        val cdb = ByteArray(10)
        cdb[0] = 0x35 // SYNCHRONIZE CACHE(10)
        try {
            val r = execute(cdb, true, null, 0, 0)
            if (r.status != 0) requestSense()
        } catch (e: IOException) {
            // ignored on purpose
        }
    }

    override fun close() {
        transport.close()
    }

    private fun maxSectors(): Int = maxOf(1, MAX_COMMAND_BYTES / sectorSize)

    private fun transferSectors(write: Boolean, lba: Long, sectors: Int, buf: ByteArray, offset: Int) {
        val cdb = if (lba + sectors <= 0xFFFFFFFFL) {
            ByteArray(10).also {
                it[0] = if (write) 0x2A else 0x28 // WRITE(10) / READ(10)
                putBe(it, 2, lba, 4)
                putBe(it, 7, sectors.toLong(), 2)
            }
        } else {
            ByteArray(16).also {
                it[0] = if (write) 0x8A.toByte() else 0x88.toByte() // WRITE(16) / READ(16)
                putBe(it, 2, lba, 8)
                putBe(it, 10, sectors.toLong(), 4)
            }
        }
        val bytes = sectors * sectorSize
        for (attempt in 0 until 2) {
            val r = execute(cdb, !write, buf, offset, bytes)
            if (r.status == 0 && r.transferred == bytes) return
            val sense = requestSense()
            // A "unit attention" (media changed, bus reset) is reported once; the retry then succeeds.
            if (sense.key == 6 && attempt == 0) continue
            throw IOException(
                "SCSI ${if (write) "write" else "read"} failed at sector $lba " +
                    "(sense key 0x%02x, code 0x%02x/0x%02x)".format(sense.key, sense.asc, sense.ascq),
            )
        }
    }

    private fun waitUntilReady() {
        for (attempt in 0 until 10) {
            val r = execute(ByteArray(6), true, null, 0, 0) // TEST UNIT READY
            if (r.status == 0) return
            requestSense()
            if (attempt < 9) Thread.sleep(200)
        }
        // Carry on: READ CAPACITY reports a clearer error if there really is no medium.
    }

    private fun readCapacity(): Pair<Int, Long> {
        val small = ByteArray(8)
        val cdb10 = ByteArray(10)
        cdb10[0] = 0x25 // READ CAPACITY(10)
        var r = execute(cdb10, true, small, 0, 8)
        if (r.status != 0 || r.transferred != 8) {
            requestSense()
            throw IOException("The drive did not report its capacity")
        }
        val last = getBe(small, 0, 4)
        val blockSize = getBe(small, 4, 4).toInt()
        if (last != 0xFFFFFFFFL) return Pair(blockSize, last + 1)

        // Larger than 2 TiB: the 16-byte variant carries a 64-bit address.
        val big = ByteArray(32)
        val cdb16 = ByteArray(16)
        cdb16[0] = 0x9E.toByte() // SERVICE ACTION IN(16)
        cdb16[1] = 0x10 // READ CAPACITY(16)
        putBe(cdb16, 10, 32, 4)
        r = execute(cdb16, true, big, 0, 32)
        if (r.status != 0 || r.transferred != 32) {
            requestSense()
            throw IOException("The drive did not report its capacity")
        }
        return Pair(getBe(big, 8, 4).toInt(), getBe(big, 0, 8) + 1)
    }

    private fun requestSense(): Sense {
        val buf = ByteArray(18)
        val cdb = ByteArray(6)
        cdb[0] = 0x03
        cdb[4] = 18
        return try {
            val r = execute(cdb, true, buf, 0, 18)
            if (r.status == 0 && r.transferred >= 14) {
                Sense(buf[2].toInt() and 0x0F, buf[12].toInt() and 0xFF, buf[13].toInt() and 0xFF)
            } else {
                Sense(0, 0, 0)
            }
        } catch (e: IOException) {
            Sense(0, 0, 0)
        }
    }

    /** One command block, optional data phase, then the status block. */
    @Synchronized
    private fun execute(cdb: ByteArray, dataIn: Boolean, data: ByteArray?, offset: Int, length: Int): Result {
        val myTag = tag
        tag = (tag + 1) and 0xFFFFFFFFL

        val cbw = ByteArray(31)
        putLe(cbw, 0, CBW_SIGNATURE, 4)
        putLe(cbw, 4, myTag, 4)
        putLe(cbw, 8, length.toLong(), 4)
        cbw[12] = if (dataIn && length > 0) 0x80.toByte() else 0
        cbw[14] = cdb.size.toByte()
        System.arraycopy(cdb, 0, cbw, 15, cdb.size)
        if (transport.bulkOut(cbw, 0, cbw.size, TIMEOUT_MS) != cbw.size) {
            transport.reset()
            throw IOException("The drive did not accept a command")
        }

        var moved = 0
        if (length > 0 && data != null) {
            var failed = false
            while (moved < length) {
                val want = minOf(CHUNK, length - moved)
                val n = if (dataIn) {
                    transport.bulkIn(data, offset + moved, want, TIMEOUT_MS)
                } else {
                    transport.bulkOut(data, offset + moved, want, TIMEOUT_MS)
                }
                if (n < 0) {
                    failed = true
                    break
                }
                moved += n
                if (n < want) break // short transfer: the status block follows
            }
            if (failed) transport.clearHalt(dataIn)
        }

        val csw = ByteArray(13)
        var got = transport.bulkIn(csw, 0, csw.size, TIMEOUT_MS)
        if (got != csw.size) {
            transport.clearHalt(true)
            got = transport.bulkIn(csw, 0, csw.size, TIMEOUT_MS)
        }
        if (got != csw.size || getLe(csw, 0, 4) != CSW_SIGNATURE || getLe(csw, 4, 4) != myTag) {
            transport.reset()
            throw IOException("The drive returned an invalid status")
        }
        return Result(csw[12].toInt() and 0xFF, moved)
    }

    private fun putLe(b: ByteArray, offset: Int, value: Long, bytes: Int) {
        for (i in 0 until bytes) b[offset + i] = (value shr (8 * i)).toByte()
    }

    private fun getLe(b: ByteArray, offset: Int, bytes: Int): Long {
        var v = 0L
        for (i in bytes - 1 downTo 0) v = (v shl 8) or (b[offset + i].toLong() and 0xFF)
        return v
    }

    private fun putBe(b: ByteArray, offset: Int, value: Long, bytes: Int) {
        for (i in 0 until bytes) b[offset + i] = (value shr (8 * (bytes - 1 - i))).toByte()
    }

    private fun getBe(b: ByteArray, offset: Int, bytes: Int): Long {
        var v = 0L
        for (i in 0 until bytes) v = (v shl 8) or (b[offset + i].toLong() and 0xFF)
        return v
    }
}
