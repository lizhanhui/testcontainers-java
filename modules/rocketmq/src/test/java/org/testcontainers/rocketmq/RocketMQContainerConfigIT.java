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
    void brokerConfigIsApplied() throws Exception {
        // The broker's console output is redirected to a file by the
        // entrypoint, so assert on that instead of the container logs.
        // Expected line: "The broker[tc-custom-broker, 127.0.0.1:10911] boot success ..."
        org.testcontainers.containers.Container.ExecResult result = rocketmq.execInContainer(
            "grep",
            "-Fc",
            "The broker[tc-custom-broker",
            "/tmp/broker-console.log"
        );
        assertThat(result.getStdout().trim()).as("custom broker name in broker log").isEqualTo("1");
    }
}
