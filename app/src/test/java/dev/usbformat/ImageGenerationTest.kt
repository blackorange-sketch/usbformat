package dev.usbformat

import dev.usbformat.disk.FileDisk
import dev.usbformat.disk.MIB
import dev.usbformat.fmt.Cancel
import dev.usbformat.fmt.EraseMode
import dev.usbformat.fmt.FormatJob
import dev.usbformat.fmt.Inspector
import dev.usbformat.fmt.Fs
import dev.usbformat.fmt.Options
import dev.usbformat.fmt.Region
import dev.usbformat.fmt.Scheme
import dev.usbformat.fmt.TableType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

/**
 * Formats sparse image files for every scheme/file system combination.
 * CI then runs sfdisk, sgdisk, fsck.fat and fsck.exfat on them (see tools/verify-images.sh).
 */
class ImageGenerationTest {
    private val dir = File("build/test-images").also { it.mkdirs() }

    private fun generate(scheme: Scheme, fs: Fs, sectorSize: Int, sizeMiB: Long): Region {
        val base = "${scheme.name.lowercase()}-${fs.name.lowercase()}-$sectorSize"
        val img = File(dir, "$base.img")
        img.delete()
        RandomAccessFile(img, "rw").use { it.setLength(sizeMiB * MIB) }

        val region = FileDisk(img.path, sectorSize).use { disk ->
            FormatJob.run(disk, Options(scheme, fs, "TEST", EraseMode.QUICK), Cancel()) { _, _, _ -> }
        }

        // The inspector must report exactly what was just written.
        FileDisk(img.path, sectorSize).use { disk ->
            val info = Inspector.inspect(disk)
            assertEquals(if (scheme == Scheme.GPT) TableType.GPT else TableType.MBR, info.table)
            assertEquals(1, info.partitions.size)
            assertEquals(
                when (fs) {
                    Fs.FAT32 -> "FAT32"
                    Fs.EXFAT -> "exFAT"
                    Fs.NTFS -> "NTFS"
                },
                info.partitions[0].fs,
            )
            assertEquals(region.startLba, info.partitions[0].startLba)
            assertEquals(region.sectors, info.partitions[0].sectors)
            assertEquals(sizeMiB * MIB / sectorSize, info.sectorCount)
        }

        if (sectorSize == 512) {
            // Extract the partition so file system checkers can be pointed straight at it.
            RandomAccessFile(img, "r").use { src ->
                File(dir, "$base.part").outputStream().use { out ->
                    src.seek(region.startLba * sectorSize)
                    var left = region.sectors * sectorSize
                    val buf = ByteArray(MIB)
                    while (left > 0) {
                        val n = minOf(left, buf.size.toLong()).toInt()
                        src.readFully(buf, 0, n)
                        out.write(buf, 0, n)
                        left -= n
                    }
                }
            }
        }
        return region
    }

    @Test
    fun allCombinations512() {
        for (scheme in Scheme.entries) {
            for (fs in Fs.entries) {
                val region = generate(scheme, fs, 512, 128)
                assertEquals(2048L, region.startLba)
                assertTrue(region.sectors > 0)
            }
        }
    }

    @Test
    fun allCombinations4096() {
        for (scheme in Scheme.entries) {
            for (fs in Fs.entries) {
                val region = generate(scheme, fs, 4096, 512)
                assertEquals(256L, region.startLba)
            }
        }
    }

    @Test
    fun blankDriveHasNoTable() {
        val img = File(dir, "blank.img")
        img.delete()
        RandomAccessFile(img, "rw").use { it.setLength(32L * MIB) }
        FileDisk(img.path, 512).use { disk ->
            val info = Inspector.inspect(disk)
            assertEquals(TableType.NONE, info.table)
            assertTrue(info.partitions.isEmpty())
        }
    }

    @Test
    fun gptHasHeadersAtBothEnds() {
        generate(Scheme.GPT, Fs.FAT32, 512, 128)
        RandomAccessFile(File(dir, "gpt-fat32-512.img"), "r").use { f ->
            val sig = ByteArray(8)
            f.seek(512)
            f.readFully(sig)
            assertEquals("EFI PART", String(sig, Charsets.US_ASCII))
            f.seek(f.length() - 512)
            f.readFully(sig)
            assertEquals("EFI PART", String(sig, Charsets.US_ASCII))
        }
    }
}
