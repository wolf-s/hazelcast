/*
 * Copyright (c) 2008-2013, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.spi.impl;

import com.hazelcast.cluster.ClusterService;
import com.hazelcast.config.Config;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.instance.GroupProperties;
import com.hazelcast.instance.MemberImpl;
import com.hazelcast.instance.Node;
import com.hazelcast.instance.ThreadContext;
import com.hazelcast.logging.ILogger;
import com.hazelcast.nio.Address;
import com.hazelcast.nio.Packet;
import com.hazelcast.nio.serialization.Data;
import com.hazelcast.nio.serialization.SerializationContext;
import com.hazelcast.nio.serialization.SerializationService;
import com.hazelcast.partition.MigrationInfo;
import com.hazelcast.partition.PartitionService;
import com.hazelcast.spi.*;
import com.hazelcast.spi.annotation.PrivateApi;
import com.hazelcast.transaction.TransactionImpl;

import java.util.Collection;
import java.util.LinkedList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public class NodeEngineImpl implements NodeEngine {

    private final Node node;
    private final ILogger logger;

    final ProxyServiceImpl proxyService;
    final ServiceManager serviceManager;
    final OperationServiceImpl operationService;
    final ExecutionServiceImpl executionService;
    final EventServiceImpl eventService;
    final AsyncInvocationServiceImpl asyncInvocationService;
    final WaitNotifyService waitNotifyService;

    public NodeEngineImpl(Node node) {
        this.node = node;
        logger = node.getLogger(NodeEngine.class.getName());
        proxyService = new ProxyServiceImpl(this);
        serviceManager = new ServiceManager(this);
        executionService = new ExecutionServiceImpl(this);
        operationService = new OperationServiceImpl(this);
        eventService = new EventServiceImpl(this);
        asyncInvocationService = new AsyncInvocationServiceImpl(this);
        waitNotifyService = new WaitNotifyService(this, new WaitingOpProcessorImpl());
    }

    @PrivateApi
    public void start() {
        serviceManager.start();
        proxyService.init();
    }

    public Address getThisAddress() {
        return node.getThisAddress();
    }

    public MemberImpl getLocalMember() {
        return node.getLocalMember();
    }

    public Config getConfig() {
        return node.getConfig();
    }

    public EventService getEventService() {
        return eventService;
    }

    public SerializationService getSerializationService() {
        return node.serializationService;
    }

    public SerializationContext getSerializationContext() {
        return node.serializationService.getSerializationContext();
    }

    public OperationService getOperationService() {
        return operationService;
    }

    public ExecutionService getExecutionService() {
        return executionService;
    }

    public PartitionService getPartitionService() {
        return node.getPartitionService();
    }

    public ClusterService getClusterService() {
        return node.getClusterService();
    }

    public ProxyService getProxyService() {
        return proxyService;
    }

    public AsyncInvocationService getAsyncInvocationService() {
        return asyncInvocationService;
    }

    public Data toData(final Object object) {
        return node.serializationService.toData(object);
    }

    public Object toObject(final Object object) {
        if (object instanceof Data) {
            return node.serializationService.toObject((Data) object);
        }
        return object;
    }

    public TransactionImpl getTransaction() {
        return ThreadContext.getTransaction(node.getName());
    }

    public boolean isActive() {
        return node.isActive();
    }

    public HazelcastInstance getHazelcastInstance() {
        return node.hazelcastInstance;
    }

    public boolean send(Packet packet, Connection connection) {
        if (connection == null || !connection.live()) return false;
        final MemberImpl memberImpl = node.getClusterService().getMember(connection.getEndPoint());
        if (memberImpl != null) {
            memberImpl.didWrite();
        }
        return connection.write(packet);
    }

    public boolean send(Packet packet, Address target) {
        return send(packet, target, null);
    }

    private boolean send(Packet packet, Address target, FutureSend futureSend) {
        final ConnectionManager connectionManager = node.getConnectionManager();
        final Connection connection = connectionManager.getConnection(target);
        if (connection != null) {
            return send(packet, connection);
        } else {
            if (futureSend == null) {
                futureSend = new FutureSend(packet, target);
            }
            final int retries = futureSend.retries;
            if (retries < 5) {
                connectionManager.getOrConnect(target, true);
                // TODO: Caution: may break the order guarantee of the packets sent from the same thread!
                executionService.schedule(futureSend, (retries + 1) * 100, TimeUnit.MILLISECONDS);
                return true;
            }
            return false;
        }
    }

    private class FutureSend implements Runnable {
        private final Packet packet;
        private final Address target;
        private volatile int retries = 0;

        private FutureSend(Packet packet, Address target) {
            this.packet = packet;
            this.target = target;
        }

        public void run() {
            retries++;
            if (logger.isLoggable(Level.FINEST)) {
                logger.log(Level.FINEST, "Retrying[" + retries + "] packet send operation to: " + target);
            }
            send(packet, target, this);
        }
    }

    public ILogger getLogger(String name) {
        return node.getLogger(name);
    }

    public GroupProperties getGroupProperties() {
        return node.getGroupProperties();
    }

    @PrivateApi
    public void handlePacket(Packet packet) {
        if (packet.isHeaderSet(Packet.HEADER_OP)) {
            operationService.handleOperation(packet);
        } else if (packet.isHeaderSet(Packet.HEADER_EVENT)) {
            eventService.handleEvent(packet);
        } else {
            throw new IllegalArgumentException("Unknown packet type !");
        }
    }

    @PrivateApi
    public <T> T getService(String serviceName) {
        return serviceManager.getService(serviceName);
    }

    public <T extends SharedService> T getSharedService(Class<T> serviceClass, String serviceName) {
        final Object service = serviceManager.getService(serviceName);
        if (service == null) {
            return null;
        }
        if (serviceClass.isAssignableFrom(service.getClass())) {
            return serviceClass.cast(service);
        } else {
            throw new IllegalArgumentException();
        }
    }

    /**
     * Returns a list of services matching provides service class/interface.
     * <br></br>
     * <b>CoreServices will be placed at the beginning of the list.</b>
     */
    @PrivateApi
    public <S> Collection<S> getServices(Class<S> serviceClass) {
        return serviceManager.getServices(serviceClass);
    }

    @PrivateApi
    public Node getNode() {
        return node;
    }

    @PrivateApi
    public void onMemberLeft(MemberImpl member) {
        operationService.onMemberLeft(member);
        waitNotifyService.onMemberLeft(member.getAddress());
        eventService.onMemberLeft(member);
    }

    @PrivateApi
    public void onMemberDisconnect(Address disconnectedAddress) {
        operationService.onMemberDisconnect(disconnectedAddress);
    }

    @PrivateApi
    public void onPartitionMigrate(MigrationInfo migrationInfo) {
        waitNotifyService.onPartitionMigrate(getThisAddress(), migrationInfo);
    }

    /**
     * Post join operations must be lock free; means no locks at all;
     * no partition locks, no key-based locks, no service level locks!
     * <p/>
     * Post join operations should return response, at least a null response.
     * <p/>
     * Also making post join operation a JoinOperation will help a lot.
     */
    @PrivateApi
    public Operation[] getPostJoinOperations() {
        final Collection<Operation> postJoinOps = new LinkedList<Operation>();
        final Operation eventPostJoinOp = eventService.getPostJoinOperation();
        if (eventPostJoinOp != null) {
            postJoinOps.add(eventPostJoinOp);
        }
        Collection<PostJoinAwareService> services = getServices(PostJoinAwareService.class);
        for (PostJoinAwareService service : services) {
            final Operation pjOp = service.getPostJoinOperation();
            if (pjOp != null) {
                if (pjOp instanceof PartitionAwareOperation) {
                    logger.log(Level.SEVERE, "Post-join operations cannot implement PartitionAwareOperation!" +
                            " Service: " + service + ", Operation: " + pjOp);
                    continue;
                }
                postJoinOps.add(pjOp);
            }
        }
        return postJoinOps.isEmpty() ? null : postJoinOps.toArray(new Operation[postJoinOps.size()]);
    }

    public long getClusterTime() {
        return node.getClusterService().getClusterTime();
    }

    @PrivateApi
    public void shutdown() {
        logger.log(Level.FINEST, "Shutting down services...");
        waitNotifyService.shutdown();
        proxyService.shutdown();
        serviceManager.shutdown();
        executionService.shutdown();
        asyncInvocationService.shutdown();
        eventService.shutdown();
        operationService.shutdown();
    }

    private class WaitingOpProcessorImpl implements WaitNotifyService.WaitingOpProcessor {

        public void invalidate(final WaitNotifyService.WaitingOp so) throws Exception {
            operationService.executeOperation(so);
        }

        public void processUnderExistingLock(Operation operation) {
            operationService.runOperationUnderExistingLock(operation);
        }
    }
}