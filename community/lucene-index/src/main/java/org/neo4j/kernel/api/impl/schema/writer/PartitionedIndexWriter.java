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
package org.neo4j.kernel.api.impl.schema.writer;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.Query;
import org.apache.lucene.store.Directory;
import org.neo4j.configuration.Config;
import org.neo4j.configuration.GraphDatabaseInternalSettings;
import org.neo4j.kernel.api.impl.index.WritableDatabaseIndex;
import org.neo4j.kernel.api.impl.index.partition.AbstractIndexPartition;

/**
 * Schema Lucene index writer implementation that supports writing into multiple partitions and creates partitions
 * on-demand if needed.
 * <p>
 * Writer threats partition as writable if partition has number of live and deleted documents that is less
 * than a value explicitly configured using {@link GraphDatabaseInternalSettings#lucene_max_partition_size}
 * or {@link #DEFAULT_MAXIMUM_PARTITION_SIZE} otherwise.
 * First observable partition that satisfy writer criteria is used for writing.
 */
public class PartitionedIndexWriter implements LuceneIndexWriter {
    // by default we still keep a spare of 10% to the maximum partition size: During concurrent updates
    // it could happen that 2 threads reserve space in a partition (without claiming it by doing addDocument):
    private static final Integer DEFAULT_MAXIMUM_PARTITION_SIZE = IndexWriter.MAX_DOCS - (IndexWriter.MAX_DOCS / 10);

    private final WritableDatabaseIndex<?, ?> index;
    private final int maximumPartitionSize;

    public PartitionedIndexWriter(WritableDatabaseIndex<?, ?> index, Config config) {
        this.index = index;
        var configuredMaxPartitionSize = config.get(GraphDatabaseInternalSettings.lucene_max_partition_size);
        maximumPartitionSize = Objects.requireNonNullElse(configuredMaxPartitionSize, DEFAULT_MAXIMUM_PARTITION_SIZE);
    }

    @Override
    public void addDocument(Document doc) throws IOException {
        getIndexWriter(1).addDocument(doc);
    }

    @Override
    public void addDocuments(int numDocs, Iterable<Document> documents) throws IOException {
        getIndexWriter(numDocs).addDocuments(documents);
    }

    @Override
    public void updateDocument(Term term, Document doc) throws IOException {
        List<AbstractIndexPartition> partitions = index.getPartitions();
        if (WritableDatabaseIndex.hasSinglePartition(partitions)
                && writablePartition(WritableDatabaseIndex.getFirstPartition(partitions), 1)) {
            WritableDatabaseIndex.getFirstPartition(partitions).getIndexWriter().updateDocument(term, doc);
        } else {
            deleteDocuments(term);
            addDocument(doc);
        }
    }

    @Override
    public void deleteDocuments(Query query) throws IOException {
        List<AbstractIndexPartition> partitions = index.getPartitions();
        for (AbstractIndexPartition partition : partitions) {
            partition.getIndexWriter().deleteDocuments(query);
        }
    }

    @Override
    public void addDirectory(int count, Directory directory) throws IOException {
        getIndexWriter(count).addIndexes(directory);
    }

    @Override
    public void deleteDocuments(Term term) throws IOException {
        List<AbstractIndexPartition> partitions = index.getPartitions();
        for (AbstractIndexPartition partition : partitions) {
            partition.getIndexWriter().deleteDocuments(term);
        }
    }

    private IndexWriter getIndexWriter(int numDocs) throws IOException {
        synchronized (index) {
            // We synchronise on the index to coordinate with all writers about how many partitions we
            // have, and when new ones are created. The discovery that a new partition needs to be added,
            // and the call to index.addNewPartition() must be atomic.
            return unsafeGetIndexWriter(numDocs);
        }
    }

    private IndexWriter unsafeGetIndexWriter(int numDocs) throws IOException {
        List<AbstractIndexPartition> indexPartitions = index.getPartitions();
        int size = indexPartitions.size();
        //noinspection ForLoopReplaceableByForEach
        for (int i = 0; i < size; i++) {
            // We should find the *first* writable partition, so we can fill holes left by index deletes,
            // after they were merged away:
            AbstractIndexPartition partition = indexPartitions.get(i);
            if (writablePartition(partition, numDocs)) {
                return partition.getIndexWriter();
            }
        }
        AbstractIndexPartition indexPartition = index.addNewPartition();
        return indexPartition.getIndexWriter();
    }

    private boolean writablePartition(AbstractIndexPartition partition, int numDocs) {
        return maximumPartitionSize - partition.getIndexWriter().getDocStats().maxDoc >= numDocs;
    }
}
