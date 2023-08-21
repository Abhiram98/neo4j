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

import org.neo4j.cypher.internal.ast.AstConstructionTestSupport
import org.neo4j.cypher.internal.expressions.RelationshipPattern
import org.neo4j.cypher.internal.util.OpenCypherExceptionFactory
import org.neo4j.cypher.internal.util.test_helpers.CypherFunSuite
import org.neo4j.cypher.internal.util.test_helpers.TestName

class RelationshipPatternPredicateParserTest extends CypherFunSuite with TestName with AstConstructionTestSupport {

  for {
    (maybeLabelExpression, maybeLabelExpressionAst) <-
      Seq(("", None), (":Foo|Bar", Some(labelDisjunction(labelRelTypeLeaf("Foo"), labelRelTypeLeaf("Bar")))))
    (maybePathLength, maybePathLengthAst) <-
      Seq(("", None), ("*1..5", Some(Some(range(Some(1), Some(5))))))
    (maybeProperties, maybePropertiesAst) <-
      Seq(("", None), ("{prop: 'test'}", Some(mapOf("prop" -> literalString("test")))))
  } yield {

    test(s"MATCH (n)-[r$maybeLabelExpression$maybePathLength $maybeProperties WHERE r.otherProp > 123]->()") {
      parseRelationshipPatterns(testName) shouldBe Seq(
        relPat(
          Some("r"),
          maybeLabelExpressionAst,
          maybePathLengthAst,
          maybePropertiesAst,
          Some(greaterThan(prop("r", "otherProp"), literalInt(123)))
        )
      )
    }

    test(s"MATCH (n)-[$maybeLabelExpression$maybePathLength $maybeProperties WHERE n.prop > 123]->()") {
      parseRelationshipPatterns(testName) shouldBe Seq(
        relPat(
          None,
          maybeLabelExpressionAst,
          maybePathLengthAst,
          maybePropertiesAst,
          Some(greaterThan(prop("n", "prop"), literalInt(123)))
        )
      )
    }
  }

  test("MATCH ()-[WHERE]->()") {
    parseRelationshipPatterns(testName) shouldBe Seq(relPat(Some("WHERE")))
  }

  /* This case is ambiguous from a language standpoint, it could be either
   * 1. an inlined WHERE clause with a map expression (which would fail in semantic checking as WHERE expects a boolean expression)
   * 2. a relationship named WHERE with a property map
   * As the second case is not just syntactically but also semantically correct, the parser has been programmed to prefer it.
   */
  test("MATCH ()-[WHERE {prop: 123}]->()") {
    parseRelationshipPatterns(testName) shouldBe Seq(
      relPat(Some("WHERE"), properties = Some(mapOf("prop" -> literal(123))))
    )
  }

  test("MATCH ()-[WHERE WHERE {prop: 123}]->()") {
    parseRelationshipPatterns(testName) shouldBe Seq(
      relPat(Some("WHERE"), predicates = Some(mapOf("prop" -> literal(123))))
    )
  }

  test("MATCH ()-[WHERE {prop: 123} WHERE {prop: 123}]->()") {
    parseRelationshipPatterns(testName) shouldBe Seq(
      relPat(
        Some("WHERE"),
        properties = Some(mapOf("prop" -> literal(123))),
        predicates = Some(mapOf("prop" -> literal(123)))
      )
    )
  }

  test("MATCH ()-[WHERE WHERE WHERE.prop > 123]->()") {
    parseRelationshipPatterns(testName) shouldBe Seq(
      relPat(
        Some("WHERE"),
        predicates = Some(greaterThan(prop("WHERE", "prop"), literalInt(123)))
      )
    )
  }

  test("MATCH ()-[WHERE WHERE.WHERE='WHERE']->()") {
    parseRelationshipPatterns(testName) shouldBe Seq(
      relPat(predicates = Some(equals(prop("WHERE", "WHERE"), literalString("WHERE"))))
    )
  }

  test("RETURN [()-[r:R WHERE r.prop > 123]->() | r]") {
    parseRelationshipPatterns(testName) shouldBe Seq(
      relPat(
        Some("r"),
        Some(labelRelTypeLeaf("R")),
        predicates = Some(greaterThan(prop("r", "prop"), literalInt(123)))
      )
    )
  }

  test("RETURN exists(()-[r {prop: 'test'} WHERE r.otherProp = 123]->()) AS result") {
    parseRelationshipPatterns(testName) shouldBe Seq(
      relPat(
        Some("r"),
        properties = Some(mapOf("prop" -> literal("test"))),
        predicates = Some(equals(prop("r", "otherProp"), literalInt(123)))
      )
    )
  }

  private val exceptionFactory = OpenCypherExceptionFactory(None)

  private def parseRelationshipPatterns(query: String): Seq[RelationshipPattern] = {
    val ast = JavaCCParser.parse(query, exceptionFactory)
    ast.folder.findAllByClass[RelationshipPattern]
  }
}
