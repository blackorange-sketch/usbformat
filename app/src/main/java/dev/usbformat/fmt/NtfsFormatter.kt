package dev.usbformat.fmt

import dev.usbformat.disk.Disk
import dev.usbformat.disk.MIB
import dev.usbformat.log.AppLog
import java.io.ByteArrayOutputStream
import java.security.SecureRandom

/**
 * Quick NTFS 3.1 formatter. It writes the same set of system files that mkntfs and Windows create:
 * $MFT, $MFTMirr, $LogFile, $Volume, $AttrDef, the root directory, $Bitmap, $Boot, $BadClus, $Secure, $UpCase and $Extend.
 * Clusters are always 4 KiB, file records 1 KiB (or the sector size on 4 KiB-sector drives).
 *
 * The result is checked in CI with ntfs-3g's tools and by mounting it with the Linux kernel's NTFS driver.
 */
object NtfsFormatter {
    private const val CLUSTER = 4096
    private const val INDEX_BLOCK = 4096
    private const val SDS_MIRROR_OFFSET = 0x40000
    private const val MFT_RECORDS = 128
    private const val SYSTEM_RECORDS = 16

    // Owner SYSTEM, group Administrators, read/write for SYSTEM and Administrators: what mkntfs puts into the reserved records.
    private const val RESERVED_SECURITY_DESCRIPTOR =
        "01000480480000005400000000000000140000000200340002000000000014009f011200010100000000000512000000" +
            "00001800" + "9f011200" + "0102000000000005200000002002000001010000000000051200000001020000000000052000000020020000"

    private class Extent(val lcn: Long, val clusters: Long) {
        val bytes: Long get() = clusters * 4096 // NTFS clusters here are always 4 KiB
    }

