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
