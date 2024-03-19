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
package org.neo4j.cypher.internal.compiler.planner.logical.steps

import org.neo4j.cypher.internal.compiler.planner.logical.LogicalPlanningContext
import org.neo4j.cypher.internal.compiler.planner.logical.ordering.InterestingOrderConfig
import org.neo4j.cypher.internal.expressions.CountStar
import org.neo4j.cypher.internal.expressions.Expression
import org.neo4j.cypher.internal.expressions.FunctionInvocation
import org.neo4j.cypher.internal.expressions.HasLabels
import org.neo4j.cypher.internal.expressions.IsNotNull
import org.neo4j.cypher.internal.expressions.LabelName
import org.neo4j.cypher.internal.expressions.LogicalVariable
import org.neo4j.cypher.internal.expressions.Property
import org.neo4j.cypher.internal.expressions.PropertyKeyName
import org.neo4j.cypher.internal.expressions.RelTypeName
import org.neo4j.cypher.internal.expressions.SemanticDirection.INCOMING
import org.neo4j.cypher.internal.expressions.SemanticDirection.OUTGOING
import org.neo4j.cypher.internal.expressions.Variable
import org.neo4j.cypher.internal.expressions.functions
import org.neo4j.cypher.internal.ir.AggregatingQueryProjection
import org.neo4j.cypher.internal.ir.PatternRelationship
import org.neo4j.cypher.internal.ir.Predicate
import org.neo4j.cypher.internal.ir.QueryGraph
import org.neo4j.cypher.internal.ir.Selections
import org.neo4j.cypher.internal.ir.SimplePatternLength
import org.neo4j.cypher.internal.ir.SinglePlannerQuery
import org.neo4j.cypher.internal.logical.plans.LogicalPlan

