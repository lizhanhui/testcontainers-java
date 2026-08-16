package org.testcontainers.rocketmq;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.time.Duration;

/**
 * Testcontainers implementation for Apache RocketMQ 5.x.
 * <p>
 * Runs a single container with a NameServer, a Broker, and a Proxy in
 * cluster mode (all intra-container traffic on 127.0.0.1).
 * <p>
 * Supported images: {@code apache/rocketmq}
 * <p>
 * Exposed ports:
 * <ul>
 *     <li>gRPC (5.x clients): 8081 (random host port)</li>
 *     <li>Remoting (4.x clients): 8080 (fixed host port 8080 — only one
 *     RocketMQContainer may run at a time per Docker host, and startup also
 *     fails if anything else on the host already binds port 8080. The remoting
 *     endpoint also assumes tests reach the container via localhost, so it does
 *     not work with a remote Docker daemon)</li>
 * </ul>
 */
public class RocketMQContainer extends GenericContainer<RocketMQContainer> {

    private static final DockerImageName DEFAULT_IMAGE_NAME = DockerImageName.parse("apache/rocketmq");

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
        addFixedExposedPort(REMOTING_PORT, REMOTING_PORT);

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
        waitingFor(
            Wait.forLogMessage(".*rocketmq-proxy startup successfully.*", 1).withStartupTimeout(Duration.ofMinutes(2))
        );
    }

    /**
     * Endpoints for the RocketMQ 5.x gRPC client ({@code rocketmq-client-java}),
     * in the form {@code host:port}.
     *
     * @return the gRPC endpoints
     */
    public String getGrpcEndpoints() {
        return getHost() + ":" + getMappedPort(GRPC_PORT);
    }

    /**
     * Nameserver address for 4.x remoting clients ({@code rocketmq-client}),
     * in the form {@code host:port}. Points at the proxy's remoting port.
     *
     * @return the remoting endpoints
     */
    public String getRemotingEndpoints() {
        return getHost() + ":" + getMappedPort(REMOTING_PORT);
    }
}
