package org.apache.rocketmq.client.source.produce;

import org.apache.rocketmq.client.exception.MQBrokerException;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.remoting.exception.RemotingException;

public class ProducerTest {
    public static void main(String[] args) throws MQClientException, MQBrokerException, RemotingException, InterruptedException {
        // 1.创建消息生产者producer，并指定生产者组名
        DefaultMQProducer producer = new DefaultMQProducer("base-sync-producer");
        // 2.指定NameServer地址
        producer.setNamesrvAddr("127.0.0.1:9876");
        // 3.启动producer
        producer.start();
        // 4.创建消息对象，指定topic、tag和消息体
        Message message = new Message("TestTopic_00001", "TagA", "Hello World!".getBytes());
        // 5.发送消息
        SendResult sendResult = producer.send(message);
        System.out.printf("%s%n", sendResult);
        // 6.关闭生产者
        producer.shutdown();
    }
}
