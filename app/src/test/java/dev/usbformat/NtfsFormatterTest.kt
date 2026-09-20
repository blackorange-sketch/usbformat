package dev.usbformat

import dev.usbformat.disk.FileDisk
import dev.usbformat.disk.MIB
import dev.usbformat.fmt.Cancel
import dev.usbformat.fmt.EraseMode
import dev.usbformat.fmt.FormatJob
import dev.usbformat.fmt.Fs
import dev.usbformat.fmt.Options
import dev.usbformat.fmt.Region
import dev.usbformat.fmt.Scheme
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

/** Cheap structural checks; the real validation is done by ntfs-3g and the kernel driver in CI. */
class NtfsFormatterTest {

    private fun le(b: ByteArray, o: Int, n: Int): Long {
        var v = 0L
        for (i in n - 1 downTo 0) v = (v shl 8) or (b[o + i].toLong() and 0xFF)
        return v
    }

    private fun formatted(sectorSize: Int, sizeMiB: Long, block: (FileDisk, Region) -> Unit) {
        val img = File.createTempFile("ntfs-test", ".img")
        try {
            RandomAccessFile(img, "rw").use { it.setLength(sizeMiB * MIB) }
            FileDisk(img.path, sectorSize).use { disk ->
                val region = FormatJob.run(disk, Options(Scheme.MBR, Fs.NTFS, "Test", EraseMode.QUICK), Cancel()) { _, _, _ -> }
                block(disk, region)
            }
        } finally {
            img.delete()
        }
    }

    @Test
    fun bootSectorAndItsBackup() {
        for (ss in listOf(512, 4096)) {
            formatted(ss, if (ss == 512) 64 else 128) { disk, region ->
                val boot = disk.read(region.startLba, 1)
                assertEquals("NTFS    ", String(boot, 3, 8, Charsets.US_ASCII))
                assertEquals(ss.toLong(), le(boot, 0x0B, 2))
                assertEquals((4096 / ss).toLong(), le(boot, 0x0D, 1))
                assertEquals(region.sectors - 1, le(boot, 0x28, 8))
                assertEquals(0x55, boot[510].toInt() and 0xFF)
                assertEquals(0xAA, boot[511].toInt() and 0xFF)
                assertArrayEquals(boot, disk.read(region.startLba + region.sectors - 1, 1))
            }
        }
    }

    @Test
    fun systemRecordsAreWellFormed() {
        for (ss in listOf(512, 4096)) {
            formatted(ss, if (ss == 512) 64 else 128) { disk, region ->
                val boot = disk.read(region.startLba, 1)
                val spc = 4096 / ss
                val recordSize = maxOf(1024, ss)
                val sectorsPerRecord = recordSize / ss
                val mftLba = region.startLba + le(boot, 0x30, 8) * spc
                val mirrorLba = region.startLba + le(boot, 0x38, 8) * spc

                for (n in 0 until 16) {
                    val rec = disk.read(mftLba + n.toLong() * sectorsPerRecord, sectorsPerRecord)
                    assertEquals("FILE", String(rec, 0, 4, Charsets.US_ASCII))
                    assertEquals(n.toLong(), le(rec, 0x2C, 4))
                    assertTrue("record $n must be in use", le(rec, 0x16, 2) and 1L == 1L)
                    val usn = le(rec, 0x30, 2)
                    for (stride in 0 until recordSize / 512) {
                        assertEquals("fixup of record $n stride $stride", usn, le(rec, (stride + 1) * 512 - 2, 2))
                    }
                    assertTrue(le(rec, 0x18, 4) <= recordSize)
                }

                // $MFTMirr repeats the first four records.
                val first = disk.read(mftLba, 4 * sectorsPerRecord)
                assertArrayEquals(first, disk.read(mirrorLba, 4 * sectorsPerRecord))
            }
        }
    }

    /** The record with the update sequence values put back, the way the kernel driver looks at it. */
    private fun undoFixups(rec: ByteArray): ByteArray {
        val r = rec.copyOf()
        val usaOffset = le(r, 4, 2).toInt()
        val count = le(r, 6, 2).toInt()
        for (i in 1 until count) {
            val end = i * 512
            r[end - 2] = r[usaOffset + 2 * i]
            r[end - 1] = r[usaOffset + 2 * i + 1]
        }
        return r
    }

