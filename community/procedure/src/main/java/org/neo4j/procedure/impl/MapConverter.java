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
package org.neo4j.procedure.impl;

import static org.neo4j.internal.kernel.api.procs.DefaultParameterValue.ntMap;

import java.util.Map;
import java.util.function.Function;
import org.neo4j.cypher.internal.evaluator.EvaluationException;
import org.neo4j.cypher.internal.evaluator.ExpressionEvaluator;
import org.neo4j.internal.kernel.api.procs.DefaultParameterValue;

public class MapConverter implements Function<String, DefaultParameterValue> {
    private final ExpressionEvaluator evaluator;

    MapConverter(ExpressionEvaluator evaluator) {
        this.evaluator = evaluator;
    }

    @SuppressWarnings("unchecked")
    @Override
    public DefaultParameterValue apply(String s) {
        try {
            return ntMap((Map<String, Object>) evaluator.evaluate(s, Map.class));
        } catch (EvaluationException e) {
            throw new IllegalArgumentException(String.format("%s is not a valid map expression", s), e);
        }
    }
}
