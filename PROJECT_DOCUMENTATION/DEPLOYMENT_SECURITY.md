# Deployment Security Model

Security properties, permissions and residual risks of the automated deployment workflow in
`deploy/`. Read this together with `DEPLOYMENT_GUIDE.md`.

> **Status: not production-ready until the privileged step is exercised by the owner.** The deploy
> account cannot perform the install/restart/rollback step by design. Treat that step as a manual
> owner action, not something the `deploy` account can do.

---

## 1. Objectives

1. The `deploy` account must not be able to gain root, edit system configuration, read secrets, or
   run arbitrary privileged commands.
2. Only a validated, checksummed artifact built from a specific clean Git commit may be installed.
3. The current jar, frontend and database are backed up and verified before any replacement.
4. Every deployment and rollback is recorded with the commit and artifact checksum.
5. No secret (database password, JWT secret, private key, key passphrase) is read, printed, stored
   in the repository, or written to logs.
6. Nginx, systemd, SSH, sudoers and `/etc/item-management.env` are not modified by the automation.
7. Database schema changes happen only through Flyway migrations reviewed and explicitly approved
   by the owner.

---

## 2. Identities and privileges

| Identity | Purpose | Privileges |
|---|---|---|
| `deploy` | SSH upload account | login + write to `/var/lib/ims-deploy/incoming`; **one** sudo permission: `/usr/local/sbin/ims-create-deployment-backup` |
| `ims` | runs the backend service | read the jar, write runtime data; no shell login assumed |
| `www-data` | serves the frontend | read-only on `/var/www/item-management` |
| owner/admin | runs the root orchestrators | full administrative access to the server |
| Windows user | builds and uploads | local filesystem + SSH key access |

### Required sudoers state

```sudoers
deploy ALL=(root) NOPASSWD: /usr/local/sbin/ims-create-deployment-backup
```

Nothing else. Verify (read-only):

```bash
sudo -l -U deploy
```

Expected: exactly one command entry, the backup script. The installer prints this requirement and
**never** edits sudoers. Do not add `systemctl`, `install`, `rsync`, `bash`, `tee`, editors or
wildcards — any of those would effectively grant root.

### Why the split exists

Installing a jar (`/opt/item-management/backend`), restarting `item-management`, and writing
`/var/www/item-management` are root operations. Granting them to `deploy` would let a compromised
upload account replace the application, alter the served site, or restart services at will. Instead:

- `deploy` can only upload into a private staging directory (`deploy:deploy 0700`);
- a root-owned orchestrator copies, validates and installs the artifact.

---

## 3. SSH and key handling

- One dedicated Ed25519 key (`ims_prod_deploy`) with a strong passphrase; the private key never
  leaves the Windows profile and is never printed, uploaded or committed.
- The private key file ACL is restricted to the Windows user only. Verify with:

  ```powershell
  icacls "$env:USERPROFILE\.ssh\ims_prod_deploy"
  ```

  Only the owning user (and `SYSTEM`/`Administrators` if inherited by policy) may have access. If
  `Users`, `Authenticated Users` or `Everyone` appear, restrict the ACL before using the key.
- `deploy.config.psd1` is git-ignored and contains only host/user/port/paths and the key **path** —
  no passphrase or token.
- The automation does not change `sshd_config`, `authorized_keys`, or the root login policy. Root
  password SSH and existing accounts are untouched.
- Recommended (not applied): restrict SSH source addresses, keep `PasswordAuthentication no` for
  `deploy`, and monitor auth failures. These require owner approval and are outside this change.

---

## 4. Installer trust model (`install-ims-deploy.sh`)

The installer runs as root and therefore trusts its own source tree. Safeguards:

| Check | Behavior |
|---|---|
| Source path | refuses `/var/lib/ims-deploy/*` (the deploy-writable area) |
| Ownership | source directory and every file must be owned by `root` |
| Permissions | source directory must not be group/other-writable; files `0755`/`0644` |
| Symlinks | source files must be regular files, not symlinks |
| Syntax | `bash -n` on every script before install |
| Accounts | `deploy` and `ims` must already exist |
| Backup script | must exist, be executable and root-owned |
| Integrity | post-install `cmp` of every installed file |
| Scope | does not touch sudoers, SSH, Nginx, systemd or the env file |

