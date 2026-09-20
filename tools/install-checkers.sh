#!/usr/bin/env bash
# Installs the tools used by tools/verify-images.sh on a GitHub Actions runner.
set -e
sudo apt-get update
sudo apt-get install -y dosfstools exfatprogs gdisk ntfs-3g
# Best effort: the default runner kernel has no NTFS driver; the extra modules may bring it.
(sudo apt-get install -y "linux-modules-extra-$(uname -r)" && sudo modprobe ntfs3) || echo "kernel ntfs3 driver is not available here"
