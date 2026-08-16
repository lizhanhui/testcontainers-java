# RocketMQ 5.0 Testcontainers Module — Design

Date: 2026-08-16
Status: Approved (design phase)

## Goal

Add a `modules/rocketmq/` module to testcontainers-java providing a
`RocketMQContainer` for integration testing against RocketMQ 5.x.

## Key decisions (from brainstorming)

- **Topology**: single container. NameServer (9876) + Proxy in `LOCAL` mode
  (Broker embedded in the Proxy process). No multi-container cluster support.
- **Client protocols**: both 5.0 gRPC (proxy port 8081) and 4.x remoting
  (proxy port 8080).
- **Config surface**: minimal + config injection (`withBrokerConfig`,
  `withProxyConfig`). No ACL/auth, no controller/DLedger, no tiered storage.

## Background facts (from /data/repo/rocketmq source)

- The 5.0 gRPC client (`org.apache.rocketmq:rocketmq-client-java`) connects
  **only** to the Proxy gRPC port — never to NameServer directly.
- Proxy `LOCAL` mode embeds the Broker in the Proxy process
  (`proxy/src/main/java/org/apache/rocketmq/proxy/ProxyMode.java`);
  deploy guide: `docs/en/proxy/deploy_guide.md`.
- Ports: NameServer 9876; Proxy gRPC 8081; Proxy remoting 8080.
- Official image `apache/rocketmq:5.x` ships `mqnamesrv`, `mqbroker`,
  `mqproxy` scripts under `distribution/bin/`.
- Broker config for single-node container use: `brokerIP1=127.0.0.1`,
  `namesrvAddr=127.0.0.1:9876` (proxy config `proxyMode=LOCAL`).

## Module structure

Modeled on `modules/redpanda/` (simpler template than HiveMQ).
Modules are auto-registered: any directory under `modules/` becomes a
Gradle subproject via `settings.gradle`.

```
modules/rocketmq/
├── build.gradle
└── src/
    ├── main/java/org/testcontainers/rocketmq/
    │   └── RocketMQContainer.java
    ├── main/resources/testcontainers/
    │   └── rocketmq-entrypoint.sh
    └── test/
        ├── java/org/testcontainers/rocketmq/  (*IT.java + docs/Demo*.java)
        └── resources/logback-test.xml
```

## Container class

`RocketMQContainer extends GenericContainer<RocketMQContainer>`:

- Default image `apache/rocketmq` with pinned 5.x tag;
  `dockerImageName.assertCompatibleWith(DEFAULT_IMAGE_NAME)`.
- Exposes 8081 (gRPC) and 8080 (remoting).
- Entrypoint script: start `mqnamesrv`, wait for port 9876, then start
  `mqproxy` with `proxyMode=LOCAL`, `brokerIP1=127.0.0.1`,
  `namesrvAddr=127.0.0.1:9876`.
- Wait strategy: `Wait.forLogMessage` on the proxy startup line
  (exact string confirmed during implementation).

### Public API

- `String getGrpcEndpoints()` → `host:mappedPort(8081)` for the 5.0 client.
- `String getRemotingEndpoints()` → `host:mappedPort(8080)` for 4.x clients'
  `namesrvAddr`.
- `withBrokerConfig(MountableFile)` → inject `broker.conf`.
- `withProxyConfig(MountableFile)` → inject `rmq-proxy.json`.

## Known risk

The 4.x remoting client through the proxy's remoting port may break under
Testcontainers port mapping if route responses advertise in-container
addresses. The gRPC path is safe (all traffic flows through the proxy).
`RocketMQContainerRemotingIT` is the validation gate; fallback options:
expose NameServer 9876 + Broker 10911, or ship gRPC-only.

## Tests

JUnit 5 + `@Testcontainers` (per HiveMQ module tests):

1. `RocketMQContainerGrpcIT` — produce/consume round-trip with
   `rocketmq-client-java` via `getGrpcEndpoints()`.
2. `RocketMQContainerRemotingIT` — round-trip with 4.x `rocketmq-client`
   via `getRemotingEndpoints()` (validation gate, see Known risk).
3. `RocketMQContainerConfigIT` — `withBrokerConfig` takes effect.

## Docs

- `docs/modules/rocketmq.md` + alphabetical nav entry in `mkdocs.yml`.
- `<!--codeinclude-->` snippets sourced from a `Demo*` class in the module's
  test sources, matching other modules.

## Build

Minimal `build.gradle`: `api project(':testcontainers')`,
`testImplementation project(':testcontainers-junit-jupiter')`, both
RocketMQ client artifacts as test-only dependencies, logback test config.

## Out of scope (YAGNI)

ACL/auth, multi-container clusters, controller/DLedger mode, tiered storage.
