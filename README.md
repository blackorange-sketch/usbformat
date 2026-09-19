# USB Format

A minimalist Android app that formats a USB flash drive, a small Rufus-like tool for the phone.
No root needed: the drive is accessed directly through the Android USB Host API, with a small built-in SCSI-over-Bulk-Only-Transport layer (no third-party USB library).

## What it does (v0.1)

- Partition scheme: **MBR** or **GPT**, one partition, aligned to 1 MiB
- File system: **FAT32** (any size, not limited to 32 GiB) or **exFAT**
- Erase modes: **quick**, **full (zeros)**, **full + test** (writes a unique pattern to every sector and reads it back: finds bad blocks and fake-capacity drives)
- Shows what is on the selected drive: capacity, partition table (MBR / GPT / none) and the file system of each partition. It is read when the drive is picked and again after formatting, together with a "Before" line for comparison
- Runs in a foreground service with a wake lock, so a long erase survives the screen turning off
- UI languages: English, Ukrainian

Not yet: **NTFS** (planned: `mkntfs` from ntfs-3g through the NDK), writing ISO images, bad-block scan with several patterns.

## Layout

```
app/src/main/java/dev/usbformat/
  disk/Disk.kt            block device interface + FileDisk (tests / PC)
  fmt/Partitioner.kt      MBR and GPT
  fmt/Fat32Formatter.kt   FAT32
  fmt/ExFatFormatter.kt   exFAT
  fmt/Eraser.kt           zero pass and write/verify pass
  fmt/FormatJob.kt        the whole operation
  fmt/Inspector.kt        reads the partition table and file systems of a drive
  usb/ScsiDisk.kt         SCSI over Bulk-Only Transport as a Disk (plain JVM, tested with a fake drive)
  usb/UsbDisks.kt         Android USB Host transport for it
  FormatService.kt        foreground service
  MainActivity.kt         Compose UI
```

Everything under `fmt/` and `disk/` is plain Kotlin without Android APIs.

## Build

Push to GitHub: the **Build** workflow produces the APK (Actions tab, artifact `usbformat-debug-apk`).
Or open the folder in Android Studio and run `app`.

## Verification

`ImageGenerationTest` formats sparse image files for every scheme/file system combination (512 and 4096 byte sectors).
The workflow then runs `sfdisk -V`, `sgdisk -v`, `fsck.fat` and `fsck.exfat` on them (`tools/verify-images.sh`).
To try it locally: `gradle :app:testDebugUnitTest && bash tools/verify-images.sh`.

## Notes

- Everything on the drive is erased. The app asks for confirmation, but check the drive in the list.
- While the app owns the drive, Android unmounts it (the system may show an "unexpectedly removed" notice). Replug it after formatting.
- The drive's write cache is not flushed explicitly. Wait a few seconds after "Done" before unplugging.
- FAT32 labels are ASCII only; exFAT labels can be any Unicode text.
- Android 15 limits `dataSync` foreground services to 6 hours per day.
