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

import org.neo4j.cypher.internal.ast.OrderBy
import org.neo4j.cypher.internal.ast.ShowSettingsClause
import org.neo4j.cypher.internal.ast.SingleQuery
import org.neo4j.cypher.internal.ast.Statements
import org.neo4j.cypher.internal.expressions.AllIterablePredicate
import org.neo4j.cypher.internal.util.symbols.CTAny
import org.neo4j.cypher.internal.util.symbols.IntegerType

class ShowSettingsCommandParserTest extends AdministrationAndSchemaCommandParserTestBase {

  Seq("SETTING", "SETTINGS").foreach { settingKeyword =>
    test(s"SHOW $settingKeyword") {
      assertAstNotAntlr(
        singleQuery(ShowSettingsClause(Left(List.empty[String]), None, List.empty, yieldAll = false)(defaultPos))
      )
    }

    test(s"SHOW $settingKeyword 'foo'") {
      assertAstNotAntlr(
        singleQuery(ShowSettingsClause(Right(literalString("foo")), None, List.empty, yieldAll = false)(defaultPos))
      )
    }

    test(s"SHOW $settingKeyword ''") {
      assertAstNotAntlr(
        singleQuery(ShowSettingsClause(Right(literalString("")), None, List.empty, yieldAll = false)(defaultPos))
      )
    }

    test(s"SHOW $settingKeyword $$param") {
      assertAstNotAntlr(
        singleQuery(
          ShowSettingsClause(Right(parameter("param", CTAny)), None, List.empty, yieldAll = false)(defaultPos)
        )
      )
    }

    test(s"SHOW $settingKeyword 'foo', 'bar'") {
      assertAstNotAntlr(
        singleQuery(ShowSettingsClause(Left(List("foo", "bar")), None, List.empty, yieldAll = false)(defaultPos))
      )
    }

    test(s"SHOW $settingKeyword 'foo'+'.'+$$name") {
      assertAstNotAntlr(singleQuery(ShowSettingsClause(
        Right(
          add(add(literalString("foo"), literalString(".")), parameter("name", CTAny))
        ),
        None,
        List.empty,
        yieldAll = false
      )(defaultPos)))
    }

    test(s"SHOW $settingKeyword ['foo', 'bar']") {
      assertAstNotAntlr(singleQuery(ShowSettingsClause(
        Right(listOfString("foo", "bar")),
        None,
        List.empty,
        yieldAll = false
      )(defaultPos)))
    }

    test(s"USE db SHOW $settingKeyword") {
      assertAstNotAntlr(SingleQuery(
        List(
          use(List("db")),
          ShowSettingsClause(Left(List.empty[String]), None, List.empty, yieldAll = false)((1, 8, 7))
        )
      )((1, 8, 7)))
    }

  }

  // Filtering tests

  test("SHOW SETTING WHERE name = 'db.setting.sub_setting'") {
    assertAstNotAntlr(singleQuery(ShowSettingsClause(
      Left(List.empty[String]),
      Some(where(
        equals(
          varFor("name"),
          literalString("db.setting.sub_setting")
        )
      )),
      List.empty,
      yieldAll = false
    )(defaultPos)))
  }

  test("SHOW SETTING WHERE name IN ['db.setting.sub_setting', 'db.another.setting']") {
    assertAstNotAntlr(singleQuery(ShowSettingsClause(
      Left(List.empty[String]),
      Some(where(
        in(
          varFor("name", (1, 20, 19)),
          listOfString(
            "db.setting.sub_setting",
            "db.another.setting"
          )
        )
      )),
      List.empty,
      yieldAll = false
    )(defaultPos)))
  }

  test("SHOW SETTING WHERE name = $name") {
    assertAstNotAntlr(singleQuery(ShowSettingsClause(
      Left(List.empty[String]),
      Some(where(
        eq(
          varFor("name"),
          parameter("name", CTAny)
        )
      )),
      List.empty,
      yieldAll = false
    )(defaultPos)))
  }

  test("SHOW SETTING WHERE name IN $list") {
    assertAstNotAntlr(singleQuery(ShowSettingsClause(
      Left(List.empty[String]),
      Some(where(
        in(
          varFor("name"),
          parameter("list", CTAny)
        )
      )),
      List.empty,
      yieldAll = false
    )(defaultPos)))
  }

  test("SHOW SETTING 'foo' WHERE isDynamic") {
    assertAstNotAntlr(singleQuery(ShowSettingsClause(
      Right(literalString("foo")),
      Some(where(varFor("isDynamic"))),
      List.empty,
      yieldAll = false
    )(defaultPos)))
  }

  test("SHOW SETTING 'foo', 'bar' WHERE isDynamic") {
    assertAstNotAntlr(singleQuery(ShowSettingsClause(
      Left(List("foo", "bar")),
      Some(where(varFor("isDynamic"))),
      List.empty,
      yieldAll = false
    )(defaultPos)))
  }

