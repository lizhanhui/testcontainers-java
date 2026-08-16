# RocketMQ 5.0 Testcontainers Module Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add a `modules/rocketmq/` module providing `RocketMQContainer` for integration testing against RocketMQ 5.x (single container: NameServer + Proxy in LOCAL mode).

**Architecture:** One `GenericContainer` subclass on the `apache/rocketmq` image. An entrypoint script starts `mqnamesrv`, waits for its boot log, then starts `mqproxy -pm local` (broker embedded in the proxy process). Exposes proxy gRPC 8081 (random mapping) and proxy remoting 8080 (fixed mapping). Design doc: `docs/plans/2026-08-16-rocketmq-testcontainer-design.md`.

**Tech Stack:** Java 8 target (repo default toolchain Java 17), Gradle, JUnit 5, `org.apache.rocketmq:rocketmq-client-java:5.0.5` (gRPC, test-only), `org.apache.rocketmq:rocketmq-client:4.9.7` (remoting, test-only).

---

## Locked-in technical decisions (verified against /data/repo/rocketmq source)

- **Wait strategy**: proxy prints `rocketmq-proxy startup successfully` to stdout (`proxy/src/main/java/org/apache/rocketmq/proxy/ProxyStartup.java:116`). Use `Wait.forLogMessage(".*rocketmq-proxy startup successfully.*", 1)`.
- **Proxy CLI**: `mqproxy -pm local -n 127.0.0.1:9876 -bc <broker.conf path> -pc <proxy.json path>` (`ProxyStartup.java:143-159`). `-bc` = broker config for local mode, `-pc` = proxy config (JSON), `-pm` = mode, `-n` = namesrvAddr.
- **gRPC + random ports**: default proxy config sets `"useEndpointPortFromRequest": true` (`ProxyConfig.java:184`) so QueryRoute echoes the client-supplied endpoint host:port instead of hardcoded 8081 (`grpc/v2/route/RouteActivity.java:194-204`).
- **Remoting + fixed port**: route responses advertise `remotingAccessAddr:remotingListenPort` (`remoting/activity/GetTopicRouteActivity.java:54`); advertised port is not separately configurable → use `addFixedExposedPort(8080, 8080)` + `"remotingAccessAddr": "127.0.0.1"`. Caveat (document in javadoc + docs page): two RocketMQContainers cannot run simultaneously on one Docker host.
- **Memory cap**: `distribution/bin/runserver.sh:104` appends `$JAVA_OPT_EXT` last, so `withEnv("JAVA_OPT_EXT", "-Xms512m -Xmx512m")` overrides the hardcoded 4g defaults.
- **Image**: `apache/rocketmq:5.3.4`. Binaries under `$ROCKETMQ_HOME/bin/` (entrypoint script resolves ROCKETMQ_HOME via glob `/home/rocketmq/rocketmq-*`; verify with `docker run --rm apache/rocketmq:5.3.4 sh -c 'ls /home/rocketmq; echo $ROCKETMQ_HOME'`).
- **NameServer readiness**: namesrv prints `The Name Server boot success` — entrypoint greps the redirected console log for `boot success` before starting the proxy.
- Gradle auto-registers any `modules/<dir>` as `:testcontainers-<dir>` (`settings.gradle`).

---

### Task 1: Module scaffold

**Files:**
- Create: `modules/rocketmq/build.gradle`

**Step 1: Create build.gradle**

```groovy
description = "Testcontainers :: RocketMQ"

dependencies {
    api project(':testcontainers')

    testImplementation project(':testcontainers-junit-jupiter')
    testImplementation 'org.apache.rocketmq:rocketmq-client-java:5.0.5'
    testImplementation 'org.apache.rocketmq:rocketmq-client:4.9.7'
    testImplementation 'org.awaitility:awaitility:4.3.0'
}
```

**Step 2: Verify Gradle picks up the module**

Run: `./gradlew projects | grep rocketmq`
Expected: `+--- Project ':testcontainers-rocketmq'`

