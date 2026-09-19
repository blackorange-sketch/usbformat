package dev.usbformat.fmt

import dev.usbformat.disk.Disk

enum class TableType { MBR, GPT, NONE }

/** [fs] is null when the file system is not recognised. */
data class PartitionInfo(val index: Int, val startLba: Long, val sectors: Long, val fs: String?)

data class DriveInfo(
    val sectorSize: Int,
    val sectorCount: Long,
    val table: TableType,
    val partitions: List<PartitionInfo>,
)

/** Read-only look at a drive: partition table type, partitions and the file system in each. */
object Inspector {

    fun inspect(disk: Disk): DriveInfo {
        val ss = disk.sectorSize
        val total = disk.sectorCount

        // A file system that starts right at sector 0 means there is no partition table at all.
        val direct = detectFs(head(disk, 0))
        if (direct != null) {
            return DriveInfo(ss, total, TableType.NONE, listOf(PartitionInfo(1, 0, total, direct)))
        }

        val s0 = disk.read(0, 1)
        if (u8(s0, 510) != 0x55 || u8(s0, 511) != 0xAA) {
            return DriveInfo(ss, total, TableType.NONE, emptyList())
        }

        class Entry(val slot: Int, val status: Int, val type: Int, val start: Long, val size: Long)

        val entries = (0 until 4).map { i ->
            val o = 446 + 16 * i
            Entry(i + 1, u8(s0, o), u8(s0, o + 4), le32(s0, o + 8), le32(s0, o + 12))
        }
        // Boot flags other than 0x00 / 0x80 mean this is not really an MBR.
        if (entries.any { it.status != 0x00 && it.status != 0x80 }) {
            return DriveInfo(ss, total, TableType.NONE, emptyList())
        }

        if (entries.any { it.type == 0xEE }) {
            val header = disk.read(1, 1)
            if (String(header, 0, 8, Charsets.US_ASCII) == "EFI PART") {
                return readGpt(disk, header)
            }
        }

        val parts = entries
            .filter { it.type != 0 && it.size > 0 }
            .map { PartitionInfo(it.slot, it.start, it.size, fsAt(disk, it.start)) }
        return DriveInfo(ss, total, TableType.MBR, parts)
    }

    private fun readGpt(disk: Disk, header: ByteArray): DriveInfo {
        val ss = disk.sectorSize
        val total = disk.sectorCount
        val entriesLba = le64(header, 72)
        val count = le32(header, 80).toInt().coerceIn(0, 256)
        val entrySize = le32(header, 84).toInt()

        val parts = ArrayList<PartitionInfo>()
        if (entrySize in 128..1024 && entriesLba in 2L until total) {
            val sectors = ((count * entrySize + ss - 1) / ss).coerceAtMost((total - entriesLba).toInt())
            if (sectors > 0) {
                val raw = disk.read(entriesLba, sectors)
                for (i in 0 until count) {
                    val o = i * entrySize
                    if (o + 48 > raw.size) break
                    if ((0 until 16).all { raw[o + it].toInt() == 0 }) continue // unused slot
                    val first = le64(raw, o + 32)
                    val last = le64(raw, o + 40)
                    if (last < first) continue
                    parts.add(PartitionInfo(i + 1, first, last - first + 1, fsAt(disk, first)))
                }
            }
        }
        return DriveInfo(ss, total, TableType.GPT, parts)
    }

    private fun fsAt(disk: Disk, lba: Long): String? {
        if (lba < 0 || lba >= disk.sectorCount) return null
        return detectFs(head(disk, lba))
    }

    /** Reads enough sectors from [lba] to cover the ext superblock magic (byte 1082). */
    private fun head(disk: Disk, lba: Long): ByteArray {
        val want = (1082 + disk.sectorSize - 1) / disk.sectorSize
        val n = minOf(want.toLong(), disk.sectorCount - lba).toInt()
        return if (n <= 0) ByteArray(0) else disk.read(lba, n)
    }

    internal fun detectFs(b: ByteArray): String? {
        if (b.size < 512) return null
        val oem = text(b, 3, 8)
        if (oem == "EXFAT   ") return "exFAT"
        if (oem == "NTFS    ") return "NTFS"
        if (text(b, 82, 8) == "FAT32   ") return "FAT32"
        val legacy = text(b, 54, 8)
        if (legacy == "FAT16   ") return "FAT16"
        if (legacy == "FAT12   ") return "FAT12"
        if (b.size >= 1082 && u8(b, 1080) == 0x53 && u8(b, 1081) == 0xEF) return "ext2/3/4"
        return null
    }

    private fun text(b: ByteArray, offset: Int, length: Int) = String(b, offset, length, Charsets.ISO_8859_1)

    private fun u8(b: ByteArray, o: Int): Int = b[o].toInt() and 0xFF

    private fun le32(b: ByteArray, o: Int): Long {
        var v = 0L
        for (i in 3 downTo 0) v = (v shl 8) or u8(b, o + i).toLong()
        return v
    }

    private fun le64(b: ByteArray, o: Int): Long {
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or u8(b, o + i).toLong()
        return v
    }
}
