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
package org.neo4j.router.impl.query.parsing;

import java.util.Optional;
import java.util.function.Supplier;
import org.neo4j.cypher.internal.ast.CatalogName;
import org.neo4j.cypher.internal.cache.CacheSize;
import org.neo4j.cypher.internal.cache.CacheTracer;
import org.neo4j.cypher.internal.cache.CaffeineCacheFactory;
import org.neo4j.cypher.internal.cache.LFUCache;
import org.neo4j.function.Observable;
import org.neo4j.router.query.QueryTargetParser;

public class QueryTargetCache implements QueryTargetParser.Cache {

    private final LFUCache<String, Optional<CatalogName>> cache;

    public QueryTargetCache(
            CaffeineCacheFactory cacheFactory, Observable<Integer> cacheSize, CacheTracer<String> tracer) {
        this.cache = new LFUCache<>(cacheFactory, new CacheSize.Dynamic(cacheSize), tracer);
    }

    public QueryTargetCache(CaffeineCacheFactory cacheFactory, int cacheSize, CacheTracer<String> tracer) {
        this.cache = new LFUCache<>(cacheFactory, new CacheSize.Static(cacheSize), tracer);
    }

    @Override
    public Optional<CatalogName> computeIfAbsent(String query, Supplier<Optional<CatalogName>> supplier) {
        return cache.computeIfAbsent(query, supplier::get);
    }
}