  test("SHOW SETTING $foo WHERE pp < 50.0") {
    assertAstNotAntlr(singleQuery(ShowSettingsClause(
      Right(parameter("foo", CTAny)),
      Some(where(lessThan(varFor("pp"), literalFloat(50.0)))),
      List.empty,
      yieldAll = false
    )(defaultPos)))
  }

  test("SHOW SETTINGS YIELD description") {
    assertAstNotAntlr(singleQuery(
      ShowSettingsClause(
        Left(List.empty[String]),
        None,
        List(commandResultItem("description")),
        yieldAll = false
      )(defaultPos),
      withFromYield(returnAllItems.withDefaultOrderOnColumns(List("description")))
    ))
  }

  test("SHOW SETTINGS YIELD * ORDER BY name SKIP 2 LIMIT 5") {
    assertAstNotAntlr(singleQuery(
      ShowSettingsClause(Left(List.empty[String]), None, List.empty, yieldAll = true)(defaultPos),
      withFromYield(
        returnAllItems((1, 23, 22)),
        Some(OrderBy(Seq(
          sortItem(varFor("name"))
        ))((1, 23, 22))),
        Some(skip(2)),
        Some(limit(5))
      )
    ))
  }

  test("SHOW SETTING YIELD name, description, value WHERE name = 'db.setting.sub_setting'") {
    assertAstNotAntlr(singleQuery(
      ShowSettingsClause(
        Left(List.empty[String]),
        None,
        List(
          commandResultItem("name"),
          commandResultItem("description"),
          commandResultItem("value")
        ),
        yieldAll = false
      )((1, 1, 0)),
      withFromYield(
        returnAllItems.withDefaultOrderOnColumns(List("name", "description", "value")),
        where = Some(where(
          eq(
            varFor("name"),
            literalString("db.setting.sub_setting")
          )
        ))
      )
    ))
  }

  test("USE db SHOW SETTINGS YIELD name, description AS pp WHERE pp < 50.0 RETURN name") {
    assertAstNotAntlr(
      singleQuery(
        use(List("db")),
        ShowSettingsClause(
          Left(List.empty[String]),
          None,
          List(commandResultItem("name"), commandResultItem("description", Some("pp"))),
          yieldAll = false
        )(pos),
        withFromYield(
          returnAllItems.withDefaultOrderOnColumns(List("name", "pp")),
          where = Some(where(lessThan(varFor("pp"), literalFloat(50.0))))
        ),
        return_(variableReturnItem("name"))
      ),
      comparePosition = false
    )
  }

  test("SHOW SETTINGS YIELD name AS SETTING, mode AS OUTPUT") {
    assertAstNotAntlr(
      singleQuery(
        ShowSettingsClause(
          Left(List.empty[String]),
          None,
          List(commandResultItem("name", Some("SETTING")), commandResultItem("mode", Some("OUTPUT"))),
          yieldAll = false
        )(pos),
        withFromYield(returnAllItems.withDefaultOrderOnColumns(List("SETTING", "OUTPUT")))
      ),
      comparePosition = false
    )
  }

  test("SHOW SETTINGS 'db.setting.sub_setting' YIELD description") {
    assertAstNotAntlr(singleQuery(
      ShowSettingsClause(
        Right(literalString("db.setting.sub_setting")),
        None,
        List(commandResultItem("description")),
        yieldAll = false
      )(defaultPos),
      withFromYield(
        returnAllItems.withDefaultOrderOnColumns(List("description"))
      )
    ))
  }

  test("SHOW SETTINGS 'db.setting.sub_setting', 'db.another.setting' YIELD description") {
    assertAstNotAntlr(singleQuery(
      ShowSettingsClause(
        Left(List("db.setting.sub_setting", "db.another.setting")),
        None,
        List(commandResultItem("description")),
        yieldAll = false
      )(
        defaultPos
      ),
      withFromYield(
        returnAllItems.withDefaultOrderOnColumns(List("description"))
      )
    ))
  }

  test("SHOW SETTINGS $list YIELD description") {
    assertAstNotAntlr(singleQuery(
      ShowSettingsClause(
        Right(parameter("list", CTAny)),
        None,
        List(commandResultItem("description")),
        yieldAll = false
      )(defaultPos),
      withFromYield(
        returnAllItems.withDefaultOrderOnColumns(List("description"))
      )
    ))
  }

  test("SHOW SETTINGS $list YIELD name, description, isExplicitlySet WHERE isExplicitlySet") {
    assertAstNotAntlr(singleQuery(
      ShowSettingsClause(
        Right(parameter("list", CTAny)),
        None,
        List(
          commandResultItem("name"),
          commandResultItem("description"),
          commandResultItem("isExplicitlySet")
        ),
        yieldAll = false
      )(defaultPos),
      withFromYield(
        returnAllItems.withDefaultOrderOnColumns(List("name", "description", "isExplicitlySet")),
        where = Some(where(varFor("isExplicitlySet")))
      )
    ))
  }

