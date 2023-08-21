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
package org.neo4j.cypher.internal.runtime.interpreted.commands.expressions

import org.mockito.Mockito.when
import org.neo4j.cypher.internal.runtime.ImplicitValueConversion.toDoubleValue
import org.neo4j.cypher.internal.runtime.ImplicitValueConversion.toLongValue
import org.neo4j.cypher.internal.util.test_helpers.CypherFunSuite
import org.neo4j.values.storable.Values.doubleValue
import org.neo4j.values.storable.Values.longValue

class ModuloTest extends CypherFunSuite {

  test("should handle large integers") {
    // Given
    val modulo = Modulo(expressionMock, expressionMock)

    modulo.calc(16000000000000001L, 16000) should equal(longValue(1L))
  }

  test("should handle large integers and floating point values") {
    // Given
    val modulo = Modulo(expressionMock, expressionMock)

    modulo.calc(16000000000000001L, 16000d) should equal(doubleValue(0.0))
  }

  def expressionMock: Expression = {
    val expression = mock[Expression]
    when(expression.children).thenReturn(Seq.empty)
    expression
  }
}
