# Deployment Guide (Automated Workflow)

How to build, upload, deploy, verify and roll back the Item Management System with the
automation in `deploy/`.

> **Approval gates.** Building and uploading are unprivileged. Installing a jar or syncing the
> frontend requires running a root-owned orchestrator, which must be done by the owner/admin.
> Nothing in this repository pushes to GitHub, changes sudoers, SSH, Nginx or the environment file,
> or restores the database automatically. Read `DEPLOYMENT_SECURITY.md` before using these tools.

This guide supersedes the manual upload/install steps in `DEPLOYMENT_CHECKLIST.md`. The manual
procedure remains documented there and in `ADMIN_DEPLOYMENT_GUIDE.md` as a fallback, and is still
authoritative for anything not covered here (first-time server setup, envelope/security settings).

---

## 1. Architecture

The deploy account has exactly one passwordless sudo permission — the pre-existing backup script.
It cannot stop/start `item-management`, write to `/opt` or `/var/www`, or run arbitrary root
commands. The workflow is therefore split in two:

```
Windows build machine                      Ubuntu production server
─────────────────────                      ─────────────────────────
Deploy-Frontend.ps1 / Deploy-Backend.ps1   deploy account (unprivileged)
  clean-git gate                             └─ scp artifact + manifest
  build (npm / gradlew)                          to /var/lib/ims-deploy/incoming
  validate artifact + manifest
  upload + verify remote sha256
  write local history
                │
                ▼
  owner runs as root ──────────────────────► /usr/local/sbin/ims-deploy-backend
                                              /usr/local/sbin/ims-deploy-frontend
                                               ├─ stage copy (root-owned)
                                               ├─ validate manifest/sha/size/contents
                                               ├─ pre-flight (service, nginx, disk, Flyway)
                                               ├─ backup DB + jar + frontend, verify
                                               ├─ install, restart, health-check
                                               ├─ auto-rollback on failure
                                               └─ append deployment history
```

| Component | Runs as | Installed path |
|---|---|---|
| `deploy/server/ims-deploy-common.sh` | root (library) | `/usr/local/sbin/ims-deploy-common.sh` |
| `deploy/server/ims-deploy-backend` | root | `/usr/local/sbin/ims-deploy-backend` |
| `deploy/server/ims-deploy-frontend` | root | `/usr/local/sbin/ims-deploy-frontend` |
| `deploy/server/ims-deploy-rollback` | root | `/usr/local/sbin/ims-deploy-rollback` |
| `deploy/server/ims-deploy-status` | root (read-only) | `/usr/local/sbin/ims-deploy-status` |
| `deploy/server/install-ims-deploy.sh` | root, one time | not installed (run from repo) |
| `deploy/windows/Deploy-Common.psm1` | Windows user | repo |
| `deploy/windows/Deploy-Frontend.ps1` | Windows user | repo |
| `deploy/windows/Deploy-Backend.ps1` | Windows user | repo |
| `deploy/windows/Test-DeploymentPrerequisites.ps1` | Windows user | repo |
| `deploy/windows/Test-DeploymentHealth.ps1` | Windows user | repo |

### Server paths used by the automation

| Purpose | Path | Owner / mode |
|---|---|---|
| Backend jar | `/opt/item-management/backend/item-management.jar` | `ims:ims 0644` |
| Frontend root | `/var/www/item-management` | `www-data:www-data` |
| systemd service | `item-management` | — |
| Environment file | `/etc/item-management.env` | never read or written by these scripts |
| Upload staging | `/var/lib/ims-deploy/incoming` | `deploy:deploy 0700` |
| Root work area | `/var/lib/ims-deploy/work` | `root:root 0700` |
| Deployment history | `/var/lib/ims-deploy/history/deployments.jsonl` | `root:root 0750` |
| Deploy lock | `/var/lib/ims-deploy/.deploy.lock` | `root:root 0600` |
| Backups | `/var/backups/item-management` | `root:root 0700` |
| Existing backup script | `/usr/local/sbin/ims-create-deployment-backup` | root-owned |

---

## 2. One-time server installation (root)

The orchestrators rely on these server tools (all packages are standard Ubuntu utilities except
`unzip`): `unzip`, `curl`, `flock` (util-linux), `ss` (iproute2), `systemctl`, `nginx`, `sha256sum`,
`tar`, `rsync`, `install`, `find`, `psql`, `pg_restore` and `gzip`. Verify them before installing:

```bash
for c in unzip curl flock ss systemctl nginx sha256sum tar rsync install find psql pg_restore gzip; do
  command -v "$c" >/dev/null || echo "MISSING: $c"
done
```

If `unzip` is missing (it is not installed by default on some Ubuntu images), install it with
`sudo apt-get install unzip`. Do not install or change anything else as part of this workflow.

Copy the repository to a root-only location on the server (for example `/root/ims-src`) and run the
installer as root. Do **not** run it from `/var/lib/ims-deploy/*`; the installer refuses that path.

```bash
sudo install -d -m 0700 /root/ims-src
# copy deploy/server/* into /root/ims-src (scp/rsync from the build machine)
sudo chown -R root:root /root/ims-src
sudo chmod 0700 /root/ims-src
sudo chmod 0755 /root/ims-src/install-ims-deploy.sh /root/ims-src/ims-deploy-backend \
     /root/ims-src/ims-deploy-frontend /root/ims-src/ims-deploy-rollback /root/ims-src/ims-deploy-status
sudo chmod 0644 /root/ims-src/ims-deploy-common.sh
sudo /root/ims-src/install-ims-deploy.sh --check   # review first
sudo /root/ims-src/install-ims-deploy.sh
```

Expected final output (abridged):

```
[install] installing orchestrators into /usr/local/sbin
[install] installed versions
  <sha256>  ims-deploy-backend
  ...
[install] sudo permissions were NOT changed
[install] deploy account retains only: sudo -n /usr/local/sbin/ims-create-deployment-backup
```

The installer refuses to proceed if the source directory is group/other-writable, not root-owned,
symlinked, or syntactically invalid. It creates the directories above and performs a byte-for-byte
`cmp` of every installed file.

Verify:

```bash
sudo /usr/local/sbin/ims-deploy-status
sudo /root/ims-src/install-ims-deploy.sh --check   # prints OK for each file, DRIFT otherwise
sudo -l -U deploy                                  # must still list only the backup script
```

> The installer does **not** edit `/etc/sudoers`, SSH configuration, Nginx, systemd or the env file.
> If `sudo -l -U deploy` shows anything beyond the backup script, stop and review with the owner.

---

## 3. Local configuration (Windows, git-ignored)

```powershell
Copy-Item deploy\windows\deploy.config.example.psd1 deploy\windows\deploy.config.psd1
notepad deploy\windows\deploy.config.psd1
```

Required keys: `SshHost`, `SshPort`, `SshUser`, `SshKeyPath`, `RemoteIncoming`,
`RemoteBackupDir`, `SiteUrls`, `ApiHealthUrl`, `LocalHistoryFile`.

`deploy/windows/deploy.config.psd1` and `deploy/.local/` are git-ignored. The file contains no
secret — only a path to the passphrase-protected key. The key passphrase is never stored.

Check readiness before the first deployment:

```powershell
powershell -ExecutionPolicy Bypass -File deploy\windows\Test-DeploymentPrerequisites.ps1
powershell -ExecutionPolicy Bypass -File deploy\windows\Test-DeploymentPrerequisites.ps1 -TestConnectivity
```

Every line prints `PASS`/`FAIL`; the script exits 1 if anything failed. `-TestConnectivity` opens
read-only SSH (`true`, then `systemctl is-active`, `ss`, local `/api/health`, checksums, `df`) and
may prompt for the key passphrase.

---

## 4. Frontend deployment

### 4.1 Build, validate and upload (Windows, unprivileged)

```powershell
powershell -ExecutionPolicy Bypass -File deploy\windows\Deploy-Frontend.ps1
```

The script:

1. refuses to run unless `git status --porcelain` is empty (tracked **and** untracked files);
2. records `HEAD` (full and 7-character commit);
3. runs `npm ci`, `npm run lint` (oxlint) and `npm run build` (`tsc -b && vite build`);
4. validates `dist/index.html`, a non-empty `dist/assets`, and refuses any `.env*` file in `dist`;
5. creates `frontend.tar.gz` from `dist` and validates entry count, paths, `index.html`, `assets/`;
6. writes `frontend.manifest` (schema/type/commit/short_commit/built_at_utc/artifact/sha256/size);
7. uploads `frontend.tar.gz` and `frontend.manifest` to the staging directory by SCP;
8. verifies both remote SHA-256 values over SSH;
9. appends an `uploaded-awaiting-root` record to `deploy/.local/history.jsonl`.

