#!/usr/bin/env bash
# Runs real checkers on the images produced by ImageGenerationTest.
# Needs: util-linux (sfdisk), gdisk (sgdisk), dosfstools (fsck.fat), exfatprogs (fsck.exfat).
set -u
dir="${1:-app/build/test-images}"
rc=0
shopt -s nullglob

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

for part in "$dir"/*-512.part; do
  echo "=== $(basename "$part")"
  case "$part" in
    *fat32*) fsck.fat -nv "$part" || rc=1 ;;
    *exfat*) fsck.exfat -n "$part" || rc=1 ;;
  esac
done

if [ "$rc" -eq 0 ]; then echo "All checks passed"; else echo "Some checks FAILED"; fi
exit $rc
