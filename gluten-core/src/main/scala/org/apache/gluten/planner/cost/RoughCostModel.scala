/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.gluten.planner.cost

import org.apache.gluten.execution.{BroadcastHashJoinExecTransformerBase, ColumnarToRowExecBase, HashAggregateExecBaseTransformer, ProjectExecTransformer, RowToColumnarExecBase, ShuffledHashJoinExecTransformerBase, SortMergeJoinExecTransformerBase}
import org.apache.gluten.extension.columnar.enumerated.RemoveFilter
import org.apache.gluten.extension.columnar.transition.{ColumnarToRowLike, RowToColumnarLike}
import org.apache.gluten.utils.PlanUtil
import org.apache.spark.sql.catalyst.optimizer.{BuildLeft, BuildRight}
import org.apache.spark.sql.catalyst.expressions.{Alias, Attribute, NamedExpression}
import org.apache.spark.sql.catalyst.plans.{ExistenceJoin, JoinType, LeftSemi}
import org.apache.spark.sql.catalyst.plans.logical.statsEstimation.JoinEstimation
import org.apache.spark.sql.catalyst.plans.logical.{Join, JoinHint}
import org.apache.spark.sql.catalyst.trees.TreeNodeTag
import org.apache.spark.sql.execution.joins.{BroadcastHashJoinExec, ShuffledHashJoinExec, SortMergeJoinExec}
import org.apache.spark.sql.execution.{ColumnarToRowExec, ProjectExec, RowToColumnarExec, SparkPlan}

class RoughCostModel extends LongCostModel {

  import org.apache.spark.sql.catalyst.plans.logical.Statistics
  private def printStats(prefix: String, stats: Statistics): Unit = {
    println(s"$prefix stats:")
    println(s"  sizeInBytes: ${stats.sizeInBytes}")
    stats.rowCount.foreach(count => println(s"  rowCount: $count"))
    stats.attributeStats.foreach { case (attr, colStat) =>
      println(s"  Column ${attr.name}:")
      println(s"    distinctCount: ${colStat.distinctCount.getOrElse("N/A")}")
      println(s"    min: ${colStat.min.getOrElse("N/A")}")
      println(s"    max: ${colStat.max.getOrElse("N/A")}")
      println(s"    nullCount: ${colStat.nullCount.getOrElse("N/A")}")
      println(s"    avgLen: ${colStat.avgLen.getOrElse("N/A")}")
      println(s"    maxLen: ${colStat.maxLen.getOrElse("N/A")}")
    }
  }

  def isJoin(node: SparkPlan): Boolean = {
    node match {
      case _: BroadcastHashJoinExecTransformerBase | _: ShuffledHashJoinExecTransformerBase | _: SortMergeJoinExecTransformerBase | _: BroadcastHashJoinExec | _: ShuffledHashJoinExec | _: SortMergeJoinExec =>
        true
    }
  }

