package dev.usbformat

import dev.usbformat.fmt.Cancel
import dev.usbformat.fmt.EraseMode
import dev.usbformat.fmt.FormatJob
import dev.usbformat.fmt.Fs
import dev.usbformat.fmt.Inspector
import dev.usbformat.fmt.Options
import dev.usbformat.fmt.Scheme
import dev.usbformat.fmt.TableType
import dev.usbformat.usb.ScsiDisk
import dev.usbformat.usb.UsbTransport
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.Random

/** A tiny USB Bulk-Only drive kept in memory: parses command blocks and answers like a real device would. */
class FakeTransport(
    private val sectors: Long,
    private val blockSize: Int = 512,
    private val reportHuge: Boolean = false,
    private var stallNextWrite: Boolean = false,
    private val dead: Boolean = false,
) : UsbTransport {
    val storage = ByteArray((sectors * blockSize).toInt())
    val commands = ArrayList<Int>()

    private var reply = ByteArray(0)
    private var replyPos = 0
    private var tag = 0L
    private var writeAt = 0
    private var writeLeft = 0

    private fun queue(data: ByteArray) {
        reply = reply.copyOfRange(replyPos, reply.size) + data
        replyPos = 0
    }

    private fun queueStatus(status: Int) {
        val csw = ByteArray(13)
        le(csw, 0, 0x53425355L, 4)
        le(csw, 4, tag, 4)
        csw[12] = status.toByte()
        queue(csw)
    }

    override fun bulkOut(data: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
        if (dead) return -1
        if (writeLeft > 0 && stallNextWrite) {
            // The drive refuses the data phase (stall) and reports the failure in the status block.
            stallNextWrite = false
            writeLeft = 0
            queueStatus(1)
            return -1
        }
        if (writeLeft > 0) {
            System.arraycopy(data, offset, storage, writeAt, length)
            writeAt += length
            writeLeft -= length
            if (writeLeft == 0) queueStatus(0)
            return length
        }
        require(length == 31) { "A command block must be 31 bytes" }
        require(readLe(data, offset, 4) == 0x43425355L) { "Bad command block signature" }
        tag = readLe(data, offset + 4, 4)
        val transferLength = readLe(data, offset + 8, 4).toInt()
        val cdbLength = data[offset + 14].toInt() and 0xFF
        val cdb = data.copyOfRange(offset + 15, offset + 15 + cdbLength)
        val op = cdb[0].toInt() and 0xFF
        commands.add(op)

        when (op) {
            0x00, 0x35 -> queueStatus(0) // TEST UNIT READY, SYNCHRONIZE CACHE
            0x25 -> { // READ CAPACITY(10)
                val out = ByteArray(8)
                be(out, 0, if (reportHuge) 0xFFFFFFFFL else sectors - 1, 4)
                be(out, 4, blockSize.toLong(), 4)
                queue(out)
                queueStatus(0)
            }
            0x9E -> { // READ CAPACITY(16)
                val out = ByteArray(32)
                be(out, 0, (4L shl 40) / blockSize - 1, 8)
                be(out, 8, blockSize.toLong(), 4)
                queue(out)
                queueStatus(0)
            }
            0x28 -> read(readBe(cdb, 2, 4), readBe(cdb, 7, 2).toInt(), transferLength)
            0x88 -> read(readBe(cdb, 2, 8), readBe(cdb, 10, 4).toInt(), transferLength)
            0x2A -> startWrite(readBe(cdb, 2, 4), readBe(cdb, 7, 2).toInt(), transferLength)
            0x8A -> startWrite(readBe(cdb, 2, 8), readBe(cdb, 10, 4).toInt(), transferLength)
            0x03 -> { // REQUEST SENSE
                queue(ByteArray(18))
                queueStatus(0)
            }
            else -> queueStatus(1)
        }
        return length
    }

    private fun read(lba: Long, count: Int, transferLength: Int) {
        require(transferLength == count * blockSize) { "Transfer length does not match the block count" }
        val from = (lba * blockSize).toInt()
        queue(storage.copyOfRange(from, from + transferLength))
        queueStatus(0)
    }

    private fun startWrite(lba: Long, count: Int, transferLength: Int) {
        require(transferLength == count * blockSize) { "Transfer length does not match the block count" }
        writeAt = (lba * blockSize).toInt()
        writeLeft = transferLength
    }

    override fun bulkIn(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
        if (dead) return -1
        val available = reply.size - replyPos
        if (available == 0) return -1
        val n = minOf(available, length)
        System.arraycopy(reply, replyPos, buffer, offset, n)
        replyPos += n
        return n
    }

    override fun clearHalt(inEndpoint: Boolean) {}

    override fun reset() {}

    override fun close() {}

    private fun le(b: ByteArray, o: Int, v: Long, n: Int) {
        for (i in 0 until n) b[o + i] = (v shr (8 * i)).toByte()
    }

    private fun be(b: ByteArray, o: Int, v: Long, n: Int) {
        for (i in 0 until n) b[o + i] = (v shr (8 * (n - 1 - i))).toByte()
    }

    private fun readLe(b: ByteArray, o: Int, n: Int): Long {
        var v = 0L
        for (i in n - 1 downTo 0) v = (v shl 8) or (b[o + i].toLong() and 0xFF)
        return v
    }

    private fun readBe(b: ByteArray, o: Int, n: Int): Long {
        var v = 0L
        for (i in 0 until n) v = (v shl 8) or (b[o + i].toLong() and 0xFF)
        return v
    }
}

