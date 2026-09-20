#!/usr/bin/env bash
# Runs real checkers on the images produced by the unit tests.
# Needs: util-linux (sfdisk), gdisk (sgdisk), dosfstools (fsck.fat), exfatprogs (fsck.exfat), ntfs-3g (ntfsinfo, ntfsfix, ntfscp, ...).
set -u
dir="${1:-app/build/test-images}"
rc=0
shopt -s nullglob

# Copies a file into an NTFS image with ntfs-3g's own write code, reads it back and runs a consistency check.
# Works everywhere, no mounting needed. This exercises allocation of MFT records and clusters and the root directory index.
ntfscp_test() {
  local img="$1" src sum back
  src=$(mktemp)
  head -c 3000000 /dev/urandom > "$src"
  sum=$(sha256sum "$src" | cut -d' ' -f1)
  echo "--- ntfscp write test: $(basename "$img")"
  ntfscp -f "$img" "$src" big.bin || { rm -f "$src"; return 1; }
  echo hello | ntfscp -f "$img" /dev/stdin hello.txt || { rm -f "$src"; return 1; }
  back=$(ntfscat "$img" big.bin | sha256sum | cut -d' ' -f1)
  rm -f "$src"
  if [ "$back" != "$sum" ]; then echo "data read back differs"; return 1; fi
  ntfsls -l "$img" || return 1
  ntfsfix -n "$img" || return 1
}

# Mounts an NTFS image, writes files, unmounts, checks it and mounts it again to read them back.
# $2 is "kernel" (ntfs3 driver) or "fuse" (ntfs-3g).
mount_test() {
  local img="$1" kind="$2" mnt sum
  mnt=$(mktemp -d)
  echo "--- $kind mount test: $(basename "$img")"
  mount_it() {
    if [ "$kind" = kernel ]; then sudo mount -t ntfs3 -o loop "$img" "$mnt"; else sudo ntfs-3g "$img" "$mnt"; fi
  }
  show_kernel_messages() { echo "--- kernel messages about NTFS:"; sudo dmesg | grep -i ntfs | tail -n 30; }
  mount_it || { echo "mount failed"; show_kernel_messages; return 1; }
  sudo mkdir "$mnt/dir_$kind" && sudo sh -c "echo hello > '$mnt/hello_$kind.txt'" || { sudo umount "$mnt"; return 1; }
  sudo dd if=/dev/urandom of="$mnt/dir_$kind/big.bin" bs=1M count=3 status=none || { sudo umount "$mnt"; return 1; }
  sum=$(sudo sha256sum "$mnt/dir_$kind/big.bin" | cut -d' ' -f1)
  sudo ls -la "$mnt"
  sudo umount "$mnt" || return 1
  ntfsfix -n "$img" || return 1
  mount_it || { echo "second mount failed"; show_kernel_messages; return 1; }
  if [ "$(sudo sha256sum "$mnt/dir_$kind/big.bin" | cut -d' ' -f1)" != "$sum" ]; then
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

# A volume made by mkntfs is the reference: it shows which of the tests can work on this machine at all.
declare -A can_mount=([kernel]=0 [fuse]=0)
control="$dir/control-mkntfs.img"
if command -v mkntfs >/dev/null 2>&1; then
  rm -f "$control"; truncate -s 126M "$control"; mkntfs -F -Q -q "$control" >/dev/null 2>&1
  echo "=== reference volume made by mkntfs"
  ntfscp_test "$control" || echo "::warning::the ntfscp test fails even on a volume made by mkntfs"
  ntfsinfo -m "$control" | head -n 40
  for kind in kernel fuse; do
    if mount_test "$control" "$kind"; then can_mount[$kind]=1; else echo "::warning::$kind NTFS mounts do not work here, those tests are skipped"; fi
  done
fi

for part in "$dir"/*-512.part; do
  echo "=== $(basename "$part")"
  case "$part" in
    *fat32*) fsck.fat -nv "$part" || rc=1 ;;
    *exfat*) fsck.exfat -n "$part" || rc=1 ;;
    *ntfs*)
      ntfs-3g.probe --readwrite "$part" || { echo "ntfs-3g.probe failed with $?"; rc=1; }
      ntfsinfo -m "$part" | head -n 40
      ntfsls -a -s -l "$part" || rc=1
      ntfsfix -n "$part" || rc=1
      ntfscp_test "$part" || rc=1
      for kind in kernel fuse; do
        if [ "${can_mount[$kind]}" -eq 1 ]; then mount_test "$part" "$kind" || rc=1; fi
      done
      ;;
  esac
done

# Files for looking at problems: kernel messages, the images (compressed) and what ntfs-3g sees in the system files.
debug="$dir/debug"; mkdir -p "$debug"
sudo dmesg | grep -i ntfs | tail -n 200 > "$debug/dmesg-ntfs.txt" || true
for name in control-mkntfs.img mbr-ntfs-512.part; do
  f="$dir/$name"; [ -f "$f" ] || continue
  gzip -c "$f" > "$debug/$name.gz"
  { for n in 0 1 2 3 4 5 6 7 8 9 10 11; do echo "##### inode $n"; ntfsinfo -v -i "$n" "$f" 2>&1; done; } > "$debug/$name.inodes.txt"
done

if [ "$rc" -eq 0 ]; then echo "All checks passed"; else echo "Some checks FAILED"; fi
exit $rc