  override def selfLongCostOf(node: SparkPlan): Long = {
    /*if (!node.logicalLink.isEmpty) {
      // Add check for join nodes
      if (node.nodeName.toLowerCase.contains("join")) {
          val logicalPlan = node.logicalLink.get
          val stats = logicalPlan.stats

          println(s"Node: ${node.nodeName} (${node.getClass.getSimpleName})")
          println(s"Logical Plan: ${logicalPlan.getClass.getSimpleName}")
          printStats("Node", stats)
          node.children.zipWithIndex.foreach { case (child, index) =>
          child.logicalLink.foreach { childLogicalPlan =>
            println(s"Logical Plan: ${childLogicalPlan.getClass.getSimpleName}")
            printStats(s"    Child $index", childLogicalPlan.stats)
          }
        }
      }
    }*/

    node match {
      //case VeloxColumnarToRowExec(_) => 1L
      //case RowToVeloxColumnarExec(_) => 1L
      // ColumnarToRow Weight = w1 = 1.3
      case columnarToRowExecBase: ColumnarToRowExecBase => {
        var cost = 0L
        node.children.zipWithIndex.foreach { case (child, index) =>
          child.logicalLink.foreach { childLogicalPlan =>
            //println(s"Child $index Logical Plan: ${childLogicalPlan.getClass.getSimpleName}")
            val calculatedCost = (childLogicalPlan.stats.sizeInBytes * BigInt(13)) / BigInt(10)
            if (calculatedCost > 1000L) cost = 10L
            else if (calculatedCost < 1L) cost = 1L
            else cost = calculatedCost.toLong
            //printStats(s"    Child $index", childLogicalPlan.stats)
          }
        }
        cost
      }
      // RowToColumnar Weight = w2 = 10
      case rowToColumnarExecBase: RowToColumnarExecBase => {
        var cost = 0L
        node.children.zipWithIndex.foreach { case (child, index) =>
          child.logicalLink.foreach { childLogicalPlan =>
            val calculatedCost = childLogicalPlan.stats.sizeInBytes * 100
            if (calculatedCost > 1000L) cost = 1000L
            else if (calculatedCost < 1L) cost = 1L
            else cost = calculatedCost.toLong

          }
        }
        cost
      }
      case join@(_: BroadcastHashJoinExecTransformerBase | _: ShuffledHashJoinExecTransformerBase | _: SortMergeJoinExecTransformerBase) => {
        {
          var buildcost = 0L
          var probecost = 0L
          var finalCost = 0L
          val logicalPlan = node.logicalLink.get
          val stats = logicalPlan.stats
          var maxNdv = 0L
          var leftNdv = 0L
          var rightNdv = 0L
          var estimatedOutputRow = stats.rowCount.map(_.toLong).getOrElse(0L)
          join match {
            case smj: SortMergeJoinExecTransformerBase =>
              buildcost = smj.left.logicalLink.map { leftLogicalPlan =>
                val leftStats = leftLogicalPlan.stats

                leftStats.sizeInBytes.toLong
              }.getOrElse(0L)
              probecost = smj.right.logicalLink.map { rightLogicalPlan =>
                val rightStats = rightLogicalPlan.stats

                rightStats.sizeInBytes.toLong
              }.getOrElse(0L)
              maxNdv = Math.max(leftNdv, rightNdv)

            case shj: ShuffledHashJoinExecTransformerBase => {

              shj.joinBuildSide match {
                case BuildLeft =>
                  // Get the left child's logical plan and stats
                  buildcost += shj.left.logicalLink.map { leftLogicalPlan =>
                    val leftStats = leftLogicalPlan.stats

                    leftStats.sizeInBytes.toLong
                  }.getOrElse(0L)

                  probecost += shj.right.logicalLink.map { rightLogicalPlan =>
                    val rightStats = rightLogicalPlan.stats

                    rightStats.sizeInBytes.toLong
                  }.getOrElse(0L)

                case BuildRight =>
                  // Similar logic for BuildRight if needed
                  // build table
                  buildcost += shj.right.logicalLink.map { rightLogicalPlan =>
                    val rightStats = rightLogicalPlan.stats

                    rightStats.sizeInBytes.toLong
                  }.getOrElse(0L) // Default to 0L if logical link is not available

                  probecost += shj.left.logicalLink.map { leftLogicalPlan =>
                    val leftStats = leftLogicalPlan.stats

                    leftStats.sizeInBytes.toLong
                  }.getOrElse(0L) // Default to 0L if logical link is not available
              }
              maxNdv = Math.max(leftNdv, rightNdv)
            }

            case bhj: BroadcastHashJoinExecTransformerBase => {

              //println("native broadcastHashJoin")
              def getDistinctCount(attr: Attribute, stats: Statistics): BigInt = {
                stats.attributeStats.get(attr).flatMap(_.distinctCount).getOrElse(
                  // Fallback to total row count if distinct count is not available
                  stats.rowCount.getOrElse(BigInt(0))
                )
              }


              bhj.joinBuildSide match {
                case BuildLeft =>
                  // Get the left child's logical plan and stats
                  buildcost += bhj.left.logicalLink.map { leftLogicalPlan =>
                    val leftStats = leftLogicalPlan.stats
                    leftStats.sizeInBytes.toLong
                  }.getOrElse(0L) // Default to 0L if logical link is not available

                  probecost += bhj.right.logicalLink.map { rightLogicalPlan =>
                    val rightStats = rightLogicalPlan.stats
                    rightStats.sizeInBytes.toLong
                  }.getOrElse(0L)

                case BuildRight =>
                  // Similar logic for BuildRight if needed
                  // build table
                  buildcost += bhj.right.logicalLink.map { rightLogicalPlan =>
                    val rightStats = rightLogicalPlan.stats
                    rightStats.sizeInBytes.toLong
                  }.getOrElse(0L) // Default to 0L if logical link is not available

                  probecost += bhj.left.logicalLink.map { leftLogicalPlan =>
                    val leftStats = leftLogicalPlan.stats
                    leftStats.sizeInBytes.toLong
                  }.getOrElse(0L) // Default to 0L if logical link is not available
              }
              maxNdv = Math.max(leftNdv, rightNdv)
            }
          }
          if (maxNdv == 0) maxNdv = 1
          estimatedOutputRow = buildcost*probecost/maxNdv
          val calculatedCost = 2 * buildcost + 4 * probecost + estimatedOutputRow
          if (calculatedCost.compareTo(1000L) > 0) finalCost = 1000L
          else if (calculatedCost.compareTo(10L) < 0) finalCost = 10L
          else finalCost = calculatedCost
          finalCost
        }
      }
      case join@(_: BroadcastHashJoinExec | _: ShuffledHashJoinExec | _: SortMergeJoinExec) =>
        def countJoinsInPath(node: SparkPlan): Int = {
          node match {
            case _: BroadcastHashJoinExecTransformerBase | _: ShuffledHashJoinExecTransformerBase | _: SortMergeJoinExecTransformerBase | _: BroadcastHashJoinExec | _: ShuffledHashJoinExec | _: SortMergeJoinExec =>
              1 + node.children.map(countJoinsInPath).sum
            case _ => node.children.map(countJoinsInPath).sum
          }
        }

        var finalCost = 0L

        def setJoinCosts(node: SparkPlan, shouldSetCost: Boolean, depth: Int = 0): Long = {
          if (depth > 20) { // Add a depth limit to prevent infinite recursion
            return 0L
          }

          if (shouldSetCost && node.getTagValue(new TreeNodeTag[AnyVal]("cost")).isDefined) {
            node.getTagValue(new TreeNodeTag[AnyVal]("cost")).get match {
              case l: Long => l
              case d: Double => d.toLong
              case _ => 0L
            }
          } else {
            node match {
              case _: BroadcastHashJoinExecTransformerBase | _: ShuffledHashJoinExecTransformerBase | _: SortMergeJoinExecTransformerBase | _: BroadcastHashJoinExec | _: ShuffledHashJoinExec | _: SortMergeJoinExec => {
                var buildcost = 0L
                var probecost = 0L
                val logicalPlan = node.logicalLink.get
                val stats = logicalPlan.stats
                var estimatedRow = 0L
                join match {
                  case smj: SortMergeJoinExec =>
                    val (leftPlan, rightPlan) = (smj.left.logicalLink.get, smj.right.logicalLink.get)
                    val leftStats = leftPlan.stats
                    val rightStats = rightPlan.stats
                    val joinType = smj.joinType match {
                      case ExistenceJoin(_) => LeftSemi
                      case other => JoinType(other.toString)
                    }
                    val dummyJoin = Join(
                      leftPlan,
                      rightPlan,
                      joinType,
                      smj.condition,
                      JoinHint.NONE
                    )
                    estimatedRow = JoinEstimation(dummyJoin).estimate.flatMap(_.rowCount).map(_.toLong).getOrElse(0L)
                    //println("row count is" + estimatedRow)
                    buildcost = smj.left.logicalLink.map(_.stats.sizeInBytes.toLong).getOrElse(0L)
                    probecost = smj.right.logicalLink.map(_.stats.sizeInBytes.toLong).getOrElse(0L)
                  case shj: ShuffledHashJoinExec =>
                    val (leftPlan, rightPlan) = (shj.left.logicalLink.get, shj.right.logicalLink.get)
                    val leftStats = leftPlan.stats
                    val rightStats = rightPlan.stats
                    // Handle ExistenceJoin and other join types
                    val joinType = shj.joinType match {
                      case ExistenceJoin(_) => LeftSemi
                      case other => JoinType(other.toString)
                    }
                    val dummyJoin = Join(
                      leftPlan,
                      rightPlan,
                      joinType,
                      shj.condition,
                      JoinHint.NONE
                    )
                    estimatedRow = JoinEstimation(dummyJoin).estimate.flatMap(_.rowCount).map(_.toLong).getOrElse(0L)
                    println("row count is" + estimatedRow)
                    shj.buildSide match {
                      case BuildLeft =>
                        buildcost = shj.left.logicalLink.map(_.stats.sizeInBytes.toLong).getOrElse(0L)
                        probecost = shj.right.logicalLink.map(_.stats.sizeInBytes.toLong).getOrElse(0L)
                      case BuildRight =>
                        buildcost = shj.right.logicalLink.map(_.stats.sizeInBytes.toLong).getOrElse(0L)
                        probecost = shj.left.logicalLink.map(_.stats.sizeInBytes.toLong).getOrElse(0L)
                    }
                  case bhj: BroadcastHashJoinExec =>
                    val (leftPlan, rightPlan) = (bhj.left.logicalLink.get, bhj.right.logicalLink.get)
                    val leftStats = leftPlan.stats
                    val rightStats = rightPlan.stats
                    val joinType = bhj.joinType match {
                      case ExistenceJoin(_) => LeftSemi
                      case other => JoinType(other.toString)
                    }
                    val dummyJoin = Join(
                      leftPlan,
                      rightPlan,
                      joinType,
                      bhj.condition,
                      JoinHint.NONE
                    )
                    estimatedRow = JoinEstimation(dummyJoin).estimate.flatMap(_.rowCount).map(_.toLong).getOrElse(0L)
                    //println("row count is" + estimatedRow)
                    bhj.buildSide match {
                      case BuildLeft =>
                        buildcost = bhj.left.logicalLink.map(_.stats.sizeInBytes.toLong).getOrElse(0L)
                        probecost = bhj.right.logicalLink.map(_.stats.sizeInBytes.toLong).getOrElse(0L)
                      case BuildRight =>
                        buildcost = bhj.right.logicalLink.map(_.stats.sizeInBytes.toLong).getOrElse(0L)
                        probecost = bhj.left.logicalLink.map(_.stats.sizeInBytes.toLong).getOrElse(0L)
                    }
                }
                val calculatedCost = -4 * math.log(buildcost + 1) + 22 * math.log(probecost + 1) + {
                  val firstTwoDigits = estimatedRow.toString.take(2).toLong
                  if (firstTwoDigits < 10) firstTwoDigits else firstTwoDigits / 10
                }
                node.setTagValue(new TreeNodeTag[AnyVal]("cost"), calculatedCost)
                calculatedCost.toLong
              }
              case _ => node.children.map(setJoinCosts(_, shouldSetCost, depth + 1)).sum
            }
          }
        }

        val joinCount = countJoinsInPath(join)
        if (joinCount >= 4) {
          finalCost = setJoinCosts(join, shouldSetCost = true)
        }
        if (finalCost > 100L) finalCost = 100L
        else if (finalCost < 1L) finalCost = 1L
        finalCost

      case _: RemoveFilter.NoopFilter =>
        // To make planner choose the tree that has applied rule PushFilterToScan.
        0L
      case nativeProject: ProjectExecTransformer =>
        nativeProject.child match {
          case _: BroadcastHashJoinExec | _: SortMergeJoinExec | _: BroadcastHashJoinExecTransformerBase | _: SortMergeJoinExecTransformerBase | _: ShuffledHashJoinExec | _:ShuffledHashJoinExecTransformerBase => {
            println("Join + Project Detected")
            100000L
          }
          case _ => 10L
        }
      case nativeAgg: HashAggregateExecBaseTransformer =>
        def countJoinsInPath(node: SparkPlan): Int = {
          node match {
            case _: BroadcastHashJoinExecTransformerBase | _: ShuffledHashJoinExecTransformerBase | _: SortMergeJoinExecTransformerBase | _: BroadcastHashJoinExec | _: ShuffledHashJoinExec | _: SortMergeJoinExec =>
              1 + node.children.map(countJoinsInPath).sum
            case _ => node.children.map(countJoinsInPath).sum
          }
        }

        val joinCount = countJoinsInPath(nativeAgg)
        if (joinCount >= 4) {
          println(s"Multiple joins ($joinCount) detected in the path of HashAggregateExecBaseTransformer")
          100000L
        } else {
          // Default cost for HashAggregateExecBaseTransformer
          10L
        }

      case ProjectExec(projectList, _) if projectList.forall(isCheapExpression) =>
        // Make trivial ProjectExec has the same cost as ProjectExecTransform to reduce unnecessary
        // c2r and r2c.
        10L
      case exec @ (_: ColumnarToRowExec | _: RowToColumnarExec) => {
        //println(s"Node: ${node.nodeName} (${node.getClass.getSimpleName})")
        var cost = 0L
        node.children.zipWithIndex.foreach { case (child, index) =>
          child.logicalLink.foreach { childLogicalPlan =>
            //println(s"Child $index Logical Plan: ${childLogicalPlan.getClass.getSimpleName}")
            val calculatedCost = childLogicalPlan.stats.sizeInBytes * 1000
            if (calculatedCost > 1000L) cost = 1000L
            else if (calculatedCost < 1L) cost = 1L
            else cost = calculatedCost.toLong
            //println("final cost of vanilla R2C/C2R " + cost)
          }
        }
        cost
      }

      case ColumnarToRowLike(_) => 10L
      case RowToColumnarLike(_) => 10L
      case p if PlanUtil.isGlutenColumnarOp(p) => 10L
      case p if PlanUtil.isVanillaColumnarOp(p) => 1000L
      // Other row ops. Usually a vanilla row op.
      case _ => 1000L
    }
  }

  private def isCheapExpression(ne: NamedExpression): Boolean = ne match {
    case Alias(_: Attribute, _) => true
    case _: Attribute => true
    case _ => false
  }
}
