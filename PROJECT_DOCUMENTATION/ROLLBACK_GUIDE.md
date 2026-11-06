# Rollback Guide

How to recover the Item Management System deployment safely.

> **Approval gate.** Database restore, database recreation, and any destructive operation require
> **explicit written owner approval**. Nothing in this guide has been executed against production.
> Prefer a forward fix over a restore. Always take a backup of the current state before rolling back.

---

## Automated rollback (preferred)

A failed deployment rolls back automatically; an already-deployed release can be rolled back
manually with the same root-owned tooling:

```bash
# newest checksum-verified backup (keeps a safety copy of the current state first)
sudo /usr/local/sbin/ims-deploy-rollback --component frontend
sudo /usr/local/sbin/ims-deploy-rollback --component backend

# or pin a specific backup by plain file name
sudo /usr/local/sbin/ims-deploy-rollback --component frontend \
     --backup frontend-20261106_120000.tar.gz
sudo /usr/local/sbin/ims-deploy-rollback --component backend \
     --backup item-management.jar.bak-20261106_120000
```

What the tool does:

1. takes the deployment lock; accepts a plain file name only (no paths, `..`, backslashes or
   symlinks) and verifies the `.sha256` file before touching anything;
2. requires the name to match `frontend-*.tar.gz` or `item-management.jar.bak-*`;
3. writes a safety copy of the current state (`frontend-pre-rollback-<TS>.tar.gz` or
   `item-management.jar.bak-pre-rollback-<TS>`) with its checksum;
4. **backend:** stops `item-management`, installs the backup jar as `ims:ims 0644`, starts the
   service, waits for local health, then verifies the public sites; **frontend:** extracts to a
   root-owned staging directory, `rsync -a --delete --exclude='.well-known/'`, fixes
   ownership/permissions, runs `nginx -t` and verifies both public sites;
5. appends a `rollback` record (`success` / `rollback-failed`) to
   `/var/lib/ims-deploy/history/deployments.jsonl`.

Exit codes: `0` success, `1` validation/safety failure (nothing changed), `2` usage. The tool does
**not** restore the database or change Nginx configuration.

> The V7/V8 caveats in §1.1 still apply to the automated backend rollback. The tool restores only
> the jar; it does not apply the compatibility shim. Review §1.1 and §10 first, and prefer a forward
> fix. Database restore remains destructive and requires explicit written owner approval (§8).

---

## 1. What can be rolled back, and what cannot

| Component | Rollback method | Reverses schema? | Risk |
|---|---|---|---|
| Frontend bundle | Restore the pre-deployment `tar.gz` | No | Low |
| Backend jar | Restore `item-management.jar.bak-<TS>` and restart | No | **Medium — see §1.1** |
| Nginx config | Restore the site file and reload | No | Low; only if it was changed |
| Environment file | Restore `/etc/item-management.env.bak-<TS>` | No | Low; never needed for V6/V7/V8 |
| Database | Restore the pre-deployment `pg_dump` | Yes | **High — destructive, data loss** |

### 1.1 The V7 limitation and the supported compatibility shim

V7 adds `items.barcode` as `NOT NULL` **without a database default**, and the older backend does not
know that column. After V7 is applied:

- Reverting **only the jar** lets the service start and serve reads, sales and reports.
- The reverted jar **cannot create items or import Excel items** — the INSERT omits `barcode` and
  PostgreSQL rejects it.
- Therefore a jar-only rollback is an **emergency read-mostly measure**, not a full rollback.

Options, in order of preference:

1. **Forward fix** — keep the new jar and V7, ship a corrective release.
2. **Non-destructive compatibility shim** — run `docs/sql/07_rollback_v7_compatibility.sql`, then
   roll back the jar. The script adds a *temporary database default* that generates a unique
   `RB` + 12-digit Code 128 barcode for rows written by the old jar. It does not drop columns, does
   not rewrite existing barcodes, and does not touch Flyway history. When the post-V7 jar is running
   again, remove the shim with `docs/sql/08_remove_v7_rollback_compatibility.sql`; removing the
   default never changes stored rows. Only with owner approval and a code review.
3. **Full database restore** — destructive; loses all data written after the backup; requires
   written approval.