    fun format(disk: Disk, region: Region, label: String) {
        val ss = disk.sectorSize
        require(ss == 512 || ss == 4096) { "Unsupported sector size for NTFS: $ss" }
        require(region.sectors * ss >= 16L * MIB) { "The volume is too small for NTFS (16 MiB minimum)" }

        val volumeSectors = region.sectors - 1 // the very last sector holds the backup boot sector
        val spc = CLUSTER / ss
        val nrClusters = volumeSectors / spc
        require(nrClusters <= 0xFFFFFFFFL) { "The volume is too large for NTFS with 4 KiB clusters" }

        val recordSize = maxOf(1024, ss)
        val now = ntfsNow()
        val serial = SecureRandom().nextLong()
        val volumeName = label.take(32)

        val attrDef = attrDefTable()
        val upcase = upcaseTable()
        val upcaseBytes = ByteArray(65536 * 2)
        for (i in upcase.indices) upcaseBytes.putLe16(i * 2, upcase[i])
        val security = buildSecurity()

        val bitmapBytes = roundUp((nrClusters + 7) / 8, 8L)
        val logBytes = (nrClusters * CLUSTER / 64).coerceIn(2L * MIB, 64L * MIB) / CLUSTER * CLUSTER

        // ---- where everything goes (one contiguous extent each)
        var cursor = 4L // $Boot takes clusters 0 and 1; the $MFT starts at 16 KiB like mkntfs does it
        fun alloc(bytes: Long): Extent {
            val e = Extent(cursor, (bytes + CLUSTER - 1) / CLUSTER)
            cursor += e.clusters
            return e
        }
        val bootExt = Extent(0, 2)
        val mftExt = alloc(MFT_RECORDS.toLong() * recordSize)
        val mftBitmapExt = alloc(CLUSTER.toLong())
        val mirrorExt = alloc(4L * recordSize)
        val attrDefExt = alloc(attrDef.size.toLong())
        val bitmapExt = alloc(bitmapBytes)
        val upcaseExt = alloc(upcaseBytes.size.toLong())
        val logExt = alloc(logBytes)
        val sdsExt = alloc(security.sds.size.toLong())
        val rootIndexExt = alloc(INDEX_BLOCK.toLong())
        val usedClusters = cursor
        require(usedClusters + 8 < nrClusters) { "The volume is too small for NTFS" }

        AppLog.log(
            "ntfs: clusters=$nrClusters used=$usedClusters mft@${mftExt.lcn}x${mftExt.clusters} " +
                "mirror@${mirrorExt.lcn} log=${logBytes / 1024} KiB record=$recordSize",
        )

        // ---- the sixteen system file records
        val mftSize = MFT_RECORDS.toLong() * recordSize
        val mftBitmapSize = roundUp((MFT_RECORDS + 7L) / 8, 8L)
        val rootRef = mftRef(5)
        val fnKeys = HashMap<Int, ByteArray>()

        fun fn(record: Int, name: String, allocated: Long, size: Long, directory: Boolean = false, extra: Long = 0): ByteArray {
            val key = fileName(rootRef, name, (if (directory) 0x10000006L else 0x06L) or extra, allocated, size, now)
            fnKeys[record] = key
            return key
        }

        val records = ArrayList<ByteArray>()

        // 0: $MFT
        records.add(
            Rec(0, recordSize, 0x01).apply {
                resident(0x10, "", stdInfo(now, 0x06, 0x100))
                resident(0x30, "", fn(0, "\$MFT", mftExt.bytes, mftSize), indexed = true)
                nonResident(0x80, "", runList(listOf(mftExt)), mftExt.clusters - 1, mftExt.bytes, mftSize, mftSize)
                nonResident(
                    0xB0, "", runList(listOf(mftBitmapExt)), mftBitmapExt.clusters - 1,
                    mftBitmapExt.bytes, mftBitmapSize, mftBitmapSize,
                )
            }.finish(),
        )

        // 1: $MFTMirr (a copy of the first four records)
        records.add(
            Rec(1, recordSize, 0x01).apply {
                resident(0x10, "", stdInfo(now, 0x06, 0x100))
                resident(0x30, "", fn(1, "\$MFTMirr", mirrorExt.bytes, 4L * recordSize), indexed = true)
                nonResident(
                    0x80, "", runList(listOf(mirrorExt)), mirrorExt.clusters - 1,
                    mirrorExt.bytes, 4L * recordSize, 4L * recordSize,
                )
            }.finish(),
        )

        // 2: $LogFile
        records.add(
            Rec(2, recordSize, 0x01).apply {
                resident(0x10, "", stdInfo(now, 0x06, 0x100))
                resident(0x30, "", fn(2, "\$LogFile", logExt.bytes, logBytes), indexed = true)
                nonResident(0x80, "", runList(listOf(logExt)), logExt.clusters - 1, logExt.bytes, logBytes, logBytes)
            }.finish(),
        )

        // 3: $Volume
        records.add(
            Rec(3, recordSize, 0x01).apply {
                resident(0x10, "", stdInfo(now, 0x06, 0x100))
                resident(0x30, "", fn(3, "\$Volume", 0, 0), indexed = true)
                if (volumeName.isNotEmpty()) resident(0x60, "", volumeName.toByteArray(Charsets.UTF_16LE))
                val info = ByteArray(12)
                info[8] = 3 // NTFS 3.1
                info[9] = 1
                resident(0x70, "", info)
                resident(0x80, "", ByteArray(0))
            }.finish(),
        )

        // 4: $AttrDef
        records.add(
            Rec(4, recordSize, 0x01).apply {
                resident(0x10, "", stdInfo(now, 0x06, 0x100))
                resident(0x30, "", fn(4, "\$AttrDef", attrDefExt.bytes, attrDef.size.toLong()), indexed = true)
                nonResident(
                    0x80, "", runList(listOf(attrDefExt)), attrDefExt.clusters - 1,
                    attrDefExt.bytes, attrDef.size.toLong(), attrDef.size.toLong(),
                )
            }.finish(),
        )

        // 5: the root directory. Its index does not fit into the record, so it has one index block of its own.
        val rootKey = fn(5, ".", INDEX_BLOCK.toLong(), INDEX_BLOCK.toLong(), directory = true, extra = 0x20L)

        // 6: $Bitmap
        records.add(ByteArray(0)) // placeholder for record 5, built below once all names are known
        records.add(
            Rec(6, recordSize, 0x01).apply {
                resident(0x10, "", stdInfo(now, 0x06, 0x100))
                resident(0x30, "", fn(6, "\$Bitmap", bitmapExt.bytes, bitmapBytes), indexed = true)
                nonResident(0x80, "", runList(listOf(bitmapExt)), bitmapExt.clusters - 1, bitmapExt.bytes, bitmapBytes, bitmapBytes)
            }.finish(),
        )

        // 7: $Boot
        records.add(
            Rec(7, recordSize, 0x01).apply {
                resident(0x10, "", stdInfo(now, 0x06, 0x100))
                resident(0x30, "", fn(7, "\$Boot", bootExt.bytes, bootExt.bytes), indexed = true)
                nonResident(0x80, "", runList(listOf(bootExt)), bootExt.clusters - 1, bootExt.bytes, bootExt.bytes, bootExt.bytes)
            }.finish(),
        )

        // 8: $BadClus, with the whole volume as one sparse "$Bad" stream (no bad clusters)
        records.add(
            Rec(8, recordSize, 0x01).apply {
                resident(0x10, "", stdInfo(now, 0x06, 0x100))
                resident(0x30, "", fn(8, "\$BadClus", 0, 0), indexed = true)
                resident(0x80, "", ByteArray(0))
                nonResident(
                    0x80, "\$Bad", runList(listOf(Extent(0, nrClusters)), sparse = true), nrClusters - 1,
                    nrClusters * CLUSTER, nrClusters * CLUSTER, 0,
                )
            }.finish(),
        )

        // 9: $Secure with its stream of security descriptors and the two indexes over it
        records.add(
            Rec(9, recordSize, 0x09).apply { // 0x08: view index present
                resident(0x10, "", stdInfo(now, 0x20000006, 0x100))
                resident(0x30, "", fn(9, "\$Secure", 0, 0, extra = 0x20000000L), indexed = true)
                nonResident(
                    0x80, "\$SDS", runList(listOf(sdsExt)), sdsExt.clusters - 1,
                    sdsExt.bytes, security.sds.size.toLong(), security.sds.size.toLong(),
                )
                resident(0x90, "\$SDH", indexRoot(0, 0x12, security.sdhEntries + endEntry(2), false))
                resident(0x90, "\$SII", indexRoot(0, 0x10, security.siiEntries + endEntry(2), false))
            }.finish(),
        )

        // 10: $UpCase
        records.add(
            Rec(10, recordSize, 0x01).apply {
                resident(0x10, "", stdInfo(now, 0x06, 0x100))
                resident(0x30, "", fn(10, "\$UpCase", upcaseExt.bytes, upcaseBytes.size.toLong()), indexed = true)
                nonResident(
                    0x80, "", runList(listOf(upcaseExt)), upcaseExt.clusters - 1,
                    upcaseExt.bytes, upcaseBytes.size.toLong(), upcaseBytes.size.toLong(),
                )
            }.finish(),
        )

        // 11: $Extend (empty directory)
        records.add(
            Rec(11, recordSize, 0x03).apply {
                resident(0x10, "", stdInfo(now, 0x06, 0x100))
                resident(0x30, "", fn(11, "\$Extend", 0, 0, directory = true), indexed = true)
                resident(0x90, "\$I30", indexRoot(0x30, 1, endEntry(2), false))
            }.finish(),
        )

        // 12..15: reserved, in use, without attributes
        for (n in 12 until SYSTEM_RECORDS) {
            records.add(
                Rec(n, recordSize, 0x01).apply {
                    resident(0x10, "", stdInfo(now, 0x06, 0).copyOf(48))
                    resident(0x50, "", hexBytes(RESERVED_SECURITY_DESCRIPTOR))
                    resident(0x80, "", ByteArray(0))
                }.finish(links = 0),
            )
        }

        // Now the root directory (record 5): every system file is listed in it, sorted the way NTFS sorts names.
        val listed = fnKeys.keys.sortedWith { a, b -> compareNames(nameOf(fnKeys.getValue(a)), nameOf(fnKeys.getValue(b)), upcase) }
        val rootEntries = ByteArrayOutputStream()
        for (n in listed) rootEntries.write(fileNameEntry(mftRef(n), fnKeys.getValue(n)))
        rootEntries.write(endEntry(2))
        val rootBlock = indexBlock(0, rootEntries.toByteArray())

        records[5] = Rec(5, recordSize, 0x03).apply {
            resident(0x10, "", stdInfo(now, 0x10000026, 0x101))
            resident(0x30, "", rootKey, indexed = true)
            resident(0x90, "\$I30", indexRoot(0x30, 1, endEntry(3, childVcn = 0), true))
            nonResident(
                0xA0, "\$I30", runList(listOf(rootIndexExt)), 0,
                rootIndexExt.bytes, INDEX_BLOCK.toLong(), INDEX_BLOCK.toLong(),
            )
            resident(0xB0, "\$I30", ByteArray(8).also { it[0] = 1 })
        }.finish()

        val mftBytes = ByteArray(mftSize.toInt())
        for ((i, r) in records.withIndex()) r.copyInto(mftBytes, i * recordSize)
        val mftBitmap = ByteArray(mftBitmapSize.toInt())
        for (i in 0 until SYSTEM_RECORDS) mftBitmap[i / 8] = (mftBitmap[i / 8].toInt() or (1 shl (i % 8))).toByte()

        // ---- boot sector
        val boot = ByteArray(ss)
        boot[0] = 0xEB.toByte(); boot[1] = 0x52; boot[2] = 0x90.toByte()
        boot.putAscii(3, "NTFS    ")
        boot.putLe16(0x0B, ss)
        boot[0x0D] = spc.toByte()
        boot[0x15] = 0xF8.toByte()
        boot.putLe16(0x18, 63)
        boot.putLe16(0x1A, 255)
        boot.putLe32(0x1C, minOf(region.startLba, 0xFFFFFFFFL))
        boot.putLe32(0x24, 0x00800080L)
        boot.putLe64(0x28, volumeSectors)
        boot.putLe64(0x30, mftExt.lcn)
        boot.putLe64(0x38, mirrorExt.lcn)
        boot[0x40] = if (recordSize >= CLUSTER) (recordSize / CLUSTER).toByte() else (-Integer.numberOfTrailingZeros(recordSize)).toByte()
        boot[0x44] = 1 // one 4 KiB cluster per index block
        boot.putLe64(0x48, serial)
        for (i in 0x54 until 0x1FE) boot[i] = 0xF4.toByte() // HLT: not a bootable volume
        boot[510] = 0x55
        boot[511] = 0xAA.toByte()
        val bootCluster = ByteArray(bootExt.bytes.toInt())
        boot.copyInto(bootCluster, 0)

        // ---- write everything. The boot sectors go last: until then the volume is not recognised as NTFS.
        val base = region.startLba
        fun lba(ext: Extent) = base + ext.lcn * spc
        fun put(ext: Extent, data: ByteArray) {
            val padded = if (data.size % CLUSTER == 0) data else data.copyOf(roundUp(data.size.toLong(), CLUSTER.toLong()).toInt())
            disk.write(lba(ext), padded)
        }

        put(mftExt, mftBytes)
        put(mftBitmapExt, mftBitmap)
        put(mirrorExt, mftBytes.copyOf(4 * recordSize))
        put(attrDefExt, attrDef)
        put(upcaseExt, upcaseBytes)
        put(sdsExt, security.sds)
        put(rootIndexExt, rootBlock)
        writeClusterBitmap(disk, lba(bitmapExt), bitmapExt.bytes, bitmapBytes, usedClusters, nrClusters)
        writeFill(disk, lba(logExt), logExt.bytes, 0xFF.toByte())

        put(bootExt, bootCluster)
        disk.write(base + region.sectors - 1, boot)
    }

