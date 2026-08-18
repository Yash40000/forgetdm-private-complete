#!/usr/bin/env bash
set -euo pipefail

LAB_DIR="${FORGETDM_COW_LAB_DIR:-/mnt/d/forgetdm-cow-lab}"
IMAGE="${FORGETDM_COW_IMAGE:-$LAB_DIR/btrfs-pool.img}"
MOUNT_POINT="${FORGETDM_COW_MOUNT:-/opt/forgetdm-cow}"
IMAGE_SIZE="${FORGETDM_COW_IMAGE_SIZE:-16G}"

mkdir -p "$LAB_DIR" "$MOUNT_POINT"

if mountpoint -q "$MOUNT_POINT"; then
  echo "COW lab already mounted at $MOUNT_POINT"
  btrfs filesystem show "$MOUNT_POINT"
  df -hT "$MOUNT_POINT"
  exit 0
fi

existing_loop="$(losetup -j "$IMAGE" 2>/dev/null | cut -d: -f1 | head -1 || true)"
if [[ -n "$existing_loop" ]]; then
  losetup -d "$existing_loop" || true
fi

if [[ ! -f "$IMAGE" ]]; then
  truncate -s "$IMAGE_SIZE" "$IMAGE"
  mkfs.btrfs -f -L FORGETDM_COW "$IMAGE"
fi

loop_device="$(losetup --find --show "$IMAGE")"
mount -t btrfs -o compress=zstd:1,noatime "$loop_device" "$MOUNT_POINT"
mkdir -p "$MOUNT_POINT/qualification"

echo "COW lab ready"
echo "image=$IMAGE"
echo "loop=$loop_device"
echo "mount=$MOUNT_POINT"
btrfs filesystem show "$MOUNT_POINT"
df -hT "$MOUNT_POINT"
