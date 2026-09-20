package dev.usbformat.fmt

import dev.usbformat.disk.Disk
import dev.usbformat.log.AppLog

data class Options(
    val scheme: Scheme,
    val fs: Fs,
    val label: String,
    val mode: EraseMode,
)

/** The whole operation in one place: optional full-drive pass, partition table, file system. */
object FormatJob {

    fun run(disk: Disk, options: Options, cancel: Cancel, progress: ProgressCallback): Region {
        AppLog.log(
            "format: scheme=${options.scheme} fs=${options.fs} erase=${options.mode} label='${options.label}' " +
                "disk=${disk.sectorCount} sectors of ${disk.sectorSize} bytes",
        )
        when (options.mode) {
            EraseMode.QUICK -> Unit
            EraseMode.ZERO -> Eraser.zeroAll(disk, cancel, progress)
            EraseMode.VERIFY -> Eraser.verifyAll(disk, cancel, progress)
        }
        cancel.check()

        progress(Phase.PARTITIONING, 0, 0)
        AppLog.log("writing the partition table")
        val region = Partitioner.create(disk, options.scheme, options.fs)
        AppLog.log("partition: start=${region.startLba} sectors=${region.sectors}")

        progress(Phase.FORMATTING, 0, 0)
        when (options.fs) {
            Fs.FAT32 -> Fat32Formatter.format(disk, region, options.label)
            Fs.EXFAT -> ExFatFormatter.format(disk, region, options.label)
            Fs.NTFS -> NtfsFormatter.format(disk, region, options.label)
        }
        if (region.firstSector.isNotEmpty()) {
            AppLog.log("writing sector 0")
            disk.write(0, region.firstSector)
        }
        AppLog.log("flushing")
        disk.flush()
        AppLog.log("format finished")
        return region
    }
}
