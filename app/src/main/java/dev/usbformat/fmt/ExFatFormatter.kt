package dev.usbformat.fmt

import dev.usbformat.disk.Disk
import dev.usbformat.disk.MIB
import java.io.ByteArrayOutputStream

/** Quick exFAT formatter: boot regions, FAT, allocation bitmap, up-case table and an empty root directory. */
object ExFatFormatter {

    fun format(disk: Disk, region: Region, label: String) {
        val ss = disk.sectorSize
        val total = region.sectors
        val volBytes = total * ss
        require(volBytes >= 8L * MIB) { "The volume is too small for exFAT" }

        val clusterBytes = maxOf(
            ss,
            when {
                volBytes <= 256L * MIB -> 4096
                volBytes <= 32L * 1024 * MIB -> 32768
                else -> 131072
            },
        )
        val spc = clusterBytes / ss
        val spcShift = Integer.numberOfTrailingZeros(spc)
        val ssShift = Integer.numberOfTrailingZeros(ss)
        val align = (MIB / ss).toLong()
        val big = volBytes >= 256L * MIB

        val fatOffset = if (big) align else maxOf(24, 65536 / ss).toLong()
        val approxClusters = (total - fatOffset) / spc
        val fatLength = ((approxClusters + 2) * 4 + ss - 1) / ss
        val heapOffset = roundUp(fatOffset + fatLength, if (big) align else spc.toLong())
        val clusterCount = (total - heapOffset) / spc
        require(clusterCount in 16L..0xFFFFFFF5L) { "The volume size is not suitable for exFAT" }

        val bitmapBytes = (clusterCount + 7) / 8
        val bitmapClusters = (bitmapBytes + clusterBytes - 1) / clusterBytes
        val upcase = upcaseTable()
        val upcaseClusters = ((upcase.size + clusterBytes - 1) / clusterBytes).toLong()
        val bitmapCluster = 2L
        val upcaseCluster = bitmapCluster + bitmapClusters
        val rootCluster = upcaseCluster + upcaseClusters
        val used = bitmapClusters + upcaseClusters + 1
        require(used <= clusterCount) { "The volume is too small for exFAT" }

        val start = region.startLba
        fun clusterLba(cluster: Long): Long = start + heapOffset + (cluster - 2) * spc

        // --- Boot region: main boot sector, 8 extended sectors, OEM parameters, reserved, checksum sector.
        val boot = ByteArray(12 * ss)
        boot[0] = 0xEB.toByte(); boot[1] = 0x76; boot[2] = 0x90.toByte()
        boot.putAscii(3, "EXFAT   ")
        boot.putLe64(64, start)
        boot.putLe64(72, total)
        boot.putLe32(80, fatOffset)
        boot.putLe32(84, fatLength)
        boot.putLe32(88, heapOffset)
        boot.putLe32(92, clusterCount)
        boot.putLe32(96, rootCluster)
        boot.putLe32(100, System.nanoTime() and 0xFFFFFFFFL)
        boot.putLe16(104, 0x0100)
        boot[108] = ssShift.toByte()
        boot[109] = spcShift.toByte()
        boot[110] = 1
        boot[111] = 0x80.toByte()
        boot[112] = 0xFF.toByte()
        for (i in 120 until 510) boot[i] = 0xF4.toByte()
        boot[510] = 0x55
        boot[511] = 0xAA.toByte()
        for (s in 1..8) boot.putLe32(s * ss + ss - 4, 0xAA550000L)

        var sum = 0
        for (i in 0 until 11 * ss) {
            if (i == 106 || i == 107 || i == 112) continue // VolumeFlags and PercentInUse are excluded
            sum = Integer.rotateRight(sum, 1) + (boot[i].toInt() and 0xFF)
        }
        for (i in 0 until ss / 4) boot.putLe32(11 * ss + i * 4, sum.toLong() and 0xFFFFFFFFL)

        // --- FAT: media descriptor entries plus chains for the bitmap, the up-case table and the root directory.
        val fat = ByteArray(roundUp((rootCluster + 1) * 4, ss.toLong()).toInt())
        fat.putLe32(0, 0xFFFFFFF8L)
        fat.putLe32(4, 0xFFFFFFFFL)
        fun chain(first: Long, count: Long) {
            for (i in 0 until count) {
                val c = first + i
                fat.putLe32((c * 4).toInt(), if (i == count - 1) 0xFFFFFFFFL else c + 1)
            }
        }
        chain(bitmapCluster, bitmapClusters)
        chain(upcaseCluster, upcaseClusters)
        chain(rootCluster, 1L)

        // --- Allocation bitmap: the first `used` clusters are taken.
        val bitmap = ByteArray((bitmapClusters * clusterBytes).toInt())
        for (c in 0 until used.toInt()) {
            bitmap[c / 8] = (bitmap[c / 8].toInt() or (1 shl (c % 8))).toByte()
        }

        // --- Root directory: volume label, allocation bitmap and up-case table entries.
        val root = ByteArray(clusterBytes)
        val name = label.take(11)
        root[0] = 0x83.toByte()
        root[1] = name.length.toByte()
        root.putBytes(2, name.toByteArray(Charsets.UTF_16LE))
        root[32] = 0x81.toByte()
        root.putLe32(32 + 20, bitmapCluster)
        root.putLe64(32 + 24, bitmapBytes)
        root[64] = 0x82.toByte()
        root.putLe32(64 + 4, tableChecksum(upcase))
        root.putLe32(64 + 20, upcaseCluster)
        root.putLe64(64 + 24, upcase.size.toLong())

        // Everything before the cluster heap starts clean; then the structures go in.
        disk.zero(start, heapOffset)
        disk.write(start, boot)
        disk.write(start + 12, boot)
        disk.write(start + fatOffset, fat)
        disk.write(clusterLba(bitmapCluster), bitmap)
        disk.write(clusterLba(upcaseCluster), upcase.copyOf((upcaseClusters * clusterBytes).toInt()))
        disk.write(clusterLba(rootCluster), root)
    }

    /**
     * Compressed up-case table (0xFFFF, n = "next n characters map to themselves").
     * Covers ASCII, Latin-1 and basic Cyrillic including Ukrainian letters; it must span the whole 0..0xFFFF range.
     */
    internal fun upcaseTable(): ByteArray {
        val map = HashMap<Int, Int>()
        for (c in 0x61..0x7A) map[c] = c - 0x20
        for (c in 0xE0..0xFE) if (c != 0xF7) map[c] = c - 0x20
        map[0xFF] = 0x178
        for (c in 0x430..0x44F) map[c] = c - 0x20
        for (c in 0x450..0x45F) map[c] = c - 0x50
        map[0x491] = 0x490

        val out = ByteArrayOutputStream()
        fun put(v: Int) {
            out.write(v and 0xFF)
            out.write((v shr 8) and 0xFF)
        }

        var i = 0
        while (i < 0x10000) {
            val mapped = map[i]
            if (mapped != null) {
                put(mapped)
                i++
                continue
            }
            var j = i
            while (j < 0x10000 && !map.containsKey(j)) j++
            val run = j - i
            if (run >= 3) {
                put(0xFFFF)
                put(run)
                i = j
            } else {
                for (k in 0 until run) put(i + k)
                i = j
            }
        }
        return out.toByteArray()
    }

    internal fun tableChecksum(data: ByteArray): Long {
        var sum = 0
        for (b in data) sum = Integer.rotateRight(sum, 1) + (b.toInt() and 0xFF)
        return sum.toLong() and 0xFFFFFFFFL
    }
}
