# Roadmap

Ideas and decisions for the future, in the order they are meant to be done. Nothing here is promised; it is a plan.

## Where the project stands

- Formatting to MBR/GPT with FAT32, exFAT or NTFS (own implementations, checked in CI with `fsck`, ntfs-3g and the Linux kernel driver).
- Quick, full (zeros) and full + test erase modes, drive info before and after, copyable on-screen log.
- Own USB layer (SCSI over Bulk-Only Transport), no third-party USB library.
- Signed releases built by GitHub Actions when a tag is pushed.
- Not verified yet: an NTFS drive formatted by the app on a real Windows PC (`chkdsk`).

## Next steps

1. ~~"Report a problem" instead of the open log panel in release builds.~~ **Done.** The log keeps collecting in
   memory only. A *Report a problem* section builds one text (app version, Android version, phone model, drive info, log) and
   opens the share sheet, plus a separate *Copy*. The raw log panel stays on in debug builds, and in release builds testers can
   turn it on by tapping the hint seven times (remembered from then on).
2. **Testing on many phones and drives.** The app erases data, so this comes before any wide publication.
   Ask testers to send the log; write down which drives and phones work in a compatibility list.
3. **Write ISO images (DD mode).**
   - Hybrid Linux ISOs (Ubuntu, Debian, Fedora, Arch, Mint, ...) can be copied byte by byte and then boot in BIOS and UEFI.
   - Pick the file with the system file dialog, show progress and speed, verify by reading back and comparing checksums.
   - Recognise ISO 9660 in the drive info, so that it is visible that the drive holds an image.
   - Warn about images that are not hybrid.
   - **Windows installer images are not supported** and the store description must say so. They need a FAT32/NTFS boot drive with the
     image unpacked, a way around the 4 GiB limit of `install.wim`, and boot code for BIOS, which is a much bigger job and cannot be
     verified without real PCs. A possible later path is installing Ventoy-style multi-boot (GPL components, separate decision).

## Distribution

- **GitHub releases:** already in place (signed APK + SHA-256).
- **F-Droid / IzzyOnDroid:** fits an open source app with no network access; needs no paid account.
- **Google Play:** check the current requirements in Play Console before starting. Expected work: developer account with identity
  verification; closed testing with a number of testers for a period of time before production (new personal accounts);
  Android App Bundle instead of APK; Play App Signing; privacy policy (the app collects nothing); Data safety form; declaration of
  the `dataSync` foreground service; a proper icon, screenshots and store texts in several languages (have translations reviewed by
  native speakers). The Play build is signed with a different key than the GitHub build, so users cannot update from one to the other.

## Free and Pro (idea)

Principles:

- The core stays free for everybody: formatting to FAT32, exFAT and NTFS, drive info, erase modes, report a problem. NTFS is already
  released for free, so it is not taken away.
- No ads. No nagging. The code is open (MIT); "Pro" is a way to support the development, not a lock.

Candidates for Pro:

- Writing ISO images and verifying them.
- Colour themes, a "Pro" mark and one polite thank-you message after buying (plus a line in the About screen).
- Later, if wanted: custom partition and cluster sizes, several test passes, saved presets.

How to build it:

- One code base and one switch: an `Entitlements` interface with `isPro()`.
- The Play build asks Google Play Billing (one-time purchase). GitHub and F-Droid builds always return `true`.
- Check the current Play Billing fees, taxes and seller registration for your country in Play Console before deciding on prices.
- Be honest in the store text about what an ISO writer can and cannot do (see above), to avoid refund requests.

## Open questions

- Which drives and phones fail? (Comes from testers.)
- Is a Windows check of NTFS possible (a friend's PC)?
- Icon and store graphics.
- More interface languages.
