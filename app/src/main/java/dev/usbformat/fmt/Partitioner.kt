package dev.usbformat.fmt

import dev.usbformat.disk.Disk
import dev.usbformat.disk.MIB
import java.security.SecureRandom
import java.util.zip.CRC32

enum class Scheme { MBR, GPT }

enum class Fs { FAT32, EXFAT, NTFS }

/** The single partition created on the drive, in sectors. */
class Region(val startLba: Long, val sectors: Long, val firstSector: ByteArray = ByteArray(0))

/** Writes a fresh MBR or GPT with one partition that spans the drive, aligned to 1 MiB. */
object Partitioner {
    private const val ENTRY_COUNT = 128
    private const val ENTRY_SIZE = 128

    // EBD0A0A2-B9E5-4433-87C0-68B6B72699C7 ("Basic data partition") in on-disk byte order.
    private val BASIC_DATA_GUID = byteArrayOf(
        0xA2.toByte(), 0xA0.toByte(), 0xD0.toByte(), 0xEB.toByte(),
        0xE5.toByte(), 0xB9.toByte(), 0x33, 0x44,
        0x87.toByte(), 0xC0.toByte(), 0x68, 0xB6.toByte(),
        0xB7.toByte(), 0x26, 0x99.toByte(), 0xC7.toByte(),
    )

    fun create(disk: Disk, scheme: Scheme, fs: Fs): Region {
        val ss = disk.sectorSize
        val total = disk.sectorCount
        val align = (MIB / ss).toLong()
        require(total * ss >= 16L * MIB) { "The drive is smaller than 16 MiB" }

        val entrySectors = ((ENTRY_COUNT * ENTRY_SIZE + ss - 1) / ss).toLong()
        val last = total - 1
        val firstUsable = 2 + entrySectors
        val lastUsable = last - entrySectors - 1

        val start = align
        val end = if (scheme == Scheme.GPT) lastUsable else last
        var size = end - start + 1
        size -= size % align
        if (scheme == Scheme.MBR) size = minOf(size, 0xFFFFFFFFL - start)
        require(size > 0) { "The drive is too small" }

        // Remove leftovers of any previous table: the first MiB and the last MiB (old backup GPT).
        // Sector 0 is written last (see FormatJob): the drive then holds a complete file system before the table points to it.
        disk.zero(1, start - 1)
        val tail = minOf(align, total - start)
        disk.zero(total - tail, tail)

        val firstSector = when (scheme) {
            Scheme.MBR -> buildMbr(disk, start, size, if (fs == Fs.FAT32) 0x0C else 0x07)
            Scheme.GPT -> writeGpt(disk, start, size, entrySectors, firstUsable, lastUsable)
        }
        return Region(start, size, firstSector)
    }

    private fun buildMbr(disk: Disk, start: Long, size: Long, type: Int): ByteArray {
        val s = ByteArray(disk.sectorSize)
        // Windows identifies disks by this signature, so it must not be zero.
        val signature = (SecureRandom().nextInt().toLong() and 0xFFFFFFFFL) or 1L
        s.putLe32(440, signature)
        val o = 446
        s[o + 1] = 0xFE.toByte(); s[o + 2] = 0xFF.toByte(); s[o + 3] = 0xFF.toByte()
        s[o + 4] = type.toByte()
        s[o + 5] = 0xFE.toByte(); s[o + 6] = 0xFF.toByte(); s[o + 7] = 0xFF.toByte()
        s.putLe32(o + 8, start)
        s.putLe32(o + 12, size)
        s[510] = 0x55
        s[511] = 0xAA.toByte()
        return s
    }

    /** Writes everything except sector 0 and returns the protective MBR for it. */
    private fun writeGpt(
        disk: Disk,
        start: Long,
        size: Long,
        entrySectors: Long,
        firstUsable: Long,
        lastUsable: Long,
    ): ByteArray {
        val ss = disk.sectorSize
        val last = disk.sectorCount - 1

        // Protective MBR: one 0xEE partition covering the whole disk.
        val pmbr = ByteArray(ss)
        pmbr[446 + 2] = 0x02
        pmbr[446 + 4] = 0xEE.toByte()
        pmbr[446 + 5] = 0xFF.toByte(); pmbr[446 + 6] = 0xFF.toByte(); pmbr[446 + 7] = 0xFF.toByte()
        pmbr.putLe32(446 + 8, 1L)
        pmbr.putLe32(446 + 12, minOf(last, 0xFFFFFFFFL))
        pmbr[510] = 0x55
        pmbr[511] = 0xAA.toByte()

        val entries = ByteArray(ENTRY_COUNT * ENTRY_SIZE)
        entries.putBytes(0, BASIC_DATA_GUID)
        entries.putBytes(16, randomGuid())
        entries.putLe64(32, start)
        entries.putLe64(40, start + size - 1)
        entries.putBytes(56, "Basic data partition".toByteArray(Charsets.UTF_16LE))
        val entriesCrc = crc(entries, entries.size)

        val diskGuid = randomGuid()

        fun header(current: Long, backup: Long, entriesLba: Long): ByteArray {
            val h = ByteArray(ss)
            h.putAscii(0, "EFI PART")
            h.putLe32(8, 0x00010000L)
            h.putLe32(12, 92L)
            h.putLe64(24, current)
            h.putLe64(32, backup)
            h.putLe64(40, firstUsable)
            h.putLe64(48, lastUsable)
            h.putBytes(56, diskGuid)
            h.putLe64(72, entriesLba)
            h.putLe32(80, ENTRY_COUNT.toLong())
            h.putLe32(84, ENTRY_SIZE.toLong())
            h.putLe32(88, entriesCrc)
            h.putLe32(16, crc(h, 92))
            return h
        }

        disk.write(2, entries)
        disk.write(1, header(1L, last, 2L))
        disk.write(last - entrySectors, entries)
        disk.write(last, header(last, 1L, last - entrySectors))
        return pmbr
    }

    private fun crc(data: ByteArray, length: Int): Long {
        val c = CRC32()
        c.update(data, 0, length)
        return c.value
    }
}