**Step 3: Commit**

```bash
git add modules/rocketmq/build.gradle
git commit -m "Add rocketmq module scaffold"
```

---

### Task 2: Container resources (entrypoint + default configs)

**Files:**
- Create: `modules/rocketmq/src/main/resources/testcontainers/rocketmq/entrypoint.sh`
- Create: `modules/rocketmq/src/main/resources/testcontainers/rocketmq/broker.conf`
- Create: `modules/rocketmq/src/main/resources/testcontainers/rocketmq/rmq-proxy.json`

**Step 1: Verify image layout (one-off manual check, do NOT script)**

Run: `docker run --rm apache/rocketmq:5.3.4 sh -c 'ls /home/rocketmq; echo "HOME=$ROCKETMQ_HOME"; which mqnamesrv mqproxy'`
Record the actual layout; adjust the glob in entrypoint.sh if it differs.

**Step 2: Write entrypoint.sh** (POSIX sh; image may be alpine-based — no bashisms)

```sh
#!/bin/sh
set -e

if [ -z "$ROCKETMQ_HOME" ]; then
  ROCKETMQ_HOME=$(ls -d /home/rocketmq/rocketmq-* 2>/dev/null | head -n 1)
fi
export ROCKETMQ_HOME
echo "Using ROCKETMQ_HOME=$ROCKETMQ_HOME"

nohup sh "$ROCKETMQ_HOME/bin/mqnamesrv" > /tmp/namesrv-console.log 2>&1 &

count=0
until grep -q "boot success" /tmp/namesrv-console.log; do
  count=$((count + 1))
  if [ "$count" -gt 60 ]; then
    echo "NameServer failed to start within 60s" >&2
    cat /tmp/namesrv-console.log >&2
    exit 1
  fi
  sleep 1
done
echo "NameServer is up"

exec sh "$ROCKETMQ_HOME/bin/mqproxy" -pm local -n 127.0.0.1:9876 \
  -bc /tmp/testcontainers/broker.conf \
  -pc /tmp/testcontainers/rmq-proxy.json
```

**Step 3: Write broker.conf**

```properties
brokerClusterName=DefaultCluster
brokerName=broker-a
brokerId=0
brokerIP1=127.0.0.1
autoCreateTopicEnable=true
```

**Step 4: Write rmq-proxy.json**

```json
{
  "rocketMQClusterName": "DefaultCluster",
  "useEndpointPortFromRequest": true,
  "remotingAccessAddr": "127.0.0.1"
}
```

**Step 5: Commit**

```bash
git add modules/rocketmq/src/main/resources
git commit -m "Add rocketmq container entrypoint and default configs"
```

---

### Task 3: RocketMQContainer class (compile-only skeleton + full implementation)

**Files:**
- Create: `modules/rocketmq/src/main/java/org/testcontainers/rocketmq/RocketMQContainer.java`

**Step 1: Write the class**

