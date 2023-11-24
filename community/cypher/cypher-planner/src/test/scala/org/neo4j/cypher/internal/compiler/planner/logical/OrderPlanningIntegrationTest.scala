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
package org.neo4j.cypher.internal.compiler.planner.logical

import org.neo4j.cypher.internal.compiler.ExecutionModel.BatchedParallel
import org.neo4j.cypher.internal.compiler.helpers.LogicalPlanBuilder
import org.neo4j.cypher.internal.compiler.planner.BeLikeMatcher.beLike
import org.neo4j.cypher.internal.compiler.planner.LogicalPlanningIntegrationTestSupport
import org.neo4j.cypher.internal.compiler.planner.LogicalPlanningTestSupport2
import org.neo4j.cypher.internal.compiler.planner.LogicalPlanningTestSupport2.QueryGraphSolverSetup
import org.neo4j.cypher.internal.compiler.planner.LogicalPlanningTestSupport2.QueryGraphSolverWithGreedyConnectComponents
import org.neo4j.cypher.internal.compiler.planner.LogicalPlanningTestSupport2.QueryGraphSolverWithIDPConnectComponents
import org.neo4j.cypher.internal.compiler.planner.StatisticsBackedLogicalPlanningConfiguration
import org.neo4j.cypher.internal.compiler.planner.StatisticsBackedLogicalPlanningConfigurationBuilder
import org.neo4j.cypher.internal.expressions.DesugaredMapProjection
import org.neo4j.cypher.internal.expressions.Expression
import org.neo4j.cypher.internal.expressions.LiteralEntry
import org.neo4j.cypher.internal.expressions.LogicalProperty
import org.neo4j.cypher.internal.expressions.LogicalVariable
import org.neo4j.cypher.internal.expressions.SemanticDirection
import org.neo4j.cypher.internal.expressions.Variable
import org.neo4j.cypher.internal.frontend.phases.ProcedureReadOnlyAccess
import org.neo4j.cypher.internal.frontend.phases.ProcedureSignature
import org.neo4j.cypher.internal.frontend.phases.QualifiedName
import org.neo4j.cypher.internal.ir.NoHeaders
import org.neo4j.cypher.internal.ir.RegularSinglePlannerQuery
import org.neo4j.cypher.internal.logical.builder.AbstractLogicalPlanBuilder.createNode
import org.neo4j.cypher.internal.logical.builder.AbstractLogicalPlanBuilder.setNodeProperty
import org.neo4j.cypher.internal.logical.plans.Aggregation
import org.neo4j.cypher.internal.logical.plans.Ascending
import org.neo4j.cypher.internal.logical.plans.Distinct
import org.neo4j.cypher.internal.logical.plans.Expand
import org.neo4j.cypher.internal.logical.plans.GetValue
import org.neo4j.cypher.internal.logical.plans.IndexOrderAscending
import org.neo4j.cypher.internal.logical.plans.IndexOrderNone
import org.neo4j.cypher.internal.logical.plans.LogicalPlan
import org.neo4j.cypher.internal.logical.plans.NodeByLabelScan
import org.neo4j.cypher.internal.logical.plans.OrderedAggregation
import org.neo4j.cypher.internal.logical.plans.OrderedDistinct
import org.neo4j.cypher.internal.logical.plans.PartialSort
import org.neo4j.cypher.internal.logical.plans.Projection
import org.neo4j.cypher.internal.logical.plans.Selection
import org.neo4j.cypher.internal.logical.plans.Skip
import org.neo4j.cypher.internal.logical.plans.Sort
import org.neo4j.cypher.internal.logical.plans.Top
import org.neo4j.cypher.internal.planner.spi.IndexOrderCapability
import org.neo4j.cypher.internal.util.test_helpers.CypherFunSuite
import org.neo4j.graphdb.schema.IndexType
import org.scalatest.Assertion

class OrderIDPPlanningIntegrationTest extends OrderPlanningIntegrationTest(QueryGraphSolverWithIDPConnectComponents)

class OrderGreedyPlanningIntegrationTest
    extends OrderPlanningIntegrationTest(QueryGraphSolverWithGreedyConnectComponents)

