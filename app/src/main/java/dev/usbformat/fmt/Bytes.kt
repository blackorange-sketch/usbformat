package dev.usbformat.fmt

import dev.usbformat.disk.Disk
import dev.usbformat.disk.MIB
import java.util.UUID

internal fun ByteArray.putLe16(offset: Int, value: Int) {
    this[offset] = value.toByte()
    this[offset + 1] = (value shr 8).toByte()
}

internal fun ByteArray.putLe32(offset: Int, value: Long) {
    for (i in 0 until 4) this[offset + i] = (value shr (8 * i)).toByte()
}

internal fun ByteArray.putLe64(offset: Int, value: Long) {
    for (i in 0 until 8) this[offset + i] = (value shr (8 * i)).toByte()
}

internal fun ByteArray.putAscii(offset: Int, text: String) {
    for (i in text.indices) this[offset + i] = text[i].code.toByte()
}

internal fun ByteArray.putBytes(offset: Int, src: ByteArray) {
    System.arraycopy(src, 0, this, offset, src.size)
}

internal fun roundUp(value: Long, unit: Long): Long = (value + unit - 1) / unit * unit

/** Overwrites [sectors] sectors starting at [lba] with zeros. */
internal fun Disk.zero(lba: Long, sectors: Long) {
    val chunk = maxOf(1, MIB / sectorSize)
    val zeros = ByteArray(chunk * sectorSize)
    var done = 0L
    while (done < sectors) {
        val n = minOf(chunk.toLong(), sectors - done).toInt()
        write(lba + done, zeros, n * sectorSize)
        done += n
    }
}

/** Random GUID in the mixed-endian on-disk layout used by GPT. */
internal fun randomGuid(): ByteArray {
    val u = UUID.randomUUID()
    val msb = u.mostSignificantBits
    val lsb = u.leastSignificantBits
    val g = ByteArray(16)
    g.putLe32(0, (msb ushr 32) and 0xFFFFFFFFL)
    g.putLe16(4, ((msb ushr 16) and 0xFFFF).toInt())
    g.putLe16(6, (msb and 0xFFFF).toInt())
    for (i in 0 until 8) g[8 + i] = (lsb ushr (56 - 8 * i)).toByte()
    return g
}
