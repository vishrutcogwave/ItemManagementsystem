# Isolated IMS browser and report QA

Run this only against a disposable local database and a loopback-only IMS instance. The script creates a uniquely named store and creates records through IMS. It reads PostgreSQL through a read-only JDBC connection. It does not dispatch print jobs to a physical bridge.

1. Build the frontend and the backend JAR with `npm run build` and `gradlew.bat bootJar` from the respective folders. The Java regression command is `gradlew.bat test`.
2. Start the JAR with its HTTP listener bound to `127.0.0.1:58080`, embedded PostgreSQL port `55483`, and a fresh `app.embedded-postgres.data-dir` beneath the backend build folder. Disable demo seeding, opt into provisioning the first platform administrator, and set a temporary username, email and password. Allow the QA frontend origin `http://127.0.0.1:55173`.
3. Start Vite on `127.0.0.1:55173` with its `/api` proxy targeting `http://127.0.0.1:58080`. A Vite inline configuration can override the default development port and target.
4. Set `IMS_QA_JDBC` to the PostgreSQL JDBC JAR, and optionally `IMS_QA_JAVA` to the Java 17 executable. Use an available Playwright installation or set `IMS_QA_PLAYWRIGHT` to the installed Playwright Core `index.mjs` entry. The runner uses Windows Chrome. Use `IMS_QA_ADMIN`, `IMS_QA_ADMIN_PASSWORD`, and `IMS_QA_ADMIN_ROTATED` to match the temporary QA administrator.
5. For a second isolated instance, set `IMS_QA_BASE` to its loopback frontend URL and `IMS_QA_PGPORT` to its disposable PostgreSQL port. Run `node qa/ims-e2e.mjs <artifact-directory>` from the repository root. Review `ims-e2e-results.json`, screenshots, downloads and PDFs. The runner exits nonzero for failed assertions.

Report reconciliation uses one controlled sale of ₹105 with ₹5 GST and one completed ₹105 purchase with a ₹30 supplier allocation. The initial report snapshot precedes the additional browser checkout. Permission checks use a separately authenticated staff session. Bridge success and failure use a browser-local mock. The paper PDFs come from Chromium, with no native print request.

The suite covers selected values on every report. It does not exhaust every combination of report filters, sort orders, exports, input domains, or browsers.

## Additional regression checks

- Run `node qa/anonymous-api.mjs <results.json>` against the loopback backend. Set `IMS_QA_API` if its port differs from 58080. The script checks anonymous access on protected controller routes and reachability of the public health, version and contact routes.
- For the migration rehearsal, extract `BOOT-INF/lib` and `BOOT-INF/classes/db/migration` from the built backend JAR. Run `qa/MigrationUpgradeCheck.java` with Java 17 source-file mode, both extracted directories on the classpath (use `BOOT-INF/lib/*` for the libraries), and a new disposable database directory as its only argument. The script starts its own embedded PostgreSQL and verifies the V13 to V14 upgrade with historical data.