case object countStorePlanner {

  def apply(query: SinglePlannerQuery, context: LogicalPlanningContext): Option[LogicalPlan] = {
    query.horizon match {
      case AggregatingQueryProjection(groupingKeys, aggregatingExpressions, queryPagination, selections, _)
        if groupingKeys.isEmpty && query.queryInput.isEmpty && aggregatingExpressions.size == 1 && queryPagination.isEmpty =>
        val (column, exp) = aggregatingExpressions.head
        val countStorePlan = checkForValidQueryGraph(query, column, exp, context)
        countStorePlan.map { plan =>
          val projectionPlan = projection(plan, groupingKeys, Some(groupingKeys), context)
          context.staticComponents.logicalPlanProducer.planHorizonSelection(
            projectionPlan,
            selections.flatPredicates,
            InterestingOrderConfig.empty,
            context
          )
        }

      case _ => None
    }
  }

  private def checkForValidQueryGraph(
    query: SinglePlannerQuery,
    columnName: LogicalVariable,
    exp: Expression,
    context: LogicalPlanningContext
  ): Option[LogicalPlan] = {
    def patternHasNoDependencies: Boolean = {
      val qg = query.queryGraph
      (qg.patternNodes ++ qg.patternRelationships.map(_.variable)).intersect(qg.argumentIds).isEmpty
    }

    query.queryGraph match {
      case QueryGraph(
          patternRelationships,
          quantifiedPathPatterns,
          patternNodes,
          argumentIds,
          selections,
          Seq(),
          hints,
          shortestRelationshipPatterns,
          _,
          shortestPathPatterns
        )
        if hints.isEmpty && shortestRelationshipPatterns.isEmpty && quantifiedPathPatterns.isEmpty && query.queryGraph.readOnly && patternHasNoDependencies && shortestPathPatterns.isEmpty =>
        checkForValidAggregations(
          query,
          columnName,
          exp,
          patternRelationships,
          patternNodes,
          argumentIds,
          selections,
          context
        )
      case _ => None
    }
  }

  private def checkForValidAggregations(
    query: SinglePlannerQuery,
    columnName: LogicalVariable,
    exp: Expression,
    patternRelationships: Set[PatternRelationship],
    patternNodes: Set[LogicalVariable],
    argumentIds: Set[LogicalVariable],
    selections: Selections,
    context: LogicalPlanningContext
  ): Option[LogicalPlan] =
    exp match {
      case // COUNT(<id>)
        func @ FunctionInvocation(_, _, false, Vector(v: Variable), _, _) if func.function == functions.Count =>
        trySolveNodeOrRelationshipAggregation(
          query,
          columnName,
          Some(v),
          patternRelationships,
          patternNodes,
          argumentIds,
          selections,
          context,
          None
        )

      case // COUNT(*)
        CountStar() =>
        trySolveNodeOrRelationshipAggregation(
          query,
          columnName,
          None,
          patternRelationships,
          patternNodes,
          argumentIds,
          selections,
          context,
          None
        )

      case // COUNT(n.prop)
        func @ FunctionInvocation(_, _, false, Vector(prop @ Property(v: Variable, propKeyName)), _, _)
        if func.function == functions.Count =>
        trySolveNodeOrRelationshipAggregation(
          query,
          columnName,
          Some(v),
          patternRelationships,
          patternNodes,
          argumentIds,
          selections,
          context,
          Some(prop)
        )

      case _ => None
    }

  /**
   * @param variableName    the name of the variable in the count function. None, if this is count(*)
   * @param propertyKeyName the name of the property in the count function. None, if this is count(*) or count(n)
   */
  private def trySolveNodeOrRelationshipAggregation(
    query: SinglePlannerQuery,
    columnName: LogicalVariable,
    variableName: Option[LogicalVariable],
    patternRelationships: Set[PatternRelationship],
    patternNodes: Set[LogicalVariable],
    argumentIds: Set[LogicalVariable],
    selections: Selections,
    context: LogicalPlanningContext,
    property: Option[Property]
  ): Option[LogicalPlan] = {
    if (
      patternRelationships.isEmpty &&
      patternNodes.nonEmpty &&
      variableName.forall(patternNodes.contains) &&
      noWrongPredicates(patternNodes, selections, property)
    ) { // MATCH (n), MATCH (n:A)

      if (couldPlanCountStoreLookupOnAllLabels(variableName, selections, property.map(_.propertyKey), context)) {
        // this is the case where the count can be answered using the counts of the provided labels

        val allLabels = patternNodes.toList.map(n => findLabel(n, selections))
        Some(context.staticComponents.logicalPlanProducer.planCountStoreNodeAggregation(
          query,
          columnName,
          allLabels,
          argumentIds,
          context
        ))
      } else {
        None
      }
    } else if (patternRelationships.size == 1 && notLoop(patternRelationships.head)) { // MATCH ()-[r]->(), MATCH ()-[r:X]->(), MATCH ()-[r:X|Y]->()
      val types = patternRelationships.head.types
      // this means that the given type implies the predicate that we do the count on through constraint
      if (types.forall(relTypeImpliesProperty(_, property.map(_.propertyKey), context))) {
        trySolveRelationshipAggregation(
          query,
          columnName,
          variableName,
          patternRelationships.head,
          argumentIds,
          selections,
          context,
          property
        )
      } else {
        None
      }
    } else {
      None
    }
  }

  private def couldPlanCountStoreLookupOnAllLabels(
    variableName: Option[LogicalVariable],
    selections: Selections,
    propertyKeyName: Option[PropertyKeyName],
    context: LogicalPlanningContext
  ): Boolean = {
    // variableName == None => count(*)
    variableName.isEmpty ||
    // propertyKeyName == None => count(n)
    propertyKeyName.isEmpty ||
    // count(n.prop) => there should be a label for n which implies prop.
    findLabel(variableName.get, selections).exists(labelImpliesProperty(_, propertyKeyName, context))
  }

  private def relTypeImpliesProperty(
    relTypeName: RelTypeName,
    propertyKeyName: Option[PropertyKeyName],
    context: LogicalPlanningContext
  ): Boolean = {
    propertyKeyName.forall(prop =>
      context.staticComponents.planContext.hasRelationshipPropertyExistenceConstraint(relTypeName.name, prop.name)
    )
  }

  private def labelImpliesProperty(
    labelName: LabelName,
    propertyKeyName: Option[PropertyKeyName],
    context: LogicalPlanningContext
  ): Boolean = {
    propertyKeyName.forall(prop =>
      context.staticComponents.planContext.hasNodePropertyExistenceConstraint(labelName.name, prop.name)
    )
  }

  /**
   * Whether this relationship is not a loop. This is relevant because the counts store counts loops twice.
   */
  private def notLoop(r: PatternRelationship): Boolean = r.left != r.right

  private def trySolveRelationshipAggregation(
    query: SinglePlannerQuery,
    columnName: LogicalVariable,
    variableName: Option[LogicalVariable],
    patternRelationship: PatternRelationship,
    argumentIds: Set[LogicalVariable],
    selections: Selections,
    context: LogicalPlanningContext,
    property: Option[Property]
  ): Option[LogicalPlan] = {
    patternRelationship match {

      case PatternRelationship(relId, (startNodeId, endNodeId), direction, types, SimplePatternLength)
        if variableName.forall(name => Set(relId, startNodeId, endNodeId).contains(name)) &&
          noWrongPredicates(Set(startNodeId, endNodeId), selections, property) =>
        def planRelAggr(fromLabel: Option[LabelName], toLabel: Option[LabelName]): Option[LogicalPlan] =
          Some(context.staticComponents.logicalPlanProducer.planCountStoreRelationshipAggregation(
            query,
            columnName,
            fromLabel,
            types,
            toLabel,
            argumentIds,
            context
          ))

        // plan relationship aggregation only when max one of the nodes has a label
        (findLabel(startNodeId, selections), direction, findLabel(endNodeId, selections)) match {
          case (None, OUTGOING, None)       => planRelAggr(None, None)
          case (None, INCOMING, None)       => planRelAggr(None, None)
          case (None, OUTGOING, endLabel)   => planRelAggr(None, endLabel)
          case (startLabel, OUTGOING, None) => planRelAggr(startLabel, None)
          case (None, INCOMING, endLabel)   => planRelAggr(endLabel, None)
          case (startLabel, INCOMING, None) => planRelAggr(None, startLabel)
          case _                            => None
        }

      case _ => None
    }
  }

  /**
   * Check if all predicates can be handled by the count store
   * Only when each predicate:
   * - matches the property key name and the property variable of the aggregate function
   * @param predicates The predicates to check
   * @param aggregateProperty The aggregate function's property
   * @return True if all predicates can be handled
   */
  private def canHandlePredicates(predicates: Set[Predicate], aggregateProperty: Option[Property]): Boolean = {
    predicates.forall(predicate =>
      predicate match {
        case Predicate(_, IsNotNull(innProperty: Property)) => aggregateProperty.contains(innProperty)
        case _                                              => false
      }
    )
  }

  /**
   * Tests whether the predicates in selections consist of only hasLabel-predicates and max one for each given node
   * or it has 'other' predicates that can all be handled.
   */
  private def noWrongPredicates(
    nodeIds: Set[LogicalVariable],
    selections: Selections,
    aggregateProperty: Option[Property]
  ): Boolean = {
    val (labelPredicates, others) = selections.predicates.partition {
      case Predicate(nIds, h: HasLabels) if nIds.forall(nodeIds.contains) && h.labels.size == 1 => true
      case _                                                                                    => false
    }
    val groupedLabelPredicates = labelPredicates.groupBy(_.dependencies intersect nodeIds)
    groupedLabelPredicates.values.forall(_.size == 1) && canHandlePredicates(others, aggregateProperty)
  }

  private def findLabel(nodeId: LogicalVariable, selections: Selections): Option[LabelName] =
    selections.predicates.collectFirst {
      case Predicate(nIds, h: HasLabels) if nIds == Set(nodeId) && h.labels.size == 1 => h.labels.head
    }
}
