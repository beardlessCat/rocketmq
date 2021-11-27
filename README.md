# 一、整体架构

整体架构按角色分文：nameServer、broker、producer及consumer。

![](https://tcs.teambition.net/storage/312c7891a6c8ce51fa6ea3adb1e453aebf6f?Signature=eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJBcHBJRCI6IjU5Mzc3MGZmODM5NjMyMDAyZTAzNThmMSIsIl9hcHBJZCI6IjU5Mzc3MGZmODM5NjMyMDAyZTAzNThmMSIsIl9vcmdhbml6YXRpb25JZCI6IiIsImV4cCI6MTYzODU5NDEzNCwiaWF0IjoxNjM3OTg5MzM0LCJyZXNvdXJjZSI6Ii9zdG9yYWdlLzMxMmM3ODkxYTZjOGNlNTFmYTZlYTNhZGIxZTQ1M2FlYmY2ZiJ9.JIMT7yyzDAHrq78OLjghMvhraiijqun1n-mClN8BxOs&download=image.png "")

## 1.broker路由注册

broker启动后，会将路由broker相关信息注册到nameServer集群中。**每个broker（无论是主从角色）都要将注册到nameServer中，而且是所有的nameServer节点。**

## 2.生产者、消费者路由信息获取

生产者、消费者需要从nameServer中获取路由信息，通过这些路由信息生产者就能知道将消息发送到broker的地址是什么，消费者这就能知道应该动哪个broker节点上取消息。

## 4.生产者消息投递

连接broker**（Master身份的broker）**进行消息投机，由broker进行消息存储相关操作。

## 3.broker主从数据同步

Master节点收到消息后，将数据同步到其所有Slave节点。

## 4.消费者消息消费

消费者连接broker**（可能是主节点，可能是从节点）**拉消息，进行消费。

# 二、高可用机制

## 1.nameServer的高可能

NameServer是集群里非常关键的一个角色，他要管理Broker信息，因此高可用机制必不可少。所以通常来说，NameServer一定会多机器部署，实现一个集群。

这时就会涉及到broker信息注册的问题，每个broker启动时，都会想所有的NameServer进行注册。**也就是每个NameServer都会有一份集群中所有Broker的信息。**

## 2.broker高可用

### 2.1 broker存活检查

Broker跟NameServer之间通过netty长链接通信，两种之间存在心跳机制。Broker会每隔30s给所有的NameServer发送心跳，告诉每个NameServer自己目前还活着，nameServer记最后一次心跳时间。

nameServer每10s检查一下心跳时间，如果超过120s未收到新的心跳包，则认为broker已经宕机，便在nameServer中剔除改broker的路由信息。

![](https://tcs.teambition.net/storage/312c6216e55e430454142ee3e837e99135bb?Signature=eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJBcHBJRCI6IjU5Mzc3MGZmODM5NjMyMDAyZTAzNThmMSIsIl9hcHBJZCI6IjU5Mzc3MGZmODM5NjMyMDAyZTAzNThmMSIsIl9vcmdhbml6YXRpb25JZCI6IjVmYWZhYjk1YTNkYmY4NTljODhkN2ZiNiIsImV4cCI6MTYzNzk5NDIzNiwiaWF0IjoxNjM3OTkwNjM2LCJyZXNvdXJjZSI6Ii9zdG9yYWdlLzMxMmM2MjE2ZTU1ZTQzMDQ1NDE0MmVlM2U4MzdlOTkxMzViYiJ9.t8iz6FsTq-j_43QzEs_gWYAXywyNkeiDZq4KrJ6G_qw&download=image.png "")

- broker心跳发送定时任务（BrokerController#start）

```java
	 //Breaker将自己注册到NameServer
        /**
         * Broker 启动时会向集群中所有的 NameServ 发送心跳语句，每隔30s向集群中所有的NameServer发送心跳包，
         * NameServer 收到 Broke的跳包后，会更新 brokerLiveTab 缓存中 BrokerLivelInfo。然后 NameServer 每隔 10s 扫描
         * brokerLiveTable ，如果连续120s没有收到心跳包，NameServer将移除该Broker的路由信息同时断开Socket连接。
         */
        this.scheduledExecutorService.scheduleAtFixedRate(new Runnable() {

            @Override
            public void run() {
                try {
                    BrokerController.this.registerBrokerAll(true, false, brokerConfig.isForceRegister());
                } catch (Throwable e) {
                    log.error("registerBrokerAll Exception", e);
                }
            }
        }, 1000 * 10, Math.max(10000, Math.min(brokerConfig.getRegisterNameServerPeriod(), 60000)), TimeUnit.MILLISECONDS);
```

- nameServer心跳检查

```java
//每10s扫描存活breaker
 this.scheduledExecutorService.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                NamesrvController.this.routeInfoManager.scanNotActiveBroker();
            }
        }, 5, 10, TimeUnit.SECONDS);


```

- 心跳检查逻辑

```java
/**
 * 扫描broker发送来的心跳日志，距最后一次心跳时间大于BROKER_CHANNEL_EXPIRED_TIME(120s)后，认为broker
 * 挂掉，断开长链接，销毁Channel
 */
public void scanNotActiveBroker() {
    Iterator<Entry<String, BrokerLiveInfo>> it = this.brokerLiveTable.entrySet().iterator();
    while (it.hasNext()) {
        Entry<String, BrokerLiveInfo> next = it.next();
        long last = next.getValue().getLastUpdateTimestamp();
        if ((last + BROKER_CHANNEL_EXPIRED_TIME) < System.currentTimeMillis()) {
            RemotingUtil.closeChannel(next.getValue().getChannel());
            it.remove();
            log.warn("The broker channel expired, {} {}ms", next.getKey(), BROKER_CHANNEL_EXPIRED_TIME);
            this.onChannelDestroy(next.getKey(), next.getValue().getChannel());
        }
    }
}
```

### 2.2broker节点剔除

如果超过120s未收到新的心跳包，则认为broker已经宕机，便在nameServer中剔除改broker的路由信息。

会根据broker节点信息，在nameServer中将所有与该broker节点相关的信息剔除。

```java
 /**
     * nameServer中所有该broker中相关的路由信息全部移除
     * @param remoteAddr
     * @param channel
     */
    public void onChannelDestroy(String remoteAddr, Channel channel) {
        String brokerAddrFound = null;
        if (channel != null) {
            try {
                try {
                    this.lock.readLock().lockInterruptibly();
                    Iterator<Entry<String, BrokerLiveInfo>> itBrokerLiveTable =
                        this.brokerLiveTable.entrySet().iterator();
                    while (itBrokerLiveTable.hasNext()) {
                        Entry<String, BrokerLiveInfo> entry = itBrokerLiveTable.next();
                        if (entry.getValue().getChannel() == channel) {
                            brokerAddrFound = entry.getKey();
                            break;
                        }
                    }
                } finally {
                    this.lock.readLock().unlock();
                }
            } catch (Exception e) {
                log.error("onChannelDestroy Exception", e);
            }
        }

        if (null == brokerAddrFound) {
            brokerAddrFound = remoteAddr;
        } else {
            log.info("the broker's channel destroyed, {}, clean it's data structure at once", brokerAddrFound);
        }

        if (brokerAddrFound != null && brokerAddrFound.length() > 0) {

            try {
                try {
                    this.lock.writeLock().lockInterruptibly();
                    this.brokerLiveTable.remove(brokerAddrFound);
                    this.filterServerTable.remove(brokerAddrFound);
                    String brokerNameFound = null;
                    boolean removeBrokerName = false;
                    Iterator<Entry<String, BrokerData>> itBrokerAddrTable =
                        this.brokerAddrTable.entrySet().iterator();
                    while (itBrokerAddrTable.hasNext() && (null == brokerNameFound)) {
                        BrokerData brokerData = itBrokerAddrTable.next().getValue();

                        Iterator<Entry<Long, String>> it = brokerData.getBrokerAddrs().entrySet().iterator();
                        while (it.hasNext()) {
                            Entry<Long, String> entry = it.next();
                            Long brokerId = entry.getKey();
                            String brokerAddr = entry.getValue();
                            if (brokerAddr.equals(brokerAddrFound)) {
                                brokerNameFound = brokerData.getBrokerName();
                                it.remove();
                                log.info("remove brokerAddr[{}, {}] from brokerAddrTable, because channel destroyed",
                                    brokerId, brokerAddr);
                                break;
                            }
                        }

                        if (brokerData.getBrokerAddrs().isEmpty()) {
                            removeBrokerName = true;
                            itBrokerAddrTable.remove();
                            log.info("remove brokerName[{}] from brokerAddrTable, because channel destroyed",
                                brokerData.getBrokerName());
                        }
                    }

                    if (brokerNameFound != null && removeBrokerName) {
                        Iterator<Entry<String, Set<String>>> it = this.clusterAddrTable.entrySet().iterator();
                        while (it.hasNext()) {
                            Entry<String, Set<String>> entry = it.next();
                            String clusterName = entry.getKey();
                            Set<String> brokerNames = entry.getValue();
                            boolean removed = brokerNames.remove(brokerNameFound);
                            if (removed) {
                                log.info("remove brokerName[{}], clusterName[{}] from clusterAddrTable, because channel destroyed",
                                    brokerNameFound, clusterName);

                                if (brokerNames.isEmpty()) {
                                    log.info("remove the clusterName[{}] from clusterAddrTable, because channel destroyed and no broker in this cluster",
                                        clusterName);
                                    it.remove();
                                }

                                break;
                            }
                        }
                    }

                    if (removeBrokerName) {
                        Iterator<Entry<String, List<QueueData>>> itTopicQueueTable =
                            this.topicQueueTable.entrySet().iterator();
                        while (itTopicQueueTable.hasNext()) {
                            Entry<String, List<QueueData>> entry = itTopicQueueTable.next();
                            String topic = entry.getKey();
                            List<QueueData> queueDataList = entry.getValue();

                            Iterator<QueueData> itQueueData = queueDataList.iterator();
                            while (itQueueData.hasNext()) {
                                QueueData queueData = itQueueData.next();
                                if (queueData.getBrokerName().equals(brokerNameFound)) {
                                    itQueueData.remove();
                                    log.info("remove topic[{} {}], from topicQueueTable, because channel destroyed",
                                        topic, queueData);
                                }
                            }

                            if (queueDataList.isEmpty()) {
                                itTopicQueueTable.remove();
                                log.info("remove topic[{}] all queue, from topicQueueTable, because channel destroyed",
                                    topic);
                            }
                        }
                    }
                } finally {
                    this.lock.writeLock().unlock();
                }
            } catch (Exception e) {
                log.error("onChannelDestroy Exception", e);
            }
        }
    }
```

### 2.3brokerMaster选举恢复

如果SlaveBroker挂掉了，对整个系统影响不是很大。但如果是MasterBroker节点挂掉，则对整个集群的影响比较大。这个时候就对消息的写入和获取都有一定的影响了。

基于**Dledger**机制实现RocketMQ的高可用自动切换的效果，在masterBroker宕机之后，能够将slaveBroker切换成MasterBroker。

把Dledger融入RocketMQ之后，就可以让一个Master Broker对应多个Slave Broker，也就是说一份数据可以有多份副本，比如一个Master Broker对应两个Slave Broker。

此时一旦Master Broker宕机了，就可以在多个副本，也就是多个Slave中，通过Dledger技术和Raft协议算法进行leader选举，直接将一个Slave Broker选举为新的Master Broker，然后这个新的Master Broker就可以对外提供服务了。

整个过程也许只要10秒或者几十秒的时间就可以完成，这样的话，就可以实现MasterBroker挂掉之后，自动从多个Slave Broker中选举出来一个新的Master Broker，继续对外服务，一切都是自动的。

## 3.生产者与消费者的容错机制

当某个broker挂掉后，但是此时其他系统（消费者或生产者）是不知道这个Broker挂掉的，还以为有所有Broker都是正常状态，此时可能某个系统就会发送消息到那个已经挂掉的Broker上去，此时是绝对不可能成功发送消息的。此时，需要一个容错机制。

假设你必须要发送消息给那台Broker，那么他挂了，他的Slave机器是一个备份，可以继续使用，你是不是可以考虑等一会儿去跟他的Slave进行通信？

总之，这些都是思路，但是现在我们先知道，对于生产者而言，他是有一套容错机制的，即使一下子没感知到某个Broker挂了，他可以有别的方案去应对。而且过一会儿，系统又会重新从NameServer拉取最新的路由信息了，此时就会知道有一个Broker已经宕机了。

### 3.1重试机制

同步发送与异步发送都会失败重试的，比如说我发送一个消息，然后超时了，这时候在MQProducer层就会进行控制重试，默认是重试2次的，加上你发送那次，一共是发送3次，如果重试完还是有问题的话，这个时候就会抛出异常了。

### 3.2延迟故障



# 三、相关问题问答

## 1.rocketMq是如何实现分布式存储架构

MQ会收到大量的消息，这些消息并不是立马就会被所有的消费方获取过去消费的，所以一般MQ都得把消息在自己本地磁盘存储起来，然后等待消费方获取消息去处理。既然如此，MQ就得存储大量的消息，可能是几百万条，可能几亿条，甚至万亿条，这么多的消息在一台机器上肯定是没法存储的，RocketMQ是如何分布式存储海量消息的呢？

发送消息到MQ的系统会把消息分散发送给多台不同的机器，假设一共有3000条消息，分散发送给3台机器，可能每台机器就是接收到1000条消息。

![](https://tcs.teambition.net/storage/312c83e853bc4c13c712373252a12cb89038?Signature=eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJBcHBJRCI6IjU5Mzc3MGZmODM5NjMyMDAyZTAzNThmMSIsIl9hcHBJZCI6IjU5Mzc3MGZmODM5NjMyMDAyZTAzNThmMSIsIl9vcmdhbml6YXRpb25JZCI6IjVmYWZhYjk1YTNkYmY4NTljODhkN2ZiNiIsImV4cCI6MTYzNzk5NzMyMiwiaWF0IjoxNjM3OTkzNzIyLCJyZXNvdXJjZSI6Ii9zdG9yYWdlLzMxMmM4M2U4NTNiYzRjMTNjNzEyMzczMjUyYTEyY2I4OTAzOCJ9.zjWu-1amqYpbFjBZYw7gexqofw8yL15poWWTZlJPtdc&download=image.png "")

## 2.RocketMQ是如何集群化部署来承载高并发访问的？

RocketMQ是可以集群化部署的，可以部署在多台机器上，假设每台机器都能抗10万并发，然后你只要让几十万请求分散到多台机器上就可以了，让每台机器承受的QPS不超过10万不就行了。

![](https://tcs.teambition.net/storage/312c1e209573db53476bf5f2020c1cf612b3?Signature=eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJBcHBJRCI6IjU5Mzc3MGZmODM5NjMyMDAyZTAzNThmMSIsIl9hcHBJZCI6IjU5Mzc3MGZmODM5NjMyMDAyZTAzNThmMSIsIl9vcmdhbml6YXRpb25JZCI6IjVmYWZhYjk1YTNkYmY4NTljODhkN2ZiNiIsImV4cCI6MTYzNzk5NzQwMCwiaWF0IjoxNjM3OTkzODAwLCJyZXNvdXJjZSI6Ii9zdG9yYWdlLzMxMmMxZTIwOTU3M2RiNTM0NzZiZjVmMjAyMGMxY2Y2MTJiMyJ9.Zfw1HSSYUHde7SHBLdlBNON2CDTVRRHTamDOiRnayAM&download=image.png "")

## 3.任何一台Broker突然宕机了怎么办？

那不就会导致RocketMQ里一部分的消息就没了吗？这就会导致MQ的不可靠和不可用，这个问题怎么解决。RocketMQ的解决思路是**Broker主从架构以及多副本策略**。

![](https://tcs.teambition.net/storage/312c7021ad137eb6521b9063493ebec3289d?Signature=eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJBcHBJRCI6IjU5Mzc3MGZmODM5NjMyMDAyZTAzNThmMSIsIl9hcHBJZCI6IjU5Mzc3MGZmODM5NjMyMDAyZTAzNThmMSIsIl9vcmdhbml6YXRpb25JZCI6IjVmYWZhYjk1YTNkYmY4NTljODhkN2ZiNiIsImV4cCI6MTYzNzk5NzY1MiwiaWF0IjoxNjM3OTk0MDUyLCJyZXNvdXJjZSI6Ii9zdG9yYWdlLzMxMmM3MDIxYWQxMzdlYjY1MjFiOTA2MzQ5M2ViZWMzMjg5ZCJ9.nez-h-WWxNGoleXqxL221wNqoD8zrYL2Ig3BU6U13yo&download=image.png "")

## 4.生产者和消费者如何从NameServer那儿获取到集群的Broker信息呢？

RocketMQ中的生产者和消费者就是这样，自己主动去NameServer拉取Broker信息的。

![](https://tcs.teambition.net/storage/312c0fc4a5f12eee8cdb931374c79e549de6?Signature=eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJBcHBJRCI6IjU5Mzc3MGZmODM5NjMyMDAyZTAzNThmMSIsIl9hcHBJZCI6IjU5Mzc3MGZmODM5NjMyMDAyZTAzNThmMSIsIl9vcmdhbml6YXRpb25JZCI6IjVmYWZhYjk1YTNkYmY4NTljODhkN2ZiNiIsImV4cCI6MTYzNzk5Nzg1NiwiaWF0IjoxNjM3OTk0MjU2LCJyZXNvdXJjZSI6Ii9zdG9yYWdlLzMxMmMwZmM0YTVmMTJlZWU4Y2RiOTMxMzc0Yzc5ZTU0OWRlNiJ9.W5mbAjQ-PQ6VSxPYOINUiMl4-3lzBYXzskK5gz4LPz8&download=image.png "")

## 5.Master Broker是如何将消息同步给Slave Broker的？

RocketMQ的Master-Slave模式采取的是Slave Broker不停的发送请求到Master Broker去拉取消息。

![](https://tcs.teambition.net/storage/312c46413da2a6c208150d8c8a4ed90fb86c?Signature=eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJBcHBJRCI6IjU5Mzc3MGZmODM5NjMyMDAyZTAzNThmMSIsIl9hcHBJZCI6IjU5Mzc3MGZmODM5NjMyMDAyZTAzNThmMSIsIl9vcmdhbml6YXRpb25JZCI6IjVmYWZhYjk1YTNkYmY4NTljODhkN2ZiNiIsImV4cCI6MTYzNzk5ODE5OCwiaWF0IjoxNjM3OTk0NTk4LCJyZXNvdXJjZSI6Ii9zdG9yYWdlLzMxMmM0NjQxM2RhMmE2YzIwODE1MGQ4YzhhNGVkOTBmYjg2YyJ9._CEBKHx4KBQyXFIuOmRERwnxtsT7JAYg5WqRoI-XC7k&download=image.png "")

## 6.消费者获取消息的时候，是从Master获取还是从Slave获取?

主从结构，作为消费者的系统在获取消息的时候，是从Master Broker获取的？还是从Slave Broker获取的？

作为消费者的系统在获取消息的时候会先发送请求到Master Broker上去，请求获取一批消息，此时Master Broker是会返回一批消息给消费者系统的。然后Master Broker在返回消息给消费者系统的时候，会根据当时Master Broker的负载情况和Slave Broker的同步情况，**向消费者系统建议下一次拉取消息的时候是从Master Broker拉取还是从Slave Broker拉取**。

举个例子，要是这个时候Master Broker负载很重，本身要抗10万写并发了，你还要从他这里拉取消息，给他加重负担，那肯定是不合适的。所以此时Master Broker就会建议你从Slave Broker去拉取消息。

或者举另外一个例子，本身这个时候Master Broker上都已经写入了100万条数据了，结果Slave Broke不知道啥原因，同步的特别慢，才同步了96万条数据，落后了整整4万条消息的同步，这个时候你作为消费者系统可能都获取到96万条数据了，那么下次还是只能从Master Broker去拉取消息。因为Slave Broker同步太慢了，导致你没法从他那里获取更新的消息了。

## 7.生产者系统是如何将消息发送给Broker的？

在发送消息之前，得先有一个Topic，然后在发送消息的时候你得指定你要发送到哪个Topic里面去。

然后生产者跟NameServer建立一个TCP长连接，然后定时从他那里拉取到最新的路由信息，包括集群里有哪些Broker，集群里有哪些Topic，每个Topic都存储在哪些Broker上。就可以通过路由信息找到自己要投递消息的Topic分布在哪几台Broker上，此时可以根据负载均衡算法，从里面选择一台Broke机器出来。

选择一台Broker之后，就可以跟那个Broker也建立一个TCP长连接，然后通过长连接向Broker发送消息即可。Broker收到消息之后就会存储在自己本地磁盘里去。

这里唯一要注意的一点，**就是生产者一定是投递消息到Master Broker的**，然后Master Broker会同步数据给他的Slave Brokers，实现一份数据多份副本，保证Master故障的时候数据不丢失，而且可以自动把Slave切换为Master提供服务。

![](https://tcs.teambition.net/storage/312c63cdf6208e36d8d16fb89588b8b92d32?Signature=eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJBcHBJRCI6IjU5Mzc3MGZmODM5NjMyMDAyZTAzNThmMSIsIl9hcHBJZCI6IjU5Mzc3MGZmODM5NjMyMDAyZTAzNThmMSIsIl9vcmdhbml6YXRpb25JZCI6IjVmYWZhYjk1YTNkYmY4NTljODhkN2ZiNiIsImV4cCI6MTYzNzk5OTYxNCwiaWF0IjoxNjM3OTk2MDE0LCJyZXNvdXJjZSI6Ii9zdG9yYWdlLzMxMmM2M2NkZjYyMDhlMzZkOGQxNmZiODk1ODhiOGI5MmQzMiJ9.z0RG8FYimelqZWvSSocVWNNa2tBcsb27daPg9nvJUIc&download=image.png "")



## 8.消费者是如何从Broker上拉取消息的？

消费者系统其实跟生产者系统原理是类似的，他们也会跟NameServer建立长连接，然后拉取路由信息，接着找到自己要获取消息的Topic在哪几台Broker上，就可以跟Broker建立长连接，从里面拉取消息了。

![](https://tcs.teambition.net/storage/312cfc0c697303545b91a58ec108186ab76b?Signature=eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJBcHBJRCI6IjU5Mzc3MGZmODM5NjMyMDAyZTAzNThmMSIsIl9hcHBJZCI6IjU5Mzc3MGZmODM5NjMyMDAyZTAzNThmMSIsIl9vcmdhbml6YXRpb25JZCI6IjVmYWZhYjk1YTNkYmY4NTljODhkN2ZiNiIsImV4cCI6MTYzNzk5OTkxOSwiaWF0IjoxNjM3OTk2MzE5LCJyZXNvdXJjZSI6Ii9zdG9yYWdlLzMxMmNmYzBjNjk3MzAzNTQ1YjkxYTU4ZWMxMDgxODZhYjc2YiJ9.KVQzrXN0ApHpK7bnL1iYtX-NS601XXTiutJsPlfLa0Y&download=image.png "")

**消费者系统可能会从Master Broker拉取消息，也可能从Slave Broker拉取消息。**













