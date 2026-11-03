# Merchant Payments and Reconciliation

A Java 21 local sandbox for fictional merchant payments, accounting, settlements, payouts and reconciliation. Money is simulated USD cents. Use only `example.test` or `demo.invalid` email addresses, the four named simulator tokens, and simulator-issued beneficiaries. Live providers and production deployment are outside this application.

The repository contains a Spring Boot 4.1.2 modular application and an independent provider simulator, each with its own PostgreSQL 17 database and credentials. Browser pages use Spring MVC, Thymeleaf, Spring Security and server-side JDBC sessions. Spring Data JPA owns payment projection reads; explicit JDBC transactions coordinate financial writes. Kafka carries durable work, provider inbox references and domain notifications; PostgreSQL owns accepted responsibility. There is no required Python, Node, Redis or external asset CDN runtime.

## Local setup

Prerequisites: Docker Engine with BuildKit and Compose v2, a POSIX shell, OpenSSL and curl. The reference target is Linux with 4 CPUs, 8 GiB RAM and at least 10 GiB free; performance qualification results are recorded separately in the implementation report. The first build downloads pinned Maven artifacts and images. BuildKit caches public Maven dependencies between builds; an empty cache works with Maven Central access. Only web and Mailpit bind to loopback.

Run from this repository root:

```sh
sh scripts/local.sh init
sh scripts/local.sh migrate
sh scripts/local.sh seed-demo
sh scripts/local.sh up
sh scripts/local.sh health
```