Flags: `-SkipBuild` (upload the existing `dist`), `-SkipInstall` (skip `npm ci`),
`-SkipConnectivityTest` (skip the read-only remote preflight), `-ConfigPath`, `-RepoRoot`.

> With `-SkipBuild` the frontend has no embedded commit, so a stale `dist` cannot be detected.
> Use it only immediately after building the same commit; otherwise build normally. The backend
> jar does embed its commit, so a stale jar is always rejected.

Expected tail:

```
Upload verified (sha256 matches).

UPLOAD COMPLETE - deployment is NOT finished.
Run this on the server as root (or an admin with sudo):

    /usr/local/sbin/ims-deploy-frontend --commit <40-hex-commit>
```

### 4.2 Privileged deploy (server, root)

```bash
sudo /usr/local/sbin/ims-deploy-frontend --commit <40-hex-commit>
```

The orchestrator:

1. takes the deploy lock (`flock`, refuses concurrent runs);
2. validates the staging directory (owned by `deploy`, mode 700, not a symlink);
3. copies both files into a root-owned work directory (TOCTOU protection) and requires the
   manifest to be root-owned `0644`;
4. validates manifest keys, commit prefix, artifact name, size (≤ 200 MB) and SHA-256;
5. rejects corrupt archives, absolute paths, `..` traversal, backslashes, symlinks, special files,
   archives without root `index.html` or `assets/`, and > 5000 entries;
6. pre-flight: web root exists and is not a symlink, nginx active, `nginx -t`, `item-management`
   active, ≥ 500 MB free on `/var`;
7. extracts into staging and re-validates the extracted tree;
8. backs up the current frontend to `frontend-<TS>.tar.gz` and verifies the archive and checksum;
9. `rsync -a --delete --exclude='.well-known/'` into `/var/www/item-management`, then sets
   `755`/`644` and `www-data:www-data`;
10. runs `nginx -t` (never reloads Nginx), checks apex and www `/` and `/api/health` are HTTP 200,
    and checks one built JS/CSS asset returns 200;
11. on any failure restores the backup and re-verifies, then exits non-zero;
12. on success prunes old frontend archives (keeps the newest **2**) and appends history.

Expected output (abridged):

```
[2026-...Z] backup ... frontend backup verified: /var/backups/item-management/frontend-...tar.gz
[2026-...Z] syncing frontend into /var/www/item-management
[2026-...Z] public check ok: https://hiremathwebstudio.in/ -> 200
[2026-...Z] public check ok: https://www.hiremathwebstudio.in/ -> 200
[2026-...Z] public API check ok: ... -> 200
[2026-...Z] asset check ok: /assets/index-....js -> 200

Deployment complete
  component : frontend
  commit    : <40-hex>
  sha256    : <artifact sha256>
  backup    : /var/backups/item-management/frontend-<TS>.tar.gz
```

### 4.3 Verify

```bash
sudo /usr/local/sbin/ims-deploy-status
powershell -File deploy\windows\Test-DeploymentHealth.ps1
```

---

## 5. Backend deployment

### 5.1 Build, validate and upload (Windows, unprivileged)

```powershell
powershell -ExecutionPolicy Bypass -File deploy\windows\Deploy-Backend.ps1
```

The script:

1. requires a clean working tree and records `HEAD`;
2. optionally runs the read-only remote preflight and refuses to continue unless local
   `/api/health` returns 200;
3. runs `backend\gradlew.bat clean test bootJar`;
4. requires exactly one non-`-plain` jar in `backend\build\libs`;
5. opens the jar as a ZIP and verifies: Spring Boot layout (`BOOT-INF/classes`, `BOOT-INF/lib`,
   `META-INF/MANIFEST.MF`), `build-info.properties` (jar root, with the legacy
   `BOOT-INF/classes/META-INF` location accepted) whose `build.git.commit`/`git.commit` matches
   `HEAD`, and at least one `db/migration/V*__*.sql` entry;
6. writes `backend.manifest`, uploads `item-management.jar` + `backend.manifest`, and verifies both
   remote SHA-256 values;