```java
package org.testcontainers.rocketmq;

import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Testcontainers implementation for Apache RocketMQ 5.x.
 * <p>
 * Runs a single container with a NameServer and a Proxy in LOCAL mode
 * (the Broker is embedded in the Proxy process).
 * <p>
 * Supported image: {@code apache/rocketmq}
 * <p>
 * Exposed ports:
 * <ul>
 *     <li>gRPC (5.x clients): 8081 (random host port)</li>
 *     <li>Remoting (4.x clients): 8080 (fixed host port 8080 — only one
 *     RocketMQContainer may run at a time per Docker host)</li>
 * </ul>
 */
public class RocketMQContainer extends GenericContainer<RocketMQContainer> {

    private static final DockerImageName DEFAULT_IMAGE_NAME = DockerImageName.parse("apache/rocketmq");

    private static final String DEFAULT_TAG = "5.3.4";

    private static final int GRPC_PORT = 8081;

    private static final int REMOTING_PORT = 8080;

    private static final String TC_DIR = "/tmp/testcontainers";

    public RocketMQContainer(String image) {
        this(DockerImageName.parse(image));
    }

    public RocketMQContainer(DockerImageName dockerImageName) {
        super(dockerImageName);
        dockerImageName.assertCompatibleWith(DEFAULT_IMAGE_NAME);

        addExposedPort(GRPC_PORT);
        // Remoting route responses always advertise remotingListenPort (see
        // GetTopicRouteActivity), so the host port must be fixed at 8080.
        addFixedExposedPort(REMOTING_PORT, REMOTING_PORT, BindMode.READ_WRITE);

        withEnv("JAVA_OPT_EXT", "-Xms512m -Xmx512m");
        withCopyFileToContainer(
            MountableFile.forClasspathResource("testcontainers/rocketmq/entrypoint.sh", 0755),
            TC_DIR + "/entrypoint.sh"
        );
        withCopyFileToContainer(
            MountableFile.forClasspathResource("testcontainers/rocketmq/broker.conf"),
            TC_DIR + "/broker.conf"
        );
        withCopyFileToContainer(
            MountableFile.forClasspathResource("testcontainers/rocketmq/rmq-proxy.json"),
            TC_DIR + "/rmq-proxy.json"
        );
        withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("sh", TC_DIR + "/entrypoint.sh"));
        waitingFor(Wait.forLogMessage(".*rocketmq-proxy startup successfully.*", 1));
    }

    /**
     * Endpoints for the RocketMQ 5.x gRPC client ({@code rocketmq-client-java}),
     * in the form {@code host:port}.
     */
    public String getGrpcEndpoints() {
        return getHost() + ":" + getMappedPort(GRPC_PORT);
    }

    /**
     * Nameserver address for 4.x remoting clients ({@code rocketmq-client}),
     * in the form {@code host:port}. Points at the proxy's remoting port.
     */
    public String getRemotingEndpoints() {
        return getHost() + ":" + getMappedPort(REMOTING_PORT);
    }
}
```

**Step 2: Compile**

Run: `./gradlew :testcontainers-rocketmq:compileJava`
Expected: BUILD SUCCESSFUL

**Step 3: Run spotless**

Run: `./gradlew :testcontainers-rocketmq:spotlessApply`
Expected: BUILD SUCCESSFUL (may reformat; keep changes)

**Step 4: Commit**

```bash
git add modules/rocketmq/src/main
git commit -m "Add RocketMQContainer"
```

---

### Task 4: gRPC integration test (5.x client round-trip)

**Files:**
- Create: `modules/rocketmq/src/test/java/org/testcontainers/rocketmq/RocketMQContainerGrpcIT.java`
- Create: `modules/rocketmq/src/test/resources/logback-test.xml` (copy from `modules/redpanda/src/test/resources/logback-test.xml`)

**Step 1: Write the failing test**

```java
package org.testcontainers.rocketmq;

import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.consumer.FilterExpression;
import org.apache.rocketmq.client.apis.consumer.PushConsumer;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.apache.rocketmq.client.apis.producer.SendReceipt;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class RocketMQContainerGrpcIT {

    @Container
    private final RocketMQContainer rocketmq = new RocketMQContainer("apache/rocketmq:5.3.4");

    @Test
    void produceAndConsumeViaGrpc() throws Exception {
        ClientServiceProvider provider = ClientServiceProvider.loadService();
        ClientConfiguration config = ClientConfiguration.newBuilder()
            .setEndpoints(rocketmq.getGrpcEndpoints())
            .build();
        String topic = "grpc-test-topic";

        CountDownLatch received = new CountDownLatch(1);
        try (
            Producer producer = provider.newProducerBuilder()
                .setClientConfiguration(config)
                .setTopics(topic)
                .build();
            PushConsumer consumer = provider.newPushConsumerBuilder()
                .setClientConfiguration(config)
                .setConsumerGroup("grpc-test-group")
                .setSubscriptionExpressions(
                    Collections.singletonMap(topic, new FilterExpression("*"))
                )
                .setMessageListener(messageView -> {
                    received.countDown();
                    return ConsumeResult.SUCCESS;
                })
                .build()
        ) {
            Message message = provider.newMessageBuilder()
                .setTopic(topic)
                .setBody(StandardCharsets.UTF_8.encode("hello"))
                .build();
            SendReceipt receipt = producer.send(message);
            assertThat(receipt.getMessageId()).isNotNull();

            assertThat(received.await(60, TimeUnit.SECONDS))
                .as("message consumed via gRPC")
                .isTrue();
        }
    }
}
```

