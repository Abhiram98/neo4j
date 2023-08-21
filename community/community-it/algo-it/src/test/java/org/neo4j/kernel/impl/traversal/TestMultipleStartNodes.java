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
package org.neo4j.kernel.impl.traversal;

import static org.neo4j.graphdb.RelationshipType.withName;
import static org.neo4j.graphdb.traversal.Evaluators.atDepth;

import org.junit.jupiter.api.Test;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.traversal.TraversalDescription;

class TestMultipleStartNodes extends TraversalTestBase {
    @Test
    void myFriendsAsWellAsYourFriends() {
        /*
         * Hey, this looks like a futuristic gun or something
         *
         *  (f8)     _----(f1)--(f5)
         *   |      /      /
         * (f7)--(you)--(me)--(f2)--(f6)
         *         |   /   \
         *         (f4)    (f3)
         */

        createGraph(
                "you KNOW me",
                "you KNOW f1",
                "you KNOW f4",
                "me KNOW f1",
                "me KNOW f4",
                "me KNOW f2",
                "me KNOW f3",
                "f1 KNOW f5",
                "f2 KNOW f6",
                "you KNOW f7",
                "f7 KNOW f8");

        try (Transaction tx = beginTx()) {
            RelationshipType knowRelType = withName("KNOW");
            Node you = getNodeWithName(tx, "you");
            Node me = getNodeWithName(tx, "me");

            String[] levelOneFriends = {"f1", "f2", "f3", "f4", "f7"};
            TraversalDescription levelOneTraversal =
                    tx.traversalDescription().relationships(knowRelType).evaluator(atDepth(1));
            expectNodes(levelOneTraversal.depthFirst().traverse(you, me), levelOneFriends);
            expectNodes(levelOneTraversal.breadthFirst().traverse(you, me), levelOneFriends);

            String[] levelTwoFriends = {"f5", "f6", "f8"};
            TraversalDescription levelTwoTraversal =
                    tx.traversalDescription().relationships(knowRelType).evaluator(atDepth(2));
            expectNodes(levelTwoTraversal.depthFirst().traverse(you, me), levelTwoFriends);
            expectNodes(levelTwoTraversal.breadthFirst().traverse(you, me), levelTwoFriends);
        }
    }
}
