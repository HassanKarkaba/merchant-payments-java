# Dependency and image identity

Baseline checked 2 November 2026. Maven Central release artifacts only; no snapshots or dynamic ranges. Parent/BOM Spring Boot 4.1.2; Java target 21; Maven Wrapper 3.9.15, wrapper-plugin 3.3.4. The wrapper SHA256 is b0d9292f06c5faded31ddcb6cb69099f316d6fea40a7778624b259bad9fed18a, computed only after matching the official Maven Central SHA512 distribution checksum. Enforcer requires Java 21, Maven 3.9.x and release dependencies. Boot fixes plugin and transitive dependency versions; exact resolved trees are external evidence.

Main dependencies include Spring Framework 7.0.10, Spring Kafka 4.1.2 / Kafka clients 4.2.2, Spring Session JDBC 4.1.1, Boot-managed JPA/Hibernate/Security/JUnit/Flyway/PostgreSQL JDBC, Apache Commons CSV 1.14.1, explicit Jackson 2.20.1 for canonical protocol encoding alongside Boot's Jackson 3 HTTP modules, java-otp 1.0.0 and Playwright Java 1.64.0 for browser tests. Refer to exact effective POM/tree for all transitive versions.

Container identities are fixed in Dockerfile/Compose:

| Image | Immutable manifest digest |
|---|---|
| postgres:17.11-bookworm | sha256:839fbb5a4942a7758dd93e18b42ea5649807491a0545b5eeeb8f0b8bb11857a5 |
| apache/kafka:4.2.2 | sha256:1213eb3943d551e5ed1fca7a4e109001cee35770b66a02a0c37a8964efe09b69 |
| axllent/mailpit:v1.32.0 | sha256:0790692be935ea4420d085694f7985b1f692ca6a8771ab0e0bd8ba75ac180498 |
| maven:3.9.15-eclipse-temurin-21 | sha256:d6e32a254897415a445654a3c43c30fbc731a4d946cf5c66d1cb9184141c20c1 |
| eclipse-temurin:21.0.13_9-jre-alpine | sha256:82cf3d9ccbe1ed46252008d51b2f2329e6b9e8a2f4394c4bebefc3ea7f4b1372 |

Spring/Apache/Jackson/JUnit/Flyway community artifacts generally use Apache 2.0 or EPL 2.0 as specified in their embedded POM/license metadata; PostgreSQL JDBC uses BSD 2-Clause; Hibernate LGPL 2.1; Eclipse Temurin OpenJDK GPLv2 with Classpath Exception; Mailpit MIT; Playwright Apache 2.0; java-otp MIT. Transitive licenses must be assessed from the resolved dependency inventory before redistribution in a different context. This inventory is not a legal opinion or a claim of a completed vulnerability audit.

The password corpus is retained data from Django 5.2.17 (19,640 deduplicated lines) with its BSD license; no Django/Python runtime is used. Upstream gzip SHA256 3c1baed62596de36860824eb3f436d5932d37ca8b06e59df78f5a44ec175afe4. License is retained alongside the resource. Source: https://github.com/django/django/tree/5.2.17/django/contrib/auth.

Primary mechanism references: https://docs.spring.io/spring-boot/system-requirements.html, https://docs.spring.io/spring-kafka/reference/kafka/transactions.html, https://www.postgresql.org/docs/17/explicit-locking.html, https://kafka.apache.org/42/documentation/, https://playwright.dev/java/docs/intro. A documentation statement does not establish the delivered application's correctness. Qualification, actual checks and remaining security/rotation obligations are recorded separately in the external implementation report.

Dependency updates require a coherent stop/migrate/restart, regenerated trees/checksums and affected boundary tests. Keep admitted money policy mprp_v1 and Java encoding 1 stable; changing dependencies must not reinterpret retained provider keys or payloads. This delivery makes no byte-for-byte reproducible-image or production security claim.

Security review on 2 November 2026 checked official Spring security advisories, PostgreSQL 17 security/releases, the Apache Kafka CVE list and Adoptium maintenance releases. The Kafka broker was changed from 4.1.0 to 4.2.2 to use the maintained fixes and match the Boot-managed 4.2.2 client. The final container runtime uses Temurin 21.0.13+9. Earlier component receipts using Kafka 4.1.0 / Java 21.0.2 are identified separately; they do not qualify the final runtime. Spring Kafka 4.1.2 is beyond the 4.1.1 fix for CVE-2026-59278. This was a scoped advisory/configuration review, not an exhaustive transitive vulnerability scan; resolved coordinate, scope, license and checksum records accompany the external report.

Sources: [Spring advisories](https://spring.io/security/), [Spring Kafka header advisory](https://spring.io/security/cve-2026-59278/), [PostgreSQL 17 security](https://www.postgresql.org/support/security/17/), [Kafka advisories](https://kafka.apache.org/cve-list/), [Temurin 21 releases](https://github.com/adoptium/temurin21-binaries/releases).