    // ------------------------------------------------------------------ MFT records

    private class Rec(private val number: Int, private val size: Int, private val flags: Int) {
        private val buf = ByteArray(size)
        private val usaCount = size / 512 + 1
        private val firstAttr = roundUpInt(0x30 + usaCount * 2, 8)
        private var pos = firstAttr
        private var nextId = 0

        fun resident(type: Int, name: String, value: ByteArray, indexed: Boolean = false) {
            val nameBytes = name.toByteArray(Charsets.UTF_16LE)
            val valueOffset = roundUpInt(0x18 + nameBytes.size, 8)
            val length = roundUpInt(valueOffset + value.size, 8)
            val a = pos
            check(a + length + 8 <= size) { "MFT record $number is full" }
            buf.putLe32(a, type.toLong())
            buf.putLe32(a + 4, length.toLong())
            buf[a + 9] = name.length.toByte()
            buf.putLe16(a + 0xA, 0x18)
            buf.putLe16(a + 0xE, nextId++)
            buf.putLe32(a + 0x10, value.size.toLong())
            buf.putLe16(a + 0x14, valueOffset)
            buf[a + 0x16] = if (indexed) 1 else 0
            nameBytes.copyInto(buf, a + 0x18)
            value.copyInto(buf, a + valueOffset)
            pos += length
        }

