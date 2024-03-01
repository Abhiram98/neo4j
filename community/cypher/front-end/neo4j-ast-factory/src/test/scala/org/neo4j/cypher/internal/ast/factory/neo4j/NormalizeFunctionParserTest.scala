/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [https://neo4j.com]
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.neo4j.cypher.internal.ast.factory.neo4j

import org.neo4j.cypher.internal.ast.factory.neo4j.test.util.AstParsing.Antlr
import org.neo4j.cypher.internal.ast.factory.neo4j.test.util.AstParsing.JavaCc
import org.neo4j.cypher.internal.ast.factory.neo4j.test.util.AstParsingTestBase
import org.neo4j.cypher.internal.ast.factory.neo4j.test.util.LegacyAstParsingTestSupport
import org.neo4j.cypher.internal.expressions.Expression

class NormalizeFunctionParserTest extends AstParsingTestBase with LegacyAstParsingTestSupport {

  // Normalize defaults to NFC
  test("normalize(\"hello\")") {
    parsesTo[Expression](function("normalize", literalString("hello"), literalString("NFC")))
  }

  // All normal form keywords parse as expected
  Seq("NFC", "NFD", "NFKC", "NFKD").foreach { normalForm =>
    test(s"normalize(foo, $normalForm)") {
      parsesTo[Expression](function("normalize", varFor("foo"), literalString(normalForm)))
    }
  }

  // Failing tests
  test("normalize(\"hello\", \"NFC\")") {
    failsParsing[Expression]
  }

  test("normalize(\"hello\", null)") {
    failsParsing[Expression]
  }

  test("normalize(\"hello\", NFF)") {
    failsParsing[Expression]
  }

  test("normalize(\"hello\", NFC, anotherVar)") {
    whenParsing[Expression].parseIn(JavaCc)(_.withAnyFailure).parseIn(Antlr)(_.withoutErrors)
  }

  test("normalize()") {
    whenParsing[Expression].parseIn(JavaCc)(_.withAnyFailure).parseIn(Antlr)(_.withoutErrors)
  }

}
