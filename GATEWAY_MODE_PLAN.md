# Plan: `connectionMode` option (Gateway vs. Direct)

## Motivation

The newer "vNext" Azure Cosmos DB Emulator only supports Gateway connection mode — it does not
support Direct mode, which is the Cosmos Java SDK's default (`CosmosClientBuilder` uses
`directMode()` unless told otherwise). Without a way to force Gateway mode, this extension cannot
connect to a vNext emulator at all. This change doesn't migrate this project's own test setup to
vNext (out of scope, tracked separately) — it just gives any consumer of the extension (including,
eventually, this project's own IT suite) a way to opt into Gateway mode via the connection string.

## Goal

Let users opt into the Cosmos Java SDK's `CosmosClientBuilder.gatewayMode()` (instead of the
SDK-default `directMode()`) via a new `connectionMode` property on the existing `cosmosdb://`
connection string, so it works in both the JSON and Mongo-like URL forms alongside
`accountEndpoint` / `accountKey` / `databaseName`.

## Confirmed design decisions

- **Config surface**: new connection-string property `connectionMode`, not an env var, system
  property, or separate config file. Follows the existing pattern of properties flowing through
  `CosmosConnectionString`'s property map.
- **Values**: `gateway` or `direct`, naming after (and mapping directly onto)
  `com.azure.cosmos.ConnectionMode.GATEWAY` / `.DIRECT`. Matching will be case-insensitive
  (`trim().toUpperCase()`) so `"Gateway"`, `"GATEWAY"`, `"gateway"` all work — flagging this as an
  implementation nuance since the SDK enum itself is uppercase-only.
- **Scope**: boolean-style switch only. No `GatewayConnectionConfig` tuning knobs
  (`maxConnectionPoolSize`, `idleConnectionTimeout`, etc.) in this change — out of scope, can be a
  follow-up if needed.
- **Default / backward compatibility**: property absent → no explicit `.gatewayMode()` /
  `.directMode()` call at all, preserving today's exact behavior (SDK default is `DIRECT`).

## Implementation

### 1. `CosmosConnectionString.java`
- Add constant `CONNECTION_MODE_PROPERTY = "connectionMode"`.
- Add `public Optional<String> getConnectionMode()` returning `getProperty(CONNECTION_MODE_PROPERTY)`,
  mirroring `getAccountEndpoint()` / `getAccountKey()` / `getDatabaseName()`. No parsing/validation
  here — this class stays a dumb property bag like today.

### 2. `CosmosClientDriver.java`
- Add a package-private static helper:
  ```java
  static ConnectionMode resolveConnectionMode(final Optional<String> connectionModeProperty) {
      return connectionModeProperty
              .map(String::trim)
              .filter(v -> !v.isEmpty())
              .map(v -> {
                  try {
                      return ConnectionMode.valueOf(v.toUpperCase(Locale.ROOT));
                  } catch (IllegalArgumentException e) {
                      throw new IllegalArgumentException(
                              "Invalid connectionMode: '" + v + "'. Valid values are: gateway, direct.", e);
                  }
              })
              .orElse(null);
  }
  ```
- In `connect(CosmosConnectionString)`, inside the existing try block (so any failure — bad value or
  SDK build failure — still gets wrapped into the current `DatabaseException` with the existing
  message, and never leaks the account key):
  ```java
  final CosmosClientBuilder builder = new CosmosClientBuilder()
          .endpoint(cosmosConnectionString.getAccountEndpoint().orElse(""))
          .key(cosmosConnectionString.getAccountKey().orElse(""))
          .consistencyLevel(ConsistencyLevel.EVENTUAL)
          .userAgentSuffix(LIQUIBASE_EXTENSION_USER_AGENT_SUFFIX);

  final ConnectionMode connectionMode = resolveConnectionMode(cosmosConnectionString.getConnectionMode());
  if (connectionMode == ConnectionMode.GATEWAY) {
      builder.gatewayMode();
  } else if (connectionMode == ConnectionMode.DIRECT) {
      builder.directMode();
  }

  client = builder.buildClient();
  ```
- New import: `com.azure.cosmos.ConnectionMode`.

## Testing

### `CosmosConnectionStringTest`
- `getConnectionMode()` returns the raw value for both JSON (`"connectionMode":"gateway"`) and URL
  (`?connectionMode=gateway`) forms.
- `getConnectionMode()` is empty when the property is absent (existing fixtures already cover this
  implicitly via `getProperty("notExisting")`; add an explicit case for `connectionMode`).

### `CosmosClientDriverTest`
- Unit-test `resolveConnectionMode` directly (no network needed):
  - `Optional.empty()` → `null`.
  - `"gateway"`, `"GATEWAY"`, `"Gateway"` → `ConnectionMode.GATEWAY`.
  - `"direct"` → `ConnectionMode.DIRECT`.
  - `"bogus"` → throws `IllegalArgumentException`.
- One end-to-end-style test reusing the existing pattern in
  `given_a_CosmosClientBuilder_exception_then_a_DatabaseException_is_thrown_...`: build a
  `CosmosConnectionString` with an invalid `connectionMode` (e.g. via
  `fromJsonConnectionString`) and assert `connect(...)` throws `DatabaseException` (fails fast on
  the invalid value, no network call needed since resolution happens before `buildClient()`).

No integration test changes required — `gateway`/`direct` mode selection isn't itself something an
`*IT.java` test needs to assert on beyond "changelog operations still work", and the emulator
already supports both modes.

## Documentation

- `README.md`, "Connection String Format" section: document `connectionMode` as an optional
  property (default `direct`), with both JSON and URL examples, next to the existing
  `accountEndpoint`/`accountKey`/`databaseName` documentation.

## Out of scope

- Exposing `GatewayConnectionConfig` / `DirectConnectionConfig` tuning parameters.
- Any XSD/`Change` changes — this is a connection-level setting, not a changelog element, so
  `dbchangelog-ext.xsd` and `META-INF/services/liquibase.change.Change` are untouched.