        fun nonResident(
            type: Int,
            name: String,
            runs: ByteArray,
            lastVcn: Long,
            allocated: Long,
            dataSize: Long,
            initialized: Long,
            sparse: Boolean = false,
        ) {
            val nameBytes = name.toByteArray(Charsets.UTF_16LE)
            val headerSize = if (sparse) 0x48 else 0x40
            val mapOffset = roundUpInt(headerSize + nameBytes.size, 8)
            val length = roundUpInt(mapOffset + runs.size, 8)
            val a = pos
            check(a + length + 8 <= size) { "MFT record $number is full" }
            buf.putLe32(a, type.toLong())
            buf.putLe32(a + 4, length.toLong())
            buf[a + 8] = 1
            buf[a + 9] = name.length.toByte()
            buf.putLe16(a + 0xA, headerSize)
            buf.putLe16(a + 0xC, if (sparse) 0x8000 else 0)
            buf.putLe16(a + 0xE, nextId++)
            buf.putLe64(a + 0x10, 0)
            buf.putLe64(a + 0x18, lastVcn)
            buf.putLe16(a + 0x20, mapOffset)
            buf.putLe16(a + 0x22, if (sparse) 4 else 0)
            buf.putLe64(a + 0x28, allocated)
            buf.putLe64(a + 0x30, dataSize)
            buf.putLe64(a + 0x38, initialized)
            if (sparse) buf.putLe64(a + 0x40, 0)
            nameBytes.copyInto(buf, a + headerSize)
            runs.copyInto(buf, a + mapOffset)
            pos += length
        }

