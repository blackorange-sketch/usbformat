#!/usr/bin/env bash
# Runs real checkers on the images produced by the unit tests.
# Needs: util-linux (sfdisk), gdisk (sgdisk), dosfstools (fsck.fat), exfatprogs (fsck.exfat), ntfs-3g (ntfsinfo, ntfsfix, ...).
set -u
dir="${1:-app/build/test-images}"
rc=0
shopt -s nullglob

# Mounts an NTFS image with the kernel driver, writes files, unmounts, checks it and mounts it again to read them back.
mount_test() {
  local img="$1" mnt sum
  mnt=$(mktemp -d)
  echo "--- kernel mount test: $(basename "$img")"
  if ! sudo mount -t ntfs3 -o loop "$img" "$mnt"; then
    echo "mount failed"; sudo dmesg | tail -n 15; return 1
  fi
  sudo mkdir "$mnt/dir" && sudo sh -c "echo hello > '$mnt/hello.txt'" || { sudo umount "$mnt"; return 1; }
  sudo dd if=/dev/urandom of="$mnt/dir/big.bin" bs=1M count=3 status=none || { sudo umount "$mnt"; return 1; }
  sum=$(sudo sha256sum "$mnt/dir/big.bin" | cut -d' ' -f1)
  sudo ls -la "$mnt"
  sudo umount "$mnt" || return 1
  ntfsfix -n "$img" || return 1
  sudo mount -t ntfs3 -o loop "$img" "$mnt" || { echo "second mount failed"; return 1; }
  if [ "$(sudo sha256sum "$mnt/dir/big.bin" | cut -d' ' -f1)" != "$sum" ]; then
    echo "data read back differs"; sudo umount "$mnt"; return 1
  fi
  sudo umount "$mnt"
  rmdir "$mnt"
}

for img in "$dir"/*-512.img; do
  echo "=== $(basename "$img")"
  sfdisk -V "$img" || rc=1
  case "$img" in
    *gpt*)
      out=$(sgdisk -v "$img" 2>&1); echo "$out"
      echo "$out" | grep -q "No problems found" || rc=1
      ;;
  esac
done

# A volume made by mkntfs shows whether kernel mounts work on this machine at all, and is a reference for comparison.
mount_ok=0
control="$dir/control-mkntfs.img"
if command -v mkntfs >/dev/null 2>&1; then
  rm -f "$control"; truncate -s 126M "$control"; mkntfs -F -Q -q "$control" >/dev/null 2>&1
  if mount_test "$control"; then mount_ok=1; else echo "::warning::kernel NTFS mounts do not work here, so mount tests are skipped"; fi
fi

for part in "$dir"/*-512.part; do
  echo "=== $(basename "$part")"
  case "$part" in
    *fat32*) fsck.fat -nv "$part" || rc=1 ;;
    *exfat*) fsck.exfat -n "$part" || rc=1 ;;
    *ntfs*)
      ntfs-3g.probe --readwrite "$part" || { echo "ntfs-3g.probe failed with $?"; rc=1; }
      ntfsinfo -m "$part" || rc=1
      ntfsls -a -s -l "$part" || rc=1
      ntfsfix -n "$part" || rc=1
      if [ "$mount_ok" -eq 1 ]; then mount_test "$part" || rc=1; fi
      ;;
  esac
done

if [ "$rc" -eq 0 ]; then echo "All checks passed"; else echo "Some checks FAILED"; fi
exit $rc