V8 (auth security) is also additive: `users.must_change_password`, `users.token_version` and the
`revoked_tokens` table all have defaults or are new tables, so a pre-V8 jar can run against a V8
schema. A post-V8 jar run against a pre-V8 schema is **not** supported — deploy the jar and V8
together. Rolling back to a pre-V8 jar does not corrupt auth data, but that jar does **not** enforce
forced password changes, per-user token versions or persistent revocation; treat a V8 jar rollback
as a short emergency measure and restore the hardened jar as soon as the cause is fixed. Do not drop
the V8 columns or table during a rollback — they are harmless to the older jar and are needed when
the hardened jar returns.

V6 is fully additive (four new tables, two permissions) and the previous jar can run against a V6
database for reads and writes of existing features. The limitation comes from V7.

### 1.2 V3 is already applied and irreversible without restore

V3 dropped `default_purchase_price` and `default_selling_price`. The current code does not use them.
Do not attempt to “roll back V3” in place; it is part of the production baseline.

---

## 2. Rollback decision tree

```
Something failed?
├── Backend won't start / Flyway error
│   ├── Wrong jar or incomplete upload  → re-upload correct jar, restart
│   ├── Flyway checksum mismatch        → restore the original migration file from the jar; never edit history
│   ├── Failed migration row            → STOP; capture logs; choose forward fix or approved restore
│   └── Schema-validation error         → jar/schema mismatch; deploy the matching jar
├── Backend starts, feature broken
│   ├── Frontend bug only               → restore frontend archive
│   ├── Data/integrity issue            → stop writes; investigate; forward fix
│   └── Unrecoverable                  → jar rollback (emergency) or approved restore
├── Printing broken
│   ├── Bridge/origin issue             → fix client; no server rollback
│   └── Server-side printing bug        → forward fix preferred
└── Customer-facing outage
    ├── Frontend only → restore frontend first (fastest, lowest risk)
    ├── Backend      → jar rollback + accept item-create limitation
    └── Data loss    → STOP writes, escalate, approved restore only
```

---

## 3. Pre-rollback actions (always)

1. **Stop writes** if data integrity is in doubt:
   ```bash
   sudo systemctl stop item-management
   ```
2. **Capture evidence**:
   ```bash
   TS=$(date +%Y%m%d_%H%M%S)
   sudo journalctl -u item-management --since '2 hours ago' --no-pager > /root/ims_incident_${TS}.log
   sudo -u postgres psql -d item_management_prod -c \
     "SELECT * FROM flyway_schema_history ORDER BY installed_rank;" > /root/ims_flyway_${TS}.txt
   sudo -u postgres pg_dump -F c -d item_management_prod -f /root/ims_broken_state_${TS}.dump
   sudo sha256sum /root/ims_broken_state_${TS}.dump
   ```
3. **Notify the owner** and record the decision and approver.
4. **Do not delete anything** — no tables, rows, migrations, logs or backups.

---

## 4. Frontend rollback (low risk)

```bash
TS=<timestamp from deployment backup>
sudo systemctl status item-management --no-pager
sudo rm -rf /var/www/item-management/*
sudo tar -xzf "/root/item-management-frontend-${TS}.tgz" -C /var/www/item-management
sudo find /var/www/item-management -type d -exec chmod 755 {} +
sudo find /var/www/item-management -type f -exec chmod 644 {} +
sudo chown -R www-data:www-data /var/www/item-management
sudo nginx -t
```

No Nginx reload is needed unless the site file changed. Verify:

```bash
ls -l /var/www/item-management/index.html
curl -sS -o /dev/null -w 'frontend HTTP %{http_code}\n' https://REPLACE_WITH_DOMAIN/
```

Users must hard-refresh or reopen the browser because hashed assets changed.

---

## 5. Backend jar rollback (emergency, read-mostly)

Only if the new backend is unusable and a forward fix cannot be produced in time.

```bash
TS=<timestamp from deployment backup>
sudo systemctl stop item-management

# Keep the failed artifact for diagnosis
sudo cp -a /opt/item-management/backend/item-management.jar \
  "/opt/item-management/backend/item-management.jar.failed-$(date +%Y%m%d_%H%M%S)"

sudo cp -a "/opt/item-management/backend/item-management.jar.bak-${TS}" \
  /opt/item-management/backend/item-management.jar

sudo systemctl start item-management
sleep 8
sudo systemctl status item-management --no-pager
ss -ltnp | grep ':8080' || echo 'port 8080 not listening'
sudo journalctl -u item-management --since '5 minutes ago' --no-pager | \
  grep -Ei 'flyway|started|error|exception' | tail -n 60
```