        fun finish(sequence: Int = number, links: Int = 1): ByteArray {
            check(pos + 8 <= size) { "MFT record $number is full" }
            buf.putLe32(pos, 0xFFFFFFFFL) // end of attributes
            pos += 8
            "FILE".toByteArray(Charsets.US_ASCII).copyInto(buf, 0)
            buf.putLe16(4, 0x30)
            buf.putLe16(6, usaCount)
            buf.putLe16(0x10, maxOf(1, sequence))
            buf.putLe16(0x12, links)
            buf.putLe16(0x14, firstAttr)
            buf.putLe16(0x16, flags)
            buf.putLe32(0x18, pos.toLong())
            buf.putLe32(0x1C, size.toLong())
            buf.putLe16(0x28, nextId)
            buf.putLe32(0x2C, number.toLong())
            applyFixups(buf, 0x30)
            return buf
        }
    }

    private fun mftRef(record: Int): Long = (maxOf(1, record).toLong() shl 48) or record.toLong()

    private fun stdInfo(time: Long, attributes: Int, securityId: Int): ByteArray {
        val v = ByteArray(72)
        for (i in 0 until 4) v.putLe64(i * 8, time)
        v.putLe32(32, attributes.toLong())
        v.putLe32(52, securityId.toLong())
        return v
    }

    private fun fileName(parent: Long, name: String, flags: Long, allocated: Long, size: Long, time: Long): ByteArray {
        val nameBytes = name.toByteArray(Charsets.UTF_16LE)
        val v = ByteArray(0x42 + nameBytes.size)
        v.putLe64(0, parent)
        for (i in 0 until 4) v.putLe64(8 + i * 8, time)
        v.putLe64(0x28, allocated)
        v.putLe64(0x30, size)
        v.putLe32(0x38, flags)
        v[0x40] = name.length.toByte()
        v[0x41] = 3 // Win32 and DOS name
        nameBytes.copyInto(v, 0x42)
        return v
    }

    private fun nameOf(fileName: ByteArray): String {
        val n = fileName[0x40].toInt() and 0xFF
        return String(fileName, 0x42, n * 2, Charsets.UTF_16LE)
    }

    private fun compareNames(a: String, b: String, upcase: IntArray): Int {
        val n = minOf(a.length, b.length)
        for (i in 0 until n) {
            val d = upcase[a[i].code] - upcase[b[i].code]
            if (d != 0) return d
        }
        return a.length - b.length
    }

    // ------------------------------------------------------------------ indexes

    private fun fileNameEntry(ref: Long, key: ByteArray): ByteArray {
        val length = roundUpInt(0x10 + key.size, 8)
        val e = ByteArray(length)
        e.putLe64(0, ref)
        e.putLe16(8, length)
        e.putLe16(0xA, key.size)
        key.copyInto(e, 0x10)
        return e
    }

    /** Entry of a "view" index (used by $Secure): a key and a data part. */
    private fun viewEntry(key: ByteArray, data: ByteArray): ByteArray {
        val dataOffset = 0x10 + key.size
        val length = roundUpInt(dataOffset + data.size, 8)
        val e = ByteArray(length)
        e.putLe16(0, dataOffset)
        e.putLe16(2, data.size)
        e.putLe16(8, length)
        e.putLe16(0xA, key.size)
        key.copyInto(e, 0x10)
        data.copyInto(e, dataOffset)
        return e
    }