Installed files: `/usr/local/sbin/ims-deploy-*` (`root:root`, `0755`; the library `0644`) and the
directories listed in `DEPLOYMENT_GUIDE.md`. Verify with
`sudo /root/ims-src/install-ims-deploy.sh --check` (prints `OK` or `DRIFT`).

---

## 5. Artifact validation pipeline

Everything uploaded by `deploy` is untrusted until validated by the root orchestrator.

### 5.1 Staging (TOCTOU)

1. Incoming directory must be a real directory (not a symlink), owned by `deploy`, mode `0700`.
2. Artifact must be a regular file, not a symlink.
3. The orchestrator copies it with `install -o root -g root -m 0644` into a fresh `root:root 0700`
   work directory and validates **only that copy**. The deploy account cannot modify or swap the
   staged file after validation.
4. The manifest is likewise staged and required to be root-owned `0644`.

### 5.2 Manifest (`schema=1`, key=value)

| Key | Validation |
|---|---|
| `schema` | must be `1` |
| `type` | must equal `backend` or `frontend` |
| `commit` | exactly 40 lowercase hex characters |
| `short_commit` | 7–12 lowercase hex characters and a prefix of `commit` |
| `built_at_utc` | `YYYY-MM-DDTHH:MM:SSZ` |
| `artifact` | fixed name: `item-management.jar` or `frontend.tar.gz` |
| `sha256` | exactly 64 lowercase hex characters |
| `size` | digits only, > 0, ≤ 300 MB (jar) / 200 MB (frontend) |

Unknown keys, duplicate keys, missing keys, malformed lines and a manifest type mismatch abort the
deployment. `--commit` must equal the manifest commit.

### 5.3 Artifact contents

Backend jar (`unzip`):

- archive integrity (`unzip -t`), ≤ 20,000 entries, no traversal checks needed because nothing is
  extracted from the jar;
- Spring Boot layout: `BOOT-INF/classes/`, `BOOT-INF/lib/`, `META-INF/MANIFEST.MF`;
- `META-INF/build-info.properties` (or the legacy `BOOT-INF/classes/META-INF/build-info.properties`)
  exists and its `build.git.commit` (or `git.commit`) is a prefix of the requested commit;
- at least one `BOOT-INF/classes/db/migration/V*__*.sql` entry (migration gate input).

Frontend archive (`tar`):

- gzip/tar integrity (`tar -tzf`), 1–5,000 entries;
- rejects absolute paths, `..` segments, backslashes and NUL/blank names;
- rejects symbolic links and block/char/fifo/socket entries;
- requires a root `index.html` and an `assets/` tree;
- after extraction into a root-owned directory, re-checks `index.html` is a regular file, `assets`
  is a directory, and no symlink exists anywhere in the tree.

### 5.4 Rollback input