Note for implementer: verify exact builder method names against `rocketmq-client-java:5.0.5` (javadoc/source jar); adjust if the API differs (e.g. `FilterExpression.SUB_ALL`).

**Step 2: Run the test**

Run: `./gradlew :testcontainers-rocketmq:test --tests "org.testcontainers.rocketmq.RocketMQContainerGrpcIT"`
Expected: PASS. If the container fails to start, inspect `docker logs` output in the test failure — most likely entrypoint paths (Task 2 Step 1 check), namesrv readiness grep, or memory limits.

**Step 3: Commit**

```bash
git add modules/rocketmq/src/test
git commit -m "Add gRPC round-trip integration test"
```

---

### Task 5: Remoting integration test (4.x client round-trip) — VALIDATION GATE

This test validates the fixed-port + `remotingAccessAddr=127.0.0.1` design. If it fails, do NOT work around the test — revisit the design (fallback: expose NameServer 9876 + Broker 10911, or drop remoting support and update the design doc).

**Files:**
- Create: `modules/rocketmq/src/test/java/org/testcontainers/rocketmq/RocketMQContainerRemotingIT.java`

**Step 1: Write the test**

```java
package org.testcontainers.rocketmq;

import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.common.message.Message;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class RocketMQContainerRemotingIT {

    @Container
    private final RocketMQContainer rocketmq = new RocketMQContainer("apache/rocketmq:5.3.4");

    @Test
    void produceAndConsumeViaRemoting() throws Exception {
        String topic = "remoting-test-topic";
        CountDownLatch received = new CountDownLatch(1);

        DefaultMQProducer producer = new DefaultMQProducer("remoting-test-producer");
        producer.setNamesrvAddr(rocketmq.getRemotingEndpoints());
        producer.start();
        try {
            producer.send(new Message(topic, "hello".getBytes(StandardCharsets.UTF_8)));
        } finally {
            producer.shutdown();
        }

        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer("remoting-test-group");
        consumer.setNamesrvAddr(rocketmq.getRemotingEndpoints());
        consumer.subscribe(topic, "*");
        consumer.registerMessageListener((MessageListenerConcurrently) (msgs, context) -> {
            received.countDown();
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        });
        consumer.start();
        try {
            assertThat(received.await(60, TimeUnit.SECONDS))
                .as("message consumed via remoting")
                .isTrue();
        } finally {
            consumer.shutdown();
        }
    }
}
```

**Step 2: Run the test**

Run: `./gradlew :testcontainers-rocketmq:test --tests "org.testcontainers.rocketmq.RocketMQContainerRemotingIT"`
Expected: PASS. If route/connection errors mention `8080` unreachable or container IPs, the advertised-address assumption is wrong — stop and re-evaluate per the gate note above.

**Step 3: Commit**

```bash
git add modules/rocketmq/src/test/java/org/testcontainers/rocketmq/RocketMQContainerRemotingIT.java
git commit -m "Add remoting round-trip integration test"
```

---

### Task 6: Config injection (withBrokerConfig / withProxyConfig)

**Files:**
- Modify: `modules/rocketmq/src/main/java/org/testcontainers/rocketmq/RocketMQContainer.java`
- Create: `modules/rocketmq/src/test/java/org/testcontainers/rocketmq/RocketMQContainerConfigIT.java`
- Create: `modules/rocketmq/src/test/resources/custom-broker.conf`

