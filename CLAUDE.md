# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A [Liquibase](https://www.liquibase.org/) extension adding support for Azure Cosmos DB (Core/SQL API), built on the [Azure Cosmos DB Java SDK v4](https://docs.microsoft.com/en-us/azure/cosmos-db/sql-api-sdk-java-v4). It plugs into Liquibase's `Database`/`Executor`/`Change`/`SqlStatement`/`LockService`/`ChangeLogHistoryService` SPIs so that standard Liquibase changelogs can create/replace/delete Cosmos containers and stored procedures, and create/upsert/update/delete items.

## Build & test

Maven project, parented by `org.liquibase:liquibase-parent-pom` (external — shared plugin config, license header enforcement, and the `run-its` profile come from there).

- Build + unit tests: `mvn clean install`
- Run a single unit test: `mvn test -Dtest=ClassNameTest`
- Run integration tests (requires a reachable Cosmos DB — emulator or real account): `mvn clean install -Prun-its`
- Run a single integration test: `mvn integration-test -Prun-its -Dtest=ClassNameIT`

Test naming convention is load-bearing, not cosmetic: the `maven-surefire-plugin` config in `pom.xml` binds `**/*Test.java` to the `test` phase and `**/*IT.java` to the `integration-test` phase. New tests must follow this suffix convention or they silently won't run in CI.

Integration tests need a Cosmos DB connection configured in `src/test/resources/application-test.properties` (`db.connection.uri`), using this extension's custom `cosmosdb://` connection string (JSON or Mongo-like form — see README "Running tests"). Locally this is usually the [Azure Cosmos DB Emulator](https://docs.microsoft.com/en-us/azure/cosmos-db/local-emulator).

CI (`.github/workflows/test.yml`) delegates to the shared `liquibase/build-logic` reusable workflow. Release automation is documented in `RELEASE.md`: PRs labeled `Extension Release Candidate :rocket:` and merged to `main` trigger a signed release to Sonatype Nexus; unit tests gate every PR, the full IT matrix runs only for release-candidate PRs.

## Architecture

### Generic layer vs. Cosmos-specific layer

The code is split into two package trees:

- `liquibase.nosql.*` — abstract base classes meant to be reusable across NoSQL Liquibase extensions in general (not Cosmos-specific). Contains `AbstractNoSqlDatabase`, `AbstractNoSqlConnection`, `AbstractNoSqlHistoryService`, `AbstractNoSqlLockService`, `NoSqlExecutor`, and the `NoSql*Statement` marker interfaces.
- `liquibase.ext.cosmosdb.*` — the concrete Cosmos DB implementation, extending everything above.

When adding a feature, put SDK-agnostic behavior in `nosql` and Cosmos SDK calls in `ext.cosmosdb`.

### Request flow for a change

`Change` (XML changelog element) → `SqlStatement` → `Executor` → Cosmos SDK:

1. A changelog XML element (e.g. `<createContainer>`) is deserialized into a `Change` subclass under `change/` (e.g. `CreateContainerChange`, extending `AbstractCosmosChange`). Its `generateStatements(Database)` builds one or more `SqlStatement`s.
2. Statements live under `statement/` and implement one of the `NoSql*Statement` interfaces from `liquibase.nosql.statement` (`NoSqlExecuteStatement`, `NoSqlUpdateStatement`, `NoSqlQueryForListStatement`, `NoSqlQueryForObjectStatement`, `NoSqlQueryForLongStatement`) depending on what kind of result they produce. Each statement's method takes the `AbstractNoSqlDatabase` and directly calls the Cosmos Java SDK (`CosmosContainer`/`CosmosDatabase`/`CosmosScripts`, etc.).
3. `NoSqlExecutor` (registered as Liquibase's `"jdbc"` executor for `CosmosLiquibaseDatabase`) is the single dispatch point: it pattern-matches the incoming `SqlStatement` against the `NoSql*Statement` interfaces and invokes the corresponding method.
4. `CosmosLiquibaseDatabase` / `CosmosConnection` hold the live `CosmosDatabase`/`CosmosClientProxy` handle; `CosmosClientDriver` builds the SDK client from a parsed `CosmosConnectionString`.

New change types require: a `Change` class, its `SqlStatement`(s), an entry in `src/main/resources/META-INF/services/liquibase.change.Change`, and (if it should be usable from XML changelogs) a new element in `src/main/resources/liquibase.parser.core.xml/dbchangelog-ext.xsd` whose name matches the `@DatabaseChange(name=...)` annotation.

### Connection string

Cosmos connections don't use a standard JDBC URL. `CosmosConnectionString` parses a custom `cosmosdb://` scheme in two accepted forms — a JSON blob or a Mongo-like `user:key@host:port/db` URL — into `accountEndpoint`/`accountKey`/`databaseName` (case-sensitive, upper-camel-case keys as in Cosmos docs). See README "Running tests" for exact syntax.

### Changelog history & locking are stored as Cosmos containers, not SQL tables

Liquibase's bookkeeping (`DATABASECHANGELOG` / `DATABASECHANGELOGLOCK`) is reimplemented as regular Cosmos containers:

- `CosmosHistoryService` (extends `AbstractNoSqlHistoryService`) creates/queries/updates the changelog container using hand-built `SqlQuerySpec` queries plus the `*Statement` classes in `statement/`.
- `CosmosLockService` (extends `AbstractNoSqlLockService`) implements optimistic locking against a single lock document (`CosmosChangeLogLock`).
- `changelog/` and `lockservice/` each have an `*ToDocumentConverter` implementing `AbstractNoSqlItemToDocumentConverter`, responsible for mapping between the Liquibase domain object (`CosmosRanChangeSet`, `CosmosChangeLogLock`) and the raw `Map<String, Object>` Cosmos document.
- `persistence/AbstractRepository<T>` is the generic CRUD wrapper (get/getAll/create/replace/upsert/delete/exists) around a `CosmosContainer` + converter, used by both the changelog and lock repositories.

### SPI registration

All Liquibase extension points are wired via `src/main/resources/META-INF/services/` files (one per Liquibase SPI: `Change`, `Database`, `DatabaseConnection`, `Executor`, `ChangeLogHistoryService`, `LockService`, `SqlGenerator`). Adding a new implementation of any of these interfaces requires adding its FQCN to the matching file or Liquibase will never discover it.

### License headers

Source files carry an Apache-2.0 header block (see `src/license/mastercard_apache_license/header.txt`) enforced by the parent POM's license plugin — keep it when adding new files.