    /** The last entry of every index node. Flag 1 = has a child node, 2 = last entry. */
    private fun endEntry(flags: Int, childVcn: Long = -1): ByteArray {
        val length = if (childVcn >= 0) 0x18 else 0x10
        val e = ByteArray(length)
        e.putLe16(8, length)
        e.putLe16(0xC, flags)
        if (childVcn >= 0) e.putLe64(0x10, childVcn)
        return e
    }

    private fun indexRoot(attributeType: Long, collation: Long, entries: ByteArray, large: Boolean): ByteArray {
        val v = ByteArray(0x20 + entries.size)
        v.putLe32(0, attributeType)
        v.putLe32(4, collation)
        v.putLe32(8, INDEX_BLOCK.toLong())
        v[0xC] = 1
        v.putLe32(0x10, 0x10)
        v.putLe32(0x14, (0x10 + entries.size).toLong())
        v.putLe32(0x18, (0x10 + entries.size).toLong())
        v[0x1C] = if (large) 1 else 0
        entries.copyInto(v, 0x20)
        return v
    }

    private fun indexBlock(vcn: Long, entries: ByteArray): ByteArray {
        val b = ByteArray(INDEX_BLOCK)
        val usaCount = INDEX_BLOCK / 512 + 1
        "INDX".toByteArray(Charsets.US_ASCII).copyInto(b, 0)
        b.putLe16(4, 0x28)
        b.putLe16(6, usaCount)
        b.putLe64(0x10, vcn)
        b.putLe32(0x18, 0x28) // entries start at 0x40
        b.putLe32(0x1C, (0x28 + entries.size).toLong())
        b.putLe32(0x20, (INDEX_BLOCK - 0x18).toLong())
        entries.copyInto(b, 0x40)
        applyFixups(b, 0x28)
        return b
    }

    // ------------------------------------------------------------------ mapping pairs

    private fun signedBytes(v: Long): Int {
        var n = 1
        while (n < 8) {
            val limit = 1L shl (8 * n - 1)
            if (v >= -limit && v < limit) return n
            n++
        }
        return 8
    }

    private fun writeLe(out: ByteArrayOutputStream, v: Long, n: Int) {
        for (i in 0 until n) out.write(((v shr (8 * i)) and 0xFF).toInt())
    }

    private fun runList(extents: List<Extent>, sparse: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream()
        var previous = 0L
        for (e in extents) {
            val lengthBytes = signedBytes(e.clusters)
            if (sparse) {
                out.write(lengthBytes)
                writeLe(out, e.clusters, lengthBytes)
            } else {
                val delta = e.lcn - previous
                val offsetBytes = signedBytes(delta)
                out.write((offsetBytes shl 4) or lengthBytes)
                writeLe(out, e.clusters, lengthBytes)
                writeLe(out, delta, offsetBytes)
                previous = e.lcn
            }
        }
        out.write(0)
        return out.toByteArray()
    }

    // ------------------------------------------------------------------ $AttrDef, $UpCase

    private fun attrDefTable(): ByteArray {
        class Def(val name: String, val type: Int, val collation: Int, val flags: Int, val min: Long, val max: Long)

        val all = listOf(
            Def("\$STANDARD_INFORMATION", 0x10, 0, 0x40, 0x30, 0x48),
            Def("\$ATTRIBUTE_LIST", 0x20, 0, 0x80, 0, -1),
            Def("\$FILE_NAME", 0x30, 1, 0x42, 0x44, 0x242),
            Def("\$OBJECT_ID", 0x40, 0, 0x40, 0, 0x100),
            Def("\$SECURITY_DESCRIPTOR", 0x50, 0, 0x00, 0, -1),
            Def("\$VOLUME_NAME", 0x60, 0, 0x40, 0x02, 0x100),
            Def("\$VOLUME_INFORMATION", 0x70, 0, 0x40, 0x0C, 0x0C),
            Def("\$DATA", 0x80, 0, 0x00, 0, -1),
            Def("\$INDEX_ROOT", 0x90, 0, 0x40, 0, -1),
            Def("\$INDEX_ALLOCATION", 0xA0, 0, 0x80, 0, -1),
            Def("\$BITMAP", 0xB0, 0, 0x80, 0, -1),
            Def("\$REPARSE_POINT", 0xC0, 0, 0x80, 0, 0x4000),
            Def("\$EA_INFORMATION", 0xD0, 0, 0x40, 8, 8),
            Def("\$EA", 0xE0, 0, 0x00, 0, 0x10000),
            Def("\$PROPERTY_SET", 0xF0, 0, 0x40, 0, -1),
            Def("\$LOGGED_UTILITY_STREAM", 0x100, 0, 0x80, 0, 0x10000),
        )
        val out = ByteArray(all.size * 160)
        for ((i, d) in all.withIndex()) {
            val o = i * 160
            d.name.toByteArray(Charsets.UTF_16LE).copyInto(out, o)
            out.putLe32(o + 128, d.type.toLong())
            out.putLe32(o + 136, d.collation.toLong())
            out.putLe32(o + 140, d.flags.toLong())
            out.putLe64(o + 144, d.min)
            out.putLe64(o + 152, d.max)
        }
        return out
    }