  test("SHOW SETTINGS YIELD (123 + xyz)") {
    assertAstNotAntlr(singleQuery(
      ShowSettingsClause(
        Right(function("YIELD", add(literalInt(123), varFor("xyz")))),
        None,
        List.empty,
        yieldAll = false
      )(pos)
    ))
  }

  test("SHOW SETTINGS YIELD") {
    assertAstNotAntlr(singleQuery(
      ShowSettingsClause(Right(varFor("YIELD")), None, List.empty, yieldAll = false)(pos)
    ))
  }

  test("SHOW SETTINGS YIELD a ORDER BY a WHERE a = 1") {
    assertAstNotAntlr(singleQuery(
      ShowSettingsClause(
        Left(List.empty),
        None,
        List(commandResultItem("a")),
        yieldAll = false
      )(pos),
      withFromYield(
        returnAllItems.withDefaultOrderOnColumns(List("a")),
        Some(orderBy(sortItem(varFor("a")))),
        where = Some(where(equals(varFor("a"), literalInt(1))))
      )
    ))
  }

  test("SHOW SETTINGS YIELD a AS b ORDER BY b WHERE b = 1") {
    assertAstNotAntlr(singleQuery(
      ShowSettingsClause(
        Left(List.empty),
        None,
        List(commandResultItem("a", Some("b"))),
        yieldAll = false
      )(pos),
      withFromYield(
        returnAllItems.withDefaultOrderOnColumns(List("b")),
        Some(orderBy(sortItem(varFor("b")))),
        where = Some(where(equals(varFor("b"), literalInt(1))))
      )
    ))
  }

  test("SHOW SETTINGS YIELD a AS b ORDER BY a WHERE a = 1") {
    assertAstNotAntlr(singleQuery(
      ShowSettingsClause(
        Left(List.empty),
        None,
        List(commandResultItem("a", Some("b"))),
        yieldAll = false
      )(pos),
      withFromYield(
        returnAllItems.withDefaultOrderOnColumns(List("b")),
        Some(orderBy(sortItem(varFor("b")))),
        where = Some(where(equals(varFor("b"), literalInt(1))))
      )
    ))
  }

  test("SHOW SETTINGS YIELD a ORDER BY EXISTS { (a) } WHERE EXISTS { (a) }") {
    assertAstNotAntlr(singleQuery(
      ShowSettingsClause(
        Left(List.empty),
        None,
        List(commandResultItem("a")),
        yieldAll = false
      )(pos),
      withFromYield(
        returnAllItems.withDefaultOrderOnColumns(List("a")),
        Some(orderBy(sortItem(simpleExistsExpression(patternForMatch(nodePat(Some("a"))), None)))),
        where = Some(where(simpleExistsExpression(patternForMatch(nodePat(Some("a"))), None)))
      )
    ))
  }

  test("SHOW SETTINGS YIELD a ORDER BY EXISTS { (b) } WHERE EXISTS { (b) }") {
    assertAstNotAntlr(singleQuery(
      ShowSettingsClause(
        Left(List.empty),
        None,
        List(commandResultItem("a")),
        yieldAll = false
      )(pos),
      withFromYield(
        returnAllItems.withDefaultOrderOnColumns(List("a")),
        Some(orderBy(sortItem(simpleExistsExpression(patternForMatch(nodePat(Some("b"))), None)))),
        where = Some(where(simpleExistsExpression(patternForMatch(nodePat(Some("b"))), None)))
      )
    ))
  }

  test("SHOW SETTINGS YIELD a AS b ORDER BY COUNT { (b) } WHERE EXISTS { (b) }") {
    assertAstNotAntlr(singleQuery(
      ShowSettingsClause(
        Left(List.empty),
        None,
        List(commandResultItem("a", Some("b"))),
        yieldAll = false
      )(pos),
      withFromYield(
        returnAllItems.withDefaultOrderOnColumns(List("b")),
        Some(orderBy(sortItem(simpleCountExpression(patternForMatch(nodePat(Some("b"))), None)))),
        where = Some(where(simpleExistsExpression(patternForMatch(nodePat(Some("b"))), None)))
      )
    ))
  }

  test("SHOW SETTINGS YIELD a AS b ORDER BY EXISTS { (a) } WHERE COLLECT { MATCH (a) RETURN a } <> []") {
    assertAstNotAntlr(singleQuery(
      ShowSettingsClause(
        Left(List.empty),
        None,
        List(commandResultItem("a", Some("b"))),
        yieldAll = false
      )(pos),
      withFromYield(
        returnAllItems.withDefaultOrderOnColumns(List("b")),
        Some(orderBy(sortItem(simpleExistsExpression(patternForMatch(nodePat(Some("b"))), None)))),
        where = Some(where(notEquals(
          simpleCollectExpression(patternForMatch(nodePat(Some("b"))), None, return_(returnItem(varFor("b"), "a"))),
          listOf()
        )))
      )
    ))
  }

