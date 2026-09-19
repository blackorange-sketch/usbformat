package dev.usbformat.fmt

import dev.usbformat.disk.Disk
import dev.usbformat.disk.MIB
import dev.usbformat.log.AppLog

/** Quick FAT32 formatter. Works for any volume size, not only up to 32 GiB like Windows. */
object Fat32Formatter {
    private const val MIN_CLUSTERS = 65525L
    private const val MAX_CLUSTERS = 0x0FFFFFF5L

    fun format(disk: Disk, region: Region, label: String) {
        val ss = disk.sectorSize
        val total = minOf(region.sectors, 0xFFFFFFFFL)
        val volBytes = total * ss

        // Cluster size follows the Microsoft table for 512-byte sectors.
        val clusterBytes = when {
            volBytes <= 260L * MIB -> 512
            volBytes <= 8L * 1024 * MIB -> 4096
            volBytes <= 16L * 1024 * MIB -> 8192
            volBytes <= 32L * 1024 * MIB -> 16384
            else -> 32768
        }
        var spc = maxOf(1, clusterBytes / ss)
        var reserved = 32L

        fun fatSectorsFor(perCluster: Int, reservedSectors: Long): Long {
            var f = 1L
            while (true) {
                val c = (total - reservedSectors - 2 * f) / perCluster
                val need = ((c + 2) * 4 + ss - 1) / ss
                if (need <= f) return f
                f = need
            }
        }

        fun clustersFor(perCluster: Int, reservedSectors: Long, fat: Long): Long =
            (total - reservedSectors - 2 * fat) / perCluster

        var fat = fatSectorsFor(spc, reserved)
        var clusters = clustersFor(spc, reserved, fat)
        while (clusters < MIN_CLUSTERS && spc > 1) {
            spc /= 2
            fat = fatSectorsFor(spc, reserved)
            clusters = clustersFor(spc, reserved, fat)
        }
        while (clusters > MAX_CLUSTERS && spc < 128) {
            spc *= 2
            fat = fatSectorsFor(spc, reserved)
            clusters = clustersFor(spc, reserved, fat)
        }
        require(clusters in MIN_CLUSTERS..MAX_CLUSTERS) { "The volume size is not suitable for FAT32" }

        // Push the data area to a 1 MiB boundary (the partition itself starts on one).
        val align = (MIB / ss).toLong()
        val extra = (align - (reserved + 2 * fat) % align) % align
        if (clustersFor(spc, reserved + extra, fat) >= MIN_CLUSTERS) {
            reserved += extra
            clusters = clustersFor(spc, reserved, fat)
        }

        AppLog.log(
            "fat32: cluster=${spc * ss} B reserved=$reserved fat=$fat sectors (x2) clusters=$clusters total=$total sectors",
        )
        val labelBytes = fatLabel(label)
        val volumeId = System.nanoTime() and 0xFFFFFFFFL

        val boot = ByteArray(ss)
        boot[0] = 0xEB.toByte(); boot[1] = 0x58; boot[2] = 0x90.toByte()
        boot.putAscii(3, "MSDOS5.0")
        boot.putLe16(11, ss)
        boot[13] = spc.toByte()
        boot.putLe16(14, reserved.toInt())
        boot[16] = 2
        boot[21] = 0xF8.toByte()
        boot.putLe16(24, 63)
        boot.putLe16(26, 255)
        boot.putLe32(28, minOf(region.startLba, 0xFFFFFFFFL))
        boot.putLe32(32, total)
        boot.putLe32(36, fat)
        boot.putLe32(44, 2L)
        boot.putLe16(48, 1)
        boot.putLe16(50, 6)
        boot[64] = 0x80.toByte()
        boot[66] = 0x29
        boot.putLe32(67, volumeId)
        boot.putBytes(71, labelBytes ?: "NO NAME    ".toByteArray(Charsets.US_ASCII))
        boot.putAscii(82, "FAT32   ")
        boot[510] = 0x55
        boot[511] = 0xAA.toByte()

        val info = ByteArray(ss)
        info.putLe32(0, 0x41615252L)
        info.putLe32(484, 0x61417272L)
        info.putLe32(488, clusters - 1)
        info.putLe32(492, 3L)
        info.putLe32(508, 0xAA550000L)

        val third = ByteArray(ss)
        third[510] = 0x55
        third[511] = 0xAA.toByte()

        val head = ByteArray(ss * 3)
        boot.copyInto(head, 0)
        info.copyInto(head, ss)
        third.copyInto(head, 2 * ss)

        val start = region.startLba
        val dataStart = reserved + 2 * fat

        // Reserved area, both FATs and the root directory cluster must start clean.
        disk.zero(start, dataStart + spc)
        disk.write(start, head)
        disk.write(start + 6, head)

        val fatHead = ByteArray(ss)
        fatHead.putLe32(0, 0x0FFFFFF8L)
        fatHead.putLe32(4, 0x0FFFFFFFL)
        fatHead.putLe32(8, 0x0FFFFFFFL)
        disk.write(start + reserved, fatHead)
        disk.write(start + reserved + fat, fatHead)

        if (labelBytes != null) {
            val root = ByteArray(ss)
            root.putBytes(0, labelBytes)
            root[11] = 0x08
            disk.write(start + dataStart, root)
        }
    }

    /** ASCII-only, upper case, at most 11 characters; null when nothing usable is left. */
    internal fun fatLabel(label: String): ByteArray? {
        val forbidden = "\"*+,./:;<=>?[\\]|"
        val cleaned = label.uppercase()
            .filter { it.code in 0x20..0x7E && it !in forbidden }
            .take(11)
            .trimEnd()
        if (cleaned.isEmpty()) return null
        return cleaned.padEnd(11, ' ').toByteArray(Charsets.US_ASCII)
    }
}