    /** Simple one-to-one upper-casing of every UTF-16 code unit; NTFS uses it to compare file names. */
    private fun upcaseTable(): IntArray {
        val table = IntArray(65536) { it }
        for (c in 0 until 65536) {
            if (c in 0xD800..0xDFFF) continue
            val upper = Character.toUpperCase(c)
            if (upper in 0..0xFFFF && upper !in 0xD800..0xDFFF) table[c] = upper
        }
        return table
    }

    // ------------------------------------------------------------------ $Secure

    private class SecurityStream(val sds: ByteArray, val sdhEntries: ByteArray, val siiEntries: ByteArray)

    private fun sid(authority: Int, vararg subAuthorities: Long): ByteArray {
        val s = ByteArray(8 + 4 * subAuthorities.size)
        s[0] = 1
        s[1] = subAuthorities.size.toByte()
        s[7] = authority.toByte()
        for ((i, v) in subAuthorities.withIndex()) s.putLe32(8 + 4 * i, v)
        return s
    }

    private fun ace(flags: Int, mask: Long, sid: ByteArray): ByteArray {
        val a = ByteArray(8 + sid.size)
        a[1] = flags.toByte()
        a.putLe16(2, a.size)
        a.putLe32(4, mask)
        sid.copyInto(a, 8)
        return a
    }

    private fun securityDescriptor(owner: ByteArray, group: ByteArray, aces: List<ByteArray>): ByteArray {
        val aclSize = 8 + aces.sumOf { it.size }
        val daclOffset = 20
        val ownerOffset = daclOffset + aclSize
        val groupOffset = ownerOffset + owner.size
        val b = ByteArray(groupOffset + group.size)
        b[0] = 1
        b.putLe16(2, 0x8004) // self-relative, DACL present
        b.putLe32(4, ownerOffset.toLong())
        b.putLe32(8, groupOffset.toLong())
        b.putLe32(16, daclOffset.toLong())
        b[daclOffset] = 2
        b.putLe16(daclOffset + 2, aclSize)
        b.putLe16(daclOffset + 4, aces.size)
        var p = daclOffset + 8
        for (a in aces) {
            a.copyInto(b, p)
            p += a.size
        }
        owner.copyInto(b, ownerOffset)
        group.copyInto(b, groupOffset)
        return b
    }

    private fun securityHash(sd: ByteArray): Long {
        var h = 0L
        var i = 0
        while (i + 4 <= sd.size) {
            var w = 0L
            for (k in 3 downTo 0) w = (w shl 8) or (sd[i + k].toLong() and 0xFF)
            h = ((((h shl 3) or (h ushr 29)) and 0xFFFFFFFFL) + w) and 0xFFFFFFFFL
            i += 4
        }
        return h
    }

