package dev.usbformat.fmt

import dev.usbformat.disk.Disk
import dev.usbformat.disk.MIB
import java.util.concurrent.CancellationException

enum class EraseMode { QUICK, ZERO, VERIFY }

enum class Phase { ZEROING, WRITING_TEST, READING_TEST, PARTITIONING, FORMATTING }

/** Cooperative cancellation: the long loops call [check] once per chunk. */
class Cancel {
    @Volatile
    var requested = false

    fun check() {
        if (requested) throw CancellationException("Cancelled")
    }
}

class VerifyException(val lba: Long) :
    Exception("Data mismatch near sector $lba: the drive is faulty or reports a fake capacity")

typealias ProgressCallback = (phase: Phase, done: Long, total: Long) -> Unit

/** Full-drive passes. Progress is reported in sectors. */
object Eraser {

    fun zeroAll(disk: Disk, cancel: Cancel, progress: ProgressCallback) {
        val ss = disk.sectorSize
        val total = disk.sectorCount
        val chunk = MIB / ss
        val zeros = ByteArray(chunk * ss)
        var lba = 0L
        while (lba < total) {
            cancel.check()
            val n = minOf(chunk.toLong(), total - lba).toInt()
            disk.write(lba, zeros, n * ss)
            lba += n
            progress(Phase.ZEROING, lba, total)
        }
    }

    /**
     * Writes an address-dependent pseudo-random pattern to every sector, then reads everything back.
     * Because every sector holds different data, wrapped-around fake capacity shows up as a mismatch.
     */
    fun verifyAll(disk: Disk, cancel: Cancel, progress: ProgressCallback) {
        val ss = disk.sectorSize
        val total = disk.sectorCount
        val chunk = MIB / ss
        val buf = ByteArray(chunk * ss)

        var lba = 0L
        while (lba < total) {
            cancel.check()
            val n = minOf(chunk.toLong(), total - lba).toInt()
            fillPattern(buf, lba, n, ss)
            disk.write(lba, buf, n * ss)
            lba += n
            progress(Phase.WRITING_TEST, lba, total)
        }

        lba = 0L
        while (lba < total) {
            cancel.check()
            val n = minOf(chunk.toLong(), total - lba).toInt()
            val got = disk.read(lba, n)
            fillPattern(buf, lba, n, ss)
            for (k in 0 until n) {
                val o = k * ss
                for (i in 0 until ss) {
                    if (got[o + i] != buf[o + i]) throw VerifyException(lba + k)
                }
            }
            lba += n
            progress(Phase.READING_TEST, lba, total)
        }
    }

    private fun fillPattern(buf: ByteArray, firstLba: Long, sectors: Int, ss: Int) {
        var p = 0
        for (k in 0 until sectors) {
            var x = (firstLba + k + 1) * -7046029254386353131L // 0x9E3779B97F4A7C15
            var i = 0
            while (i < ss) {
                x = x xor (x shl 13)
                x = x xor (x ushr 7)
                x = x xor (x shl 17)
                for (b in 0 until 8) buf[p + i + b] = (x ushr (8 * b)).toByte()
                i += 8
            }
            p += ss
        }
    }
}
