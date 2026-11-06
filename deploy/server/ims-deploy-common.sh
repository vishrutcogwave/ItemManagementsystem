#!/usr/bin/env bash
# Shared library for IMS root-owned deployment orchestrators.
# Installed to /usr/local/sbin/ims-deploy-common.sh by install-ims-deploy.sh.
# Never contains secrets. Never reads /etc/item-management.env.

set -euo pipefail

IMS_APP_ROOT=${IMS_APP_ROOT:-/opt/item-management}
IMS_JAR=${IMS_JAR:-$IMS_APP_ROOT/backend/item-management.jar}
IMS_WEB_ROOT=${IMS_WEB_ROOT:-/var/www/item-management}
IMS_SERVICE=${IMS_SERVICE:-item-management}
IMS_SERVICE_USER=${IMS_SERVICE_USER:-ims}
IMS_SERVICE_GROUP=${IMS_SERVICE_GROUP:-ims}
IMS_WEB_USER=${IMS_WEB_USER:-www-data}
IMS_WEB_GROUP=${IMS_WEB_GROUP:-www-data}
IMS_BACKUP_DIR=${IMS_BACKUP_DIR:-/var/backups/item-management}
IMS_INCOMING=${IMS_INCOMING:-/var/lib/ims-deploy/incoming}
IMS_WORK_ROOT=${IMS_WORK_ROOT:-/var/lib/ims-deploy/work}
IMS_HISTORY_DIR=${IMS_HISTORY_DIR:-/var/lib/ims-deploy/history}
IMS_LOCK=${IMS_LOCK:-/var/lib/ims-deploy/.deploy.lock}
IMS_DB_NAME=${IMS_DB_NAME:-item_management_prod}
IMS_BACKUP_SCRIPT=${IMS_BACKUP_SCRIPT:-/usr/local/sbin/ims-create-deployment-backup}
IMS_HEALTH_URL=${IMS_HEALTH_URL:-http://127.0.0.1:8080/api/health}
IMS_VERSION_URL=${IMS_VERSION_URL:-http://127.0.0.1:8080/api/version}
IMS_DOMAINS=${IMS_DOMAINS:-"https://hiremathwebstudio.in https://www.hiremathwebstudio.in"}
IMS_BACKUP_KEEP=${IMS_BACKUP_KEEP:-2}
IMS_DEPLOY_USER=${IMS_DEPLOY_USER:-deploy}
IMS_MAX_JAR_BYTES=${IMS_MAX_JAR_BYTES:-314572800}
IMS_MAX_FRONTEND_BYTES=${IMS_MAX_FRONTEND_BYTES:-209715200}

log() {
  printf '[%s] %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*" >&2
}

die() {
  log "ERROR: $*"
  exit 1
}

require_root() {
  [ "$(id -u)" -eq 0 ] || die "this script must run as root"
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || die "required command not found: $1"
}

is_hex40() {
  [[ ${1:-} =~ ^[0-9a-f]{40}$ ]]
}

is_sha256() {
  [[ ${1:-} =~ ^[0-9a-f]{64}$ ]]
}

valid_component() {
  case ${1:-} in
    backend|frontend) return 0 ;;
    *) return 1 ;;
  esac
}