**Step 1: Add the two methods to RocketMQContainer**

```java
    /**
     * Replace the broker configuration used by the embedded broker (local mode).
     */
    public RocketMQContainer withBrokerConfig(MountableFile brokerConfig) {
        return withCopyFileToContainer(brokerConfig, TC_DIR + "/broker.conf");
    }

    /**
     * Replace the proxy configuration (rmq-proxy.json).
     */
    public RocketMQContainer withProxyConfig(MountableFile proxyConfig) {
        return withCopyFileToContainer(proxyConfig, TC_DIR + "/rmq-proxy.json");
    }
```

Note: `withCopyFileToContainer` before start copies at create time; later copies overwrite earlier ones targeting the same path, so user config wins over the defaults copied in the constructor. Verify this in Step 3's test — if ordering turns out to be non-deterministic, instead store the MountableFile in fields and copy in `containerIsStarting`.

**Step 2: Write the test**

`custom-broker.conf`: same as default but `brokerName=tc-custom-broker`.

```java
package org.testcontainers.rocketmq;

import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class RocketMQContainerConfigIT {

    @Container
    private final RocketMQContainer rocketmq = new RocketMQContainer("apache/rocketmq:5.3.4")
        .withBrokerConfig(MountableFile.forClasspathResource("custom-broker.conf"));

    @Test
    void brokerConfigIsApplied() {
        // Local-mode proxy logs: "The broker[tc-custom-broker, ...] boot success ..."
        assertThat(rocketmq.getLogs()).contains("The broker[tc-custom-broker");
    }
}
```

**Step 3: Run**

Run: `./gradlew :testcontainers-rocketmq:test --tests "org.testcontainers.rocketmq.RocketMQContainerConfigIT"`
Expected: PASS

**Step 4: Commit**

```bash
git add modules/rocketmq
git commit -m "Add broker/proxy config injection"
```

---

### Task 7: Docs

**Files:**
- Create: `docs/modules/rocketmq.md`
- Modify: `mkdocs.yml` (nav, alphabetical — `rocketmq` goes right after `redpanda`)
- Create: `modules/rocketmq/src/test/java/org/testcontainers/rocketmq/DemoRocketMQContainer.java` (codeinclude source; follow an existing module's Demo class, e.g. hivemq's)

**Step 1: Find all doc registration points**

Run: `grep -rn "redpanda" mkdocs.yml docs/index.md docs/modules/ --include="*.md" -l`
Mirror exactly what redpanda has (mkdocs nav entry; no other index edits expected).

**Step 2: Write docs/modules/rocketmq.md**

Follow the structure of `docs/modules/redpanda.md`: short intro, usage via `<!--codeinclude-->` block referencing the Demo class, note on fixed remoting port 8080 caveat.

**Step 3: Verify docs build (optional but cheap)**

Run: `./gradlew :docs:... ` — if no docs gradle task exists, skip; mkdocs is built by CI.

**Step 4: Commit**

```bash
git add docs/modules/rocketmq.md mkdocs.yml modules/rocketmq/src/test/java/org/testcontainers/rocketmq/DemoRocketMQContainer.java
git commit -m "Add rocketmq module docs"
```

---

### Task 8: Final verification

**Step 1: Full module test run**

Run: `./gradlew :testcontainers-rocketmq:test`
Expected: all 3 ITs PASS

**Step 2: Spotless check**

Run: `./gradlew :testcontainers-rocketmq:spotlessCheck`
Expected: BUILD SUCCESSFUL

**Step 3: Javadoc/compile check as CI would**

Run: `./gradlew :testcontainers-rocketmq:build -x test`
Expected: BUILD SUCCESSFUL

---

## Out of scope (do not implement)

ACL/auth, multi-container clusters, controller/DLedger mode, tiered storage, multiple parallel containers on one host (blocked by fixed remoting port — documented caveat).