`ims-deploy-rollback --backup <name>` accepts a **plain file name only** (regex
`^[A-Za-z0-9._-]+$`, no `/`, `\`, `..`), requires a sibling `.sha256` that verifies, requires the
file to be inside `/var/backups/item-management` and not a symlink, and requires the name to match
the expected component pattern. No caller-supplied path or shell fragment is ever executed.

---

## 6. Pre-flight and database safety

- Deployment is refused unless `item-management` is active, the current jar exists, `nginx -t`
  passes, and there is ≥ 500 MB free on `/var`.
- Deployment is refused if `flyway_schema_history` contains any `success = false` row.
- Pending migrations (jar migrations not yet applied) stop the deployment. The owner must review
  the SQL and pass `--allow-migrations`; the refusal is recorded in history. Flyway, not the deploy
  script, applies migrations on startup. No SQL is executed by the automation.
- The automation never restores, drops or recreates the database. Database restore remains a
  separate, owner-approved procedure (`ROLLBACK_GUIDE.md`).

---

## 7. Secrets hygiene

- The scripts never read `/etc/item-management.env` and never print its contents.
- `psql` runs as the `postgres` system user; no password is passed on a command line or stored in
  an argument.
- Logs contain paths, service names, commit hashes, checksums and HTTP status codes only.
- The Windows module stores no credentials. The SSH key passphrase is requested by OpenSSH.
- `.gitignore` excludes `deploy/windows/deploy.config.psd1` and `deploy/.local/` (history and
  manifest copies). Never commit those.

---

## 8. Concurrency and audit

- A single `flock`-based lock (`/var/lib/ims-deploy/.deploy.lock`) serializes all deploys and
  rollbacks. A second run exits immediately; the lock file is not deleted while held.
- Server history (`/var/lib/ims-deploy/history/deployments.jsonl`, `root:root 0750`) is
  append-only from the scripts' perspective and records commit, artifact checksum, backup path,
  Flyway versions, result, operator (`SUDO_USER`) and host.
- Windows upload history (`deploy/.local/history.jsonl`) records the upload and that the root step
  is still pending (`uploaded-awaiting-root`) — never a false success.

---

## 9. Residual risks and explicit non-goals

| Risk / gap | Status |
|---|---|
| **One manual privileged step** — `deploy` cannot install/restart | Accepted; requires the owner to run the orchestrator. Not "fully automated". |
| `deploy` can write arbitrary files into its own incoming directory | Mitigated: root stages and validates copies; only fixed artifact names are consumed. |
| A compromised Windows build machine can upload a malicious artifact | Mitigated by SHA-256, manifest, clean-git gate and jar commit check; not eliminated — the root operator must trust the build machine. |
| Root orchestrator code is trusted once installed | Protected by root-owned source tree and post-install `cmp`; review changes before reinstalling. |
| No database restore automation | Intentional: destructive and requires explicit written owner approval. |
| No Nginx configuration change | Intentional: site config is not modified or reloaded; `nginx -t` is verification only. |
| No sudo/SSH/systemd/env changes | Intentional; the installer only adds root-owned scripts and directories. |
| Root password SSH policy unchanged | Intentional; not part of this change. |
| Key passphrase strength | Owner responsibility; never stored by the tooling. |

**Not implemented, by design:** passwordless sudo for service control, a deploy-account systemd
unit, remote command execution from the server, automatic Git pull on the server, automatic schema
changes, and automatic database rollback.

---

## 10. Security verification checklist

Run before the first real deployment and after any change to the scripts:

```bash
# 1. sudo privilege is unchanged (exactly one command)
sudo -l -U deploy

# 2. installed scripts match the reviewed source
sudo /root/ims-src/install-ims-deploy.sh --check

# 3. staging directory is not world/group accessible
stat -c '%U:%G %a %n' /var/lib/ims-deploy /var/lib/ims-deploy/incoming \
      /var/lib/ims-deploy/work /var/lib/ims-deploy/history /var/backups/item-management

# 4. secrets are not in the repo
git -C <repo> grep -nE 'BEGIN (OPENSSH|RSA|EC) PRIVATE KEY|JWT_SECRET|DB_PASSWORD' || true
```

Windows:

```powershell
powershell -File deploy\windows\Test-DeploymentPrerequisites.ps1
icacls "$env:USERPROFILE\.ssh\ims_prod_deploy"
git check-ignore deploy\windows\deploy.config.psd1 deploy\.local\history.jsonl
```

Expected: one sudo entry; all `stat` modes as documented (`0755`/`0700`/`0700`/`0750`/`0700`); the
key ACL lists only the owning user; both local paths are ignored by Git.

---

## 11. Incident response

If an uploaded artifact or the deploy account is suspected compromised:

1. **Do not run** `ims-deploy-*`. Inspect the manifest and checksums in the incoming directory:
   `stat` ownership/mode, `sha256sum`, and compare with the value produced on Windows.
2. Remove or quarantine the staged files (root action). Do not execute them.
3. Review `/var/lib/ims-deploy/history/deployments.jsonl` and `journalctl -u item-management`.
4. Verify the running artifacts against the newest verified backups and `/api/version`.
5. Rotate the deploy SSH key (new passphrase) and update `deploy.config.psd1`; remove the old
   public key from `authorized_keys` only with owner approval.
6. If the service or site was modified, follow `ROLLBACK_GUIDE.md`; a database restore still needs
   explicit written owner approval.
