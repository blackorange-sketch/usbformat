package dev.usbformat.fmt

import dev.usbformat.disk.Disk

data class Options(
    val scheme: Scheme,
    val fs: Fs,
    val label: String,
    val mode: EraseMode,
)

/** The whole operation in one place: optional full-drive pass, partition table, file system. */
object FormatJob {

    fun run(disk: Disk, options: Options, cancel: Cancel, progress: ProgressCallback): Region {
        when (options.mode) {
            EraseMode.QUICK -> Unit
            EraseMode.ZERO -> Eraser.zeroAll(disk, cancel, progress)
            EraseMode.VERIFY -> Eraser.verifyAll(disk, cancel, progress)
        }
        cancel.check()

        progress(Phase.PARTITIONING, 0, 0)
        val region = Partitioner.create(disk, options.scheme, options.fs)

        progress(Phase.FORMATTING, 0, 0)
        when (options.fs) {
            Fs.FAT32 -> Fat32Formatter.format(disk, region, options.label)
            Fs.EXFAT -> ExFatFormatter.format(disk, region, options.label)
        }
        disk.flush()
        return region
    }
}
