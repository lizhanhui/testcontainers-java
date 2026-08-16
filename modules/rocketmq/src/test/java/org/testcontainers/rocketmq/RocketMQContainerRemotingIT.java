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
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Testcontainers
class RocketMQContainerRemotingIT {

    @Container
    private final RocketMQContainer rocketmq = new RocketMQContainer("apache/rocketmq:5.3.4");

    @Test
    void produceAndConsumeViaRemoting() throws Exception {
        String topic = "remoting-test-topic";
        CountDownLatch received = new CountDownLatch(1);

        // Topic auto-creation does not work through the proxy's remoting route
        // lookups, so create the topic up front. mqadmin exits 0 even on
        // failure, and the embedded broker registers its topic configs with
        // the NameServer asynchronously, so poll until the topic route is
        // actually visible.
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
        consumer.registerMessageListener(
            (MessageListenerConcurrently) (msgs, context) -> {
                received.countDown();
                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            }
        );
        consumer.start();
        try {
            assertThat(received.await(60, TimeUnit.SECONDS)).as("message consumed via remoting").isTrue();
        } finally {
            consumer.shutdown();
        }
    }
}