json_escape() {
  local s=${1:-}
  s=${s//\\/\\\\}
  s=${s//\"/\\\"}
  s=${s//$'\n'/ }
  s=${s//$'\r'/ }
  s=${s//$'\t'/ }
  printf '%s' "$s"
}

append_history() {
  # action result commit artifact_sha backup flyway_before flyway_after detail
  local action=${1:-unknown} result=${2:-unknown} commit=${3:-} sha=${4:-}
  local backup=${5:-} fw_before=${6:-} fw_after=${7:-} detail=${8:-}
  local line
  line=$(printf '{"ts":"%s","action":"%s","result":"%s","commit":"%s","artifact_sha256":"%s","backup":"%s","flyway_before":"%s","flyway_after":"%s","detail":"%s","operator":"%s","host":"%s"}' \
    "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    "$(json_escape "$action")" "$(json_escape "$result")" "$(json_escape "$commit")" \
    "$(json_escape "$sha")" "$(json_escape "$backup")" "$(json_escape "$fw_before")" \
    "$(json_escape "$fw_after")" "$(json_escape "$detail")" \
    "$(json_escape "${SUDO_USER:-root}")" "$(json_escape "$(hostname -f 2>/dev/null || hostname)")")
  install -d -o root -g root -m 0750 "$IMS_HISTORY_DIR" 2>/dev/null || true
  if [ ! -e "$IMS_HISTORY_DIR/deployments.jsonl" ]; then
    install -o root -g root -m 0640 /dev/null "$IMS_HISTORY_DIR/deployments.jsonl" 2>/dev/null || true
  fi
  if ! printf '%s\n' "$line" >>"$IMS_HISTORY_DIR/deployments.jsonl"; then
    log "WARN: could not append deployment history"
    return 1
  fi
  return 0
}

acquire_lock() {
  install -d -o root -g root -m 0755 "$(dirname "$IMS_LOCK")"
  if [ ! -e "$IMS_LOCK" ]; then
    install -o root -g root -m 0600 /dev/null "$IMS_LOCK"
  fi
  exec 9>"$IMS_LOCK"
  flock -n 9 || die "another deployment is already running"
}

assert_incoming_safe() {
  [ -d "$IMS_INCOMING" ] || die "incoming directory missing: $IMS_INCOMING"
  [ ! -L "$IMS_INCOMING" ] || die "incoming directory must not be a symlink"
  [ "$(stat -c %U "$IMS_INCOMING")" = "$IMS_DEPLOY_USER" ] \
    || die "incoming directory must be owned by $IMS_DEPLOY_USER"
  [ "$(stat -c %a "$IMS_INCOMING")" = "700" ] \
    || die "incoming directory must be mode 700"
}

# Copy an uploaded artifact into a root-owned work directory and make it
# immutable to the deploy account before any validation happens.
stage_artifact() {
  local name=$1 work=$2
  local src="$IMS_INCOMING/$name"
  [ -f "$src" ] || die "uploaded artifact missing: $name"
  [ ! -L "$src" ] || die "uploaded artifact must not be a symlink: $name"
  local size
  size=$(stat -c %s "$src")
  [ "$size" -le "$IMS_MAX_JAR_BYTES" ] || die "uploaded artifact is too large: $name"
  install -o root -g root -m 0644 "$src" "$work/$name" || die "could not stage $name"
  printf '%s' "$work/$name"
}

declare -g M_TYPE='' M_COMMIT='' M_SHORT='' M_BUILT='' M_ARTIFACT='' M_SHA='' M_SIZE=''
declare -g JAR_MIGRATIONS='' JAR_BACKUP='' FRONTEND_BACKUP='' DB_BACKUP_PATH=''

parse_manifest() {
  local file=$1 expected_type=$2
  [ -f "$file" ] || die "manifest missing"
  [ ! -L "$file" ] || die "manifest must not be a symlink"
  [ "$(stat -c %U "$file")" = root ] || die "manifest must be root-owned"
  [ "$(stat -c %a "$file")" = "644" ] || die "manifest must be mode 644"

  local seen='' line key value
  M_TYPE=''; M_COMMIT=''; M_SHORT=''; M_BUILT=''; M_ARTIFACT=''; M_SHA=''; M_SIZE=''
  while IFS= read -r line || [ -n "$line" ]; do
    line=${line%$'\r'}
    [ -n "$line" ] || continue
    case $line in
      *=*) ;;
      *) die "malformed manifest line" ;;
    esac
    key=${line%%=*}
    value=${line#*=}
    case " $seen " in
      *" $key "*) die "duplicate manifest key: $key" ;;
    esac
    seen="$seen $key"
    case $key in
      schema) [ "$value" = "1" ] || die "unsupported manifest schema" ;;
      type) M_TYPE=$value ;;
      commit) M_COMMIT=$value ;;
      short_commit) M_SHORT=$value ;;
      built_at_utc) M_BUILT=$value ;;
      artifact) M_ARTIFACT=$value ;;
      sha256) M_SHA=$value ;;
      size) M_SIZE=$value ;;
      *) die "unknown manifest key: $key" ;;
    esac
  done <"$file"

  for key in schema type commit short_commit built_at_utc artifact sha256 size; do
    case " $seen " in
      *" $key "*) ;;
      *) die "manifest missing key: $key" ;;
    esac
  done

  M_COMMIT=${M_COMMIT,,}
  M_SHORT=${M_SHORT,,}
  M_SHA=${M_SHA,,}
  [ "$M_TYPE" = "$expected_type" ] || die "manifest type mismatch"
  is_hex40 "$M_COMMIT" || die "manifest commit must be 40 hex characters"
  [[ $M_SHORT =~ ^[0-9a-f]{7,12}$ ]] || die "manifest short_commit invalid"
  [[ $M_COMMIT == "$M_SHORT"* ]] || die "manifest short_commit is not a prefix of commit"
  [[ $M_BUILT =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$ ]] \
    || die "manifest built_at_utc invalid"
  is_sha256 "$M_SHA" || die "manifest sha256 invalid"
  [[ $M_SIZE =~ ^[0-9]{1,12}$ ]] || die "manifest size invalid"

  case $expected_type in
    backend)
      [ "$M_ARTIFACT" = "item-management.jar" ] || die "unexpected backend artifact name"
      [ "$M_SIZE" -gt 0 ] && [ "$M_SIZE" -le "$IMS_MAX_JAR_BYTES" ] || die "jar size out of range"
      ;;
    frontend)
      [ "$M_ARTIFACT" = "frontend.tar.gz" ] || die "unexpected frontend artifact name"
      [ "$M_SIZE" -gt 0 ] && [ "$M_SIZE" -le "$IMS_MAX_FRONTEND_BYTES" ] || die "frontend size out of range"
      ;;
  esac
}

