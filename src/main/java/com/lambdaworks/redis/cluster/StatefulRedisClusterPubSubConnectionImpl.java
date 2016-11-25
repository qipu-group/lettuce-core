/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.lambdaworks.redis.cluster;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.concurrent.TimeUnit;

import com.lambdaworks.redis.AbstractRedisClient;
import com.lambdaworks.redis.RedisChannelWriter;
import com.lambdaworks.redis.RedisException;
import com.lambdaworks.redis.RedisURI;
import com.lambdaworks.redis.cluster.models.partitions.Partitions;
import com.lambdaworks.redis.cluster.models.partitions.RedisClusterNode;
import com.lambdaworks.redis.cluster.pubsub.RedisClusterPubSubListener;
import com.lambdaworks.redis.cluster.pubsub.StatefulRedisClusterPubSubConnection;
import com.lambdaworks.redis.cluster.pubsub.api.async.RedisClusterPubSubAsyncCommands;
import com.lambdaworks.redis.cluster.pubsub.api.reactive.RedisClusterPubSubReactiveCommands;
import com.lambdaworks.redis.cluster.pubsub.api.sync.NodeSelectionPubSubCommands;
import com.lambdaworks.redis.cluster.pubsub.api.sync.PubSubNodeSelection;
import com.lambdaworks.redis.cluster.pubsub.api.sync.RedisClusterPubSubCommands;
import com.lambdaworks.redis.codec.RedisCodec;
import com.lambdaworks.redis.pubsub.RedisPubSubAsyncCommandsImpl;
import com.lambdaworks.redis.pubsub.RedisPubSubReactiveCommandsImpl;
import com.lambdaworks.redis.pubsub.StatefulRedisPubSubConnection;
import com.lambdaworks.redis.pubsub.StatefulRedisPubSubConnectionImpl;
import com.lambdaworks.redis.pubsub.api.async.RedisPubSubAsyncCommands;
import com.lambdaworks.redis.pubsub.api.sync.RedisPubSubCommands;

/**
 * @author Mark Paluch
 */
class StatefulRedisClusterPubSubConnectionImpl<K, V> extends StatefulRedisPubSubConnectionImpl<K, V>
        implements StatefulRedisClusterPubSubConnection<K, V> {

    private final PubSubClusterEndpoint<K, V> endpoint;
    private volatile Partitions partitions;

    /**
     * Initialize a new connection.
     *
     * @param writer the channel writer
     * @param codec Codec used to encode/decode keys and values.
     * @param timeout Maximum time to wait for a response.
     * @param unit Unit of time for the timeout.
     */
    public StatefulRedisClusterPubSubConnectionImpl(PubSubClusterEndpoint<K, V> endpoint, RedisChannelWriter writer,
            RedisCodec<K, V> codec, long timeout, TimeUnit unit) {

        super(endpoint, writer, codec, timeout, unit);
        this.endpoint = endpoint;
    }

    @Override
    public RedisClusterPubSubAsyncCommands<K, V> async() {
        return (RedisClusterPubSubAsyncCommands<K, V>) super.async();
    }

    @Override
    protected RedisPubSubAsyncCommandsImpl<K, V> newRedisAsyncCommandsImpl() {
        return new RedisClusterPubSubAsyncCommandsImpl<>(this, codec);
    }

    @Override
    public RedisClusterPubSubCommands<K, V> sync() {
        return (RedisClusterPubSubCommands<K, V>) super.sync();
    }

    @SuppressWarnings("unchecked")
    @Override
    protected RedisPubSubCommands<K, V> newRedisSyncCommandsImpl() {

        return (RedisPubSubCommands) Proxy.newProxyInstance(AbstractRedisClient.class.getClassLoader(),
                new Class<?>[] { RedisClusterPubSubCommands.class, RedisPubSubCommands.class }, syncInvocationHandler());
    }

    private InvocationHandler syncInvocationHandler() {
        return new ClusterFutureSyncInvocationHandler<K, V>(this, RedisPubSubAsyncCommands.class, PubSubNodeSelection.class,
                NodeSelectionPubSubCommands.class, async());
    }

    @Override
    public RedisClusterPubSubReactiveCommands<K, V> reactive() {
        return (RedisClusterPubSubReactiveCommands<K, V>) super.reactive();
    }

    @Override
    protected RedisPubSubReactiveCommandsImpl<K, V> newRedisReactiveCommandsImpl() {
        return new RedisClusterPubSubReactiveCommandsImpl<K, V>(this, codec);
    }

    @Override
    public void activated() {
        super.activated();
        async().clusterMyId().thenAccept(nodeId -> endpoint.setClusterNode(partitions.getPartitionByNodeId(nodeId)));
    }

    @Override
    public StatefulRedisPubSubConnection<K, V> getConnection(String nodeId) {

        RedisURI redisURI = lookup(nodeId);

        if (redisURI == null) {
            throw new RedisException("NodeId " + nodeId + " does not belong to the cluster");
        }

        return (StatefulRedisPubSubConnection<K, V>) getClusterDistributionChannelWriter().getClusterConnectionProvider()
                .getConnection(ClusterConnectionProvider.Intent.WRITE, nodeId);
    }

    @Override
    public StatefulRedisPubSubConnection<K, V> getConnection(String host, int port) {

        return (StatefulRedisPubSubConnection<K, V>) getClusterDistributionChannelWriter().getClusterConnectionProvider()
                .getConnection(ClusterConnectionProvider.Intent.WRITE, host, port);
    }

    public void setPartitions(Partitions partitions) {
        this.partitions = partitions;
        getClusterDistributionChannelWriter().setPartitions(partitions);
    }

    public Partitions getPartitions() {
        return partitions;
    }

    @Override
    public void setNodeMessagePropagation(boolean enabled) {
        this.endpoint.setNodeMessagePropagation(enabled);
    }

    /**
     * Add a new {@link RedisClusterPubSubListener listener}.
     *
     * @param listener the listener, must not be {@literal null}.
     */
    @Override
    public void addListener(RedisClusterPubSubListener<K, V> listener) {
        endpoint.addListener(listener);
    }

    /**
     * Remove an existing {@link RedisClusterPubSubListener listener}.
     *
     * @param listener the listener, must not be {@literal null}.
     */
    @Override
    public void removeListener(RedisClusterPubSubListener<K, V> listener) {
        endpoint.removeListener(listener);
    }

    RedisClusterPubSubListener<K, V> getUpstreamListener() {
        return endpoint.getUpstreamListener();
    }

    protected ClusterDistributionChannelWriter getClusterDistributionChannelWriter() {
        return (ClusterDistributionChannelWriter) super.getChannelWriter();
    }

    private RedisURI lookup(String nodeId) {

        for (RedisClusterNode partition : partitions) {
            if (partition.getNodeId().equals(nodeId)) {
                return partition.getUri();
            }
        }
        return null;
    }
}