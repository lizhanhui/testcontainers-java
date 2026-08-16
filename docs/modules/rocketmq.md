# RocketMQ

Testcontainers can be used to automatically instantiate and manage [Apache RocketMQ](https://rocketmq.apache.org/) containers.
More precisely Testcontainers uses the official Docker images for [RocketMQ](https://hub.docker.com/r/apache/rocketmq)

The container runs a NameServer, a Broker, and a Proxy (cluster mode) in a single container,
supporting both the 5.x gRPC clients (`rocketmq-client-java`) and the 4.x remoting clients (`rocketmq-client`).

## Example

Create a `RocketMQContainer` to use it in your tests:

<!--codeinclude-->
[Creating a RocketMQ container](../../modules/rocketmq/src/test/java/org/testcontainers/rocketmq/DemoRocketMQContainer.java) inside_block:constructor
<!--/codeinclude-->

Now your tests can produce and consume messages using the RocketMQ 5.x gRPC client:

<!--codeinclude-->
[gRPC endpoints](../../modules/rocketmq/src/test/java/org/testcontainers/rocketmq/DemoRocketMQContainer.java) inside_block:grpcEndpoints
<!--/codeinclude-->

or the 4.x remoting client:

<!--codeinclude-->
[Remoting endpoints](../../modules/rocketmq/src/test/java/org/testcontainers/rocketmq/DemoRocketMQContainer.java) inside_block:remotingEndpoints
<!--/codeinclude-->

!!! note "Topic creation"
    The 5.x gRPC client fetches topic routes eagerly at startup and topics auto-created on the
    send path are rejected by the 5.3 broker, so create topics up front (e.g. via
    `mqadmin updateTopic` executed inside the container).

!!! warning "Fixed remoting port"
    The proxy advertises its remoting listen port (8080) in route responses, so the host port is
    fixed at 8080: only one `RocketMQContainer` may run at a time per Docker host, startup fails if
    anything else on the host already binds port 8080, and remoting clients must reach the container
    via localhost (remoting does not work with a remote Docker daemon).

## Adding this module to your project dependencies

Add the following dependency to your `pom.xml`/`build.gradle` file:

=== "Gradle"
    ```groovy
    testImplementation "org.testcontainers:testcontainers-rocketmq:{{latest_version}}"
    ```
=== "Maven"
    ```xml
    <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>testcontainers-rocketmq</artifactId>
        <version>{{latest_version}}</version>
        <scope>test</scope>
    </dependency>
    ```