verify_file_matches_manifest() {
  local file=$1
  local actual_size actual_sha
  actual_size=$(stat -c %s "$file")
  [ "$actual_size" = "$M_SIZE" ] || die "artifact size does not match manifest"
  actual_sha=$(sha256sum "$file" | awk '{print $1}')
  [ "$actual_sha" = "$M_SHA" ] || die "artifact sha256 does not match manifest"
  printf '%s' "$actual_sha"
}

validate_jar() {
  local jar=$1 expected_commit=$2
  [ -f "$jar" ] || die "jar missing"
  [ ! -L "$jar" ] || die "jar must not be a symlink"
  unzip -tqq "$jar" >/dev/null 2>&1 || die "jar archive is corrupt"

  local listing count
  listing=$(unzip -Z1 "$jar") || die "cannot list jar contents"
  count=$(printf '%s\n' "$listing" | wc -l)
  [ "$count" -le 20000 ] || die "jar contains too many entries"

  printf '%s\n' "$listing" | grep -q '^BOOT-INF/classes/' || die "not a Spring Boot fat jar"
  printf '%s\n' "$listing" | grep -q '^BOOT-INF/lib/' || die "jar has no bundled libraries"
  printf '%s\n' "$listing" | grep -qx 'META-INF/MANIFEST.MF' || die "jar manifest missing"

  local props embedded
  # Spring Boot writes build-info.properties to the jar root; older layouts used
  # BOOT-INF/classes/META-INF. Accept both, and both property key forms.
  props=$(unzip -p "$jar" 'META-INF/build-info.properties' 2>/dev/null || true)
  if [ -z "$props" ]; then
    props=$(unzip -p "$jar" 'BOOT-INF/classes/META-INF/build-info.properties' 2>/dev/null || true)
  fi
  [ -n "$props" ] || die "build-info.properties missing from jar"
  embedded=$(printf '%s\n' "$props" \
    | sed -n -e 's/^build\.git\.commit=//p' -e 's/^git\.commit=//p' \
    | tr -d '\r' | head -1)
  [ -n "$embedded" ] || die "jar has no embedded git commit"
  [[ $expected_commit == "$embedded"* ]] || die "jar commit ($embedded) does not match expected ($expected_commit)"

  JAR_MIGRATIONS=$(printf '%s\n' "$listing" \
    | grep -E '^BOOT-INF/classes/db/migration/V[0-9][0-9._]*__.*\.sql$' \
    | sed -E 's#^BOOT-INF/classes/db/migration/V([0-9][0-9._]*)__.*#\1#' \
    | sort -V | paste -sd, -)
  [ -n "$JAR_MIGRATIONS" ] || die "jar contains no Flyway migrations"
  log "jar commit $embedded, migrations $JAR_MIGRATIONS"
}