    /**
     * The attribute checks of the Linux kernel's NTFS driver (mi_enum_attr): it refuses a whole file record if a single
     * attribute breaks one of them. Found the hard way: a $BadClus attribute whose data size exceeded its allocated size.
     */
    private fun attributeProblems(r: ByteArray, clusterMask: Long, volumeSize: Long): List<String> {
        val problems = ArrayList<String>()
        val used = le(r, 0x18, 4)
        val total = le(r, 0x1C, 4)
        var off = le(r, 0x14, 2).toInt()
        if (used > total) problems.add("used > total")
        if (off >= used || off < 0x2A || off % 4 != 0) problems.add("bad first attribute offset")
        var previous = 0L
        while (true) {
            if (off + 8 > used) { problems.add("no end marker"); break }
            val type = le(r, off, 4)
            if (type == 0xFFFFFFFFL) break
            if (type == 0L || type and 0xF != 0L || type > 0x100) { problems.add("bad type $type"); break }
            if (type < previous) problems.add("attributes not ordered by type at $off")
            val size = le(r, off + 4, 4).toInt()
            if (off + size > used) { problems.add("attribute at $off is longer than the record"); break }
            val nonResident = r[off + 8].toInt()
            val nameLength = r[off + 9].toInt() and 0xFF
            val nameOffset = le(r, off + 0xA, 2).toInt()
            val flags = le(r, off + 0xC, 2).toInt()
            if (nonResident == 0) {
                val dataOffset = le(r, off + 0x14, 2).toInt()
                val dataSize = le(r, off + 0x10, 4).toInt()
                if (size < 0x18 || dataOffset > size || dataOffset + dataSize > size) problems.add("resident data at $off out of range")
                if (nameLength > 0 && nameOffset + 2 * nameLength > dataOffset) problems.add("name overlaps the data at $off")
            } else {
                if (nonResident != 1 || size < 0x40) { problems.add("bad non-resident header at $off"); break }
                val runOffset = le(r, off + 0x20, 2).toInt()
                if (runOffset > size) problems.add("run list offset at $off")
                if (nameLength > 0 && nameOffset + 2 * nameLength > runOffset) problems.add("name overlaps the run list at $off")
                val startVcn = le(r, off + 0x10, 8)
                val endVcn = le(r, off + 0x18, 8)
                if (startVcn > endVcn + 1) problems.add("start VCN after end VCN at $off")
                val allocated = le(r, off + 0x28, 8)
                val dataSize = le(r, off + 0x30, 8)
                val initialized = le(r, off + 0x38, 8)
                if (initialized > dataSize) problems.add("initialised size above data size at $off")
                if (dataSize > allocated) problems.add("data size $dataSize above allocated size $allocated at $off (type $type)")
                if (allocated and clusterMask != 0L) problems.add("allocated size not cluster aligned at $off")
                if (startVcn == 0L && flags and 0x8001 != 0) {
                    if (size < 0x48) problems.add("sparse or compressed header too small at $off")
                    val totalSize = le(r, off + 0x40, 8)
                    if (totalSize and clusterMask != 0L || totalSize > allocated) problems.add("bad total size at $off")
                } else {
                    if (le(r, off + 0x22, 2) != 0L) problems.add("compression unit on a plain attribute at $off")
                    if (allocated > volumeSize) problems.add("allocated size above the volume size at $off")
                }
            }
            previous = type
            off += size
        }
        return problems
    }

    @Test
    fun everySystemRecordPassesTheKernelDriversAttributeChecks() {
        for (ss in listOf(512, 4096)) {
            formatted(ss, if (ss == 512) 64 else 128) { disk, region ->
                val boot = disk.read(region.startLba, 1)
                val recordSize = maxOf(1024, ss)
                val sectorsPerRecord = recordSize / ss
                val mftLba = region.startLba + le(boot, 0x30, 8) * (4096 / ss)
                val volumeSize = le(boot, 0x28, 8) * ss
                for (n in 0 until 16) {
                    val rec = undoFixups(disk.read(mftLba + n.toLong() * sectorsPerRecord, sectorsPerRecord))
                    val problems = attributeProblems(rec, 4095, volumeSize)
                    assertTrue("record $n (sector size $ss): $problems", problems.isEmpty())
                }
            }
        }
    }
}
