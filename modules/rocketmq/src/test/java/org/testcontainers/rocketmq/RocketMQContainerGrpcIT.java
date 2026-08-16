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
import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Testcontainers
class RocketMQContainerGrpcIT {

    @Container
    private final RocketMQContainer rocketmq = new RocketMQContainer("apache/rocketmq:5.3.4");

    @Test
    void produceAndConsumeViaGrpc() throws Exception {
        ClientServiceProvider provider = ClientServiceProvider.loadService();
        ClientConfiguration config = ClientConfiguration.newBuilder().setEndpoints(rocketmq.getGrpcEndpoints()).build();
        String topic = "grpc-test-topic";

        // The 5.x gRPC client eagerly fetches topic routes at producer/consumer
        // startup, so the topic must exist beforehand (broker auto-creation only
        // kicks in when a message arrives, which is too late). The 5.x client has
        // no admin API, so create the topic via mqadmin inside the container.
        // mqadmin exits 0 even on failure, and the embedded broker registers
        // itself and its topic configs with the NameServer asynchronously, so
        // poll until the topic route is actually visible.
        await("topic route registered")
            .atMost(Duration.ofSeconds(60))
            .until(() -> {
                rocketmq.execInContainer(
                    "sh",
                    "-c",
                    "sh $ROCKETMQ_HOME/bin/mqadmin updateTopic -n 127.0.0.1:9876 -c DefaultCluster -t " +
                    topic +
                    " >/dev/null 2>&1"
                );
                return rocketmq
                    .execInContainer(
                        "sh",
                        "-c",
                        "sh $ROCKETMQ_HOME/bin/mqadmin topicRoute -n 127.0.0.1:9876 -t " + topic
                    )
                    .getStdout()
                    .contains("brokerDatas");
            });

        CountDownLatch received = new CountDownLatch(1);
        try (
            Producer producer = provider.newProducerBuilder().setClientConfiguration(config).setTopics(topic).build();
            PushConsumer consumer = provider
                .newPushConsumerBuilder()
                .setClientConfiguration(config)
                .setConsumerGroup("grpc-test-group")
                .setSubscriptionExpressions(Collections.singletonMap(topic, new FilterExpression("*")))
                .setMessageListener(messageView -> {
                    received.countDown();
                    return ConsumeResult.SUCCESS;
                })
                .build()
        ) {
            Message message = provider
                .newMessageBuilder()
                .setTopic(topic)
                .setBody("hello".getBytes(StandardCharsets.UTF_8))
                .build();
            SendReceipt receipt = producer.send(message);
            assertThat(receipt.getMessageId()).isNotNull();

            assertThat(received.await(60, TimeUnit.SECONDS)).as("message consumed via gRPC").isTrue();
        }
    }
}