validate_archive_path() {
  local path=${1:-}
  [ -n "$path" ] || return 1
  case $path in
    /*) return 1 ;;
  esac
  case "/$path/" in
    */../*) return 1 ;;
  esac
  case $path in
    *\\*) return 1 ;;
  esac
  return 0
}

validate_frontend_tar() {
  local tar=$1
  [ -f "$tar" ] || die "frontend archive missing"
  [ ! -L "$tar" ] || die "frontend archive must not be a symlink"
  tar -tzf "$tar" >/dev/null 2>&1 || die "frontend archive is corrupt"

  local entries count path
  entries=$(tar -tzf "$tar") || die "cannot list frontend archive"
  count=$(printf '%s\n' "$entries" | wc -l)
  [ "$count" -gt 0 ] || die "frontend archive is empty"
  [ "$count" -le 5000 ] || die "frontend archive has too many entries"

  while IFS= read -r path; do
    validate_archive_path "$path" || die "unsafe archive entry: $path"
  done <<<"$entries"

  if tar -tvzf "$tar" | grep -q '^l'; then
    die "frontend archive contains symbolic links"
  fi
  if tar -tvzf "$tar" | grep -qE '^[bcps]'; then
    die "frontend archive contains special files"
  fi

  printf '%s\n' "$entries" | grep -qxE '(\./)?index\.html' || die "frontend archive has no root index.html"
  printf '%s\n' "$entries" | grep -qE '^(\./)?assets/' || die "frontend archive has no assets directory"
}

backend_running() {
  systemctl is-active --quiet "$IMS_SERVICE"
}

port_listening() {
  ss -ltn 2>/dev/null | grep -q ':8080 '
}

http_code() {
  local url=$1
  curl -sS -o /dev/null -w '%{http_code}' --max-time 15 "$url" 2>/dev/null || printf '000'
}

wait_backend_healthy() {
  local timeout=${1:-120} elapsed=0 code
  while [ "$elapsed" -lt "$timeout" ]; do
    if backend_running && port_listening; then
      code=$(http_code "$IMS_HEALTH_URL")
      if [ "$code" = "200" ] && curl -fsS --max-time 10 "$IMS_HEALTH_URL" 2>/dev/null \
        | grep -q '"status"[[:space:]]*:[[:space:]]*"UP"'; then
        return 0
      fi
    fi
    sleep 3
    elapsed=$((elapsed + 3))
  done
  return 1
}

check_public_endpoints() {
  local domain code
  for domain in $IMS_DOMAINS; do
    code=$(http_code "$domain/")
    [ "$code" = "200" ] || { log "public check failed: $domain/ -> $code"; return 1; }
    log "public check ok: $domain/ -> 200"
  done
  return 0
}

check_public_api() {
  local domain code
  for domain in $IMS_DOMAINS; do
    code=$(http_code "$domain/api/health")
    [ "$code" = "200" ] || { log "public API check failed: $domain/api/health -> $code"; return 1; }
    log "public API check ok: $domain/api/health -> 200"
  done
  return 0
}

psql_q() {
  if command -v runuser >/dev/null 2>&1; then
    runuser -u postgres -- psql -X -A -t -v ON_ERROR_STOP=1 -d "$IMS_DB_NAME" -c "$1"
  else
    sudo -u postgres psql -X -A -t -v ON_ERROR_STOP=1 -d "$IMS_DB_NAME" -c "$1"
  fi
}

flyway_versions() {
  psql_q "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank" \
    | tr -d '\r' | sed '/^[[:space:]]*$/d' | paste -sd, -
}

flyway_failed_count() {
  psql_q "SELECT count(*) FROM flyway_schema_history WHERE success = false" | tr -d '[:space:]'
}

pending_migrations() {
  local applied pending='' version
  applied=$(flyway_versions)
  while IFS= read -r version; do
    [ -n "$version" ] || continue
    case ",$applied," in
      *",$version,"*) ;;
      *) pending="$pending $version" ;;
    esac
  done <<<"$(printf '%s' "$JAR_MIGRATIONS" | tr ',' '\n')"
  printf '%s' "${pending# }"
}

run_db_backup() {
  [ -x "$IMS_BACKUP_SCRIPT" ] || die "backup script missing or not executable: $IMS_BACKUP_SCRIPT"
  local started newest ref
  started=$(date +%s)
  ref=$(mktemp)
  touch -d "@$((started - 60))" "$ref" || die "could not create backup time reference"
  log "running existing backup script"
  if ! "$IMS_BACKUP_SCRIPT"; then
    rm -f "$ref"
    die "existing backup script failed; deployment aborted"
  fi
  # Accept both layouts: flat *.dump files and the per-run directory written
  # by the existing backup script (database.dump inside a timestamped dir).
  newest=$(find "$IMS_BACKUP_DIR" -maxdepth 2 -type f \
    \( -name '*.dump' -o -name '*.sql' -o -name '*.sql.gz' -o -name 'database.dump' \) \
    -newer "$ref" -printf '%T@ %p\n' 2>/dev/null \
    | sort -nr | head -1 | cut -d' ' -f2-)
  rm -f "$ref"
  [ -n "$newest" ] || die "backup script reported success but no fresh dump was found"
  verify_db_dump "$newest"
  DB_BACKUP_PATH=$newest
  log "database backup verified: $newest"
}

verify_db_dump() {
  local file=$1 header dir base
  dir=$(dirname "$file")
  base=$(basename "$file")
  [ -s "$file" ] || die "database dump is empty: $file"
  case $file in
    *.sql.gz) gzip -t "$file" || die "gzip integrity check failed: $file" ;;
    *)
      header=$(head -c 5 "$file" || true)
      if [ "$header" = "PGDMP" ]; then
        pg_restore --list "$file" >/dev/null 2>&1 || die "pg_restore cannot read dump: $file"
      fi
      ;;
  esac
  if [ "$dir" = "$IMS_BACKUP_DIR" ]; then
    # Flat layout: optional sibling checksum file, as before.
    if [ -f "$file.sha256" ]; then
      (cd "$dir" && sha256sum -c "$base.sha256" >/dev/null) \
        || die "database dump checksum failed: $file"
    fi
    return 0
  fi
  # Per-run directory written by the existing backup script: only trust it when
  # the script marked it verified and left the checksum manifest it verified.
  [ -f "$dir/.verified" ] || die "backup directory is not marked verified: $dir"
  [ -f "$dir/SHA256SUMS" ] || die "backup checksum manifest is missing: $dir/SHA256SUMS"
  (cd "$dir" && sha256sum -c SHA256SUMS --ignore-missing >/dev/null) \
    || die "database dump checksum failed: $file"
}

backup_current_jar() {
  local ts=$1 dest="$IMS_BACKUP_DIR/item-management.jar.bak-$ts"
  install -o root -g root -m 0600 "$IMS_JAR" "$dest" || die "could not back up current jar"
  (cd "$IMS_BACKUP_DIR" && sha256sum "$(basename "$dest")" >"$(basename "$dest").sha256") \
    || die "could not write jar checksum"
  chmod 0600 "$dest.sha256"
  (cd "$IMS_BACKUP_DIR" && sha256sum -c "$(basename "$dest").sha256" >/dev/null) \
    || die "jar backup checksum verification failed"
  JAR_BACKUP=$dest
  log "jar backup verified: $dest"
}

backup_current_frontend() {
  local ts=$1 dest="$IMS_BACKUP_DIR/frontend-$ts.tar.gz"
  [ -d "$IMS_WEB_ROOT" ] || die "frontend directory missing: $IMS_WEB_ROOT"
  tar -czf "$dest" -C "$IMS_WEB_ROOT" . || die "could not back up frontend"
  tar -tzf "$dest" >/dev/null 2>&1 || die "frontend backup archive is corrupt"
  (cd "$IMS_BACKUP_DIR" && sha256sum "$(basename "$dest")" >"$(basename "$dest").sha256") \
    || die "could not write frontend checksum"
  chmod 0600 "$dest" "$dest.sha256"
  (cd "$IMS_BACKUP_DIR" && sha256sum -c "$(basename "$dest").sha256" >/dev/null) \
    || die "frontend backup checksum verification failed"
  FRONTEND_BACKUP=$dest
  log "frontend backup verified: $dest"
}

prune_verified_backups() {
  local pattern=$1 keep=${2:-$IMS_BACKUP_KEEP} index=0 file
  while IFS= read -r file; do
    index=$((index + 1))
    if [ "$index" -gt "$keep" ]; then
      rm -f "$file" "$file.sha256" && log "pruned old backup: $file"
    fi
  done <<<"$(find "$IMS_BACKUP_DIR" -maxdepth 1 -type f -name "$pattern" \
    -printf '%T@ %p\n' | sort -nr | cut -d' ' -f2-)"
}

restore_jar_backup() {
  local backup=$1
  [ -f "$backup" ] || return 1
  systemctl stop "$IMS_SERVICE" >/dev/null 2>&1 || true
  install -o "$IMS_SERVICE_USER" -g "$IMS_SERVICE_GROUP" -m 0644 "$backup" "$IMS_JAR" || return 1
  systemctl start "$IMS_SERVICE" || return 1
  wait_backend_healthy 120
}

restore_frontend_backup() {
  local backup=$1 stage=$2
  [ -f "$backup" ] || return 1
  rm -rf "$stage" && install -d -o root -g root -m 0700 "$stage" || return 1
  tar -xzf "$backup" -C "$stage" --no-same-owner --no-same-permissions || return 1
  rsync -a --delete --exclude='.well-known/' "$stage/" "$IMS_WEB_ROOT/" || return 1
  find "$IMS_WEB_ROOT" -type d -exec chmod 755 {} + || return 1
  find "$IMS_WEB_ROOT" -type f -exec chmod 644 {} + || return 1
  chown -R "$IMS_WEB_USER:$IMS_WEB_GROUP" "$IMS_WEB_ROOT" || return 1
}

print_summary() {
  local component=$1 commit=$2 sha=$3 backup=$4
  printf '\n'
  printf 'Deployment complete\n'
  printf '  component : %s\n' "$component"
  printf '  commit    : %s\n' "$commit"
  printf '  sha256    : %s\n' "$sha"
  printf '  backup    : %s\n' "$backup"
  printf '  history   : %s/deployments.jsonl\n' "$IMS_HISTORY_DIR"
  printf '\n'
}