  test("SHOW SETTINGS YIELD a AS b ORDER BY b + COUNT { () } WHERE b OR EXISTS { () }") {
    assertAstNotAntlr(singleQuery(
      ShowSettingsClause(
        Left(List.empty),
        None,
        List(commandResultItem("a", Some("b"))),
        yieldAll = false
      )(pos),
      withFromYield(
        returnAllItems.withDefaultOrderOnColumns(List("b")),
        Some(orderBy(sortItem(add(varFor("b"), simpleCountExpression(patternForMatch(nodePat()), None))))),
        where = Some(where(or(varFor("b"), simpleExistsExpression(patternForMatch(nodePat()), None))))
      )
    ))
  }

  test("SHOW SETTINGS YIELD a AS b ORDER BY a + EXISTS { () } WHERE a OR ALL (x IN [1, 2] WHERE x IS :: INT)") {
    assertAstNotAntlr(singleQuery(
      ShowSettingsClause(
        Left(List.empty),
        None,
        List(commandResultItem("a", Some("b"))),
        yieldAll = false
      )(pos),
      withFromYield(
        returnAllItems.withDefaultOrderOnColumns(List("b")),
        Some(orderBy(sortItem(add(varFor("b"), simpleExistsExpression(patternForMatch(nodePat()), None))))),
        where = Some(where(or(
          varFor("b"),
          AllIterablePredicate(
            varFor("x"),
            listOfInt(1, 2),
            Some(isTyped(varFor("x"), IntegerType(isNullable = true)(pos)))
          )(pos)
        )))
      )
    ))
  }

  test("SHOW SETTINGS YIELD name as value, value as name where size(value) > 0 RETURN value as name") {
    assertAstNotAntlr(singleQuery(
      ShowSettingsClause(
        Left(List.empty),
        None,
        List(
          commandResultItem("name", Some("value")),
          commandResultItem("value", Some("name"))
        ),
        yieldAll = false
      )(pos),
      withFromYield(
        returnAllItems.withDefaultOrderOnColumns(List("value", "name")),
        where = Some(where(
          greaterThan(size(varFor("value")), literalInt(0))
        ))
      ),
      return_(aliasedReturnItem("value", "name"))
    ))
  }

  // Negative tests

  test("SHOW ALL SETTINGS") {
    assertFailsWithMessageStart[Statements](
      testName,
      """Invalid input 'SETTINGS': expected
        |  "CONSTRAINT"
        |  "CONSTRAINTS"
        |  "FUNCTION"
        |  "FUNCTIONS"
        |  "INDEX"
        |  "INDEXES"
        |  "PRIVILEGE"
        |  "PRIVILEGES"
        |  "ROLE"
        |  "ROLES"""".stripMargin
    )
  }

  test("SHOW SETTING $foo, $bar") {
    assertFailsWithMessageStart[Statements](
      testName,
      """Invalid input ',': expected
        |  "!="
        |  "%"""".stripMargin
    )
  }

  test("SHOW SETTING $foo $bar") {
    assertFailsWithMessageStart[Statements](
      testName,
      """Invalid input '$': expected
        |  "!="
        |  "%"""".stripMargin
    )
  }

  test("SHOW SETTING 'bar', $foo") {
    assertFailsWithMessageStart[Statements](testName, """Invalid input '$': expected "\"" or "\'" """)
  }

  test("SHOW SETTING $foo, 'bar'") {
    assertFailsWithMessageStart[Statements](
      testName,
      """Invalid input ',': expected
        |  "!="
        |  "%"""".stripMargin
    )
  }

  test("SHOW SETTING 'foo' 'bar'") {
    assertFailsWithMessageStart[Statements](
      testName,
      """Invalid input 'bar': expected
        |  "!="
        |  "%"""".stripMargin
    )
  }

  test("SHOW SETTINGS YIELD (123 + xyz) AS foo") {
    failsToParse[Statements]
  }

  test("SHOW SETTINGS YIELD * YIELD *") {
    failsToParse[Statements]
  }

  test("SHOW SETTINGS WHERE name = 'db.setting' YIELD *") {
    failsToParse[Statements]
  }

  test("SHOW SETTINGS WHERE name = 'db.setting' RETURN *") {
    failsToParse[Statements]
  }

  test("SHOW SETTINGS YIELD a b RETURN *") {
    failsToParse[Statements]
  }

  test("SHOW SETTINGS RETURN *") {
    failsToParse[Statements]
  }
}
