package org.testcontainers.rocketmq;

import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class DemoRocketMQContainer {

    // constructor {
    @Container
    final RocketMQContainer rocketmq = new RocketMQContainer("apache/rocketmq:5.3.4");

    // }

    void endpoints() {
        // grpcEndpoints {
        String grpcEndpoints = rocketmq.getGrpcEndpoints();
        // }

        // remotingEndpoints {
        String remotingEndpoints = rocketmq.getRemotingEndpoints();
        // }
    }
}
