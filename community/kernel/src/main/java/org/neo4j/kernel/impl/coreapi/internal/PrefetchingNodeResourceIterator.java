/*
 * Copyright (c) 2002-2019 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
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
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.impl.coreapi.internal;

import java.util.NoSuchElementException;

import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.ResourceIterator;
import org.neo4j.kernel.api.Statement;

abstract class PrefetchingNodeResourceIterator implements ResourceIterator<Node>
{
    private final Statement statement;
    private final NodeFactory nodeFactory;
    private long next;
    private boolean closed;

    private static final long NOT_INITIALIZED = -2L;
    protected static final long NO_ID = -1L;

    PrefetchingNodeResourceIterator( Statement statement, NodeFactory nodeFactory )
    {
        this.statement = statement;
        this.nodeFactory = nodeFactory;
        this.next = NOT_INITIALIZED;
    }

    @Override
    public boolean hasNext()
    {
        if ( next == NOT_INITIALIZED )
        {
            next = fetchNext();
        }
        return next != NO_ID;
    }

    @Override
    public Node next()
    {
        if ( !hasNext() )
        {
            close();
            throw new NoSuchElementException(  );
        }
        Node nodeProxy = nodeFactory.make( next );
        next = fetchNext();
        return nodeProxy;
    }

    @Override
    public void close()
    {
        if ( !closed )
        {
            next = NO_ID;
            closeResources( statement );
            closed = true;
        }
    }

    abstract long fetchNext();

    abstract void closeResources( Statement statement );
}