`init` refuses to overwrite `.local/environment.env`. This private file contains independent random credentials and environment identity; keep it private. Change `WEB_PORT` and `MAIL_PORT` there before startup if ports conflict. Defaults are [application](http://127.0.0.1:8180) and [captured email](http://127.0.0.1:8026). The `migrate` command explicitly builds image-contained source, starts infrastructure and applies Flyway migrations with migration credentials. Normal web/worker processes have no DDL authority or simulator database connection.

The optional seed creates Juniper Goods, two independent owners, finance, analyst, operations, reconciliation, finance approver and auditor identities. `.local/demo-access.txt` contains generated passwords and TOTP seeds with owner-only filesystem permissions. Seeding an existing environment refuses to replace credentials. Sign in with a generated address and password, then use its seed in a TOTP authenticator. Codes are consumed globally; wait for a fresh 30-second code after login before a sensitive step-up. Every business page requires verified email and completed MFA. Sensitive approval uses [step-up](http://127.0.0.1:8180/accounts/step-up/) with password and fresh code; it lasts five minutes in that session.

New registrations create PENDING merchants. Open Mailpit to retrieve the verification URL, confirm it with POST, enroll an authenticator, then sign in as operations to activate the merchant. Invitations bind an exact fictional email and issuing owner. Analyst accounts inspect but cannot mutate financial records. A platform role grants only its designated staff responsibilities.

Without demo seeding, provision the first operations identity through the privileged local command below. Create the new password in a regular owner-only file (`chmod 600`), use a new stable UUID for the accountable local operator, and replace the example file path. The password must meet the same password rules of 15–128 characters as registration. No password or authenticator code belongs in command arguments.

```sh
sh scripts/account-admin.sh bootstrap-staff operator@example.test OPERATIONS OPERATOR_UUID 'Initial fictional environment operator' /absolute/private/new-password
```

The first identity signs in and enrolls MFA. Later staff provisioning uses the same command with two additional private files: the current operations actor's password and fresh TOTP. Supported staff roles are `OPERATIONS`, `RECONCILIATION`, `FINANCE_APPROVER` and `AUDITOR`. Lost-factor recovery uses `sh scripts/account-admin.sh recover-mfa EMAIL OPERATOR_UUID REASON OPERATOR_PASSWORD_FILE OPERATOR_TOTP_FILE`. This audits the reason, revokes sessions and existing factors, and preserves roles and merchant ownership. The narrowly limited sole-operations owner-recovery mode is `MPRP_ACCOUNT_OWNER_RECOVERY=true`; it still requires the separate owner database credential and the same recorded operator identity. These operator commands do not grant web processes privileged database credentials.

```sh
sh scripts/local.sh logs web
sh scripts/local.sh logs worker
sh scripts/local.sh stop
sh scripts/local.sh up
sh scripts/local.sh restart
```

Ordinary stop preserves every project volume. Restart reuses run identity and recovers due jobs, expired leases, unpublished events and retained inbox events. It never turns an uncertain monetary result into a decline or a fresh provider attempt. Do not remove volumes to troubleshoot.

## Complete sandbox walkthrough

1. Sign in as owner. Open Juniper Goods → Payments and links → Create payment link. Enter `100.00`, `AUTOMATIC`, and `1440`. Copy the one-time URL; only its digest remains stored.
2. Open checkout in a guest browser, enter a fictional name and `tok_demo_success`, and confirm. Refresh the browser-scoped receipt until captured. Merchant inspection shows the durable operation. The journal posts a capture of 10,000 cents and a fee of 320 cents.
3. Refund `25.00` with a reason. Inspect the retained operation and payment until the refund of 2,500 cents completes. Merchant payable is 7,180 cents.
4. Sign in as operations and open `/operations/sandbox/`. Flush the selected merchant's settlement with a stable cutoff identity. Repeating that cutoff inspects the existing batch. Bank control becomes 7,180 cents when its authenticated transfer is applied.
5. As reconciliation, open `/operations/imports/from-simulator/` and request each of `PROCESSOR_ECONOMIC`, `PROCESSOR_TRANSFER` and `BANK` for the merchant and a UTC interval containing the activity. This obtains immutable manifests and exact CSV bytes. Preview staged evidence, then confirm. Uploads can also supply those same authenticated bytes and metadata. Staging, confirmation and matching never create money.
6. Open the reconciliation workspace, run exact automatic matching, and investigate ambiguity. Group forms support one line to several sources or several lines to one source, same evidence layer/sign. Reversal retains history and requires an open period.
7. As finance or owner, propose the beneficiary `acct_demo_` followed by the merchant/provider UUID without hyphens. A different owner performs step-up and approves it. Propose a payout within settled availability; the different owner independently approves its frozen destination and amount. Inspect the payout, journal, settlement and exports.
8. To test uncertainty, create a second link and use `tok_demo_timeout_after_commit`. The simulator commits independently and loses the ordinary response. The application retains the original key and resolves through authenticated events or the atomic resolver. UNKNOWN is visible until a definitive observation applies. Never replace its key or release its reservation because time passed.

For manual authorization select `MANUAL`, then make one final capture, including a partial capture of at least 100 cents, or void before the exact seven-day expiry. Four simulator tokens are supported: `tok_demo_success`, `tok_demo_decline`, `tok_demo_timeout_after_commit`, `tok_demo_requires_review`; the last is terminal rejection.

## Verification

```sh
./mvnw verify
sh scripts/verify-simulator.sh
sh scripts/verify-ledger.sh
sh scripts/verify-history.sh
sh scripts/verify-recovery-script.sh
sh scripts/verify.sh
```

Java 21 is required for host Maven builds. The wrapper fixes Maven 3.9.15 and verifies its distribution checksum. Unit builds explicitly skip conditional real-service suites unless their isolated configuration is supplied. A successful unit build does not establish composed, database, broker or browser acceptance. `verify-simulator.sh`, `verify-ledger.sh` and `verify-history.sh` use uniquely named PostgreSQL containers and trap cleanup. `verify-history.sh` selects nine history and three closure PostgreSQL cases plus two replay unit cases. `verify-recovery-script.sh` uses a fake Docker executable and creates no live service resources. `verify.sh` builds and runs a uniquely named isolated Compose environment with fresh identities, independent volumes/ports and external evidence output. It removes only its own verification resources. Browser checks use pinned Java Playwright; see `scripts/verify-browser.sh` for required isolated URL and private credential inputs. Test evidence and screenshots belong outside the source tree.

The browser runner defaults to all three engines and a 15-second functional action deadline. For a scoped investigation, `MPRP_BROWSER_ENGINE=webkit` selects one engine, `MPRP_BROWSER_FINANCE_ENGINE=webkit` assigns the financial journey, and `MPRP_BROWSER_JOURNEY=registration` reruns only registration after the shared access checks. `MPRP_BROWSER_TIMEOUT_MS` accepts 15000–60000; an increased functional timeout is not a latency or capacity qualification. Preserve the original failed run and record every override with its narrower scope.

## Accounting and durable responsibility

`payments.PaymentService` validates admission under current authority and the merchant control lock. `operations.OperationService` reserves the scoped action identity and commits operation, job, audit and outbox together. Equivalent retries inspect their operation; changed input returns 409. Rejected input consumes no key. `OperationWorker` commits a dispatch claim, releases local locks before provider HTTP and applies a validated result in a separate transaction. Lease owner, generation, run, epoch and expiry fence completion. Unknown work retains reservations and uses the original atomic provider resolver. Only provably unsent jobs can cancel.

`accounting.LedgerService` recognizes authenticated economic sources and posts immutable double-entry journals. `V3__payments_accounting.sql` enforces source/merchant/period/currency scope, positive entries, exact balanced totals and account mapping at real commit. `V4__workflows.sql` guards allocation capacity, layer, sign, account and complete groups. `V6__guard_registry.sql` pins actual database guard definitions; capability readiness compares them. Deficits are visible, and clearing receivables cannot fund payouts. Correcting posted history means a new source, never an edit.

`settlements.FinancialWorkflowService` coordinates distinct-owner approvals, frozen beneficiaries, settled availability and reservations. `disputes.DisputeService` applies ordered full history. `reconciliation.StatementService` validates entire CSVs, encrypts bounded staging and commits exact manifest confirmation atomically; expiry persists even when the request returns an error. `MatchService`, `CorrectionService` and `PeriodService` own evidence allocations, independent correction decisions and retained close/reopen snapshots. Split manifests retain every partition and high-water.

Controllers bind strict forms and choose HTML/303 results; services own transactions. Queries use UTC instants, UUIDs and checked integer cents; accounting months are first-day UTC dates. JPA `@Version` handles stale projection input and does not replace funds locks. New migrations increment Flyway versions and must preserve posted history. Do not change an already-applied migration in a populated environment; add a forward migration. This fresh source baseline is not compatible with an existing Python database.

## Kafka

Topics/groups are prefixed `mprp.<environment UUID>.s1.`. The four topic suffixes are `work.v1`, `provider-events.v1`, `domain-events.v1`, `failures.v1`; three partitions, seven-day retention, 256 KiB maximum records, local single broker replication 1. Keys are merchant UUIDs or account UUIDs for account-only work. Kafka 4.2.2 is pinned; the exact Boot-managed client version is recorded in the dependency inventory.

`KafkaTransport` leases an outbox head per aggregate/stream, publishes with idempotence and `acks=all`, and marks sent only after acknowledgment. A crash in that gap duplicates delivery. Consumers disable automatic offset commit and commit the database receipt/disposition before acknowledging offsets. A callback inbox, a Kafka consumer receipt and a provider key are distinct identities. Equal IDs with changed content quarantine; sequence gaps retain deferred envelopes without applying them early. Database discovery recovers accepted jobs/inbox after broker outages or retention expiry. Admission counters bound fresh work at 10,000 jobs and 100,000 unpublished events; accepted effects and recovery have reserved capacity. There is no claim of an atomic PostgreSQL/Kafka/provider transaction.

## Recovery and retention

```sh
sh scripts/local.sh backup
sh scripts/local.sh restore .local/backups/<timestamp>
```

Backup stops all effect/publication processes, creates separate database dumps plus broker position inventory under an environment sentinel, then restarts. Local backups expire after 30 days; preserve encryption keys separately. Restore requires typing the exact environment UUID, affects only this Compose project and enters recovery hold. `/operations/recovery/` creates an immutable provider/local inventory; a different current finance approver releases only unchanged, fully reconciled evidence. Missing admissions, journals or completion records keep hold. Ordinary Kafka offsets do not prove absence of independently committed provider money.

An interrupted paired restore retains a private marker next to the environment file. Rerun the restore command with the same unmodified backup directory; its path and both dump digests must agree. Startup, restart, migration, seeding, rotation and backup stay blocked until both database imports and both recovery flags succeed. A shared environment action lock also prevents startup from racing the restore. Database dump/import and broker inventory commands have finite in-container deadlines. `sh scripts/verify-recovery-script.sh` checks these interruption, resume, concurrency and retention command paths with a fake Docker executable; it does not perform or qualify an actual database restore.

After 365 days closed, `/operations/purges/` permits an operations proposal and independent finance approval with explicit backup-store inventory. The separate destructive operator command `sh scripts/purge.sh PURGE_ID MERCHANT_UUID PROVIDER_INVENTORY_HASH BACKUP_DIRECTORY` requires explicit typed merchant identity, separate purge database credentials, permanent simulator retirement, provider purge completion and resumable local receipt. Web and ordinary workers receive no purge credentials. Inspect archived backups explicitly before purging; purging current databases does not rewrite retained backup files. Retired namespaces and provider/action identities remain permanently reserved.

Before an owner requests closure, drain balances and finish accepted financial work while the merchant remains ACTIVE. Flush every outstanding batch, including a zero-net batch, and complete any required payout. A blocked request returns 409 and preserves its status; operations can resume a suspended merchant when appropriate. After the request enters CLOSING, finish reconciliation and close periods before operations completes closure. Pending provider callbacks/history, missing batch membership or transfer evidence block closure and purge.

Large authenticated provider results retain a compact immutable summary and recover the fixed source snapshot through 250-row pages. The worker validates the whole snapshot before posting resumable chunks of at most 100 nontransfer sources, then finalizes transfers and projections. `/operations/provider-history/` shows retained progress and safe failure reasons; a current operations actor with step-up can redrive the same task and original key. More than 10,000 sources stays visibly MANUAL and needs an implementation extension; redrive cannot bypass that bound.

For encryption rotation, stop web and both workers, retain the old key under its unchanged ID, and configure a new `MPRP_ENCRYPTION_KEY_ID` plus 32 random bytes in `MPRP_ENCRYPTION_KEY`. `MPRP_ENCRYPTION_PREVIOUS_KEYS` uses `id=base64;id=base64` entries; quote this value in the shell-sourced private environment file. Run `sh scripts/local.sh rotate-encryption`; it revokes sessions and re-encrypts retained seeds, mail, exports and staging in committed batches. Rerun an interrupted bounded command with the same key ring before restarting. Keep prior keys for retained backups; assigning different material to an existing key ID is refused. This command's full persisted rotation and interruption behavior remains unqualified by the current execution receipts.

## Diagnostics and configuration

`/health/live/` means the JVM responds. `/health/ready/` reports read, financial_admission, callback, worker, reconciliation, notification and recovery separately. New money requires safe database roles, exact guards, pairing, fresh worker/provider/broker, safe clock, capacity and no restore hold. Health probes never consume provider keys or create money. Staff diagnostics and unknown-operation inventory are at `/operations/status/`; audit at `/operations/audit/`.

For port conflicts choose unused loopback ports in private configuration. For Kafka unavailable or a stale worker inspect worker/Kafka logs and restart the failed process; accepted work remains in PostgreSQL. A missing/changed trigger or unsafe role requires correcting schema/role through explicit migrations, not bypassing readiness. Bad simulator pairing or restore hold requires inventory/reconciliation. Expired verification/reset tokens require a new captured email. UNKNOWN requires retained-operation inspection and original-key resolution. Unsupported Flyway baselines fail safely; never enable clean/reset against development data.

All service origins are fixed local allowlists. Cookies are HTTP-only SameSite=Lax; local HTTP is intentional and Secure cookies apply when using TLS. CSRF protects browser mutations; callbacks use signed method/path/query/time/nonce/body digest. AES-GCM protects seeds and transient delivery/staging/export material with context binding. Secrets come only from ignored private environment inputs. Credential rotation must preserve admitted payload/provider keys. Complete key-rotation qualification and all unexecuted adverse-path assertions are tracked in the external implementation report; do not infer production readiness.

## Service signing key rotation

Rotate both applications together during an operator maintenance window, after any interrupted paired restore has completed and while no other lifecycle command is running. Use the same private environment file for every step. From the repository root, stop all four effect processes:

```sh
MPRP_ROTATION_ENV_FILE=${MPRP_ENV_FILE:-.local/environment.env}
docker compose --env-file "$MPRP_ROTATION_ENV_FILE" -f compose.yaml stop -t 35 web worker simulator simulator-worker
```

Edit that private file without placing secret values in command arguments or logs. Give the new signing key a fresh ID, random secret of at least 32 characters, UTC activation time and expiry. Preserve the former key as the complete previous-key tuple:

| Private field | Value |
| --- | --- |
| `MPRP_SERVICE_KEY_ID` | New unique signing key ID. |
| `MPRP_SERVICE_SECRET` | New random signing secret; generate it privately, for example with `openssl rand -hex 32`. |
| `MPRP_SERVICE_KEY_ACTIVE_FROM` | New UTC ISO 8601 activation instant, active by restart. |
| `MPRP_SERVICE_KEY_EXPIRES_AT` | New UTC ISO 8601 expiry later than activation. |
| `MPRP_PREVIOUS_SERVICE_KEY_ID` | Former effective current key ID; the initial default is `local-v1`. |
| `MPRP_PREVIOUS_SERVICE_KEY_SECRET` | Former `MPRP_SERVICE_SECRET`; both main and simulator use this exact variable name. |
| `MPRP_PREVIOUS_SERVICE_KEY_ACTIVE_FROM` | Former activation instant. |
| `MPRP_PREVIOUS_SERVICE_KEY_EXPIRES_AT` | No later than its original expiry or 24 hours after the new activation, whichever is earlier. |

All four previous-key fields must be supplied together or all left blank. The previous key verifies only within its own validity window and before the new key's activation plus 24 hours; signing uses the active current key. Using the same ID for current and previous keys, an incomplete tuple or an excessive overlap is refused. Reload the edited file and restart the same four processes together:

```sh
set -a
. "$MPRP_ROTATION_ENV_FILE"
set +a
docker compose --env-file "$MPRP_ROTATION_ENV_FILE" -f compose.yaml up -d web worker simulator simulator-worker
MPRP_ENV_FILE="$MPRP_ROTATION_ENV_FILE" sh scripts/local.sh health
```

After the overlap ends, stop those four processes again, clear all four previous-key fields in the same private file, and restart from a fresh shell so no exported old values override it. Keep admitted provider keys, canonical payloads, action identities and financial policy unchanged throughout rotation. This changes transport authentication only. Full persisted rotation and expiry behavior across both running applications remains an uncompleted acceptance item.

## Source navigation and licenses

Parent `pom.xml` manages `protocol`, `application`, `simulator`. `protocol.CanonicalJson`, `Money`, `HmacAuth` implement shared runtime encodings. Golden fixtures live in `contracts/` and module test resources. Main feature packages are under `application/src/main/java/com/hassankarkaba/mprp/`; server templates and CSS live in `application/src/main/resources/`. Each simulator has independent migrations under its own resources. `scripts/local.sh` owns local lifecycle; recovery and purge are separate operator scripts. Dependency identity/licenses are in `DEPENDENCIES.md`; resolved trees and execution evidence are external deliverables.

Source archives exclude credentials, private local state, governing documents, logs, databases, backups, caches and generated targets. All necessary source, wrapper inputs, migrations, canonical fixtures and assets are retained. The implementation report distinguishes actual passing checks, unperformed acceptance and remaining limitations. Production hosting, real financial rails and user acceptance remain unassessed.
