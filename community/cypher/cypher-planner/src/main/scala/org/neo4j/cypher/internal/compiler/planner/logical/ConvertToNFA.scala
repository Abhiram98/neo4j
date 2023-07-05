/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
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
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.compiler.planner.logical

import org.neo4j.cypher.internal.expressions.Ands
import org.neo4j.cypher.internal.expressions.Expression
import org.neo4j.cypher.internal.expressions.RelationshipUniquenessPredicate
import org.neo4j.cypher.internal.expressions.UnPositionedVariable.varFor
import org.neo4j.cypher.internal.ir.ExhaustiveNodeConnection
import org.neo4j.cypher.internal.ir.PatternRelationship
import org.neo4j.cypher.internal.ir.QuantifiedPathPattern
import org.neo4j.cypher.internal.ir.Selections
import org.neo4j.cypher.internal.ir.SelectivePathPattern
import org.neo4j.cypher.internal.ir.SimplePatternLength
import org.neo4j.cypher.internal.ir.VarPatternLength
import org.neo4j.cypher.internal.logical.plans.Expand.VariablePredicate
import org.neo4j.cypher.internal.logical.plans.NFA
import org.neo4j.cypher.internal.logical.plans.NFABuilder
import org.neo4j.cypher.internal.util.UpperBound
import org.neo4j.exceptions.InternalException

import scala.collection.immutable.ListSet

object ConvertToNFA {

  /**
   *
   * @return the NFA representing the selective path pattern and the selections from the spp that could not be inlined.
   */
  def convertToNfa(
    spp: SelectivePathPattern,
    fromLeft: Boolean,
    availableSymbols: Set[String],
    predicatesOnTargetNode: Seq[Expression]
  ): (NFA, Selections) = {
    val firstNodeName = if (fromLeft) spp.left else spp.right

    val builder = new NFABuilder(varFor(firstNodeName))
    val connections = spp.pathPattern.connections
    val directedConnections = if (fromLeft) connections else connections.reverse

    val nonInlinedSelections =
      convertToNfa(
        builder,
        directedConnections.toIterable,
        spp.selections ++ predicatesOnTargetNode,
        fromLeft,
        availableSymbols
      )

    val lastNode = builder.getLastState
    builder.addFinalState(lastNode)
    (builder.build(), nonInlinedSelections)
  }