7. writes local history `uploaded-awaiting-root`.

Flags: `-SkipBuild` (upload the existing jar), `-SkipConnectivityTest`, `-ConfigPath`, `-RepoRoot`.

> The jar's embedded commit comes from Gradle `buildInfo.additional['git.commit']` and must match
> `HEAD`. A dirty tree is rejected before the build, so the artifact always corresponds to a commit.

### 5.2 Privileged deploy (server, root)

```bash
sudo /usr/local/sbin/ims-deploy-backend --commit <40-hex-commit>
```

The orchestrator:

1. takes the deploy lock and validates the staging directory;
2. stages root-owned copies and validates manifest, artifact name, size (≤ 300 MB), SHA-256 and
   the jar layout, including the embedded commit and bundled Flyway migrations;
3. pre-flight: `item-management` active, current jar present, `nginx -t`, ≥ 500 MB free on `/var`,
   no failed rows in `flyway_schema_history`;
4. computes pending migrations from the jar's `db/migration` entries versus applied versions. If
   anything is pending it **refuses** and logs a `refused` history record unless
   `--allow-migrations` is supplied. Flyway applies migrations on startup;
5. runs the existing `/usr/local/sbin/ims-create-deployment-backup`, finds the fresh dump created
   by that run, and verifies its header (`PGDMP`), `pg_restore --list` readability and `.sha256`
   when present;
6. backs up the current jar (`item-management.jar.bak-<TS>`, mode 0600) **and** the current
   frontend (`frontend-<TS>.tar.gz`), verifying both checksums; if any backup fails, nothing is
   installed;
7. stops `item-management`, installs the jar as `ims:ims 0644`, starts the service;
8. waits up to 150 s for systemd active + port 8080 + local `/api/health` `"status":"UP"`;
9. verifies public apex and www `/` and `/api/health` return 200, no failed Flyway rows, no pending
   migrations, and logs the runtime `gitCommit` from `/api/version` (mismatch is a warning only,
   because `GIT_COMMIT` in the environment can override the embedded value);
10. on failure keeps a copy of the failed jar, restores the previous jar, restarts, re-checks
    health, appends the result and exits 4 (or 5 if rollback also failed);
11. on success prunes old jar and frontend backups (newest **2** each) and appends history.

Expected output (abridged):

```
[2026-...Z] applied migrations before deploy: 1,2,...,8
[2026-...Z] running existing backup script
[2026-...Z] database backup verified: /var/backups/item-management/<dump>
[2026-...Z] jar backup verified: /var/backups/item-management/item-management.jar.bak-<TS>
[2026-...Z] frontend backup verified: /var/backups/item-management/frontend-<TS>.tar.gz
[2026-...Z] deploying backend jar (service will be restarted)
[2026-...Z] backend health endpoint is UP

Deployment complete
  component : backend
  commit    : <40-hex>
  sha256    : <jar sha256>
  backup    : /var/backups/item-management/item-management.jar.bak-<TS>
```

### 5.3 Verify

```bash
sudo /usr/local/sbin/ims-deploy-status
curl -fsS https://hiremathwebstudio.in/api/version
powershell -File deploy\windows\Test-DeploymentHealth.ps1 -ExpectedCommit <40-hex-commit>
```

`Test-DeploymentHealth.ps1` checks both site URLs, the public API health endpoint and, with
`-ExpectedCommit`, that `/api/version` reports a `gitCommit` starting with the deployed commit.
It is read-only and exits 1 on any failure.

---

## 6. Backups, retention and history

| Artifact | Created by | Naming | Verification |
|---|---|---|---|
| PostgreSQL dump | existing root backup script | as configured by that script (`*.dump` / `*.sql` / `*.sql.gz`) | non-empty, `PGDMP` header + `pg_restore --list` (or `gzip -t`), optional `.sha256` |
| Current jar | `ims-deploy-backend` | `item-management.jar.bak-<TS>` + `.sha256` | `sha256sum -c` |
| Current frontend | both orchestrators | `frontend-<TS>.tar.gz` + `.sha256` | `tar -tzf` + `sha256sum -c` |

- Backups are created **before** the service is stopped and before any file is replaced.
- A failure in any backup aborts the deployment before installation.
- The orchestrators never delete a backup unless the new deployment succeeded. Database dumps are
  left to the existing backup script's own retention.
