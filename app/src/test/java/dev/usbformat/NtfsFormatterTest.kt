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
}
