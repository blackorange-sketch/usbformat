package dev.usbformat.usb

import dev.usbformat.disk.Disk
import dev.usbformat.log.AppLog
import java.io.IOException

/** The few USB operations SCSI-over-Bulk-Only needs. Implemented on Android by [AndroidUsbTransport]. */
interface UsbTransport {
    /** Returns the number of bytes moved, or a negative value on error, timeout or stall. */
    fun bulkOut(data: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int

    fun bulkIn(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int

    /** Clears a halted (stalled) bulk endpoint on the drive and on the host side. */
    fun clearHalt(inEndpoint: Boolean)

    /** Bulk-Only Mass Storage Reset, then clears both endpoints. */
    fun reset()

    fun close()

    /** Short technical description of the connection, appended to error messages. */
    fun describe(): String = ""

    /** Resets the whole device through its port, like unplugging and replugging it. False if not possible. */
    fun hardReset(): Boolean = false

    /** GET_STATUS of a bulk endpoint: bit 0 set means halted. -1 if unavailable. */
    fun endpointStatus(inEndpoint: Boolean): Int = -1
}

/** The drive stopped answering even after every kind of reset; it has to be unplugged and plugged in again. */
class DriveUnresponsiveException(message: String) : IOException(message)

/**
 * A USB flash drive as a [Disk]: SCSI commands (READ/WRITE, READ CAPACITY, SYNCHRONIZE CACHE)
 * wrapped in Bulk-Only Transport command/status blocks. Plain JVM code, so it can be tested with a fake transport.
 * Everything unusual is written to [AppLog].
 */
class ScsiDisk(
    private val transport: UsbTransport,
    var ioTimeoutMs: Int = 30_000,
) : Disk, AutoCloseable {

    private companion object {
        const val CBW_SIGNATURE = 0x43425355L // "USBC"
        const val CSW_SIGNATURE = 0x53425355L // "USBS"
        const val COMMAND_TIMEOUT_MS = 5_000 // sending a 31-byte command block should be instant
        const val INIT_TIMEOUT_MS = 5_000 // while waiting for the drive to come up
        const val CHUNK = 16 * 1024 // per bulk transfer; a multiple of every bulk packet size
        const val RAMP_START = 512
        const val MAX_COMMAND_BYTES = 64 * 1024 // per SCSI command; cheap controllers dislike more
        const val LOGGED_READ_WRITES = 8
    }

    private class Result(val status: Int, val transferred: Int)

    private class Sense(val key: Int, val asc: Int, val ascq: Int)

    /**
     * Command size that starts tiny and doubles after every success, up to [max]. After a failure it goes back to the
     * last size that worked and stops growing, so a drive or phone that cannot take big transfers still works.
     */
    private class Ramp(private val min: Int, private val max: Int) {
        var bytes = min
            private set
        private var limited = false

        fun success() {
            if (!limited && bytes < max) bytes = minOf(max, bytes * 2)
        }

        /** False when the size was already at the minimum. */
        fun failure(): Boolean {
            limited = true
            if (bytes <= min) return false
            bytes = maxOf(min, bytes / 2)
            return true
        }
    }

    private var tag = 1L
    private var readWritesLogged = 0
    private var readRamp = Ramp(RAMP_START, MAX_COMMAND_BYTES)
    private var writeRamp = Ramp(RAMP_START, MAX_COMMAND_BYTES)
    private var initializing = true

    override val sectorSize: Int
    override val sectorCount: Long

    init {
        log("opening, I/O timeout ${ioTimeoutMs} ms")
        waitUntilReady()
        val (blockSize, blocks) = readCapacity()
        if (blockSize != 512 && blockSize != 4096) {
            throw IOException("Unsupported sector size: $blockSize bytes")
        }
        sectorSize = blockSize
        sectorCount = blocks
        log("capacity: $blocks sectors of $blockSize bytes")
        logDeviceInfo()
        initializing = false
    }

    override fun read(lba: Long, count: Int): ByteArray {
        val out = ByteArray(count * sectorSize)
        transferAll(false, lba, out, count)
        return out
    }

    override fun write(lba: Long, data: ByteArray, length: Int) {
        require(length % sectorSize == 0) { "Length must be a multiple of the sector size" }
        transferAll(true, lba, data, length / sectorSize)
    }

    /** Asks the drive to commit its write cache. Not every drive supports it, so failure is not an error. */
    override fun flush() {
        val cdb = ByteArray(10)
        cdb[0] = 0x35 // SYNCHRONIZE CACHE(10)
        try {
            val r = execute(cdb, true, null, 0, 0)
            if (r.status != 0) requestSense()
        } catch (e: IOException) {
            log("flush failed (ignored): ${e.message}")
        }
    }

    override fun close() {
        log("closing")
        transport.close()
    }

    private fun log(message: String) = AppLog.log("scsi: $message")

    private fun timeoutMs(): Int = if (initializing) INIT_TIMEOUT_MS else ioTimeoutMs

    /** Keeps the link awake and checks the drive still answers. */
    fun ping(): Boolean = try {
        execute(ByteArray(6), true, null, 0, 0).status == 0 // TEST UNIT READY
    } catch (e: IOException) {
        false
    }

    private fun transferAll(write: Boolean, lba: Long, buf: ByteArray, count: Int) {
        val what = if (write) "write" else "read"
        val ramp = if (write) writeRamp else readRamp
        var done = 0
        var errorsAtMinimum = 0
        while (done < count) {
            val n = minOf(count - done, maxOf(1, ramp.bytes / sectorSize))
            try {
                transferSectors(write, lba + done, n, buf, done * sectorSize)
            } catch (e: IOException) {
                log("$what of ${n * sectorSize} bytes at sector ${lba + done} failed: ${e.message}")
                if (e is DriveUnresponsiveException) throw e
                if (ramp.failure()) {
                    log("$what: using ${ramp.bytes} bytes per command from now on")
                } else {
                    errorsAtMinimum++
                    if (errorsAtMinimum > 3) throw e
                }
                Thread.sleep(300)
                continue
            }
            ramp.success()
            done += n
        }
    }

    /** Writes what the drive says about itself to the log, and whether it reports write protection. */
    private fun logDeviceInfo() {
        try {
            val inquiry = ByteArray(36)
            val cdb = ByteArray(6)
            cdb[0] = 0x12 // INQUIRY
            cdb[4] = 36
            val r = execute(cdb, true, inquiry, 0, 36)
            if (r.status == 0 && r.transferred >= 32) {
                log(
                    "inquiry: type=${inquiry[0].toInt() and 0x1F} removable=${(inquiry[1].toInt() and 0x80) != 0} " +
                        "vendor='${ascii(inquiry, 8, 8)}' product='${ascii(inquiry, 16, 16)}' revision='${ascii(inquiry, 32, 4)}'",
                )
            } else {
                requestSense()
            }
        } catch (e: IOException) {
            log("inquiry failed: ${e.message}")
        }
        try {
            val header = ByteArray(4)
            val cdb = ByteArray(6)
            cdb[0] = 0x1A // MODE SENSE(6)
            cdb[2] = 0x3F
            cdb[4] = 4
            val r = execute(cdb, true, header, 0, 4)
            if (r.status == 0 && r.transferred >= 4) {
                log("mode sense: write protected=${(header[2].toInt() and 0x80) != 0}")
            } else {
                requestSense()
            }
        } catch (e: IOException) {
            log("mode sense failed: ${e.message}")
        }
    }

    private fun ascii(b: ByteArray, offset: Int, length: Int): String =
        String(b, offset, length, Charsets.US_ASCII).trim()

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
        val what = if (write) "write" else "read"
        val bytes = sectors * sectorSize
        for (attempt in 0 until 2) {
            val r = execute(cdb, !write, buf, offset, bytes, minOf(CHUNK, bytes))
            if (r.status == 0 && r.transferred == bytes) return
            val sense = requestSense()
            log(
                "$what at sector $lba: status=${r.status} moved=${r.transferred}/$bytes " +
                    "sense key=0x%02x asc=0x%02x ascq=0x%02x".format(sense.key, sense.asc, sense.ascq),
            )
            // A "unit attention" (media changed, bus reset) is reported once; the retry then succeeds.
            if (sense.key == 6 && attempt == 0) continue
            throw IOException(
                "SCSI $what failed at sector $lba " +
                    "(sense key 0x%02x, code 0x%02x/0x%02x)".format(sense.key, sense.asc, sense.ascq),
            )
        }
    }

    private fun waitUntilReady() {
        for (attempt in 0 until 10) {
            try {
                val r = execute(ByteArray(6), true, null, 0, 0) // TEST UNIT READY
                if (r.status == 0) return
                requestSense()
            } catch (e: IOException) {
                // Right after the drive was taken over from Android it can need a moment (and a reset) to answer.
                log("not ready yet (attempt ${attempt + 1}): ${e.message}")
                if (attempt >= 1) throw e
            }
            if (attempt < 9) Thread.sleep(300)
        }
        log("the drive never reported ready; continuing anyway")
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
    private fun execute(
        cdb: ByteArray,
        dataIn: Boolean,
        data: ByteArray?,
        offset: Int,
        length: Int,
        maxTransfer: Int = CHUNK,
    ): Result {
        val op = cdb[0].toInt() and 0xFF
        val myTag = tag
        tag = (tag + 1) and 0xFFFFFFFFL

        val cbw = ByteArray(31)
        putLe(cbw, 0, CBW_SIGNATURE, 4)
        putLe(cbw, 4, myTag, 4)
        putLe(cbw, 8, length.toLong(), 4)
        cbw[12] = if (dataIn && length > 0) 0x80.toByte() else 0
        cbw[14] = cdb.size.toByte()
        System.arraycopy(cdb, 0, cbw, 15, cdb.size)

        fun send(): Int = transport.bulkOut(cbw, 0, cbw.size, COMMAND_TIMEOUT_MS)

        // Escalating recovery when the drive does not take the command block.
        var sent = send()
        if (sent != cbw.size) {
            log("${opName(op)}: command block not accepted (result $sent); clearing halt and retrying")
            transport.clearHalt(false)
            sent = send()
        }
        if (sent != cbw.size) {
            log("${opName(op)}: still not accepted (result $sent); resetting")
            transport.reset()
            sent = send()
        }
        if (sent != cbw.size) {
            log("${opName(op)}: still not accepted (result $sent); resetting the whole device")
            if (transport.hardReset()) {
                Thread.sleep(1500)
                sent = send()
            } else {
                log("a device reset is not available")
            }
        }
        if (sent != cbw.size) {
            log("${opName(op)}: rejected after every kind of reset (result $sent). ${transport.describe()}")
            throw DriveUnresponsiveException(
                "The drive stopped responding. Unplug it, plug it in again and retry. (${transport.describe()})",
            )
        }

        var moved = 0
        if (length > 0 && data != null) {
            var failed = false
            while (moved < length) {
                val want = minOf(maxTransfer, length - moved)
                val n = if (dataIn) {
                    transport.bulkIn(data, offset + moved, want, timeoutMs())
                } else {
                    transport.bulkOut(data, offset + moved, want, timeoutMs())
                }
                if (n < 0) {
                    log("${opName(op)}: data phase error (result $n) after $moved of $length bytes")
                    failed = true
                    break
                }
                moved += n
                if (n < want) {
                    log("${opName(op)}: short transfer, $n of $want bytes")
                    break // the status block follows
                }
            }
            if (failed) {
                log(
                    "endpoint status: out=${transport.endpointStatus(false)} in=${transport.endpointStatus(true)} " +
                        "(1 means halted, -1 unknown)",
                )
                transport.clearHalt(dataIn)
            }
        }

        val csw = ByteArray(13)
        var got = transport.bulkIn(csw, 0, csw.size, timeoutMs())
        if (got != csw.size) {
            log("${opName(op)}: status read returned $got; clearing halt and reading again")
            transport.clearHalt(true)
            got = transport.bulkIn(csw, 0, csw.size, timeoutMs())
        }
        if (got == csw.size && getLe(csw, 0, 4) == CSW_SIGNATURE && getLe(csw, 4, 4) != myTag) {
            // Most likely a leftover status block from an earlier command; the real one should follow.
            log("${opName(op)}: stale status block (tag ${getLe(csw, 4, 4)}, expected $myTag); reading again")
            got = transport.bulkIn(csw, 0, csw.size, timeoutMs())
        }
        if (got != csw.size || getLe(csw, 0, 4) != CSW_SIGNATURE || getLe(csw, 4, 4) != myTag) {
            log(
                "${opName(op)}: invalid status block, read=$got bytes=${hex(csw)} expectedTag=$myTag " +
                    "(moved $moved of $length). ${transport.describe()}",
            )
            transport.reset()
            throw IOException("The drive returned an invalid status (${transport.describe()})")
        }

        val status = csw[12].toInt() and 0xFF
        val readWrite = op == 0x28 || op == 0x2A || op == 0x88 || op == 0x8A
        if (readWrite) readWritesLogged++
        val routinePing = op == 0x00 && status == 0 && !initializing
        if (!routinePing && (!readWrite || readWritesLogged <= LOGGED_READ_WRITES || status != 0 || moved != length)) {
            log("${opName(op)} ${if (dataIn) "in" else "out"} $length bytes -> status=$status moved=$moved")
        }
        return Result(status, moved)
    }

    private fun opName(op: Int): String = when (op) {
        0x00 -> "TEST UNIT READY"
        0x03 -> "REQUEST SENSE"
        0x25 -> "READ CAPACITY(10)"
        0x28 -> "READ(10)"
        0x2A -> "WRITE(10)"
        0x35 -> "SYNC CACHE"
        0x88 -> "READ(16)"
        0x8A -> "WRITE(16)"
        0x9E -> "READ CAPACITY(16)"
        else -> "op 0x%02x".format(op)
    }

    private fun hex(b: ByteArray): String = b.joinToString(" ") { "%02x".format(it) }

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