- Jar and frontend backups keep the newest **2**; pruning only runs after a successful deploy.

Server history is append-only JSONL at `/var/lib/ims-deploy/history/deployments.jsonl`, one object
per event (`action`, `result`, `commit`, `artifact_sha256`, `backup`, `flyway_before`,
`flyway_after`, `detail`, `operator`, `host`, `ts`). Results are `success`, `rolled-back`,
`rollback-failed` or `refused`. Local upload history is written to `deploy/.local/history.jsonl`.

Read it with:

```bash
sudo tail -n 20 /var/lib/ims-deploy/history/deployments.jsonl
sudo /usr/local/sbin/ims-deploy-status
```

---

## 7. Git commit tracking

The deployed commit is traceable at three levels:

1. `HEAD` is captured on Windows and enforced clean before building.
2. The full commit is written into the manifest, validated on the server against `--commit` and
   checked against the jar's embedded `git.commit`.
3. `/api/version` returns `gitCommit` from `app.build.git-commit` (defaults to the embedded Gradle
   build-info) so the running process can be checked at any time.

If `/api/version` reports a different commit, the orchestrator logs a warning. Likely causes: the
`GIT_COMMIT` environment variable in `/etc/item-management.env` overrides the embedded value, or
the service did not actually restart. Investigate before considering the deployment finished.

---

## 8. Failure handling and exit codes

| Exit | Meaning | What happened |
|---|---|---|
| 0 | success | artifact deployed and verified |
| 1 | aborted | validation/pre-flight/backup failure; nothing installed |
| 2 | usage | bad arguments |
| 4 | deploy failed | previous artifact restored and service healthy |
| 5 | rollback failed | automatic rollback did not restore health — use `ROLLBACK_GUIDE.md` |

On failure the failed jar is retained as `failed-item-management-<TS>.jar` (mode 0600) for
diagnosis. Frontend failures restore the pre-deployment archive. In both cases the same failure
reason is written to the history file.

Common aborts:

| Message | Cause / action |
|---|---|
| `another deployment is already running` | An orchestrator holds the lock; wait or inspect processes. Never delete the lock file while a deploy runs. |
| `incoming directory must be owned by deploy` / `mode 700` | Server staging permissions drifted; fix ownership/mode, do not chmod the upload itself. |
| `artifact sha256 does not match manifest` | Upload corrupted or was replaced; re-run the Windows upload. |
| `jar commit (...) does not match expected` | Built from a different commit than the manifest; rebuild from a clean checkout. |
| `pending migrations (...)` | Expected; review the SQL and rerun with `--allow-migrations` only with owner approval. |
| `less than 500 MB free on /var` | Free space (old backups/dumps) before deploying. |
| `nginx configuration test failed` | Pre-existing Nginx problem; do not deploy until `nginx -t` passes. |
| `frontend rollback verified` / `rollback succeeded` | The deploy failed but the previous version is healthy; read the `DEPLOYMENT FAILED:` line and the history record. |

---

## 9. Pre-deployment gate (always)

Run the Windows checks immediately before each deployment:

```powershell
powershell -File deploy\windows\Test-DeploymentPrerequisites.ps1 -TestConnectivity
```

- Working tree must be clean (commit or remove untracked files — untracked files also block).
- The SSH key must exist with user-only ACLs; `deploy` must have no sudo beyond the backup script.
- Read-only remote preflight must show `health=200` and the expected jar/index checksums.

Then build/upload, run the root orchestrator, and verify with `ims-deploy-status` and
`Test-DeploymentHealth.ps1`.

---

## 10. Current limitation — one manual privileged step

The deploy account intentionally has only the backup-script sudo permission. Consequently:

- **The Windows scripts end at "uploaded and verified".** They cannot install or restart anything.
- **`ims-deploy-backend` / `ims-deploy-frontend` / `ims-deploy-rollback` / `ims-deploy-status` must be
  run by the owner as root** (directly or through the owner's own admin access).

Fully hands-off deployment for the `deploy` account would require broadening sudo (for example
`systemctl restart item-management` and write access to `/opt` and `/var/www`). That was explicitly
out of scope and was **not** done. Do not add those permissions without a separate security review
and written owner approval; see `DEPLOYMENT_SECURITY.md` for the analysis.