class ScsiDiskTest {

    @Test
    fun reportsCapacity() {
        val disk = ScsiDisk(FakeTransport(32768))
        assertEquals(512, disk.sectorSize)
        assertEquals(32768L, disk.sectorCount)
    }

    @Test
    fun capacityAbove2TiBUsesReadCapacity16() {
        val fake = FakeTransport(32768, reportHuge = true)
        val disk = ScsiDisk(fake)
        assertEquals((4L shl 40) / 512, disk.sectorCount)
        assertTrue(0x9E in fake.commands)
    }

    @Test
    fun writeThenReadRoundTrip() {
        val fake = FakeTransport(32768)
        val disk = ScsiDisk(fake)
        val data = ByteArray(300 * 1024).also { Random(1).nextBytes(it) } // spans several SCSI commands
        disk.write(5, data)
        assertArrayEquals(data, disk.read(5, data.size / 512))
        assertArrayEquals(data, fake.storage.copyOfRange(5 * 512, 5 * 512 + data.size))
    }

    @Test
    fun roundTripWith4096ByteSectors() {
        val fake = FakeTransport(4096, blockSize = 4096)
        val disk = ScsiDisk(fake)
        assertEquals(4096, disk.sectorSize)
        val data = ByteArray(200 * 1024).also { Random(2).nextBytes(it) }
        disk.write(3, data)
        assertArrayEquals(data, disk.read(3, data.size / 4096))
    }

    @Test
    fun recoversFromAStalledWriteByUsingSmallerTransfers() {
        val fake = FakeTransport(32768, stallNextWrite = true)
        val disk = ScsiDisk(fake)
        val data = ByteArray(200 * 1024).also { Random(3).nextBytes(it) }
        disk.write(10, data)
        assertArrayEquals(data, disk.read(10, data.size / 512))
    }

    @Test
    fun aDeadDriveFailsWithAnIoException() {
        try {
            ScsiDisk(FakeTransport(1024, dead = true))
            fail("a drive that never answers must not open")
        } catch (e: IOException) {
            // expected
        }
    }

    @Test
    fun formattingThroughTheUsbLayerProducesAReadableDrive() {
        val fake = FakeTransport(131072) // 64 MiB
        val disk = ScsiDisk(fake)
        FormatJob.run(disk, Options(Scheme.GPT, Fs.FAT32, "TEST", EraseMode.QUICK), Cancel()) { _, _, _ -> }
        val info = Inspector.inspect(disk)
        assertEquals(TableType.GPT, info.table)
        assertEquals("FAT32", info.partitions.single().fs)
        assertTrue("the drive cache should be flushed at the end", 0x35 in fake.commands)
    }
}
