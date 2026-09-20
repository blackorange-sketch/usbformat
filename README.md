# USB Format

A minimalist Android app that formats a USB flash drive, a small Rufus-like tool for the phone.
No root needed: the drive is accessed directly through the Android USB Host API, with a small built-in
SCSI-over-Bulk-Only-Transport layer (no third-party USB library).

[Українська версія](README.uk.md)

## Install

Download the signed APK from the **Releases** page and open it on the phone (allow installing from this source when asked).
The phone must support USB OTG, and you need an OTG cable or adapter.

## How to use

1. **Open the app first.**
2. Plug the drive in. Android asks for access to it: allow it.
3. Pick the partition scheme (MBR / GPT), the file system (FAT32 / exFAT), the erase mode and, if you like, a label.
4. Tap **Format** and confirm. **Everything on the drive is erased.**
5. When it says *Done*, tap **Release drive** (or unplug the drive) so that Android mounts it again.

> Plug the drive in while the app is open. If it was already plugged in when you opened the app, Android has probably
> mounted it already and the app cannot take it over reliably: unplug it and plug it in again with the app open.

The **Log** section at the bottom records what the app does with the drive. If something goes wrong, tap **Copy log**
and attach it to the bug report.

## What it does

- Partition scheme: **MBR** or **GPT**, one partition, aligned to 1 MiB
- File system: **FAT32** (any size, not limited to 32 GiB), **exFAT** or **NTFS**
- Erase modes: **quick**, **full (zeros)**, **full + test** (writes a unique pattern to every sector and reads it back:
  finds bad blocks and fake-capacity drives)
- Shows what is on the selected drive: capacity, partition table (MBR / GPT / none) and the file system of each partition,
  before and after formatting
- Runs in a foreground service with a wake lock, so a long erase survives the screen turning off
- Languages: English, Ukrainian

Not yet: writing ISO images, a bad-block scan with several patterns.

NTFS is written by the app itself (4 KiB clusters, NTFS 3.1). It is checked in CI with ntfs-3g's tools and by mounting it
with the Linux kernel's NTFS driver, but it is a newer part of the app than FAT32 and exFAT: check a drive on Windows
(`chkdsk`) before trusting it with important data.

## Building

Push to GitHub: the **Build** workflow produces a debug APK (Actions tab, artifact `usbformat-debug-apk`).
Or open the folder in Android Studio and run `app`.

### Verification

`ImageGenerationTest` formats sparse image files for every scheme/file system combination (512 and 4096 byte sectors),
and `ScsiDiskTest` runs the whole USB layer against an in-memory fake drive.
The workflow then runs `sfdisk -V`, `sgdisk -v`, `fsck.fat`, `fsck.exfat` and, for NTFS, `ntfs-3g.probe`, `ntfsinfo`, `ntfsfix`
and a mount with the kernel driver (write files, unmount, mount again, compare) on the images (`tools/verify-images.sh`).
Locally: `gradle :app:testDebugUnitTest && bash tools/verify-images.sh`.

## Releasing

Releases are signed APKs published by the **Release** workflow when a version tag is pushed.
The signing key is created once and stored in GitHub secrets, never in the repository.

```
# once: create the key (keep usbformat.jks somewhere private and back it up: losing it means users must uninstall to update)
pkg install openjdk-17                       # Termux
keytool -genkeypair -keystore usbformat.jks -alias usbformat \
  -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=USB Format"

# once: store it as repository secrets (run inside the repository)
base64 -w0 usbformat.jks > usbformat.b64
gh secret set KEYSTORE_BASE64 < usbformat.b64
gh secret set KEYSTORE_PASSWORD                # the password you gave keytool
gh secret set KEY_PASSWORD                     # the same password
gh secret set KEY_ALIAS --body usbformat
rm usbformat.b64

# for every release
git tag v0.1.0 && git push origin v0.1.0
```

The workflow runs the tests and the file system checks, builds and signs the APK, and attaches it, together with its
SHA-256, to a new GitHub release.

## Layout

```
app/src/main/java/dev/usbformat/
  disk/Disk.kt            block device interface + FileDisk (tests / PC), LoggingDisk
  fmt/Partitioner.kt      MBR and GPT
  fmt/Fat32Formatter.kt   FAT32
  fmt/ExFatFormatter.kt   exFAT
  fmt/NtfsFormatter.kt    NTFS
  fmt/Eraser.kt           zero pass and write/verify pass
  fmt/Inspector.kt        reads the partition table and file systems of a drive
  fmt/FormatJob.kt        the whole operation
  usb/ScsiDisk.kt         SCSI over Bulk-Only Transport as a Disk (plain JVM, tested with a fake drive)
  usb/UsbDisks.kt         Android USB Host transport for it
  usb/UsbSession.kt       keeps one drive open while the app works with it
  log/AppLog.kt           on-screen, copyable log
  FormatService.kt        foreground service
  MainActivity.kt         Compose UI
```

Everything under `fmt/`, `disk/` and `usb/ScsiDisk.kt` is plain Kotlin without Android APIs.

## Notes

- The app opens the drive once and keeps it until you tap **Release drive**, unplug it or close the app. While it is held,
  Android does not mount it (the system may show an "unexpectedly removed" notice). Closing and re-opening the drive in
  quick succession, or taking over a drive that Android is already reading, can leave some drives unresponsive.
- USB transfers start small (512 bytes per command) and double after every success. After a failure the size goes back
  to the last one that worked. A TEST UNIT READY keep-alive runs every 1.5 s while the drive is held.
- FAT32 labels are ASCII only; exFAT labels can be any Unicode text.
- Android 15 limits `dataSync` foreground services to 6 hours per day.

## License

[MIT](LICENSE).
