/*
 * Copyright (c) 2008 - 2013 10gen, Inc. <http://10gen.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.mongodb.connection;

import org.mongodb.MongoException;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.mongodb.assertions.Assertions.isTrue;
import static org.mongodb.assertions.Assertions.notNull;
import static org.mongodb.connection.ServerConnectionState.Connecting;

class DefaultServer implements ClusterableServer {
    private final ScheduledExecutorService scheduledExecutorService;
    private final ServerAddress serverAddress;
    private final ConnectionProvider connectionProvider;
    private final AsyncConnectionProvider asyncConnectionProvider;
    private final IsMasterServerStateNotifier stateNotifier;
    private final ScheduledFuture<?> scheduledFuture;
    private final Set<ChangeListener<ServerDescription>> changeListeners =
            Collections.newSetFromMap(new ConcurrentHashMap<ChangeListener<ServerDescription>, Boolean>());
    private volatile ServerDescription description;
    private volatile boolean isClosed;

    public DefaultServer(final ServerAddress serverAddress,
                         final DefaultServerSettings settings,
                         final ConnectionProvider connectionProvider,
                         final AsyncConnectionProvider asyncConnectionProvider,
                         final ConnectionFactory heartbeatConnectionFactory,
                         final ScheduledExecutorService scheduledExecutorService,
                         final BufferPool<ByteBuffer> bufferPool) {
        notNull("connectionProvider", connectionProvider);
        notNull("heartbeatConnectionFactory", heartbeatConnectionFactory);
        notNull("scheduledExecutorService", scheduledExecutorService);
        notNull("bufferPool", bufferPool);

        this.scheduledExecutorService = notNull("scheduledExecutorService", scheduledExecutorService);
        this.serverAddress = notNull("serverAddress", serverAddress);
        this.connectionProvider = connectionProvider;
        this.asyncConnectionProvider = asyncConnectionProvider;
        this.description = ServerDescription.builder().state(Connecting).address(serverAddress).build();
        this.stateNotifier = new IsMasterServerStateNotifier(serverAddress, new DefaultServerStateListener(), heartbeatConnectionFactory,
                bufferPool);
        this.scheduledFuture = scheduledExecutorService.scheduleAtFixedRate(stateNotifier, 0,
                settings.getHeartbeatFrequency(TimeUnit.MILLISECONDS), TimeUnit.MILLISECONDS);
    }

    @Override
    public ServerConnection getConnection() {
        isTrue("open", !isClosed());

        return new DefaultServerConnection(connectionProvider.get());
    }

    @Override
    public AsyncServerConnection getAsyncConnection() {
        isTrue("open", !isClosed());

        if (asyncConnectionProvider == null) {
            throw new UnsupportedOperationException("Asynchronous connections not supported in this version of Java");
        }
        return new DefaultServerAsyncConnection(asyncConnectionProvider.get());
    }

    @Override
    public ServerDescription getDescription() {
        isTrue("open", !isClosed());

        return description;
    }

    @Override
    public void addChangeListener(final ChangeListener<ServerDescription> changeListener) {
        isTrue("open", !isClosed());

        changeListeners.add(changeListener);
    }

    @Override
    public void invalidate() {
        isTrue("open", !isClosed());

        description = ServerDescription.builder().state(Connecting).address(serverAddress).build();
        scheduledExecutorService.submit(stateNotifier);
    }

    @Override
    public void close() {
        if (!isClosed()) {
            connectionProvider.close();
            if (asyncConnectionProvider != null) {
                asyncConnectionProvider.close();
            }
            scheduledFuture.cancel(true);
            stateNotifier.close();
            isClosed = true;
        }
    }

    @Override
    public boolean isClosed() {
        return isClosed;
    }

    private void handleException() {
        invalidate();  // TODO: handle different exceptions sub-classes differently
    }

    private final class DefaultServerStateListener implements ChangeListener<ServerDescription> {
        @Override
        public void stateChanged(final ChangeEvent<ServerDescription> event) {
            description = event.getNewValue();
            for (ChangeListener<ServerDescription> listener : changeListeners) {
                listener.stateChanged(event);
            }
        }

    }

    private class DefaultServerConnection implements ServerConnection {
        private Connection wrapped;

        public DefaultServerConnection(final Connection wrapped) {
            this.wrapped = wrapped;
        }

        @Override
        public ServerAddress getServerAddress() {
            isTrue("open", !isClosed());
            return wrapped.getServerAddress();
        }

        @Override
        public void sendMessage(final ChannelAwareOutputBuffer buffer) {
            isTrue("open", !isClosed());
            try {
                wrapped.sendMessage(buffer);
            } catch (MongoException e) {
                handleException();
                throw e;
            }
        }

        @Override
        public ResponseBuffers receiveMessage(final ResponseSettings responseSettings) {
            isTrue("open", !isClosed());
            try {
                return wrapped.receiveMessage(responseSettings);
            } catch (MongoException e) {
                handleException();
                throw e;
            }
        }

        @Override
        public void close() {
            if (wrapped != null) {
                wrapped.close();
                wrapped = null;
            }
        }

        @Override
        public boolean isClosed() {
            return wrapped == null;
        }

        @Override
        public ServerDescription getDescription() {
            return description;
        }
    }

    // TODO: chain callbacks in order to be notified of exceptions
    private class DefaultServerAsyncConnection implements AsyncServerConnection {
        private AsyncConnection wrapped;

        public DefaultServerAsyncConnection(final AsyncConnection wrapped) {
            this.wrapped = notNull("wrapped", wrapped);
        }

        @Override
        public ServerAddress getServerAddress() {
            isTrue("open", !isClosed());
            return wrapped.getServerAddress();
        }

        @Override
        public void sendMessage(final ChannelAwareOutputBuffer buffer, final SingleResultCallback<ResponseBuffers> callback) {
            isTrue("open", !isClosed());
            wrapped.sendMessage(buffer, new InvalidatingSingleResultCallback(callback));
        }

        @Override
        public void sendAndReceiveMessage(final ChannelAwareOutputBuffer buffer, final ResponseSettings responseSettings,
                                          final SingleResultCallback<ResponseBuffers> callback) {
            isTrue("open", !isClosed());
            wrapped.sendAndReceiveMessage(buffer, responseSettings, new InvalidatingSingleResultCallback(callback));
        }

        @Override
        public void receiveMessage(final ResponseSettings responseSettings, final SingleResultCallback<ResponseBuffers> callback) {
            isTrue("open", !isClosed());
            wrapped.receiveMessage(responseSettings, new InvalidatingSingleResultCallback(callback));
        }

        @Override
        public void close() {
            if (wrapped != null) {
                wrapped.close();
                wrapped = null;
            }
        }

        @Override
        public boolean isClosed() {
            return wrapped == null;
        }

        @Override
        public ServerDescription getDescription() {
            return description;
        }


        private final class InvalidatingSingleResultCallback implements SingleResultCallback<ResponseBuffers> {
            private final SingleResultCallback<ResponseBuffers> callback;

            public InvalidatingSingleResultCallback(final SingleResultCallback<ResponseBuffers> callback) {
                this.callback = callback;
            }

            @Override
            public void onResult(final ResponseBuffers result, final MongoException e) {
                if (e != null) {
                    invalidate();
                }
                callback.onResult(result, e);
            }
        }
    }
}