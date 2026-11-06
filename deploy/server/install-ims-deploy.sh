#!/usr/bin/env bash
# Installs the IMS deployment orchestrators. Run as root from a trusted copy of
# the repository (for example /root), never from the deploy-writable incoming
# directory.
#
# Usage: sudo ./install-ims-deploy.sh [--check]
#
# This installer does NOT modify sudoers, SSH, Nginx, systemd or the environment
# file. The deploy account keeps exactly one sudo permission: the pre-existing
# /usr/local/sbin/ims-create-deployment-backup.

set -euo pipefail

CHECK_ONLY=0
case ${1:-} in
  '') ;;
  --check) CHECK_ONLY=1 ;;
  -h|--help)
    printf 'Usage: %s [--check]\n' "$(basename "$0")" >&2
    exit 0
    ;;
  *) printf 'Unknown argument: %s\n' "$1" >&2; exit 2 ;;
esac

[ "$(id -u)" -eq 0 ] || { echo "ERROR: run as root" >&2; exit 1; }

SRC_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
SYS_DIR=/usr/local/sbin
DEPLOY_USER=${IMS_DEPLOY_USER:-deploy}
SERVICE_USER=${IMS_SERVICE_USER:-ims}
BACKUP_SCRIPT=${IMS_BACKUP_SCRIPT:-/usr/local/sbin/ims-create-deployment-backup}

LIB_FILES=(ims-deploy-common.sh)
BIN_FILES=(ims-deploy-backend ims-deploy-frontend ims-deploy-rollback ims-deploy-status)

die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
log() { printf '[install] %s\n' "$*"; }

case $SRC_DIR in
  /var/lib/ims-deploy/*) die "refusing to install from the deploy-writable directory: $SRC_DIR" ;;
esac

src_mode=$(stat -c %a "$SRC_DIR")
if ((8#$src_mode & 8#022)); then
  die "source directory is writable by group or others ($src_mode); copy it to a root-only path"
fi
[ "$(stat -c %U "$SRC_DIR")" = root ] || die "source directory must be owned by root"

for file in "${LIB_FILES[@]}" "${BIN_FILES[@]}"; do
  path="$SRC_DIR/$file"
  [ -f "$path" ] || die "missing source file: $file"
  [ ! -L "$path" ] || die "source file must not be a symlink: $file"
  [ "$(stat -c %U "$path")" = root ] || die "source file must be root-owned: $file"
  [ "$(stat -c %a "$path")" = "755" ] || [ "$(stat -c %a "$path")" = "644" ] \
    || die "source file must not be writable by group/others: $file"
  if grep -q $'\r' "$path"; then
    die "$file contains CRLF line endings; copy it as LF (for example with dos2unix)"
  fi
  bash -n "$path" || die "syntax error in $file"
done

id "$DEPLOY_USER" >/dev/null 2>&1 || die "deploy user does not exist: $DEPLOY_USER"
id "$SERVICE_USER" >/dev/null 2>&1 || die "service user does not exist: $SERVICE_USER"
[ -x "$BACKUP_SCRIPT" ] || die "existing backup script missing: $BACKUP_SCRIPT"
[ "$(stat -c %U "$BACKUP_SCRIPT")" = root ] || die "backup script must be root-owned"

if [ "$CHECK_ONLY" -eq 1 ]; then
  rc=0
  for file in "${LIB_FILES[@]}" "${BIN_FILES[@]}"; do
    if cmp -s "$SRC_DIR/$file" "$SYS_DIR/$file"; then
      printf 'OK      %s\n' "$file"
    else
      printf 'DRIFT   %s\n' "$file"
      rc=1
    fi
  done
  exit "$rc"
fi

log "creating deployment directories"
install -d -o root -g root -m 0755 /var/lib/ims-deploy
install -d -o "$DEPLOY_USER" -g "$DEPLOY_USER" -m 0700 /var/lib/ims-deploy/incoming
install -d -o root -g root -m 0700 /var/lib/ims-deploy/work
install -d -o root -g root -m 0750 /var/lib/ims-deploy/history
if [ ! -e /var/lib/ims-deploy/.deploy.lock ]; then
  install -o root -g root -m 0600 /dev/null /var/lib/ims-deploy/.deploy.lock
fi
if [ ! -d /var/backups/item-management ]; then
  install -d -o root -g root -m 0700 /var/backups/item-management
fi

log "installing orchestrators into $SYS_DIR"
for file in "${BIN_FILES[@]}"; do
  install -o root -g root -m 0755 "$SRC_DIR/$file" "$SYS_DIR/$file"
done
for file in "${LIB_FILES[@]}"; do
  install -o root -g root -m 0644 "$SRC_DIR/$file" "$SYS_DIR/$file"
done

for file in "${BIN_FILES[@]}" "${LIB_FILES[@]}"; do
  cmp -s "$SRC_DIR/$file" "$SYS_DIR/$file" || die "post-install verification failed: $file"
done

log "installed versions"
for file in "${BIN_FILES[@]}" "${LIB_FILES[@]}"; do
  printf '  %s  %s\n' "$(sha256sum "$SYS_DIR/$file" | awk '{print $1}')" "$file"
done

printf '\n'
log "sudo permissions were NOT changed"
log "deploy account retains only: sudo -n $BACKUP_SCRIPT"
log "privileged deployment must be executed by the owner/admin:"
log "  $SYS_DIR/ims-deploy-backend --commit <sha>"
log "  $SYS_DIR/ims-deploy-frontend --commit <sha>"
log "  $SYS_DIR/ims-deploy-status"
log "  $SYS_DIR/ims-deploy-rollback --component <backend|frontend>"
printf '\n'
log "verify with: $SYS_DIR/ims-deploy-status"
exit 0