Expected with V6/V7 already applied:

- Service starts and Flyway reports no new migrations.
- Login, dashboard, sales, purchases, reports, printing continue to work.
- **Item creation and Excel import fail** (NOT NULL `items.barcode`).

Immediate mitigations for the item-create limitation:

- Do not run imports or create items until the new jar is restored or a compensating migration is
  approved.
- Record affected user actions for later reconciliation.
- Restore the new jar as soon as the fix is ready.

If the service refuses to start because Flyway sees a newer schema, **do not delete Flyway history**.
The previous jar normally tolerates a newer applied version; if it does not, a forward fix is the only
safe path short of an approved restore.

---

## 6. Nginx rollback (only if the site file changed)

```bash
sudo cp -a /etc/nginx/sites-available/item-management \
  /etc/nginx/sites-available/item-management.failed-$(date +%Y%m%d_%H%M%S)
sudo cp -a /etc/nginx/sites-available/item-management.bak-<TS> \
  /etc/nginx/sites-available/item-management
sudo nginx -t && sudo systemctl reload nginx
```

If no backup of the site file exists, reconstruct it from `ADMIN_DEPLOYMENT_GUIDE.md` § 4.4 and have a
second person review it before reloading.

---

## 7. Environment rollback

Only if the env file was modified during the deployment. It should not have been.

```bash
sudo cp -a /etc/item-management.env.bak-<TS> /etc/item-management.env
sudo chmod 600 /etc/item-management.env
sudo systemctl restart item-management
```

Never rotate `JWT_SECRET` as part of a rollback — it invalidates every active session. Never change
`DB_PASSWORD` unless the database password was actually changed.

---

## 8. Database restore (destructive — written approval required)

### 8.1 When it is justified

- A migration left the schema inconsistent and no forward migration can repair it.
- Data corruption is confirmed and the pre-deployment backup is known good.
- The owner has approved the loss of all data written after the backup.

### 8.2 Preconditions

- [ ] Written owner approval recorded.
- [ ] The broken-state dump from §3 exists and is readable.
- [ ] The pre-deployment dump exists and passes `pg_restore --list`.
- [ ] The application is stopped.
- [ ] All users notified of maintenance.
- [ ] A second person is available to verify counts after restore.

### 8.3 Procedure

```bash
# 1. Stop the application
sudo systemctl stop item-management
sudo systemctl is-active item-management || true

# 2. Record current counts for the incident report
sudo -u postgres psql -d item_management_prod -c \
  "SELECT (SELECT COUNT(*) FROM items) AS items,
          (SELECT COUNT(*) FROM sales) AS sales,
          (SELECT COUNT(*) FROM purchases) AS purchases,
          (SELECT COUNT(*) FROM users) AS users;"

# 3. Terminate remaining connections
sudo -u postgres psql -d postgres -c \
  "SELECT pg_terminate_backend(pid) FROM pg_stat_activity
   WHERE datname = 'item_management_prod' AND pid <> pg_backend_pid();"

# 4. Recreate the database (DESTRUCTIVE)
sudo -u postgres dropdb --if-exists item_management_prod
sudo -u postgres createdb -O ims_user item_management_prod

# 5. Restore the pre-deployment dump
sudo -u postgres pg_restore -d item_management_prod --no-owner --role=ims_user \
  /root/item_management_pre_deploy_<TS>.dump

# 6. Verify schema and data
sudo -u postgres psql -d item_management_prod -c \
  "SELECT installed_rank, version, description, success FROM flyway_schema_history ORDER BY installed_rank;"
sudo -u postgres psql -d item_management_prod -c \
  "SELECT (SELECT COUNT(*) FROM items) AS items,
          (SELECT COUNT(*) FROM sales) AS sales,
          (SELECT COUNT(*) FROM purchases) AS purchases,
          (SELECT COUNT(*) FROM users) AS users;"

# 7. Restore the previous jar and frontend, then start
sudo cp -a /opt/item-management/backend/item-management.jar.bak-<TS> \
  /opt/item-management/backend/item-management.jar
sudo systemctl start item-management
sudo systemctl status item-management --no-pager
```

Notes:

- The restored database contains the schema level of the backup (V1–V5). The previous jar must match
  that level. Do **not** start the new jar against the restored database unless you intend to
  re-apply V6/V7.
- Restore the matching frontend archive as well, or keep the new frontend only if its API usage is
  backward compatible (it is for reads, but barcode UI will fail without V7).
- `--no-owner --role=ims_user` avoids ownership conflicts.
- Never restore over the production database while the service is running.

### 8.4 Post-restore verification

- [ ] Flyway history matches the backup.
- [ ] Row counts match the pre-deployment record (allow for expected differences documented in the
      incident log).
- [ ] Login works for a store user and a platform admin.
- [ ] One read-only sale report loads.
- [ ] One new sale posts and decrements stock.
- [ ] Printing still works (V6 jar + V1–V5? If printing tables were in the backup, fine; otherwise
      printing is unavailable until V6 is re-applied).
- [ ] Owner sign-off recorded.

---

## 9. Rollback of platform-account changes

Platform account management is API/UI only; it adds no schema. To disable it without a restore:

- Deploy the previous frontend (hides the Store Accounts screen), and/or
- Deploy the previous jar (removes `/api/platform/accounts`).

No data migration is involved. Existing user records and password hashes are unchanged. If a password
was reset during the incident, the affected user must use the temporary password or receive another
reset after recovery — do not attempt to recover the old hash.

---

## 10. Rollback of barcode changes

Barcode support is additive. To disable it without a restore:

- Deploy the previous frontend (hides barcode UI).
- Keep the new backend or deploy the previous jar (see §5 limitation).
- Do **not** drop the `barcode` column or index in production. They are harmless and dropping them
  requires an approved destructive migration.

### 10.1 Supported jar rollback after V7

Use the shim instead of accepting the item-create limitation:

```bash
# 1. Stop the application so no INSERT runs during the change
sudo systemctl stop item-management

# 2. Apply the additive compatibility shim (takes a brief ACCESS EXCLUSIVE lock)
sudo -u postgres psql -v ON_ERROR_STOP=1 -d item_management_prod \
  -f /root/ims/docs/sql/07_rollback_v7_compatibility.sql

# 3. Roll back the jar (see §5) and start it
sudo cp -a "/opt/item-management/backend/item-management.jar.bak-<TS>" \
  /opt/item-management/backend/item-management.jar
sudo systemctl start item-management
```

Verify the shim:

```bash
sudo -u postgres psql -d item_management_prod -c \
  "SELECT column_default FROM information_schema.columns
   WHERE table_name='items' AND column_name='barcode';"
sudo -u postgres psql -d item_management_prod -c \
  "SELECT COUNT(*) FROM items WHERE barcode IS NULL;"
```

After the post-V7 jar is running again, remove the shim:

```bash
sudo -u postgres psql -v ON_ERROR_STOP=1 -d item_management_prod \
  -f /root/ims/docs/sql/08_remove_v7_rollback_compatibility.sql
```

The script lists how many `RB…` barcodes were generated while the shim was active. Keep those rows;
relabel the items through the normal barcode API if desired. Never delete affected rows.

### 10.2 No destructive automatic rollback

No migration in this release deletes barcode data. V7 only adds and backfills; V8 only adds columns
and a table. There is no automatic down-migration. Recovery is always the forward fix, the shim
above, or an approved restore.

---

## 11. Communication template

```
Subject: IMS deployment status — [COMPLETED / ROLLED BACK / INCIDENT]

Release: <commit / jar hash>
Window: <start>–<end>
Status: <what happened>
Customer impact: <none / specific>
Actions taken: <deploy / rollback / restore>
Data impact: <none / data after HH:MM lost (restore only)>
Backups: <paths + hashes>
Next step and owner: <...>
```

---

## 12. Rollback checklist

- [ ] Incident logged with timestamps.
- [ ] Owner approval recorded (required for database restore).
- [ ] Broken-state dump created.
- [ ] Frontend rollback attempted first when the issue is UI-only.
- [ ] Jar rollback understood to be read-mostly under V7.
- [ ] No Flyway history edited or deleted.
- [ ] No table/row deletion.
- [ ] No secret rotated.
- [ ] Verification queries run after recovery.
- [ ] Users notified.
- [ ] Backup and rollback artifacts retained until the next successful release.
- [ ] Post-incident review scheduled.