abstract class OrderPlanningIntegrationTest(queryGraphSolverSetup: QueryGraphSolverSetup)
    extends CypherFunSuite
    with LogicalPlanningTestSupport2
    with LogicalPlanningIntegrationTestSupport {

  locally {
    queryGraphSolver = queryGraphSolverSetup.queryGraphSolver()
  }

  override def plannerBuilder(): StatisticsBackedLogicalPlanningConfigurationBuilder =
    super.plannerBuilder()
      .enableConnectComponentsPlanner(queryGraphSolverSetup.useIdpConnectComponents)

  private def shouldNotSort(plan: LogicalPlan): Assertion = {
    withClue(plan) {
      plan.folder.treeCount {
        case _: Sort        => ()
        case _: PartialSort => ()
      } shouldBe 0
    }
  }

  test("ORDER BY previously unprojected column in WITH") {
    val plan = new givenConfig().getLogicalPlanFor("MATCH (a:A) WITH a ORDER BY a.age RETURN a.name")._1

    val labelScan = NodeByLabelScan(varFor("a"), labelName("A"), Set.empty, IndexOrderNone)
    val ageProperty = prop("a", "age")
    val nameProperty = prop("a", "name")

    val projection = Projection(labelScan, Map(varFor("a.age") -> ageProperty))
    val sort = Sort(projection, Seq(Ascending(varFor("a.age"))))
    val resultProjection = Projection(sort, Map(varFor("a.name") -> nameProperty))

    plan should equal(resultProjection)
  }

  test("ORDER BY previously unprojected column in WITH and return that column") {
    val plan = new givenConfig().getLogicalPlanFor("MATCH (a:A) WITH a ORDER BY a.age RETURN a.name, a.age")._1
    val labelScan = NodeByLabelScan(varFor("a"), labelName("A"), Set.empty, IndexOrderNone)
    val nameProperty = prop("a", "name")

    val projection = Projection(labelScan, Map(varFor("a.age") -> cachedNodePropFromStore("a", "age")))
    val sort = Sort(projection, Seq(Ascending(varFor("a.age"))))
    val resultProjection =
      Projection(
        sort,
        Map(varFor("a.name") -> nameProperty, varFor("a.age") -> cachedNodeProp("a", "age"))
      )

    plan should equal(resultProjection)
  }

  test("ORDER BY previously unprojected column in WITH and project and return that column") {
    val plan =
      new givenConfig().getLogicalPlanFor("MATCH (a:A) WITH a, a.age AS age ORDER BY age RETURN a.name, age")._1

    val labelScan = NodeByLabelScan(varFor("a"), labelName("A"), Set.empty, IndexOrderNone)
    val ageProperty = prop("a", "age")
    val nameProperty = prop("a", "name")

    val projection = Projection(labelScan, Map(varFor("age") -> ageProperty))
    val sort = Sort(projection, Seq(Ascending(varFor("age"))))
    val resultProjection = Projection(sort, Map(varFor("a.name") -> nameProperty))

    plan should equal(resultProjection)
  }

  test("ORDER BY renamed column old name in WITH and project and return that column") {
    val plan = new givenConfig().getLogicalPlanFor(
      "MATCH (a:A) WITH a AS b, a.age AS age ORDER BY a RETURN b.name, age",
      stripProduceResults = false
    )._1

    plan should equal(
      new LogicalPlanBuilder()
        .produceResults("`b.name`", "age")
        .projection("b.name AS `b.name`")
        .projection("a AS b", "a.age AS age")
        .nodeByLabelScan("a", "A", IndexOrderAscending)
        .build()
    )
  }

  test("ORDER BY renamed column new name in WITH and project and return that column") {
    val plan = new givenConfig().getLogicalPlanFor(
      "MATCH (a:A) WITH a AS b, a.age AS age ORDER BY b RETURN b.name, age",
      stripProduceResults = false
    )._1

    plan should equal(
      new LogicalPlanBuilder()
        .produceResults("`b.name`", "age")
        .projection("b.name AS `b.name`")
        .projection("a AS b", "a.age AS age")
        .nodeByLabelScan("a", "A", IndexOrderAscending)
        .build()
    )
  }

  test("ORDER BY renamed column expression with old name in WITH and project and return that column") {
    val plan = new givenConfig().getLogicalPlanFor(
      "MATCH (a:A) WITH a AS b, a.age AS age ORDER BY a.foo, a.age + 5 RETURN b.name, age"
    )._1

    plan should equal(new LogicalPlanBuilder(wholePlan = false)
      .projection("b.name AS `b.name`")
      .sort("`b.foo` ASC", "`age + 5` ASC")
      .projection("b.foo AS `b.foo`", "age + 5 AS `age + 5`")
      .projection("a AS b", "a.age AS age")
      .nodeByLabelScan("a", "A")
      .build())
  }

  test("ORDER BY renamed column expression with new name in WITH and project and return that column") {
    val plan = new givenConfig()
      .getLogicalPlanFor(
        "MATCH (a:A) WITH a AS b, a.age AS age ORDER BY b.foo, b.age + 5 RETURN b.name, age",
        stripProduceResults = false
      )._1
    val cachedProperties: Set[LogicalProperty] = Set(
      cachedNodeProp("a", "foo", "b", knownToAccessStore = true),
      cachedNodeProp("a", "age", "b", knownToAccessStore = true)
    )

    plan should equal(new LogicalPlanBuilder()
      .produceResults("`b.name`", "age")
      .projection("b.name AS `b.name`")
      .projection("cacheN[a.age] AS age")
      .sort("`b.foo` ASC", "`b.age + 5` ASC")
      .projection(Map(
        "b.foo" -> cachedNodeProp("a", "foo", "b"),
        "b.age + 5" -> add(cachedNodeProp("a", "age", "b"), literalInt(5))
      ))
      .cacheProperties(cachedProperties)
      .projection("a AS b")
      .nodeByLabelScan("a", "A")
      .build())
  }

  test("ORDER BY previously unprojected column in RETURN") {
    val plan = new givenConfig().getLogicalPlanFor("MATCH (a:A) RETURN a.name ORDER BY a.age")._1

    val labelScan = NodeByLabelScan(varFor("a"), labelName("A"), Set.empty, IndexOrderNone)
    val ageProperty = prop("a", "age")
    val nameProperty = prop("a", "name")

    val projection = Projection(labelScan, Map(varFor("a.age") -> ageProperty))
    val sort = Sort(projection, Seq(Ascending(varFor("a.age"))))
    val result = Projection(sort, Map(varFor("a.name") -> nameProperty))

    plan should equal(result)
  }

  test("ORDER BY previously unprojected column in RETURN and return that column") {
    val plan = new givenConfig().getLogicalPlanFor("""MATCH (a:A) RETURN a.name, a.age ORDER BY a.age""")._1

    val labelScan = NodeByLabelScan(varFor("a"), labelName("A"), Set.empty, IndexOrderNone)
    val ageProperty = prop("a", "age")
    val nameProperty = prop("a", "name")

    val projection = Projection(labelScan, Map(varFor("a.age") -> ageProperty))
    val sort = Sort(projection, Seq(Ascending(varFor("a.age"))))
    val result = Projection(sort, Map(varFor("a.name") -> nameProperty))

    plan should equal(result)
  }

  test("ORDER BY previously unprojected column in RETURN and project and return that column") {
    val plan = new givenConfig().getLogicalPlanFor("MATCH (a:A) RETURN a.name, a.age AS age ORDER BY age")._1

    val labelScan = NodeByLabelScan(varFor("a"), labelName("A"), Set.empty, IndexOrderNone)
    val ageProperty = prop("a", "age")
    val nameProperty = prop("a", "name")

    val projection = Projection(labelScan, Map(varFor("age") -> ageProperty))
    val sort = Sort(projection, Seq(Ascending(varFor("age"))))
    val result = Projection(sort, Map(varFor("a.name") -> nameProperty))

    plan should equal(result)
  }

  test("ORDER BY previously unprojected column in RETURN *") {
    val plan = new givenConfig().getLogicalPlanFor("MATCH (a:A) RETURN * ORDER BY a.age")._1

    val labelScan = NodeByLabelScan(varFor("a"), labelName("A"), Set.empty, IndexOrderNone)
    val ageProperty = prop("a", "age")

    val projection = Projection(labelScan, Map(varFor("a.age") -> ageProperty))
    val sort = Sort(projection, Seq(Ascending(varFor("a.age"))))

    plan should equal(sort)
  }

  test("ORDER BY previously unprojected column in RETURN * and return that column") {
    val plan = new givenConfig().getLogicalPlanFor("MATCH (a:A) RETURN *, a.age ORDER BY a.age")._1

    val labelScan = NodeByLabelScan(varFor("a"), labelName("A"), Set.empty, IndexOrderNone)
    val ageProperty = prop("a", "age")

    val projection = Projection(labelScan, Map(varFor("a.age") -> ageProperty))
    val sort = Sort(projection, Seq(Ascending(varFor("a.age"))))

    plan should equal(sort)
  }

  test("ORDER BY previously unprojected column in RETURN * and project and return that column") {
    val plan = new givenConfig().getLogicalPlanFor("MATCH (a:A) RETURN *, a.age AS age ORDER BY age")._1

    val labelScan = NodeByLabelScan(varFor("a"), labelName("A"), Set.empty, IndexOrderNone)
    val ageProperty = prop("a", "age")

    val projection = Projection(labelScan, Map(varFor("age") -> ageProperty))
    val sort = Sort(projection, Seq(Ascending(varFor("age"))))

    plan should equal(sort)
  }

  test("ORDER BY previously unprojected column with expression in WITH") {
    val query = "MATCH (a:A) WITH a ORDER BY a.age + 4 RETURN a.name"

    val planner = plannerBuilder()
      .setAllNodesCardinality(100)
      .setLabelCardinality("A", 10)
      .build()

    planner.plan(query).stripProduceResults should equal(
      planner.subPlanBuilder()
        .projection("a.name AS `a.name`")
        .sort("`a.age + 4` ASC")
        .projection("a.age + 4 AS `a.age + 4`")
        .nodeByLabelScan("a", "A")
        .build()
    )
  }

  test("ORDER BY previously unprojected DISTINCT column in WITH and project and return it") {
    val plan = new givenConfig().getLogicalPlanFor("MATCH (a:A) WITH DISTINCT a.age AS age ORDER BY age RETURN age")._1

    val labelScan = NodeByLabelScan(varFor("a"), labelName("A"), Set.empty, IndexOrderNone)
    val ageProperty = prop("a", "age")

    val distinct = Distinct(labelScan, Map(varFor("age") -> ageProperty))
    val sort = Sort(distinct, Seq(Ascending(varFor("age"))))

    plan should equal(sort)
  }

  test("ORDER BY column that isn't referenced in WITH DISTINCT") {
    val query = "MATCH (a:A) WITH DISTINCT a.name AS name, a ORDER BY a.age RETURN name"

    val planner = plannerBuilder()
      .setAllNodesCardinality(100)
      .setLabelCardinality("A", 10)
      .build()

    planner.plan(query).stripProduceResults should equal(
      planner.subPlanBuilder()
        .sort("`a.age` ASC")
        .projection("a.age AS `a.age`")
        .projection("a.name AS name")
        .nodeByLabelScan("a", "A", IndexOrderAscending)
        .build()
    )
  }

  test("ORDER BY previously unprojected AGGREGATING column in WITH and project and return it") {
    val plan = new givenConfig()
      .getLogicalPlanFor(
        "MATCH (a:A) WITH a.name AS name, sum(a.age) AS age ORDER BY age RETURN name, age",
        stripProduceResults = false
      )._1

    plan should equal(new LogicalPlanBuilder()
      .produceResults("name", "age")
      .sort("age ASC")
      .aggregation(Seq("cacheN[a.name] AS name"), Seq("sum(cacheN[a.age]) AS age"))
      .cacheProperties("cacheNFromStore[a.age]", "cacheNFromStore[a.name]")
      .nodeByLabelScan("a", "A", IndexOrderNone)
      .build())
  }

  test("ORDER BY previously unprojected GROUPING column in WITH and project and return it") {
    val plan = new givenConfig()
      .getLogicalPlanFor(
        "MATCH (a:A) WITH a.name AS name, sum(a.age) AS age ORDER BY name RETURN name, age",
        stripProduceResults = false
      )._1

    plan should equal(new LogicalPlanBuilder()
      .produceResults("name", "age")
      .sort("name ASC")
      .aggregation(Seq("cacheN[a.name] AS name"), Seq("sum(cacheN[a.age]) AS age"))
      .cacheProperties("cacheNFromStore[a.age]", "cacheNFromStore[a.name]")
      .nodeByLabelScan("a", "A", IndexOrderNone)
      .build())
  }

  test("ORDER BY column that isn't referenced in WITH GROUP BY") {
    val plan = new givenConfig()
      .getLogicalPlanFor(
        "MATCH (a:A) WITH a.name AS name, a, sum(a.age) AS age ORDER BY a.foo RETURN name, age",
        stripProduceResults = false
      )._1

    plan should equal(new LogicalPlanBuilder()
      .produceResults("name", "age")
      .sort("`a.foo` ASC")
      .projection("a.foo AS `a.foo`")
      .orderedAggregation(Seq("cacheN[a.name] AS name", "a AS a"), Seq("sum(cacheN[a.age]) AS age"), Seq("a"))
      .cacheProperties("cacheNFromStore[a.age]", "cacheNFromStore[a.name]")
      .nodeByLabelScan("a", "A", IndexOrderAscending)
      .build())
  }

  test("should use ordered aggregation if there is one grouping column, ordered") {
    val plan = new givenConfig().getLogicalPlanFor("MATCH (a:A) WITH a ORDER BY a.foo RETURN a.foo, count(a.foo)")._1

    val expectedPlan = new LogicalPlanBuilder(wholePlan = false)
      .orderedAggregation(Seq("`a.foo` AS `a.foo`"), Seq("count(cache[a.foo]) AS `count(a.foo)`"), Seq("`a.foo`"))
      .sort("`a.foo` ASC")
      .projection("cacheFromStore[a.foo] AS `a.foo`")
      .nodeByLabelScan("a", "A")
      .build()

    plan should equal(expectedPlan)
  }

  test(
    "should not use ordered aggregation if the execution model does not preserve order (one grouping column, ordered)"
  ) {
    val plan = new givenConfig { executionModel = BatchedParallel(1, 2) }
      .getLogicalPlanFor("MATCH (a:A) WITH a ORDER BY a.foo RETURN a.foo, count(a.foo)")._1

    val expectedPlan = new LogicalPlanBuilder(wholePlan = false)
      .aggregation(Seq("`a.foo` AS `a.foo`"), Seq("count(cache[a.foo]) AS `count(a.foo)`"))
      .sort("`a.foo` ASC")
      .projection("cacheFromStore[a.foo] AS `a.foo`")
      .nodeByLabelScan("a", "A")
      .build()

    plan should equal(expectedPlan)
  }

  test("should use ordered aggregation if there is one aliased grouping column, ordered") {
    val plan =
      new givenConfig().getLogicalPlanFor("MATCH (a:A) WITH a ORDER BY a.foo RETURN a.foo AS x, count(a.foo)")._1
    val expectedPlan = new LogicalPlanBuilder(wholePlan = false)
      .orderedAggregation(Seq("cache[a.foo] AS x"), Seq("count(cache[a.foo]) AS `count(a.foo)`"), Seq("cache[a.foo]"))
      .sort("`a.foo` ASC")
      .projection("cacheFromStore[a.foo] AS `a.foo`")
      .nodeByLabelScan("a", "A")
      .build()

    plan should equal(expectedPlan)
  }

  test("should use ordered aggregation if there are two identical grouping columns (first aliased), ordered") {
    val plan =
      new givenConfig().getLogicalPlanFor("MATCH (a:A) WITH a ORDER BY a.foo RETURN a.foo AS x, a.foo, count(a.foo)")._1
    val expectedPlan = new LogicalPlanBuilder(wholePlan = false)
      .orderedAggregation(
        Seq("`a.foo` AS x", "`a.foo` AS `a.foo`"),
        Seq("count(cache[a.foo]) AS `count(a.foo)`"),
        Seq("`a.foo`")
      )
      .sort("`a.foo` ASC")
      .projection("cacheFromStore[a.foo] AS `a.foo`")
      .nodeByLabelScan("a", "A")
      .build()

    plan should equal(expectedPlan)
  }

  test("should use ordered aggregation if there are two identical grouping columns (second aliased), ordered") {
    val plan =
      new givenConfig().getLogicalPlanFor("MATCH (a:A) WITH a ORDER BY a.foo RETURN a.foo, a.foo AS y, count(a.foo)")._1
    val expectedPlan = new LogicalPlanBuilder(wholePlan = false)
      .orderedAggregation(
        Seq("`a.foo` AS `a.foo`", "`a.foo` AS y"),
        Seq("count(cache[a.foo]) AS `count(a.foo)`"),
        Seq("`a.foo`")
      )
      .sort("`a.foo` ASC")
      .projection("cacheFromStore[a.foo] AS `a.foo`")
      .nodeByLabelScan("a", "A")
      .build()

    plan should equal(expectedPlan)
  }

  test("should use ordered aggregation if there are two identical grouping columns (both aliased), ordered") {
    val plan =
      new givenConfig().getLogicalPlanFor(
        "MATCH (a:A) WITH a ORDER BY a.foo RETURN a.foo AS x, a.foo AS y, count(a.foo)"
      )._1
    val expectedPlan = new LogicalPlanBuilder(wholePlan = false)
      .orderedAggregation(
        Seq("cache[a.foo] AS x", "cache[a.foo] AS y"),
        Seq("count(cache[a.foo]) AS `count(a.foo)`"),
        Seq("cache[a.foo]")
      )
      .sort("`a.foo` ASC")
      .projection("cacheFromStore[a.foo] AS `a.foo`")
      .nodeByLabelScan("a", "A")
      .build()

    plan should equal(expectedPlan)
  }

  test("should use ordered aggregation if there are two grouping columns, one ordered") {
    val plan =
      new givenConfig().getLogicalPlanFor("MATCH (a:A) WITH a ORDER BY a.foo RETURN a.foo, a.bar, count(a.foo)")._1
    val expectedPlan = new LogicalPlanBuilder(wholePlan = false)
      .orderedAggregation(
        Seq("`a.foo` AS `a.foo`", "a.bar AS `a.bar`"),
        Seq("count(cache[a.foo]) AS `count(a.foo)`"),
        Seq("`a.foo`")
      )
      .sort("`a.foo` ASC")
      .projection("cacheFromStore[a.foo] AS `a.foo`")
      .nodeByLabelScan("a", "A", IndexOrderNone)
      .build()

    plan should equal(expectedPlan)
  }

  test("should use ordered aggregation if there are two grouping columns, both ordered") {
    val plan =
      new givenConfig().getLogicalPlanFor(
        "MATCH (a:A) WITH a ORDER BY a.foo, a.bar RETURN a.foo, a.bar, count(a.foo)"
      )._1
    val expectedPlan = new LogicalPlanBuilder(wholePlan = false)
      .orderedAggregation(
        Seq("`a.foo` AS `a.foo`", "`a.bar` AS `a.bar`"),
        Seq("count(cache[a.foo]) AS `count(a.foo)`"),
        Seq("`a.foo`", "`a.bar`")
      )
      .sort("`a.foo` ASC", "`a.bar` ASC")
      .projection("cache[a.foo] AS `a.foo`", "cache[a.bar] AS `a.bar`")
      .cacheProperties("cacheFromStore[a.foo]", "cacheFromStore[a.bar]")
      .nodeByLabelScan("a", "A", IndexOrderNone)
      .build()

    plan should equal(expectedPlan)
  }

  test("should use ordered distinct if there is one grouping column, ordered") {
    val plan = new givenConfig().getLogicalPlanFor("MATCH (a:A) WITH a ORDER BY a.foo RETURN DISTINCT a.foo")._1
    val expectedPlan = new LogicalPlanBuilder(wholePlan = false)
      .orderedDistinct(Seq("`a.foo`"), "`a.foo` AS `a.foo`")
      .sort("`a.foo` ASC")
      .projection("a.foo AS `a.foo`")
      .nodeByLabelScan("a", "A")
      .build()

    plan should equal(expectedPlan)
  }

  test(
    "should not use ordered distinct if the execution model does not preserve order (one grouping column, ordered)"
  ) {
    val plan = new givenConfig { executionModel = BatchedParallel(1, 2) }.getLogicalPlanFor(
      "MATCH (a:A) WITH a ORDER BY a.foo RETURN DISTINCT a.foo"
    )._1
    val expectedPlan = new LogicalPlanBuilder(wholePlan = false)
      .distinct("`a.foo` AS `a.foo`")
      .sort("`a.foo` ASC")
      .projection("a.foo AS `a.foo`")
      .nodeByLabelScan("a", "A")
      .build()

    plan should equal(expectedPlan)
  }

  test("should use ordered distinct if there is one aliased grouping column, ordered") {
    val plan = new givenConfig().getLogicalPlanFor("MATCH (a:A) WITH a ORDER BY a.foo RETURN DISTINCT a.foo AS x")._1
    val expectedPlan = new LogicalPlanBuilder(wholePlan = false)
      .orderedDistinct(Seq("cache[a.foo]"), "cache[a.foo] AS x")
      .sort("`a.foo` ASC")
      .projection("cacheFromStore[a.foo] AS `a.foo`")
      .nodeByLabelScan("a", "A")
      .build()

    plan should equal(expectedPlan)
  }

  test("should use ordered distinct if there is one aliased grouping column, index-backed ordered") {
    val plan = new givenConfig {
      indexOn("A", "foo")
        .providesOrder(IndexOrderCapability.BOTH)
        .providesValues()
    }.getLogicalPlanFor("MATCH (a:A) WHERE a.foo IS NOT NULL WITH a ORDER BY a.foo RETURN DISTINCT a.foo AS x")._1
    val expectedPlan = new LogicalPlanBuilder(wholePlan = false)
      .orderedDistinct(Seq("cache[a.foo]"), "cache[a.foo] AS x")
      .nodeIndexOperator(
        "a:A(foo)",
        indexOrder = IndexOrderAscending,
        getValue = _ => GetValue,
        indexType = IndexType.RANGE
      )
      .build()

    plan should equal(expectedPlan)
  }

  test(
    "should not use ordered distinct if the execution model does not preserve order (one aliased grouping column, index-backed ordered)"
  ) {
    val plan = new givenConfig {
      indexOn("A", "foo")
        .providesOrder(IndexOrderCapability.BOTH)
        .providesValues()
      executionModel = BatchedParallel(1, 2)
    }.getLogicalPlanFor("MATCH (a:A) WHERE a.foo IS NOT NULL WITH a ORDER BY a.foo RETURN DISTINCT a.foo AS x")._1
    val expectedPlan = new LogicalPlanBuilder(wholePlan = false)
      .distinct("cache[a.foo] AS x")
      .sort("`a.foo` ASC")
      .projection("cacheN[a.foo] AS `a.foo`")
      .nodeIndexOperator(
        "a:A(foo)",
        indexOrder = IndexOrderAscending,
        getValue = _ => GetValue,
        indexType = IndexType.RANGE
      )
      .build()

    plan should equal(expectedPlan)
  }

  test("should use ordered distinct if there are two identical grouping columns (first aliased), ordered") {
    val plan =
      new givenConfig().getLogicalPlanFor("MATCH (a:A) WITH a ORDER BY a.foo RETURN DISTINCT a.foo AS x, a.foo")._1
    val expectedPlan = new LogicalPlanBuilder(wholePlan = false)
      .orderedDistinct(Seq("`a.foo`"), "`a.foo` AS x", "`a.foo` AS `a.foo`")
      .sort("`a.foo` ASC")
      .projection("a.foo AS `a.foo`")
      .nodeByLabelScan("a", "A")
      .build()

    plan should equal(expectedPlan)
  }

  test("should use ordered distinct if there are two identical grouping columns (second aliased), ordered") {
    val plan =
      new givenConfig().getLogicalPlanFor("MATCH (a:A) WITH a ORDER BY a.foo RETURN DISTINCT a.foo, a.foo AS y")._1
    val expectedPlan = new LogicalPlanBuilder(wholePlan = false)
      .orderedDistinct(Seq("`a.foo`"), "`a.foo` AS `a.foo`", "`a.foo` AS y")
      .sort("`a.foo` ASC")
      .projection("a.foo AS `a.foo`")
      .nodeByLabelScan("a", "A")
      .build()

    plan should equal(expectedPlan)
  }

  test("should use ordered distinct if there are two identical grouping columns (both aliased), ordered") {
    val plan =
      new givenConfig().getLogicalPlanFor("MATCH (a:A) WITH a ORDER BY a.foo RETURN DISTINCT a.foo AS x, a.foo AS y")._1
    val expectedPlan = new LogicalPlanBuilder(wholePlan = false)
      .orderedDistinct(Seq("cache[a.foo]"), "cache[a.foo] AS x", "cache[a.foo] AS y")
      .sort("`a.foo` ASC")
      .projection("cacheFromStore[a.foo] AS `a.foo`")
      .nodeByLabelScan("a", "A")
      .build()

    plan should equal(expectedPlan)
  }

  test("should use ordered distinct if there are two grouping columns, one ordered") {
    val plan = new givenConfig().getLogicalPlanFor("MATCH (a:A) WITH a ORDER BY a.foo RETURN DISTINCT a.foo, a.bar")._1
    val expectedPlan = new LogicalPlanBuilder(wholePlan = false)
      .orderedDistinct(Seq("`a.foo`"), "`a.foo` AS `a.foo`", "a.bar AS `a.bar`")
      .sort("`a.foo` ASC")
      .projection("a.foo AS `a.foo`")
      .nodeByLabelScan("a", "A")
      .build()

    plan should equal(expectedPlan)
  }

  test("should use ordered distinct if there are two grouping columns, both ordered") {
    val plan =
      new givenConfig().getLogicalPlanFor("MATCH (a:A) WITH a ORDER BY a.foo, a.bar RETURN DISTINCT a.foo, a.bar")._1
    val expectedPlan = new LogicalPlanBuilder(wholePlan = false)
      .orderedDistinct(Seq("`a.foo`", "`a.bar`"), "`a.foo` AS `a.foo`", "`a.bar` AS `a.bar`")
      .sort("`a.foo` ASC", "`a.bar` ASC")
      .projection("cache[a.foo] AS `a.foo`", "cache[a.bar] AS `a.bar`")
      .cacheProperties("cacheFromStore[a.foo]", "cacheFromStore[a.bar]")
      .nodeByLabelScan("a", "A")
      .build()

    plan should equal(expectedPlan)
  }

  private val idpGiven = new givenConfig {

    cardinality = mapCardinality {
      case RegularSinglePlannerQuery(queryGraph, _, _, _, _) if queryGraph.patternNodes == Set("u")           => 2.0
      case RegularSinglePlannerQuery(queryGraph, _, _, _, _) if queryGraph.patternNodes == Set("p")           => 10.0
      case RegularSinglePlannerQuery(queryGraph, _, _, _, _) if queryGraph.patternNodes == Set("b")           => 10.0
      case RegularSinglePlannerQuery(queryGraph, _, _, _, _) if queryGraph.patternNodes == Set("u", "p")      => 20.0
      case RegularSinglePlannerQuery(queryGraph, _, _, _, _) if queryGraph.patternNodes == Set("p", "b")      => 100.0
      case RegularSinglePlannerQuery(queryGraph, _, _, _, _) if queryGraph.patternNodes == Set("u", "p", "b") => 200.0
      case _ => throw new IllegalStateException("Unexpected PlannerQuery")
    }
  }

  test("should mark leveragedOrder in collect with ORDER BY") {
    val (plan, _, attrs) = new givenConfig().getLogicalPlanFor("MATCH (a) WITH a ORDER BY a.age RETURN collect(a.name)")
    val leveragedOrders = attrs.leveragedOrders

    leveragedOrders.get(plan.id) should be(true)
  }

  test("should not mark leveragedOrder in count with ORDER BY") {
    val (plan, _, attrs) = new givenConfig().getLogicalPlanFor("MATCH (a) WITH a ORDER BY a.age RETURN count(a.name)")
    val leveragedOrders = attrs.leveragedOrders

    leveragedOrders.get(plan.id) should be(false)
  }

  test("should not mark leveragedOrder in collect with no ORDER BY") {
    val (plan, _, attrs) = new givenConfig().getLogicalPlanFor("MATCH (a) RETURN collect(a.name)")
    val leveragedOrders = attrs.leveragedOrders

    leveragedOrders.get(plan.id) should be(false)
  }

  test("should mark leveragedOrder if using ORDER BY in RETURN") {
    val (plan, _, attrs) =
      new givenConfig().getLogicalPlanFor("MATCH (a) RETURN a ORDER BY a.age", stripProduceResults = false)
    val leveragedOrders = attrs.leveragedOrders

    leveragedOrders.get(plan.id) should be(true)
  }

  test("should mark leveragedOrder if using ORDER BY in RETURN after a WITH") {
    val (plan, _, attrs) = new givenConfig().getLogicalPlanFor(
      "MATCH (a) WITH a AS a, 1 AS foo RETURN a ORDER BY a.age",
      stripProduceResults = false
    )
    val leveragedOrders = attrs.leveragedOrders

    leveragedOrders.get(plan.id) should be(true)
  }

  test("should not mark leveragedOrder if using ORDER BY in RETURN in a UNION") {
    val (plan, _, attrs) = new givenConfig().getLogicalPlanFor(
      "MATCH (a) RETURN a ORDER BY a.age UNION RETURN 1 AS a",
      stripProduceResults = false
    )
    val leveragedOrders = attrs.leveragedOrders

    leveragedOrders.get(plan.id) should be(false)
  }

  test("should mark leveragedOrder in LIMIT with ORDER BY") {
    val (plan, _, attrs) = new givenConfig().getLogicalPlanFor("MATCH (a) WITH a ORDER BY a.age LIMIT 1 RETURN a.name")
    val leveragedOrders = attrs.leveragedOrders

    val projection = plan.asInstanceOf[Projection]
    val top = projection.lhs.get.asInstanceOf[Top]

    leveragedOrders.get(projection.id) should be(false)
    leveragedOrders.get(top.id) should be(true)
  }

  test("should mark leveragedOrder in SKIP with ORDER BY") {
    val (plan, _, attrs) = new givenConfig().getLogicalPlanFor("MATCH (a) WITH a ORDER BY a.age SKIP 1 RETURN a.name")
    val leveragedOrders = attrs.leveragedOrders

    val projection = plan.asInstanceOf[Projection]
    val skip = projection.source.asInstanceOf[Skip]
    val sort = skip.source.asInstanceOf[Sort]

    leveragedOrders.get(projection.id) should be(false)
    leveragedOrders.get(skip.id) should be(true)
    leveragedOrders.get(sort.id) should be(true)
  }

  test("Should plan sort before first expand when sorting on property") {
    val query =
      """MATCH (u:Person)-[f:FRIEND]->(p:Person)-[r:READ]->(b:Book)
        |WHERE u.name STARTS WITH 'Joe'
        |RETURN u.name, b.title
        |ORDER BY u.name""".stripMargin
    val plan = idpGiven.getLogicalPlanFor(query)._1

    plan should beLike {
      case Projection(
          Selection(
            _,
            Expand(
              Selection(
                _,
                Expand(
                  Sort(_, Seq(Ascending(LogicalVariable("u.name")))),
                  _,
                  _,
                  _,
                  _,
                  _,
                  _
                )
              ),
              _,
              _,
              _,
              _,
              _,
              _
            )
          ),
          _
        ) => ()
    }
  }

  test("Should plan sort before first expand when sorting on node") {
    val planner = plannerBuilder()
      .setAllNodesCardinality(14)
      .setLabelCardinality("Person", 12)
      .setLabelCardinality("Book", 2)
      .setRelationshipCardinality("()-[:FRIEND]->()", 50)
      .setRelationshipCardinality("()-[:FRIEND]->(:Person)", 50)
      .setRelationshipCardinality("()-[:READ]->()", 50)
      .setRelationshipCardinality("(:Person)-[:READ]->(:Book)", 50)
      .setRelationshipCardinality("(:Person)-[:READ]->()", 50)
      .setRelationshipCardinality("()-[:READ]->(:Book)", 50)
      .build()

    // Not having a label on u, otherwise we can take the order from the label scan and don't need to sort at all,
    // which would make this test useless.
    val query =
      """MATCH (u)-[f:FRIEND]->(p:Person)-[r:READ]->(b:Book)
        |WHERE u.name STARTS WITH 'Joe'
        |RETURN u.name, b.title
        |ORDER BY u""".stripMargin

    val plan = planner.plan(query).stripProduceResults
    plan shouldEqual planner.subPlanBuilder()
      .projection("cacheN[u.name] AS `u.name`", "b.title AS `b.title`")
      .filter("b:Book")
      .expandAll("(p)-[r:READ]->(b)")
      .filter("p:Person")
      .expandAll("(u)-[f:FRIEND]->(p)")
      .sort("u ASC")
      .filter("cacheNFromStore[u.name] STARTS WITH 'Joe'")
      .allNodeScan("u")
      .build()
  }

  test("Should plan sort before first expand when sorting on renamed property") {
    val query =
      """MATCH (u:Person)-[f:FRIEND]->(p:Person)-[r:READ]->(b:Book)
        |WHERE u.name STARTS WITH 'Joe'
        |RETURN u.name AS name, b.title
        |ORDER BY name""".stripMargin
    val plan = idpGiven.getLogicalPlanFor(query)._1

    plan should beLike {
      case Projection(
          Selection(
            _,
            Expand(
              Selection(
                _,
                Expand(
                  Sort(_, Seq(Ascending(LogicalVariable("name")))),
                  _,
                  _,
                  _,
                  _,
                  _,
                  _
                )
              ),
              _,
              _,
              _,
              _,
              _,
              _
            )
          ),
          _
        ) => ()
    }
  }

  test("Should plan sort before first expand when sorting on the old name of a renamed property") {
    val query =
      """MATCH (u:Person)-[f:FRIEND]->(p:Person)-[r:READ]->(b:Book)
        |WHERE u.name STARTS WITH 'Joe'
        |RETURN u.name AS name, b.title
        |ORDER BY u.name""".stripMargin
    val plan = idpGiven.getLogicalPlanFor(query)._1

    plan should beLike {
      case Projection(
          Selection(
            _,
            Expand(
              Selection(
                _,
                Expand(
                  Sort(_, Seq(Ascending(LogicalVariable("name")))),
                  _,
                  _,
                  _,
                  _,
                  _,
                  _
                )
              ),
              _,
              _,
              _,
              _,
              _,
              _
            )
          ),
          _
        ) => ()
    }
  }

  test("Should plan sort before first expand when sorting on a property of a renamed node") {
    val query =
      """MATCH (u:Person)-[f:FRIEND]->(p:Person)-[r:READ]->(b:Book)
        |WHERE u.name STARTS WITH 'Joe'
        |RETURN u AS v, b.title
        |ORDER BY v.name""".stripMargin
    val plan = idpGiven.getLogicalPlanFor(query)._1

    plan should beLike {
      case Projection(
          Selection(
            _,
            Expand(
              Selection(
                _,
                Expand(
                  Sort(_, Seq(Ascending(LogicalVariable("v.name")))),
                  _,
                  _,
                  _,
                  _,
                  _,
                  _
                )
              ),
              _,
              _,
              _,
              _,
              _,
              _
            )
          ),
          _
        ) => ()
    }
  }

  test("Should plan sort after expand if lower cardinality") {
    val query =
      """MATCH (u:Person)-[f:FRIEND]->(p:Person)-[r:READ]->(b:Book)
        |WHERE u.name STARTS WITH 'Joe'
        |RETURN u.name, b.title
        |ORDER BY u.name""".stripMargin
    val plan = new givenConfig {
      cardinality = mapCardinality {
        case RegularSinglePlannerQuery(queryGraph, _, _, _, _) if queryGraph.patternNodes == Set("u")           => 10.0
        case RegularSinglePlannerQuery(queryGraph, _, _, _, _) if queryGraph.patternNodes == Set("p")           => 10.0
        case RegularSinglePlannerQuery(queryGraph, _, _, _, _) if queryGraph.patternNodes == Set("b")           => 10.0
        case RegularSinglePlannerQuery(queryGraph, _, _, _, _) if queryGraph.patternNodes == Set("u", "p")      => 5.0
        case RegularSinglePlannerQuery(queryGraph, _, _, _, _) if queryGraph.patternNodes == Set("p", "b")      => 100.0
        case RegularSinglePlannerQuery(queryGraph, _, _, _, _) if queryGraph.patternNodes == Set("u", "p", "b") => 50.0
        case _ => throw new IllegalStateException("Unexpected PlannerQuery")
      }
    }.getLogicalPlanFor(query)._1

    plan should beLike {
      case Projection(
          Selection(
            _,
            Expand(
              Sort(_, Seq(Ascending(LogicalVariable("u.name")))),
              _,
              _,
              _,
              _,
              _,
              _
            )
          ),
          _
        ) => ()
    }
  }

  test("Should not expand from lowest cardinality point, if sorting can be avoided, when sorting on 1 variables.") {
    val query =
      """MATCH (a:A)-->(b:B)-->(c:C)
        |WHERE a.prop IS NOT NULL AND b.prop IS NOT NULL AND c.prop  IS NOT NULL
        |RETURN a, b, c
        |ORDER BY a.prop""".stripMargin
    val plan = new givenConfig {
      // Make it cheapest to start on b
      labelCardinality = Map("A" -> 200.0, "B" -> 100.0, "C" -> 200.0)
      indexOn("A", "prop").providesOrder(IndexOrderCapability.BOTH)
      indexOn("B", "prop").providesOrder(IndexOrderCapability.BOTH)
      indexOn("C", "prop").providesOrder(IndexOrderCapability.BOTH)
    }.getLogicalPlanFor(query)._1

    // Different join and expand variants are OK, but it should maintain the
    // order from a
    shouldNotSort(plan)
  }

  test(
    "Should not plan cartesian product with lowest cardinality point leftmost, if sorting can be avoided, when sorting on 1 variables."
  ) {
    assume(
      queryGraphSolverSetup == QueryGraphSolverWithIDPConnectComponents,
      "This test requires the IDP connect components planner"
    )
    val query =
      """MATCH (a:A), (b:B), (c:C)
        |WHERE a.prop IS NOT NULL AND b.prop IS NOT NULL AND c.prop IS NOT NULL
        |RETURN a, b, c
        |ORDER BY a.prop""".stripMargin
    val plan = new givenConfig {
      // Make it cheapest to start on b
      labelCardinality = Map("A" -> 200.0, "B" -> 100.0, "C" -> 200.0)
      indexOn("A", "prop").providesOrder(IndexOrderCapability.BOTH)
      indexOn("B", "prop").providesOrder(IndexOrderCapability.BOTH)
      indexOn("C", "prop").providesOrder(IndexOrderCapability.BOTH)
    }.getLogicalPlanFor(query)._1

    // Different cartesian product variants are OK, but it should maintain the
    // order from a
    shouldNotSort(plan)
  }

  test(
    "Should not plan value hash join with lowest cardinality point leftmost, if sorting can be avoided, when sorting on 1 variables."
  ) {
    val query =
      """MATCH (a:A), (b:B)
        |WHERE a.prop IS NOT NULL AND b.prop IS NOT NULL AND a.foo = b.foo
        |RETURN a, b
        |ORDER BY a.prop""".stripMargin

    val selectivities = Map[Expression, Double](
      hasLabels("a", "A") -> 0.5,
      hasLabels("b", "B") -> 0.9,
      equals(prop("a", "foo"), prop("b", "foo")) -> 0.4,
      isNotNull(prop("a", "prop")) -> 0.4,
      isNotNull(prop("b", "prop")) -> 0.4
    ).withDefaultValue(1.0)

    val plan = new givenConfig {
      cardinality = selectivitiesCardinality(selectivities, qg => Math.pow(100.0, qg.connectedComponents.size))
      indexOn("A", "prop").providesOrder(IndexOrderCapability.BOTH)
      indexOn("B", "prop").providesOrder(IndexOrderCapability.BOTH)
    }.getLogicalPlanFor(query)._1

    // It should maintain the
    // order from a
    shouldNotSort(plan)
  }

  test("Should not expand from lowest cardinality point, if sorting can be avoided, when sorting on 2 variables.") {
    val query =
      """MATCH (a:A)-->(b:B)-->(c:C)
        |WHERE a.prop IS NOT NULL AND b.prop IS NOT NULL AND c.prop IS NOT NULL
        |RETURN a, b, c
        |ORDER BY a.prop, c.prop""".stripMargin
    val plan = new givenConfig {
      // Make it cheapest to start on b
      labelCardinality = Map("A" -> 200.0, "B" -> 100.0, "C" -> 200.0)
      indexOn("A", "prop").providesOrder(IndexOrderCapability.BOTH)
      indexOn("B", "prop").providesOrder(IndexOrderCapability.BOTH)
      indexOn("C", "prop").providesOrder(IndexOrderCapability.BOTH)
    }.getLogicalPlanFor(query)._1

    // Different join and expand variants are OK, but it should maintain the
    // order from a and only need to partially sort c
    plan.folder.treeCount {
      case _: Sort => ()
    } shouldBe 0
    plan.folder.treeCount {
      case PartialSort(_, Seq(Ascending(LogicalVariable("a.prop"))), Seq(Ascending(LogicalVariable("c.prop"))), None) =>
        ()
    } shouldBe 1
  }

  test(
    "Should not plan cartesian product with lowest cardinality point leftmost, if sorting can be avoided, when sorting on 2 variables."
  ) {
    assume(
      queryGraphSolverSetup == QueryGraphSolverWithIDPConnectComponents,
      "This test requires the IDP connect components planner"
    )
    val query =
      """MATCH (a:A), (b:B), (c:C)
        |WHERE a.prop IS NOT NULL AND b.prop IS NOT NULL AND c.prop IS NOT NULL
        |RETURN a, b, c
        |ORDER BY a.prop, c.prop""".stripMargin
    val plan = new givenConfig {
      // Make it cheapest to start on b
      labelCardinality = Map("A" -> 200.0, "B" -> 100.0, "C" -> 200.0)
      indexOn("A", "prop").providesOrder(IndexOrderCapability.BOTH)
      indexOn("B", "prop").providesOrder(IndexOrderCapability.BOTH)
      indexOn("C", "prop").providesOrder(IndexOrderCapability.BOTH)
    }.getLogicalPlanFor(query)._1

    // Different cartesian product variants are OK, but it should maintain the
    // order from a and only need to partially sort c
    plan.folder.treeCount {
      case _: Sort => ()
    } shouldBe 0
    plan.folder.treeCount {
      case PartialSort(_, Seq(Ascending(LogicalVariable("a.prop"))), Seq(Ascending(LogicalVariable("c.prop"))), None) =>
        ()
    } shouldBe 1
  }

  test(
    "Should not plan value hash join with lowest cardinality point leftmost, if sorting can be avoided, when sorting on 2 variables."
  ) {
    assume(
      queryGraphSolverSetup == QueryGraphSolverWithIDPConnectComponents,
      "This test requires the IDP connect components planner"
    )
    val query =
      """MATCH (a:A), (b:B)
        |WHERE a.prop IS NOT NULL AND b.prop IS NOT NULL AND a.foo = b.foo
        |RETURN a, b
        |ORDER BY a.prop, b.prop""".stripMargin

    val selectivities = Map[Expression, Double](
      hasLabels("a", "A") -> 0.5,
      hasLabels("b", "B") -> 0.9,
      equals(prop("a", "foo"), prop("b", "foo")) -> 0.4,
      isNotNull(prop("a", "prop")) -> 0.4,
      isNotNull(prop("b", "prop")) -> 0.4
    ).withDefaultValue(1.0)

    val plan = new givenConfig {
      cardinality = selectivitiesCardinality(selectivities, qg => Math.pow(100.0, qg.connectedComponents.size))
      indexOn("A", "prop").providesOrder(IndexOrderCapability.BOTH)
      indexOn("B", "prop").providesOrder(IndexOrderCapability.BOTH)
    }.getLogicalPlanFor(query)._1

    // It should maintain the
    // order from a and only need to partially sort b
    plan.folder.treeCount {
      case _: Sort => ()
    } shouldBe 0
    plan.folder.treeCount {
      case PartialSort(_, Seq(Ascending(LogicalVariable("a.prop"))), Seq(Ascending(LogicalVariable("b.prop"))), None) =>
        ()
    } shouldBe 1
  }

  test(
    "Should not expand (longer pattern) from lowest cardinality point, if sorting can be avoided, when sorting on 2 variables."
  ) {
    val query =
      """MATCH (a:A)-->(b:B)-->(c:C)--(d:D)--(e:E)
        |WHERE a.prop IS NOT NULL AND b.prop IS NOT NULL AND c.prop IS NOT NULL AND d.prop IS NOT NULL AND e.prop IS NOT NULL
        |RETURN a, b, c
        |ORDER BY a.prop, e.prop""".stripMargin

    // Make it most expensive to start on a, but only by a very small margin
    val selectivities = Map[Expression, Double](
      hasLabels("a", "A") -> 1.0,
      hasLabels("b", "B") -> 0.99,
      hasLabels("c", "C") -> 0.99,
      hasLabels("d", "D") -> 0.99,
      hasLabels("e", "E") -> 0.99
    ).withDefaultValue(1.0)

    val plan = new givenConfig {
      // The more nodes, the less row
      cardinality = selectivitiesCardinality(selectivities, qg => 1000000 * Math.pow(0.7, qg.patternNodes.size))
      indexOn("A", "prop").providesOrder(IndexOrderCapability.BOTH)
      indexOn("B", "prop").providesOrder(IndexOrderCapability.BOTH)
      indexOn("C", "prop").providesOrder(IndexOrderCapability.BOTH)
      indexOn("D", "prop").providesOrder(IndexOrderCapability.BOTH)
      indexOn("E", "prop").providesOrder(IndexOrderCapability.BOTH)
    }.getLogicalPlanFor(query)._1

    // Different join and expand variants are OK, but it should maintain the
    // order from a and only need to partially sort e
    plan.folder.treeCount {
      case _: Sort => ()
    } shouldBe 0
    plan should beLike {
      case PartialSort(_, Seq(Ascending(LogicalVariable("a.prop"))), Seq(Ascending(LogicalVariable("e.prop"))), None) =>
        ()
    }
  }

  test("Should choose larger index on the same variable, if sorting can be avoided, when sorting on 2 variables.") {
    val query =
      """MATCH (a:A)-->(b)-->(c)
        |WHERE a.prop IS NOT NULL AND a.foo IS NOT NULL
        |RETURN a, b, c
        |ORDER BY a.prop, c.prop""".stripMargin

    val selectivities = Map[Expression, Double](
      hasLabels("a", "A") -> 1.0,
      isNotNull(prop("a", "prop")) -> 0.5,
      isNotNull(prop("a", "foo")) -> 0.1 // Make it cheapest to start on a.foo.
    ).withDefaultValue(1.0)

    val plan = new givenConfig {
      cardinality = selectivitiesCardinality(selectivities, qg => Math.pow(100.0, qg.patternNodes.size))
      indexOn("A", "prop").providesOrder(IndexOrderCapability.BOTH)
      indexOn("A", "foo")
    }.getLogicalPlanFor(query)._1

    // Different join and expand variants are OK, but it should maintain the
    // order from a and only need to partially sort c.
    // If this were to disregard sorting, the index on a.foo would be more selective and thus a better choice than a.prop.
    plan.folder.treeCount {
      case _: Sort => ()
    } shouldBe 0
    plan should beLike {
      case PartialSort(_, Seq(Ascending(LogicalVariable("a.prop"))), Seq(Ascending(LogicalVariable("c.prop"))), None) =>
        ()
    }
  }

  test("Should choose larger index on the same variable, if sorting can be avoided, when sorting on 3 variables.") {
    val query =
      """MATCH (a:A)-->(b)-->(c)
        |WHERE a.prop IS NOT NULL AND a.foo IS NOT NULL
        |RETURN a, b, c
        |ORDER BY a.prop, b.prop, c.prop""".stripMargin

    val selectivities = Map[Expression, Double](
      hasLabels("a", "A") -> 1.0,
      isNotNull(prop("a", "prop")) -> 0.5,
      isNotNull(prop("a", "foo")) -> 0.1 // Make it cheapest to start on a.foo.
    ).withDefaultValue(1.0)

    val plan = new givenConfig {
      cardinality = selectivitiesCardinality(selectivities, qg => Math.pow(100.0, qg.patternNodes.size))
      indexOn("A", "prop").providesOrder(IndexOrderCapability.BOTH)
      indexOn("A", "foo")
    }.getLogicalPlanFor(query)._1

    // Different join and expand variants are OK, but it should maintain the
    // order from a and only need to partially sort b, c.
    // If this were to disregard sorting, the index on a.foo would be more selective and thus a better choice than a.prop.
    withClue(plan) {
      plan.folder.treeCount {
        case _: Sort => ()
      } shouldBe 0
      plan should beLike {
        case PartialSort(
            _,
            Seq(Ascending(LogicalVariable("a.prop"))),
            Seq(Ascending(LogicalVariable("b.prop")), Ascending(LogicalVariable("c.prop"))),
            None
          ) => ()
      }
    }
  }

  test(
    "Should choose larger index on the same variable, if sorting can be avoided, when sorting on 2 variables, with projection."
  ) {
    val query =
      """MATCH (a:A)-->(b)-->(c)
        |WHERE a.prop IS NOT NULL AND a.foo IS NOT NULL
        |RETURN a.prop AS first, c.prop AS second
        |ORDER BY first, second""".stripMargin

    val selectivities = Map[Expression, Double](
      hasLabels("a", "A") -> 1.0,
      isNotNull(prop("a", "prop")) -> 0.5,
      isNotNull(prop("a", "foo")) -> 0.1
    ).withDefaultValue(1.0)

    val plan = new givenConfig {
      // Make it cheapest to start on a.foo.
      cardinality = selectivitiesCardinality(selectivities, qg => Math.pow(100.0, qg.patternNodes.size))
      indexOn("A", "prop").providesOrder(IndexOrderCapability.BOTH)
      indexOn("A", "foo")
    }.getLogicalPlanFor(query)._1

    // Different join and expand variants are OK, but it should maintain the
    // order from a and only need to partially sort c.
    // If this were to disregard sorting, the index on a.foo would be more selective and thus a better choice than a.prop.
    plan.folder.treeCount {
      case _: Sort => ()
    } shouldBe 0
    plan should beLike {
      case PartialSort(_, Seq(Ascending(LogicalVariable("first"))), Seq(Ascending(LogicalVariable("second"))), None) =>
        ()
    }
  }

  test("Should plan Partial Sort in between Expands, when sorting on 2 variables with projection.") {
    val query =
      """MATCH (a:A)-->(b)-->(c)
        |WHERE a.prop IS NOT NULL
        |RETURN a.prop AS first, b.prop AS second
        |ORDER BY first, second""".stripMargin

    val selectivities = Map[Expression, Double](
      hasLabels("a", "A") -> 1.0,
      isNotNull(prop("a", "prop")) -> 0.5
    ).withDefaultValue(1.0)

    val plan = new givenConfig {
      cardinality = selectivitiesCardinality(selectivities, qg => Math.pow(100.0, qg.patternNodes.size))
      indexOn("A", "prop").providesOrder(IndexOrderCapability.BOTH)
    }.getLogicalPlanFor(query)._1

    // Different join and expand variants are OK, but it should maintain the
    // order from a and only need to partially sort b.
    plan.folder.treeCount {
      case _: Sort => ()
    } shouldBe 0
    plan.folder.treeCount {
      case PartialSort(_, Seq(Ascending(LogicalVariable("first"))), Seq(Ascending(LogicalVariable("second"))), None) =>
        ()
    } shouldBe 1
    // The PartialSort should be between the expands, not at the end.
    plan should not be a[PartialSort]
  }

  test("Should plan Partial Sort in between Expands, when sorting on 3 variables with projection.") {
    val query =
      """MATCH (a:A)-->(b)-->(c)-->(d)
        |WHERE a.prop IS NOT NULL
        |RETURN a.prop AS first, b.prop AS second, c.prop AS third
        |ORDER BY first, second, third""".stripMargin

    val selectivities = Map[Expression, Double](
      hasLabels("a", "A") -> 1.0,
      isNotNull(prop("a", "prop")) -> 0.5
    ).withDefaultValue(1.0)

    val plan = new givenConfig {
      cardinality = selectivitiesCardinality(selectivities, qg => Math.pow(100.0, qg.patternNodes.size))
      indexOn("A", "prop").providesOrder(IndexOrderCapability.BOTH)
    }.getLogicalPlanFor(query)._1

    withClue(plan) {
      // Different join and expand variants are OK, but it should maintain the
      // order from a and only need to partially sort b, c.
      plan.folder.treeCount {
        case _: Sort => ()
      } shouldBe 0
      plan.folder.treeCount {
        case PartialSort(
            _,
            Seq(Ascending(LogicalVariable("first"))),
            Seq(Ascending(LogicalVariable("second")), Ascending(LogicalVariable("third"))),
            None
          ) => ()
      } shouldBe 1
      // The PartialSort should be between the expands, not at the end.
      plan should not be a[PartialSort]
    }
  }

  test("Should plan sort last when sorting on a property in last node in the expand") {
    val query =
      """MATCH (u:Person)-[f:FRIEND]->(p)-[r:READ]->(b:Book)
        |WHERE u.name STARTS WITH 'Joe'
        |RETURN u.name, b.title
        |ORDER BY b.title""".stripMargin
    val plan = new givenConfig {
      indexOn("Person", "name")
      labelCardinality = Map("Person" -> 10, "Book" -> 1000)
    }.getLogicalPlanFor(query)._1

    plan should beLike {
      case Projection(
          Sort(_, Seq(Ascending(LogicalVariable("b.title")))),
          _
        ) => ()
    }
  }

  test("Should plan sort last when sorting on the last node in the expand") {
    val query =
      """MATCH (u:Person)-[f:FRIEND]->(p)-[r:READ]->(b:Book)
        |WHERE u.name STARTS WITH 'Joe'
        |RETURN u.name, b.title
        |ORDER BY b""".stripMargin
    val plan = new givenConfig {
      indexOn("Person", "name")
      labelCardinality = Map("Person" -> 10, "Book" -> 1000)
    }.getLogicalPlanFor(query)._1

    plan should beLike {
      case Projection(
          Sort(_, Seq(Ascending(LogicalVariable("b")))),
          _
        ) => ()
    }
  }

  test(
    "Should plan sort between the expands when ordering by functions of both nodes in first expand and included aliased in return"
  ) {
    val query =
      """MATCH (u:Person)-[f:FRIEND]->(p:Person)-[r:READ]->(b:Book)
        |WHERE u.name STARTS WITH 'Joe'
        |RETURN u.name + p.name AS add, b.title
        |ORDER BY add""".stripMargin
    val plan = idpGiven.getLogicalPlanFor(query)._1

    plan should beLike {
      case Projection(
          Selection(
            _,
            Expand(
              Sort(_, Seq(Ascending(LogicalVariable("add")))),
              _,
              _,
              _,
              _,
              _,
              _
            )
          ),
          _
        ) => ()
    }
  }

  test(
    "Should plan sort between the expands when ordering by functions of both nodes in first expand and included unaliased in return"
  ) {
    val query =
      """MATCH (u:Person)-[f:FRIEND]->(p:Person)-[r:READ]->(b:Book)
        |WHERE u.name STARTS WITH 'Joe'
        |RETURN u.name + p.name, b.title
        |ORDER BY u.name + p.name""".stripMargin
    val plan = idpGiven.getLogicalPlanFor(query)._1

    plan should beLike {
      case Projection(
          Selection(
            _,
            Expand(
              Sort(_, Seq(Ascending(LogicalVariable("u.name + p.name")))),
              _,
              _,
              _,
              _,
              _,
              _
            )
          ),
          _
        ) => ()
    }
  }

  test(
    "Should plan sort between the expands when ordering by functions of both nodes in first expand and not included in the return"
  ) {
    val query =
      """MATCH (u:Person)-[f:FRIEND]->(p:Person)-[r:READ]->(b:Book)
        |WHERE u.name STARTS WITH 'Joe'
        |RETURN u.name AS uname, p.name AS pname, b.title
        |ORDER BY uname + pname""".stripMargin
    val plan = idpGiven.getLogicalPlanFor(query)._1

    plan should beLike {
      case Projection(
          Selection(
            _,
            Expand(
              Sort(_, Seq(Ascending(LogicalVariable("uname + pname")))),
              _,
              _,
              _,
              _,
              _,
              _
            )
          ),
          _
        ) => ()
    }
  }

  test("Should plan sort last when ordering by functions of node in last expand") {
    val query =
      """MATCH (u:Person)-[f:FRIEND]->(p:Person)-[r:READ]->(b:Book)
        |WHERE u.name STARTS WITH 'Joe'
        |RETURN u, b.title
        |ORDER BY u.name + b.title""".stripMargin
    val plan = new givenConfig().getLogicalPlanFor(query)._1

    plan should beLike {
      case Sort(_, Seq(Ascending(LogicalVariable("u.name + `b.title`")))) => ()
    }
  }

  test("should handle pattern comprehension within map projection followed by ORDER BY") {
    val query =
      """
        |MATCH (n:`Operation`) WITH n RETURN n{.id,
        | Operation_bankAccount_BankAccount: [(n)<-[:`bankAccount`]-(n_bankAccount:`BankAccount`)|n_bankAccount{.id,
        |  BankAccount_user_jhi_user: [(n_bankAccount)-[:`user`]->(n_bankAccount_user:`jhi_user`)|n_bankAccount_user{.user_id,
        |   jhi_user_HAS_AUTHORITY_jhi_authority: [(n_bankAccount_user)-[:`HAS_AUTHORITY`]->(n_bankAccount_user_authorities:`jhi_authority`)|n_bankAccount_user_authorities{.name}]}],
        |  BankAccount_operations_Operation: [(n_bankAccount)-[:`operations`]->(n_bankAccount_operations:`Operation`)|n_bankAccount_operations{.id}]}],
        | Operation_LABELS_Label: [(n)-[:`LABELS`]->(n_labels:`Label`)|n_labels{.id,
        |  Label_OPERATIONS_Operation: [(n_labels)-[:`OPERATIONS`]->(n_labels_operations:`Operation`)|n_labels_operations{.id, .date, .description, .amount}]}]} ORDER by n.id
        |""".stripMargin

    val (plan, _, _) = new givenConfig().getLogicalPlanFor(query)
    plan shouldBe a[Sort]
  }

  private val chooseLargerIndexConfig: StatisticsBackedLogicalPlanningConfiguration = {
    val nodeCount = 10000
    plannerBuilder()
      .setAllNodesCardinality(nodeCount)
      .setLabelCardinality("A", nodeCount)
      .setRelationshipCardinality("(:A)-[]->()", nodeCount * nodeCount)
      .setRelationshipCardinality("()-[]->()", nodeCount * nodeCount)
      .addNodeIndex("A", Seq("prop"), 0.9, uniqueSelectivity = 0.9, providesOrder = IndexOrderCapability.BOTH)
      .addNodeIndex("A", Seq("foo"), 0.8, uniqueSelectivity = 0.8) // Make it cheapest to start on a.foo.
      .build()
  }

  test(
    "Should choose larger index on the same variable, if sorting can be avoided, when sorting on 1 variable with DISTINCT."
  ) {
    val query =
      """MATCH (a:A)-->(b)-->(c)
        |WHERE a.prop IS NOT NULL AND a.foo IS NOT NULL
        |RETURN DISTINCT a.prop
        |ORDER BY a.prop""".stripMargin

    val plan = chooseLargerIndexConfig
      .plan(query)
      .stripProduceResults

    shouldNotSort(plan)

    plan should beLike {
      case _: OrderedDistinct => ()
    }
  }

  test("Should choose larger index on the same variable, if sorting can be avoided, when sorting after horizon.") {
    val query =
      """MATCH (a:A)
        |WHERE a.prop IS NOT NULL AND a.foo IS NOT NULL
        |WITH DISTINCT a
        |MATCH (a)-->(b)-->(c)
        |RETURN a, b, c
        |ORDER BY a.prop""".stripMargin

    val plan = chooseLargerIndexConfig
      .plan(query)
      .stripProduceResults

    shouldNotSort(plan)
  }

  test("Should sort before widening expands, when sorting after horizon with updates.") {
    val query =
      """MATCH (a:A)
        |WHERE a.prop IS NOT NULL AND a.foo IS NOT NULL
        |CREATE (newNode)
        |WITH DISTINCT a
        |MATCH (a)-[r1]->(b)-[r2]->(c)
        |RETURN a, b, c
        |ORDER BY a.prop""".stripMargin

    val plan = chooseLargerIndexConfig
      .plan(query)
      .stripProduceResults

    val expectedPlan = chooseLargerIndexConfig.subPlanBuilder()
      .filter("not r2 = r1")
      .expandAll("(b)-[r2]->(c)")
      .expandAll("(a)-[r1]->(b)")
      .sort("`a.prop` ASC")
      .projection("cacheN[a.prop] AS `a.prop`")
      .eager()
      .create(createNode("newNode"))
      .filter("cacheNFromStore[a.prop] IS NOT NULL")
      .nodeIndexOperator("a:A(foo)", indexType = IndexType.RANGE)
      .build()

    plan shouldEqual expectedPlan
  }

  test(
    "Should choose larger index on the same variable, if sorting can be avoided, when sorting after multiple horizons."
  ) {
    val query =
      """MATCH (a:A)
        |WHERE a.prop IS NOT NULL AND a.foo IS NOT NULL
        |WITH DISTINCT a
        |WITH a SKIP 1
        |MATCH (a)-->(b)-->(c)
        |RETURN a, b, c
        |ORDER BY a.prop""".stripMargin

    val plan = chooseLargerIndexConfig
      .plan(query)
      .stripProduceResults

    shouldNotSort(plan)
  }

  test(
    "Should choose larger index on the same variable, if sorting can be avoided, when sorting after multiple horizons with alias."
  ) {
    val query =
      """MATCH (a:A)
        |WHERE a.prop IS NOT NULL AND a.foo IS NOT NULL
        |WITH DISTINCT a
        |WITH a AS x SKIP 1
        |MATCH (x)-->(b)-->(c)
        |RETURN x, b, c
        |ORDER BY x.prop""".stripMargin

    val plan = chooseLargerIndexConfig
      .plan(query)
      .stripProduceResults

    shouldNotSort(plan)
  }

  test(
    "Should choose larger index on the same variable, if sorting can be avoided, when sorting after multiple horizons with alias and multiple components."
  ) {
    assume(
      queryGraphSolverSetup == QueryGraphSolverWithIDPConnectComponents,
      "This test requires the IDP connect components planner"
    )
    val query =
      """MATCH (a:A), (x)
        |WHERE a.prop IS NOT NULL AND a.foo IS NOT NULL AND
        |      x.prop IS NOT NULL AND x.prop IS NOT NULL AND
        |      x.prop = a.prop
        |WITH DISTINCT a, x
        |WITH x, a AS y SKIP 1
        |MATCH (y)-->(b)-->(c)
        |RETURN x, y, b, c
        |ORDER BY y.prop""".stripMargin

    val plan = chooseLargerIndexConfig
      .plan(query)
      .stripProduceResults

    shouldNotSort(plan)
  }

  private val wideningExpandConfig: StatisticsBackedLogicalPlanningConfiguration = {
    val proc = ProcedureSignature(
      QualifiedName(Seq("my"), "proc"),
      IndexedSeq(),
      None,
      None,
      ProcedureReadOnlyAccess,
      id = 1
    )

    val nodeCount = 10000.0
    plannerBuilder()
      .setAllNodesCardinality(nodeCount)
      .setRelationshipCardinality("()-[]->()", nodeCount * nodeCount)
      .setRelationshipCardinality("()-[:R]->()", nodeCount * nodeCount)
      .setRelationshipCardinality("()-[:Q]->()", nodeCount * nodeCount)
      .setRelationshipCardinality("()-[:NARROW]->()", nodeCount / 10)
      .addProcedure(proc)
      .removeRelationshipLookupIndex() // Let's avoid RelTypeScan since we are testing Expands
      .build()
  }

  test("should sort before widening expand and distinct") {
    val query =
      """MATCH (a)-->(b)
        |RETURN DISTINCT a.prop
        |ORDER BY a.prop""".stripMargin

    val plan = wideningExpandConfig
      .plan(query)
      .stripProduceResults

    plan should beLike {
      case OrderedDistinct(Expand(_: Sort, _, _, _, _, _, _), _, Seq(Variable("a.prop"))) => ()
    }
  }

  test("should sort before widening expand and distinct with alias, ordering by alias") {
    val query =
      """MATCH (a)-->(b)
        |RETURN DISTINCT a.prop AS x
        |ORDER BY x""".stripMargin

    val plan = wideningExpandConfig
      .plan(query)
      .stripProduceResults

    plan should beLike {
      case OrderedDistinct(Expand(_: Sort, _, _, _, _, _, _), _, Seq(Variable("x"))) => ()
    }
  }

  test("should sort before widening expand and distinct with alias, ordering by property") {
    val query =
      """MATCH (a)-->(b)
        |RETURN DISTINCT a.prop AS x
        |ORDER BY a.prop""".stripMargin

    val plan = wideningExpandConfig
      .plan(query)
      .stripProduceResults

    plan should beLike {
      case OrderedDistinct(Expand(_: Sort, _, _, _, _, _, _), _, Seq(Variable("x"))) => ()
    }
  }

  test("should sort before widening expand and distinct expression with alias, ordering by alias") {
    val query =
      """MATCH (a)-->(b)
        |RETURN DISTINCT a.prop+1 AS x
        |ORDER BY x""".stripMargin

    val plan = wideningExpandConfig
      .plan(query)
      .stripProduceResults

    plan should beLike {
      case OrderedDistinct(Expand(_: Sort, _, _, _, _, _, _), _, Seq(Variable("x"))) => ()
    }
  }

  test("should sort before widening expand and distinct expression with alias, ordering by expression") {
    val query =
      """MATCH (a)-->(b)
        |RETURN DISTINCT a.prop+1 AS x
        |ORDER BY a.prop+1""".stripMargin

    val plan = wideningExpandConfig
      .plan(query)
      .stripProduceResults

    plan should beLike {
      case OrderedDistinct(Expand(_: Sort, _, _, _, _, _, _), _, Seq(Variable("x"))) => ()
    }
  }

  test(
    "should sort before widening expand and distinct expression with alias, ordering by expression, difference in whitespace"
  ) {
    val query =
      """MATCH (a)-->(b)
        |RETURN DISTINCT a.prop +1 AS x
        |ORDER BY a.prop+ 1""".stripMargin

    val plan = wideningExpandConfig
      .plan(query)
      .stripProduceResults

    plan should beLike {
      case OrderedDistinct(Expand(_: Sort, _, _, _, _, _, _), _, Seq(Variable("x"))) => ()
    }
  }

  test("should sort before widening expand and distinct expression, ordering by expression, difference in whitespace") {
    val query =
      """MATCH (a)-->(b)
        |RETURN DISTINCT a.prop+ 1
        |ORDER BY a.prop +1""".stripMargin

    val plan = wideningExpandConfig
      .plan(query)
      .stripProduceResults

    plan should beLike {
      case OrderedDistinct(Expand(_: Sort, _, _, _, _, _, _), _, Seq(Variable("a.prop+ 1"))) => ()
    }
  }

  test("should sort before widening expand and distinct with two properties with alias, ordering by first alias") {
    val query =
      """MATCH (a)-->(b)
        |RETURN DISTINCT a.prop AS p1, a.prop AS p2
        |ORDER BY p1""".stripMargin

    val plan = wideningExpandConfig
      .plan(query)
      .stripProduceResults

    plan should beLike {
      case OrderedDistinct(Expand(_: Sort, _, _, _, _, _, _), _, Seq(Variable("p1"))) => ()
    }
  }

  test("should sort after narrowing expand and distinct") {
    val query =
      """MATCH (a)-->(b)
        |RETURN DISTINCT a.prop
        |ORDER BY a.prop""".stripMargin

    val nodeCount = 10000.0
    val plan = plannerBuilder()
      .setAllNodesCardinality(nodeCount)
      .setRelationshipCardinality("()-[]->()", nodeCount / 10)
      .build()
      .plan(query)
      .stripProduceResults

    plan should beLike {
      case Sort(_: Distinct, _) => ()
    }
  }

  test("should sort before widening expand and aggregation") {
    val query =
      """MATCH (a)-[:R]->(b)-[:Q]->()
        |RETURN a.prop, count(*) AS c
        |ORDER BY a.prop""".stripMargin

    val plan = wideningExpandConfig
      .plan(query)
      .stripProduceResults

    plan should beLike {
      case OrderedAggregation(
          Expand(Expand(_: Sort, _, _, _, _, _, _), _, _, _, _, _, _),
          _,
          _,
          Seq(Variable("a.prop"))
        ) => ()
    }
  }

  test("should sort before widening expand and aggregation with alias, ordering by alias") {
    val query =
      """MATCH (a)-[:R]->(b)-[:Q]->()
        |RETURN a.prop AS x, count(*) AS c
        |ORDER BY x""".stripMargin

    val plan = wideningExpandConfig
      .plan(query)
      .stripProduceResults

    plan should beLike {
      case OrderedAggregation(Expand(Expand(_: Sort, _, _, _, _, _, _), _, _, _, _, _, _), _, _, Seq(Variable("x"))) =>
        ()
    }
  }

  test("should sort before widening expand and aggregation with alias, ordering by property") {
    val query =
      """MATCH (a)-[:R]->(b)-[:Q]->()
        |RETURN a.prop AS x, count(*) AS c
        |ORDER BY a.prop""".stripMargin

    val plan = wideningExpandConfig
      .plan(query)
      .stripProduceResults

    plan should beLike {
      case OrderedAggregation(Expand(Expand(_: Sort, _, _, _, _, _, _), _, _, _, _, _, _), _, _, Seq(Variable("x"))) =>
        ()
    }
  }

  test("should sort before widening expand and aggregation expression with alias, ordering by alias") {
    val query =
      """MATCH (a)-[:R]->(b)-[:Q]->()
        |RETURN a.prop+1 AS x, count(*) AS c
        |ORDER BY x""".stripMargin

    val plan = wideningExpandConfig
      .plan(query)
      .stripProduceResults

    plan should beLike {
      case OrderedAggregation(Expand(Expand(_: Sort, _, _, _, _, _, _), _, _, _, _, _, _), _, _, Seq(Variable("x"))) =>
        ()
    }
  }

  test("should sort before widening expand and aggregation expression with alias, ordering by expression") {
    val query =
      """MATCH (a)-[:R]->(b)-[:Q]->()
        |WITH a.prop+1 AS x, count(*) AS c
        |RETURN x, c
        |ORDER BY x""".stripMargin

    val plan = wideningExpandConfig
      .plan(query)
      .stripProduceResults

    plan should beLike {
      case OrderedAggregation(Expand(Expand(_: Sort, _, _, _, _, _, _), _, _, _, _, _, _), _, _, Seq(Variable("x"))) =>
        ()
    }
  }

  test("should sort before widening expand and aggregation with two properties with alias, ordering by first alias") {
    val query =
      """MATCH (a)-[:R]->(b)-[:Q]->()
        |RETURN a.prop AS p1, a.prop AS p2, count(*) AS c
        |ORDER BY p1""".stripMargin

    val plan = wideningExpandConfig
      .plan(query)
      .stripProduceResults

    plan should beLike {
      case OrderedAggregation(Expand(Expand(_: Sort, _, _, _, _, _, _), _, _, _, _, _, _), _, _, Seq(Variable("p1"))) =>
        ()
    }
  }

  test("should sort after narrowing expand and aggregation") {
    val query =
      """MATCH (a)-->(b)
        |RETURN a.prop, count(*) AS c
        |ORDER BY a.prop""".stripMargin

    val nodeCount = 10000.0
    val plan = plannerBuilder()
      .setAllNodesCardinality(nodeCount)
      .setRelationshipCardinality("()-[]->()", nodeCount / 10)
      .build()
      .plan(query)
      .stripProduceResults

    plan should beLike {
      case Sort(_: Aggregation, _) => ()
    }
  }

  test("should sort between narrowing and widening expands with aggregation") {
    val query =
      """MATCH (a)-[:NARROW]->(b)-[wideRel:Q]->()
        |RETURN a.prop AS p1, a.prop AS p2, count(*) AS c
        |ORDER BY p1""".stripMargin

    val plan = wideningExpandConfig
      .plan(query)
      .stripProduceResults

    plan should beLike {
      case OrderedAggregation(
          Expand(Sort(Projection(_: Expand, _), _), _, _, _, _, LogicalVariable("wideRel"), _),
          _,
          _,
          Seq(Variable("p1"))
        ) => ()
    }
  }

  test(s"Should use OrderedDistinct on node variable") {
    val query = "MATCH (a:A)--() RETURN DISTINCT a"
    val planner = plannerBuilder()
      .setAllNodesCardinality(100)
      .setLabelCardinality("A", 10)
      .setAllRelationshipsCardinality(100)
      .setRelationshipCardinality("(:A)-[]->()", 10)
      .setRelationshipCardinality("()-[]->(:A)", 10)
      .build()

    planner.plan(query).stripProduceResults should equal(
      planner.subPlanBuilder()
        .orderedDistinct(Seq("a"), "a AS a")
        .expandAll("(a)-[anon_0]-(anon_1)")
        .nodeByLabelScan("a", "A", indexOrder = IndexOrderAscending)
        .build()
    )
  }

  test(s"Should use OrderedAggregation on node variable") {
    val plan = new givenConfig().getLogicalPlanFor("MATCH (a:A) RETURN a, count(*) AS c")._1

    val expectedPlan = new LogicalPlanBuilder(wholePlan = false)
      .orderedAggregation(Seq("a AS a"), Seq("count(*) AS c"), Seq("a"))
      .nodeByLabelScan("a", "A", indexOrder = IndexOrderAscending)
      .build()

    plan shouldEqual expectedPlan
  }

  test("should sort before widening expand when sorting after horizon") {
    val query =
      """MATCH (a)
        |WITH a SKIP 0
        |MATCH (a)-[r]->(b)
        |RETURN a, b
        |ORDER BY a.prop""".stripMargin

    val plan = wideningExpandConfig
      .plan(query)
      .stripProduceResults

    val expectedPlan = wideningExpandConfig.subPlanBuilder()
      .expand("(a)-[r]->(b)")
      .skip(0)
      .sort("`a.prop` ASC")
      .projection("a.prop AS `a.prop`")
      .allNodeScan("a")
      .build()

    plan shouldEqual expectedPlan
  }

  test("should sort after multiple query parts with narrowing expands, before widening expand") {
    val query =
      """MATCH (a)-[r1:NARROW]->(b)
        |WITH DISTINCT a, b
        |MATCH (b)-[r2:NARROW]->(c)-[r3:NARROW]->(d)
        |WITH DISTINCT a, b, c, d
        |MATCH (d)-[r4:R]->(e)
        |RETURN a, b, c, d, e ORDER BY a.prop""".stripMargin

    val plan = wideningExpandConfig
      .plan(query)
      .stripProduceResults

    val expectedPlan = wideningExpandConfig.subPlanBuilder()
      .expandAll("(d)-[r4:R]->(e)")
      .sort("`a.prop` ASC")
      .projection("a.prop AS `a.prop`")
      .distinct("a AS a", "b AS b", "c AS c", "d AS d")
      .filter("not r3 = r2")
      .expandAll("(c)-[r3:NARROW]->(d)")
      .expandAll("(b)-[r2:NARROW]->(c)")
      .distinct("a AS a", "b AS b")
      .expandAll("(a)-[r1:NARROW]->(b)")
      .allNodeScan("a")
      .build()

    plan shouldEqual expectedPlan
  }

  test("should sort after distinct, before widening expand") {
    val query =
      """MATCH (a)-[r1:NARROW]->(b)
        |WITH DISTINCT a, b
        |MATCH (b)-[r2:R]->(c)
        |RETURN a, b, c ORDER BY a.prop""".stripMargin

    val plan = wideningExpandConfig
      .plan(query)
      .stripProduceResults

    val expectedPlan = wideningExpandConfig.subPlanBuilder()
      .expandAll("(b)-[r2:R]->(c)")
      .sort("`a.prop` ASC")
      .projection("a.prop AS `a.prop`")
      .distinct("a AS a", "b AS b")
      .expandAll("(a)-[r1:NARROW]->(b)")
      .allNodeScan("a")
      .build()

    plan shouldEqual expectedPlan
  }

  test("should sort before widening expand after updating plan in horizon") {
    val query =
      """MATCH (a)-[r1:NARROW]->(b)
        |FOREACH (x in [1,2,3] | SET a.prop = x)
        |WITH *
        |MATCH (b)-[r2:R]->(c)
        |RETURN a, b, c ORDER BY a""".stripMargin

    val plan = wideningExpandConfig
      .plan(query)
      .stripProduceResults

    val expectedPlan = wideningExpandConfig.subPlanBuilder()
      .expandAll("(b)-[r2:R]->(c)")
      .sort("a ASC")
      .eager()
      .foreach("x", "[1, 2, 3]", Seq(setNodeProperty("a", "prop", "x")))
      .expandAll("(a)-[r1:NARROW]->(b)")
      .allNodeScan("a")
      .build()

    plan shouldEqual expectedPlan
  }

  test("should sort before 2 widening expands with unwind inbetween") {
    val query =
      """MATCH (a)-[r1:R]->(b)
        |UNWIND [1, 2, 3] AS i
        |MATCH (b)-[r2:R]->(c)
        |RETURN a, b, c ORDER BY a.prop""".stripMargin

    val plan = wideningExpandConfig
      .plan(query)
      .stripProduceResults

    val expectedPlan = wideningExpandConfig.subPlanBuilder()
      .expandAll("(b)-[r2:R]->(c)")
      .unwind("[1, 2, 3] AS i")
      .expandAll("(a)-[r1:R]->(b)")
      .sort("`a.prop` ASC")
      .projection("a.prop AS `a.prop`")
      .allNodeScan("a")
      .build()

    plan shouldEqual expectedPlan
  }

  test("should sort between narrowing and widening expand with unwind inbetween") {
    val query =
      """MATCH (a)-[r1:NARROW]->(b)
        |UNWIND [1, 2, 3] AS i
        |MATCH (b)-[r2:R]->(c)
        |RETURN a, b, c ORDER BY a.prop""".stripMargin

    val plan = wideningExpandConfig
      .plan(query)
      .stripProduceResults

    val expectedPlan = wideningExpandConfig.subPlanBuilder()
      .expandAll("(b)-[r2:R]->(c)")
      .unwind("[1, 2, 3] AS i")
      .sort("`a.prop` ASC")
      .projection("a.prop AS `a.prop`")
      .expandAll("(a)-[r1:NARROW]->(b)")
      .allNodeScan("a")
      .build()

    plan shouldEqual expectedPlan
  }

  test("should sort between narrowing and widening expand with call subquery inbetween") {
    val query =
      """MATCH (a)-[r1:NARROW]->(b)
        |CALL {
        |  MATCH (d) RETURN d
        |}
        |MATCH (b)-[r2:R]->(c)
        |RETURN a, b, c ORDER BY a.prop""".stripMargin

    val plan = wideningExpandConfig
      .plan(query)
      .stripProduceResults

    val expectedPlan = wideningExpandConfig.subPlanBuilder()
      .expandAll("(b)-[r2:R]->(c)")
      .cartesianProduct()
      .|.allNodeScan("d")
      .sort("`a.prop` ASC")
      .projection("a.prop AS `a.prop`")
      .expandAll("(a)-[r1:NARROW]->(b)")
      .allNodeScan("a")
      .build()

    plan shouldEqual expectedPlan
  }

  test("should sort between narrowing and widening expand with procedure call inbetween") {
    val query =
      """MATCH (a)-[r1:NARROW]->(b)
        |CALL my.proc()
        |MATCH (b)-[r2:R]->(c)
        |RETURN a, b, c ORDER BY a.prop""".stripMargin

    val plan = wideningExpandConfig
      .plan(query)
      .stripProduceResults

    val expectedPlan = wideningExpandConfig.subPlanBuilder()
      .expandAll("(b)-[r2:R]->(c)")
      .procedureCall("my.proc()")
      .sort("`a.prop` ASC")
      .projection("a.prop AS `a.prop`")
      .expandAll("(a)-[r1:NARROW]->(b)")
      .allNodeScan("a")
      .build()

    plan shouldEqual expectedPlan
  }

  test("should sort between narrowing and widening expand with LOAD CSV inbetween") {
    val query =
      """MATCH (a)-[r1:NARROW]->(b)
        |LOAD CSV FROM 'url' AS line
        |MATCH (b)-[r2:R]->(c)
        |RETURN a, b, c ORDER BY a.prop""".stripMargin

    val plan: LogicalPlan = wideningExpandConfig
      .plan(query)
      .stripProduceResults

    val expectedPlan = wideningExpandConfig.subPlanBuilder()
      .expandAll("(b)-[r2:R]->(c)")
      .loadCSV("'url'", "line", NoHeaders, None)
      .sort("`a.prop` ASC")
      .projection("a.prop AS `a.prop`")
      .expandAll("(a)-[r1:NARROW]->(b)")
      .allNodeScan("a")
      .build()

    plan shouldEqual expectedPlan
  }

  test("should sort between aggregation and widening expand") {
    val query =
      """MATCH (a)-[r1:NARROW]->(aa)
        |WITH count(distinct a) AS count
        |MATCH (b)-[r2:R]->(c)
        |RETURN b, c, count ORDER BY count""".stripMargin

    val plan = wideningExpandConfig
      .plan(query)
      .stripProduceResults

    val expectedPlan = wideningExpandConfig.subPlanBuilder()
      .expandAll("(b)-[r2:R]->(c)")
      .apply()
      .|.allNodeScan("b", "count")
      .sort("count ASC")
      .aggregation(Seq(), Seq("count(distinct a) AS count"))
      .expandAll("(a)-[r1:NARROW]->(aa)")
      .allNodeScan("a")
      .build()

    plan shouldEqual expectedPlan
  }

  test("should sort between distinct and widening expand") {
    val query =
      """MATCH (a)
        |WITH DISTINCT a.count AS count
        |MATCH (b)-[r2:R]->(c)
        |RETURN b, c, count ORDER BY count""".stripMargin

    val plan = wideningExpandConfig
      .plan(query)
      .stripProduceResults

    val expectedPlan = wideningExpandConfig.subPlanBuilder()
      .expandAll("(b)-[r2:R]->(c)")
      .apply()
      .|.allNodeScan("b", "count")
      .sort("count ASC")
      .distinct("a.count AS count")
      .allNodeScan("a")
      .build()

    plan shouldEqual expectedPlan
  }

  test("should not sort before widening expand if there is an updating plan in tail") {
    val query =
      """MATCH (a)-[r1:NARROW]->(b)
        |WITH DISTINCT a, b
        |MATCH (b)-[r2:R]->(c)
        |CREATE (newNode)
        |RETURN a, b, c ORDER BY a.prop""".stripMargin

    val plan = wideningExpandConfig
      .plan(query)
      .stripProduceResults

    val expectedPlan = wideningExpandConfig.subPlanBuilder()
      .sort("`a.prop` ASC")
      .projection("a.prop AS `a.prop`")
      .create(createNode("newNode"))
      .eager()
      .expandAll("(b)-[r2:R]->(c)")
      .distinct("a AS a", "b AS b")
      .expandAll("(a)-[r1:NARROW]->(b)")
      .allNodeScan("a")
      .build()

    plan shouldEqual expectedPlan
  }

  test("should consider interesting order for query part even when there is a required order from tail") {
    val query =
      """MATCH (n:N)
        |WHERE n.prop IS NOT NULL
        |WITH DISTINCT n.prop AS prop, n.otherProp AS other
        |RETURN prop, other
        |ORDER BY other""".stripMargin

    val cfg = plannerBuilder()
      .setAllNodesCardinality(10000)
      .setLabelCardinality("N", 1000)
      .addNodeIndex(
        "N",
        Seq("prop"),
        existsSelectivity = 0.9,
        uniqueSelectivity = 0.9,
        withValues = true,
        providesOrder = IndexOrderCapability.BOTH
      )
      .build()

    val plan = cfg
      .plan(query)
      .stripProduceResults

    val expectedPlan = cfg.subPlanBuilder()
      .sort("other ASC")
      .orderedDistinct(Seq("cache[n.prop]"), "cache[n.prop] AS prop", "n.otherProp AS other")
      .nodeIndexOperator(
        "n:N(prop)",
        getValue = _ => GetValue,
        indexOrder = IndexOrderAscending,
        indexType = IndexType.RANGE
      )
      .build()

    plan shouldEqual expectedPlan
  }

  test("should propagate interesting order also through 2 horizons and with aggregation") {
    val query =
      """MATCH (a)
        |WITH a.name AS name1,
        |   count(a) AS count1
        |WITH name1 AS name2,
        |    count1 AS count2
        |RETURN name2, count2
        |  ORDER BY name2, count2""".stripMargin

    val cfg = plannerBuilder()
      .setAllNodesCardinality(10000)
      .build()

    val plan = cfg
      .plan(query)
      .stripProduceResults

    val expectedPlan = cfg.subPlanBuilder()
      .sort("name2 ASC", "count2 ASC")
      .projection("name1 AS name2", "count1 AS count2")
      .aggregation(Seq("a.name AS name1"), Seq("count(a) AS count1"))
      .allNodeScan("a")
      .build()

    plan shouldEqual expectedPlan
  }

  test("should not sort if order of two MATCHes combined (RHS token index order) gives the desired order") {
    val query =
      """MATCH (a:A)
        |  WHERE a.p1 IS NOT NULL AND a.p2 IS NOT NULL
        |WITH DISTINCT a.p1 AS p1, a.p2 AS p2
        |MATCH (b:B)
        |RETURN *
        |  ORDER BY p1, p2, b
        |""".stripMargin

    val cfg = plannerBuilder()
      .setAllNodesCardinality(1000)
      .setLabelCardinality("A", 100)
      .setLabelCardinality("B", 100)
      .addNodeIndex("A", Seq("p1", "p2"), 1.0, 0.03, providesOrder = IndexOrderCapability.BOTH)
      .build()

    val plan = cfg
      .plan(query)
      .stripProduceResults

    shouldNotSort(plan)
  }

  test("should not sort if order of two MATCHes combined (RHS range index order) gives the desired order") {
    val query =
      """MATCH (a:A)
        |  WHERE a.p1 IS NOT NULL AND a.p2 IS NOT NULL
        |WITH DISTINCT a.p1 AS p1, a.p2 AS p2
        |MATCH (b:B)
        |  WHERE b.p IS NOT NULL
        |RETURN *
        |  ORDER BY p1, p2, b.p
        |""".stripMargin

    val cfg = plannerBuilder()
      .setAllNodesCardinality(1000)
      .setLabelCardinality("A", 100)
      .setLabelCardinality("B", 100)
      .addNodeIndex("A", Seq("p1", "p2"), 1.0, 0.03, providesOrder = IndexOrderCapability.BOTH)
      .addNodeIndex("B", Seq("p"), 1.0, 0.03, providesOrder = IndexOrderCapability.BOTH)
      .build()

    val plan = cfg
      .plan(query)
      .stripProduceResults

    shouldNotSort(plan)
  }

  test("should not sort if order of two nodes combined gives the desired order.") {
    val query =
      """MATCH (a:A), (b:B)
        |RETURN *
        |  ORDER BY a, b
        |""".stripMargin

    val cfg = plannerBuilder()
      .setAllNodesCardinality(1000)
      .setLabelCardinality("A", 25)
      .setLabelCardinality("B", 50)
      .build()

    val plan = cfg
      .plan(query)
      .stripProduceResults

    shouldNotSort(plan)
  }

  test("should not sort if order of two nodes (reversed) combined gives the desired order.") {
    assume(
      queryGraphSolverSetup == QueryGraphSolverWithIDPConnectComponents,
      "This test requires the IDP connect components planner"
    )

    val query =
      """MATCH (a:A), (b:B)
        |RETURN *
        |  ORDER BY b, a
        |""".stripMargin

    val cfg = plannerBuilder()
      .setAllNodesCardinality(1000)
      .setLabelCardinality("A", 25)
      .setLabelCardinality("B", 50)
      .build()

    val plan = cfg
      .plan(query)
      .stripProduceResults

    shouldNotSort(plan)
  }

  test("should not sort if order of two components joined with a nested index join gives the desired order") {
    val query =
      """MATCH (a:A), (b:B)
        |  WHERE b.p = a.p
        |RETURN *
        |  ORDER BY a, b.p
        |""".stripMargin

    val cfg = plannerBuilder()
      .setAllNodesCardinality(1000)
      .setLabelCardinality("A", 100)
      .setLabelCardinality("B", 100)
      .addNodeIndex("B", Seq("p"), 1.0, 0.03, providesOrder = IndexOrderCapability.BOTH)
      .build()

    val plan = cfg
      .plan(query)
      .stripProduceResults

    shouldNotSort(plan)
  }

  test("should not sort if order of a MATCH and an OPTIONAL MATCH combined gives the desired order") {
    val query =
      """MATCH (a:A)
        |OPTIONAL MATCH (b:B)
        |  WHERE b.p IS NOT NULL
        |RETURN *
        |  ORDER BY a, b.p
        |""".stripMargin

    val cfg = plannerBuilder()
      .setAllNodesCardinality(1000)
      .setLabelCardinality("A", 100)
      .setLabelCardinality("B", 100)
      .addNodeIndex("B", Seq("p"), 1.0, 0.03, providesOrder = IndexOrderCapability.BOTH)
      .build()

    val plan = cfg
      .plan(query)
      .stripProduceResults

    println(plan)

    shouldNotSort(plan)
  }

  test("should push sort of prefix of sort columns into a component") {
    assume(
      queryGraphSolverSetup == QueryGraphSolverWithIDPConnectComponents,
      "This test requires the IDP connect components planner"
    )

    val query =
      """MATCH (a), (b)
        |RETURN *
        |  ORDER BY a, b
        |""".stripMargin

    val cfg = plannerBuilder()
      .setAllNodesCardinality(1000)
      .setLabelCardinality("A", 100)
      .setLabelCardinality("B", 100)
      .build()

    val plan = cfg
      .plan(query)
      .stripProduceResults

    plan should equal(
      cfg.subPlanBuilder()
        .partialSort(Seq("a ASC"), Seq("b ASC")) // And just complete with a PartialSort
        .cartesianProduct()
        .|.allNodeScan("b")
        .sort("a ASC") // Should already sort here before CartesianProduct
        .allNodeScan("a")
        .build()
    )
  }

  test("should push sort below UNWIND and leverage provided order on aliased column") {
    val planner = plannerBuilder().setAllNodesCardinality(100).build()

    val q =
      """
        |MATCH (a)
        |UNWIND [1, 2, 3] AS x
        |RETURN DISTINCT a, a AS b
        |ORDER BY b
        |""".stripMargin

    val plan = planner.plan(q).stripProduceResults
    plan shouldEqual planner.subPlanBuilder()
      .orderedDistinct(Seq("a"), "a AS a", "a AS b")
      .unwind("[1, 2, 3] AS x")
      .sort("b ASC")
      .projection("a AS b")
      .allNodeScan("a")
      .build()
  }

  test("Should not plan an aggregation inside a regular projection as part of sort planning") {
    val planner = plannerBuilder()
      .setAllNodesCardinality(100)
      .setRelationshipCardinality("()-[:HAS_ATTRIBUTE]->()", 1500)
      .build()

    val q =
      """
        |MATCH (x)-[:HAS_ATTRIBUTE]->(y)
        |WITH x, count(y) as common
        |RETURN x
        |ORDER BY common
        |""".stripMargin

    val plan = planner.plan(q).stripProduceResults
    plan shouldEqual planner.subPlanBuilder()
      .sort("common ASC")
      .aggregation(Seq("x AS x"), Seq("count(y) AS common"))
      .relationshipTypeScan("(x)-[anon_0:HAS_ATTRIBUTE]->(y)", IndexOrderNone)
      .build()
  }

  test("ORDER BY the map projection of a renamed variable") {
    val query =
      """MATCH p = (a:A)-[:R]->+(:B)
        |WITH a AS n ORDER BY n{.prop}
        |RETURN n.prop""".stripMargin

    val planner = plannerBuilder()
      .setAllNodesCardinality(100)
      .setLabelCardinality("A", 10)
      .setLabelCardinality("B", 10)
      .setRelationshipCardinality("()-[:R]->()", 100)
      .setRelationshipCardinality("(:A)-[:R]->()", 100)
      .setRelationshipCardinality("()-[:R]->(:B)", 100)
      .setRelationshipCardinality("(:A)-[:R]->(:B)", 100)
      .build()

    val mapProjection = DesugaredMapProjection(
      varFor("n"),
      Seq(LiteralEntry(propName("prop"), cachedNodeProp("a", "prop", "n", knownToAccessStore = true))(pos)),
      includeAllProps = false
    )(pos)

    planner.plan(query) should equal(
      planner.planBuilder()
        .produceResults("`n.prop`")
        .projection(Map("n.prop" -> cachedNodeProp("a", "prop", "n")))
        .filter("anon_0:B")
        .expand("(a)-[anon_1:R*1..]->(anon_0)")
        .sort("`n{prop: n.prop}` ASC")
        .projection(Map("n{prop: n.prop}" -> mapProjection))
        .projection("a AS n")
        .nodeByLabelScan("a", "A", IndexOrderNone)
        .build()
    )
  }

  test("ORDER BY the map projection of a complex expression") {
    val query =
      """MATCH p = (a:A)-[:R*]->(:B)
        |WITH nodes(p)[1] AS n ORDER BY n{.prop}
        |RETURN n.prop""".stripMargin

    val planner = plannerBuilder()
      .setAllNodesCardinality(100)
      .setLabelCardinality("A", 10)
      .setLabelCardinality("B", 10)
      .setRelationshipCardinality("()-[:R]->()", 100)
      .setRelationshipCardinality("(:A)-[:R]->()", 100)
      .setRelationshipCardinality("()-[:R]->(:B)", 100)
      .setRelationshipCardinality("(:A)-[:R]->(:B)", 100)
      .build()

    val mapProjection = DesugaredMapProjection(
      varFor("n"),
      Seq(LiteralEntry(propName("prop"), cachedNodeProp("n", "prop", "n", knownToAccessStore = true))(pos)),
      includeAllProps = false
    )(pos)

    val secondNodeInPathProjection =
      containerIndex(nodes(PathExpressionBuilder.node("a").outToVarLength("anon_0", "anon_1").build()), 1)

    planner.plan(query) shouldEqual planner.planBuilder()
      .produceResults("`n.prop`")
      .projection(Map("n.prop" -> cachedNodeProp("n", "prop", "n")))
      .sort("`n{prop: n.prop}` ASC")
      .projection(Map("n{prop: n.prop}" -> mapProjection))
      .projection(Map("n" -> secondNodeInPathProjection))
      .expand(
        "(a)-[anon_0:R*1..]->(anon_1)",
        expandMode = Expand.ExpandInto,
        projectedDir = SemanticDirection.OUTGOING,
        nodePredicates = Seq(),
        relationshipPredicates = Seq()
      )
      .cartesianProduct()
      .|.nodeByLabelScan("anon_1", "B", IndexOrderNone)
      .nodeByLabelScan("a", "A", IndexOrderNone)
      .build()
  }

  test("should not push down ORDER BY rand(), 2 components") {
    val planner = plannerBuilder()
      .setAllNodesCardinality(100)
      .setLabelCardinality("A", 100)
      .build()

    val query =
      """
        |MATCH (a1:A), (a2:A)
        |WHERE a1<>a2
        |RETURN *
        |ORDER BY rand()
        |LIMIT 5
        |""".stripMargin

    planner.plan(query).stripProduceResults should equal(
      planner.subPlanBuilder()
        .top(5, "`rand()` ASC")
        .projection("rand() AS `rand()`")
        .filter("NOT a1 = a2")
        .cartesianProduct()
        .|.nodeByLabelScan("a2", "A")
        .nodeByLabelScan("a1", "A")
        .build()
    )
  }

  test("should not push down ORDER BY rand(), 2 nodes in pattern") {
    val planner = plannerBuilder()
      .setAllNodesCardinality(100)
      .setAllRelationshipsCardinality(1000)
      .setLabelCardinality("A", 100)
      .setRelationshipCardinality("(:A)-[]->(:A)", 100)
      .setRelationshipCardinality("()-[]->(:A)", 100)
      .setRelationshipCardinality("(:A)-[]->()", 100)
      .build()

    val query =
      """
        |MATCH (a1:A)-[r]->(a2:A)
        |WHERE a1<>a2
        |RETURN *
        |ORDER BY rand()
        |LIMIT 5
        |""".stripMargin

    planner.plan(query).stripProduceResults should equal(
      planner.subPlanBuilder()
        .top(5, "`rand()` ASC")
        .projection("rand() AS `rand()`")
        .filter("NOT a1 = a2", "a2:A")
        .expandAll("(a1)-[r]->(a2)")
        .nodeByLabelScan("a1", "A")
        .build()
    )
  }
}
