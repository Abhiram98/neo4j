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

import static org.neo4j.kernel.database.NamedDatabaseId.SYSTEM_DATABASE_NAME;
import static scala.jdk.javaapi.OptionConverters.toJava;

import java.util.Optional;
import org.neo4j.cypher.internal.PreParsedQuery;
import org.neo4j.cypher.internal.PreParser;
import org.neo4j.cypher.internal.ast.AdministrationCommand;
import org.neo4j.cypher.internal.ast.CatalogName;
import org.neo4j.cypher.internal.compiler.CypherParsing;
import org.neo4j.cypher.internal.frontend.phases.BaseState;
import org.neo4j.cypher.internal.tracing.CompilationTracer;
import org.neo4j.cypher.internal.util.CancellationChecker;
import org.neo4j.cypher.internal.util.RecordingNotificationLogger;
import org.neo4j.fabric.eval.StaticUseEvaluation;
import org.neo4j.router.query.Query;
import org.neo4j.router.query.QueryTargetParser;
import scala.Option;

public class StandardQueryTargetParser implements QueryTargetParser {

    public static final CatalogName SYSTEM_DATABASE_CATALOG_NAME = CatalogName.of(SYSTEM_DATABASE_NAME);
    private final Cache cache;
    private final PreParser preParser;
    private final CypherParsing parsing;
    private final CompilationTracer tracer;
    private final CancellationChecker cancellationChecker;
    private final StaticUseEvaluation staticUseEvaluation = new StaticUseEvaluation();

    public StandardQueryTargetParser(
            QueryTargetParser.Cache cache,
            PreParser preParser,
            CypherParsing parsing,
            CompilationTracer tracer,
            CancellationChecker cancellationChecker) {
        this.cache = cache;
        this.preParser = preParser;
        this.parsing = parsing;
        this.tracer = tracer;
        this.cancellationChecker = cancellationChecker;
    }

    @Override
    public Optional<CatalogName> parseQueryTarget(Query query) {
        return cache.computeIfAbsent(query.text(), () -> doParseQueryTarget(query));
    }

    private Optional<CatalogName> doParseQueryTarget(Query query) {
        var queryTracer = tracer.compileQuery(query.text());
        var notificationLogger = new RecordingNotificationLogger();
        var preParsedQuery = preParser.preParse(query.text(), notificationLogger);
        var parsedQuery = parse(query, queryTracer, preParsedQuery);
        return determineTarget(parsedQuery);
    }

    private BaseState parse(
            Query query, CompilationTracer.QueryCompilationEvent queryTracer, PreParsedQuery preParsedQuery) {
        return parsing.parseQuery(
                preParsedQuery.statement(),
                preParsedQuery.rawStatement(),
                new RecordingNotificationLogger(),
                preParsedQuery.options().queryOptions().planner().name(),
                Option.apply(preParsedQuery.options().offset()),
                queryTracer,
                query.parameters(),
                cancellationChecker);
    }

    private Optional<CatalogName> determineTarget(BaseState parsedQuery) {
        var statement = parsedQuery.statement();

        if (statement instanceof AdministrationCommand) {
            return Optional.of(SYSTEM_DATABASE_CATALOG_NAME);
        } else {
            var catalogNameOption = staticUseEvaluation.evaluateStaticLeadingGraphSelection(statement);
            return toJava(catalogNameOption);
        }
    }
}