  /**
   *
   * @return the selections that could not be inlined
   */
  private def convertToNfa(
    builder: NFABuilder,
    connections: Iterable[ExhaustiveNodeConnection],
    selections: Selections,
    fromLeft: Boolean,
    availableSymbols: Set[String]
  ): Selections = {
    // we cannot inline uniqueness predicates but we do not have to solve them as the algorithm for finding shortest paths will do that.
    val selectionsWithoutUniquenessPredicates = selections.filter(_.expr match {
      case _: RelationshipUniquenessPredicate => false
      case _                                  => true
    })

    // go over the node connections and keep track of selections we could inline
    val (_, inlinedSelections) = connections.foldLeft((builder, Selections.empty)) {
      case ((builder, inlinedSelections), nodeConnection) =>
        val newlyInlinedSelections = nodeConnection match {
          case PatternRelationship(relationshipName, (left, right), dir, types, SimplePatternLength) =>
            val sourceState = builder.getLastState
            val target = if (fromLeft) right else left
            val targetState = builder.addAndGetState(varFor(target))
            val directionToPlan = if (fromLeft) dir else dir.reversed

            val relPredicates =
              selectionsWithoutUniquenessPredicates.predicatesGiven(availableSymbols + relationshipName)
            val relVariablePredicates = toVariablePredicates(relationshipName, relPredicates)
            val nodePredicates = selectionsWithoutUniquenessPredicates.predicatesGiven(availableSymbols + target)
            val nodeVariablePredicates = toVariablePredicates(target, nodePredicates)
            builder.addTransition(
              sourceState,
              targetState,
              NFA.RelationshipExpansionPredicate(
                relationshipVariable = varFor(relationshipName),
                relPred = relVariablePredicates,
                types = types,
                dir = directionToPlan,
                nodePred = nodeVariablePredicates
              )
            )
            Selections.from(relPredicates ++ nodePredicates)

          case PatternRelationship(_, _, _, _, _: VarPatternLength) =>
            throw new InternalException("Converting legacy var-length relationships to NFAs is not supported yet.")

          case QuantifiedPathPattern(
              leftBinding,
              rightBinding,
              patternRelationships,
              _,
              qppSelections,
              repetition,
              _,
              _
            ) =>
            /*
             * When adding a QPP, we need to
             * 1. Jump into the pattern via juxtaposing sourceOuter to sourceInner
             * 2. Reiterate the pattern within the QPP as many times as the lower bound needs us to.
             * Now we could potentially exit the QPP. But we also need to make sure that the upper bound is observed by
             * 3. a) Either reiterating the pattern until we reach the UpperBound or
             *    b) adding a transition back, so that we can run the last iteration of the QPP again.
             * 4. For all states which could potentially exit the QPP, we juxtapose targetInner to targetOuter.
             * 5. If there is a lower bound of 0, we need to add an extra transition, skipping the whole inner pattern of the QPP.
             *
             * E.g. For the pattern `(start) ((a)-[r]->(b)) {2, 3} (end)`
             *
             *
             *
             *                                                                                ┌─────────────────────────────────────────────────┐
             *                                                                                |                                         [4]────>|
             *                                                                                |                                          |      v
             * ┌──────────┐     ┌──────┐  ()-[r]->()   ┌──────┐     ┌──────┐  ()-[r]->()   ┌──────┐     ┌──────┐  ()-[r]->()   ┌──────┐  v  ╔════════╗
             * │ 0, start │ ──> │ 1, a │ ────────────> │ 2, b │ ──> │ 3, a │ ────────────> │ 4, b │ ──> │ 5, a │ ────────────> │ 6, b │ ──> ║ 7, end ║
             * └──────────┘     └──────┘               └──────┘     └──────┘               └──────┘     └──────┘               └──────┘     ╚════════╝
             *               ^  \_________________________________________________________________/ \________________________________/
             *               |                                   |                                                  |
             *              [1]                                 [2]                                               [3.a]
             *
             *
             * (kudos to https://github.com/ggerganov/dot-to-ascii)
             */

            // === 1. Add entry juxtaposition ===
            val sourceBinding = if (fromLeft) leftBinding else rightBinding
            val sourceOuterState = builder.getLastState
            val sourceInnerName = sourceBinding.inner
            // var because it will get overwritten if the lower bound is > 1
            var lastSourceInnerState = builder.addAndGetState(varFor(sourceInnerName))
            val predicatesOnSourceInner = qppSelections.predicatesGiven(availableSymbols + sourceInnerName)
            val variablePredicateOnSourceInner = toVariablePredicates(sourceInnerName, predicatesOnSourceInner)
            builder.addTransition(
              sourceOuterState,
              lastSourceInnerState,
              NFA.NodeJuxtapositionPredicate(variablePredicateOnSourceInner)
            )

            // === 2.a) Add inner transitions ===
            val relsInOrder = if (fromLeft) patternRelationships else patternRelationships.reverse

            def addQppInnerTransitions(): Selections =
              convertToNfa(builder, relsInOrder, qppSelections -- predicatesOnSourceInner, fromLeft, availableSymbols)

            val nonInlinedQppSelections = addQppInnerTransitions()
            if (nonInlinedQppSelections.nonEmpty) {
              throw new InternalException(s"$nonInlinedQppSelections could not be inlined into NFA")
            }
            // === 2.b) Unrolling for lower bound ===
            // If the lower bound is larger than 1, repeat the inner steps of the QPP (min - 1) times.
            for (_ <- 1L to (repetition.min - 1)) {
              val targetInnerState = builder.getLastState
              lastSourceInnerState = builder.addAndGetState(varFor(sourceInnerName))
              builder.addTransition(
                targetInnerState,
                lastSourceInnerState,
                NFA.NodeJuxtapositionPredicate(variablePredicateOnSourceInner)
              )
              addQppInnerTransitions()
            }

            // 3. By unrolling, we have reached the first target inner state from which we can exit the QPP.
            val exitableTargetInnerState = builder.getLastState
            val furtherExitableTargetInnerStates = repetition.max match {
              case UpperBound.Unlimited =>
                builder.addTransition(
                  exitableTargetInnerState,
                  lastSourceInnerState,
                  NFA.NodeJuxtapositionPredicate(variablePredicateOnSourceInner)
                )
                Seq.empty
              case UpperBound.Limited(max) =>
                for (_ <- Math.max(repetition.min, 1) until max) yield {
                  val targetInnerState = builder.getLastState
                  val sourceInnerState = builder.addAndGetState(varFor(sourceInnerName))
                  builder.addTransition(
                    targetInnerState,
                    sourceInnerState,
                    NFA.NodeJuxtapositionPredicate(variablePredicateOnSourceInner)
                  )
                  addQppInnerTransitions()
                  builder.getLastState
                }
            }
            val exitableTargetInnerStates = exitableTargetInnerState +: furtherExitableTargetInnerStates

            // === 4. Add exit juxtapositions ===
            // Connect all exitableTargetInnerStates with the targetOuterState
            val targetBinding = if (fromLeft) rightBinding else leftBinding
            val targetOuterName = targetBinding.outer
            val targetOuterState = builder.addAndGetState(varFor(targetOuterName))
            val predicatesOnTargetOuter =
              selectionsWithoutUniquenessPredicates.predicatesGiven(availableSymbols + targetOuterName)
            val variablePredicateOnTargetOuter = toVariablePredicates(targetOuterName, predicatesOnTargetOuter)
            exitableTargetInnerStates.foreach { targetInnerState =>
              builder.addTransition(
                targetInnerState,
                targetOuterState,
                NFA.NodeJuxtapositionPredicate(variablePredicateOnTargetOuter)
              )
            }

            // 5. For a repetition lower bound of 0, we need to add this shortcut around the QPP pattern
            if (repetition.min == 0) {
              builder.addTransition(
                sourceOuterState,
                targetOuterState,
                NFA.NodeJuxtapositionPredicate(variablePredicateOnTargetOuter)
              )
            }
            Selections.from(predicatesOnSourceInner ++ predicatesOnTargetOuter)
        }
        (builder, inlinedSelections ++ newlyInlinedSelections)
    }
    selectionsWithoutUniquenessPredicates -- inlinedSelections
  }

  private def toVariablePredicates(variableName: String, predicates: Seq[Expression]): Option[VariablePredicate] = {
    Option.when(predicates.nonEmpty)(VariablePredicate(varFor(variableName), Ands.create(predicates.to(ListSet))))
  }
}