    private fun buildSecurity(): SecurityStream {
        val system = sid(5, 18)
        val administrators = sid(5, 32, 544)
        val users = sid(5, 32, 545)
        val authenticated = sid(5, 11)
        val fullControl = 0x001F01FFL
        val modify = 0x001301BFL
        val readExecute = 0x001200A9L
        val inherit = 0x03 // this folder, subfolders and files

        // 0x100: system files. 0x101: the root of the volume, inherited by everything created later.
        val descriptors = listOf(
            0x100 to securityDescriptor(
                administrators, system,
                listOf(ace(0, fullControl, system), ace(0, fullControl, administrators)),
            ),
            0x101 to securityDescriptor(
                administrators, system,
                listOf(
                    ace(inherit, fullControl, system),
                    ace(inherit, fullControl, administrators),
                    ace(inherit, modify, authenticated),
                    ace(inherit, readExecute, users),
                ),
            ),
        )

        val sds = ByteArray(SDS_MIRROR_OFFSET + 1024)
        class Placed(val hash: Long, val id: Int, val offset: Int, val length: Int)
        val placed = ArrayList<Placed>()
        var offset = 0
        for ((id, sd) in descriptors) {
            val hash = securityHash(sd)
            val length = 20 + sd.size
            sds.putLe32(offset, hash)
            sds.putLe32(offset + 4, id.toLong())
            sds.putLe64(offset + 8, offset.toLong())
            sds.putLe32(offset + 16, length.toLong())
            sd.copyInto(sds, offset + 20)
            placed.add(Placed(hash, id, offset, length))
            offset = roundUpInt(offset + length, 16)
        }
        // The stream is mirrored 256 KiB further on.
        sds.copyInto(sds, SDS_MIRROR_OFFSET, 0, offset)
        val stream = sds.copyOf(SDS_MIRROR_OFFSET + offset)

        fun header(p: Placed): ByteArray {
            val h = ByteArray(20)
            h.putLe32(0, p.hash)
            h.putLe32(4, p.id.toLong())
            h.putLe64(8, p.offset.toLong())
            h.putLe32(16, p.length.toLong())
            return h
        }

        val sdh = ByteArrayOutputStream()
        for (p in placed.sortedWith(compareBy<Placed>({ it.hash }, { it.id }))) {
            val key = ByteArray(8)
            key.putLe32(0, p.hash)
            key.putLe32(4, p.id.toLong())
            val data = ByteArray(24)
            header(p).copyInto(data, 0)
            data[20] = 0x49 // "II"
            data[22] = 0x49
            sdh.write(viewEntry(key, data))
        }
        val sii = ByteArrayOutputStream()
        for (p in placed.sortedBy { it.id }) {
            val key = ByteArray(4)
            key.putLe32(0, p.id.toLong())
            sii.write(viewEntry(key, header(p)))
        }
        return SecurityStream(stream, sdh.toByteArray(), sii.toByteArray())
    }

    // ------------------------------------------------------------------ big writes

    /** $Bitmap: one bit per cluster; the metadata clusters are used, and so are the bits past the end of the volume. */
    private fun writeClusterBitmap(disk: Disk, lba: Long, extentBytes: Long, bitmapBytes: Long, used: Long, clusters: Long) {
        val chunk = ByteArray(MIB)
        var written = 0L
        while (written < extentBytes) {
            val n = minOf(chunk.size.toLong(), extentBytes - written).toInt()
            for (i in 0 until n) {
                val byteIndex = written + i
                var v = 0
                if (byteIndex < bitmapBytes) {
                    val first = byteIndex * 8
                    v = when {
                        first + 8 <= used -> 0xFF
                        first >= clusters -> 0xFF
                        first >= used && first + 8 <= clusters -> 0
                        else -> {
                            var bits = 0
                            for (bit in 0 until 8) {
                                val cluster = first + bit
                                if (cluster < used || cluster >= clusters) bits = bits or (1 shl bit)
                            }
                            bits
                        }
                    }
                }
                chunk[i] = v.toByte()
            }
            disk.write(lba + written / disk.sectorSize, chunk, n)
            written += n
        }
    }

    private fun writeFill(disk: Disk, lba: Long, bytes: Long, value: Byte) {
        val chunk = ByteArray(MIB)
        chunk.fill(value)
        var written = 0L
        while (written < bytes) {
            val n = minOf(chunk.size.toLong(), bytes - written).toInt()
            disk.write(lba + written / disk.sectorSize, chunk, n)
            written += n
        }
    }

    private fun ntfsNow(): Long = (System.currentTimeMillis() + 11644473600000L) * 10000L
}

/** Every 512-byte stride ends with the update sequence number; the original bytes are kept in the array. */
private fun applyFixups(buf: ByteArray, usaOffset: Int) {
    val strides = buf.size / 512
    val usn = 1
    buf.putLe16(usaOffset, usn)
    for (i in 0 until strides) {
        val end = (i + 1) * 512
        buf[usaOffset + 2 + 2 * i] = buf[end - 2]
        buf[usaOffset + 3 + 2 * i] = buf[end - 1]
        buf[end - 2] = usn.toByte()
        buf[end - 1] = (usn shr 8).toByte()
    }
}

private fun roundUpInt(value: Int, unit: Int): Int = (value + unit - 1) / unit * unit

private fun hexBytes(hex: String): ByteArray = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
