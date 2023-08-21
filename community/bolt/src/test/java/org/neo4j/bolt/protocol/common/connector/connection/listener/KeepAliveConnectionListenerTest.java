/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [https://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.neo4j.bolt.protocol.common.connector.connection.listener;

import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.neo4j.bolt.protocol.common.BoltProtocol;
import org.neo4j.bolt.protocol.common.connector.connection.Connection;
import org.neo4j.bolt.protocol.common.handler.KeepAliveHandler;
import org.neo4j.logging.AssertableLogProvider;
import org.neo4j.logging.LogAssertions;
import org.neo4j.memory.MemoryTracker;

class KeepAliveConnectionListenerTest {

    private static final String CONNECTION_ID = "bolt-keepalive";

    private Connection connection;
    private MemoryTracker memoryTracker;
    private Channel channel;
    private ChannelPipeline pipeline;
    private AssertableLogProvider logProvider;

    private KeepAliveConnectionListener listener;

    @BeforeEach
    void prepareListener() {
        this.connection = Mockito.mock(Connection.class, Mockito.RETURNS_MOCKS);
        this.memoryTracker = Mockito.mock(MemoryTracker.class);
        this.channel = Mockito.mock(Channel.class);
        this.pipeline = Mockito.mock(ChannelPipeline.class, Mockito.RETURNS_SELF);
        this.logProvider = new AssertableLogProvider();

        Mockito.doReturn(CONNECTION_ID).when(this.connection).id();
        Mockito.doReturn(this.memoryTracker).when(this.connection).memoryTracker();
        Mockito.doReturn(this.channel).when(this.connection).channel();
        Mockito.doReturn(this.pipeline).when(this.channel).pipeline();

        this.listener = new KeepAliveConnectionListener(connection, true, 4242, this.logProvider);
    }

    @Test
    void shouldInstallKeepAliveHandlerOnProtocolSelected() {
        var protocol = Mockito.mock(BoltProtocol.class);

        this.listener.onProtocolSelected(protocol);

        var inOrder = Mockito.inOrder(this.connection, this.memoryTracker, this.channel, this.pipeline);

        inOrder.verify(this.connection).memoryTracker();
        inOrder.verify(this.memoryTracker).allocateHeap(KeepAliveHandler.SHALLOW_SIZE);
        inOrder.verify(this.connection).channel();
        inOrder.verify(this.channel).pipeline();
        inOrder.verify(this.pipeline).addLast(ArgumentMatchers.any(KeepAliveHandler.class));
        inOrder.verify(this.connection).removeListener(this.listener);

        Mockito.verifyNoInteractions(protocol);

        LogAssertions.assertThat(this.logProvider)
                .forLevel(AssertableLogProvider.Level.DEBUG)
                .forClass(KeepAliveConnectionListener.class)
                .containsMessageWithArgumentsContaining("Installing keep alive handler", CONNECTION_ID);
    }

    @Test
    void shouldReleaseMemoryOnRemoval() {
        this.listener.onListenerRemoved();

        Mockito.verify(this.memoryTracker).releaseHeap(KeepAliveConnectionListener.SHALLOW_SIZE);
    }
}
