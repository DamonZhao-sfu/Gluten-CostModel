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

import org.apache.gluten.execution.{BroadcastHashJoinExecTransformerBase, ColumnarToRowExecBase, RowToColumnarExecBase}
import org.apache.gluten.extension.columnar.enumerated.RemoveFilter
import org.apache.gluten.extension.columnar.transition.{ColumnarToRowLike, RowToColumnarLike}
import org.apache.gluten.utils.PlanUtil
import org.apache.spark.sql.catalyst.optimizer.{BuildLeft, BuildRight}
import org.apache.spark.sql.catalyst.expressions.{Alias, Attribute, NamedExpression}
import org.apache.spark.sql.execution.joins.{BroadcastHashJoinExec}
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
        println(s"Node: ${node.nodeName} (${node.getClass.getSimpleName})")
        var cost = 0L
        node.children.zipWithIndex.foreach { case (child, index) =>
          child.logicalLink.foreach { childLogicalPlan =>
            println(s"Child $index Logical Plan: ${childLogicalPlan.getClass.getSimpleName}")
            val calculatedCost = (childLogicalPlan.stats.sizeInBytes * BigInt(13)) / BigInt(10)
            cost = if (calculatedCost >= Long.MaxValue || calculatedCost < 1L ) 1L else calculatedCost.toLong
            //printStats(s"    Child $index", childLogicalPlan.stats)
          }
        }
        println("cost of Native C2R " + cost)
        cost
      }
      // RowToColumnar Weight = w2 = 10
      case rowToColumnarExecBase: RowToColumnarExecBase => {
        println(s"Node: ${node.nodeName} (${node.getClass.getSimpleName})")
        var cost = 0L
        node.children.zipWithIndex.foreach { case (child, index) =>
          child.logicalLink.foreach { childLogicalPlan =>
            println(s"Child $index Logical Plan: ${childLogicalPlan.getClass.getSimpleName}")
            val calculatedCost = childLogicalPlan.stats.sizeInBytes * 10
            cost = if (calculatedCost >= Long.MaxValue || calculatedCost < 1L ) 1L else calculatedCost.toLong
          }
        }
        println("cost of Native R2C " + cost)
        cost
      }
      case join: BroadcastHashJoinExecTransformerBase => {
        var buildcost = 0L
        var probecost = 0L
        val logicalPlan = node.logicalLink.get
        val stats = logicalPlan.stats
        val estimatedOutputRow = stats.rowCount
        join.joinBuildSide match {
          case BuildLeft =>
            // Get the left child's logical plan and stats
            buildcost += join.left.logicalLink.map { leftLogicalPlan =>
              val leftStats = leftLogicalPlan.stats
              // Use leftStats.sizeInBytes or leftStats.rowCount as needed
              println(s"Left table size: ${leftStats.sizeInBytes}")
              println(s"Left table row count: ${leftStats.rowCount}")
              leftStats.sizeInBytes.toLong // Convert to Long explicitly
            }.getOrElse(0L) // Default to 0L if logical link is not available

            probecost += join.right.logicalLink.map { rightLogicalPlan =>
              val rightStats = rightLogicalPlan.stats
              // Use rightStats.sizeInBytes or rightStats.rowCount as needed
              println(s"Right table size: ${rightStats.sizeInBytes}")
              println(s"Right table row count: ${rightStats.rowCount}")
              rightStats.sizeInBytes.toLong // or use this value for cost calculation
            }.getOrElse(0L)

          case BuildRight =>
            // Similar logic for BuildRight if needed
            // build table
            buildcost += join.right.logicalLink.map { rightLogicalPlan =>
              val rightStats = rightLogicalPlan.stats
              // Use rightStats.sizeInBytes or rightStats.rowCount as needed
              println(s"Right table size: ${rightStats.sizeInBytes}")
              println(s"Right table row count: ${rightStats.rowCount}")
              rightStats.sizeInBytes.toLong // or use this value for cost calculation
            }.getOrElse(0L) // Default to 0L if logical link is not available

            probecost += join.left.logicalLink.map { leftLogicalPlan =>
              val leftStats = leftLogicalPlan.stats
              // Use leftStats.sizeInBytes or leftStats.rowCount as needed
              println(s"Left table size: ${leftStats.sizeInBytes}")
              println(s"Left table row count: ${leftStats.rowCount}")
              leftStats.sizeInBytes.toLong // Convert to Long explicitly
            }.getOrElse(0L) // Default to 0L if logical link is not available
        }
        val calculatedCost = 0.0028334613454632594 * buildcost + 0.0004203502149754413 * probecost + 11.579
        val finalCost = if (calculatedCost < 0 || calculatedCost > Long.MaxValue) 10L else calculatedCost.toLong
        println("final cost of native BHJ is " + finalCost)
        finalCost
      }

      case vanillaJoin: BroadcastHashJoinExec => {
        var buildcost = 0L
        var probecost = 0L
        val logicalPlan = node.logicalLink.get
        val stats = logicalPlan.stats
        val estimatedOutputRow = stats.rowCount
        vanillaJoin match {
          case bhj: BroadcastHashJoinExec => bhj.buildSide match {
            case BuildLeft=>
              buildcost = bhj.left.logicalLink.map { leftLogicalPlan =>
                val leftStats = leftLogicalPlan.stats
                // Use leftStats.sizeInBytes or leftStats.rowCount as needed
                println(s"Left table size: ${leftStats.sizeInBytes}")
                println(s"Left table row count: ${leftStats.rowCount}")
                leftStats.sizeInBytes.toLong // Convert to Long explicitly
              }.getOrElse(0L) // Default to 0L if logical link is not available
              probecost = bhj.right.logicalLink.map { rightLogicalPlan =>
                val rightStats = rightLogicalPlan.stats
                // Use rightStats.sizeInBytes or rightStats.rowCount as needed
                println(s"Right table size: ${rightStats.sizeInBytes}")
                println(s"Right table row count: ${rightStats.rowCount}")
                rightStats.sizeInBytes.toLong // or use this value for cost calculation
              }.getOrElse(0L)

            case BuildRight=>
              buildcost = bhj.right.logicalLink.map { rightLogicalPlan =>
                val rightStats = rightLogicalPlan.stats
                // Use rightStats.sizeInBytes or rightStats.rowCount as needed
                println(s"Right table size: ${rightStats.sizeInBytes}")
                println(s"Right table row count: ${rightStats.rowCount}")
                rightStats.sizeInBytes.toLong // or use this value for cost calculation
              }.getOrElse(0L) // Default to 0L if logical link is not available

              probecost = bhj.left.logicalLink.map { leftLogicalPlan =>
                val leftStats = leftLogicalPlan.stats
                // Use leftStats.sizeInBytes or leftStats.rowCount as needed
                println(s"Left table size: ${leftStats.sizeInBytes}")
                println(s"Left table row count: ${leftStats.rowCount}")
                leftStats.sizeInBytes.toLong // Convert to Long explicitly
              }.getOrElse(0L) // Default to 0L if logical link is not available
          }
        }
        val calculatedCost = -217.6308 + -4.2935 * math.log(buildcost + 1) + 22.3983 * math.log(probecost + 1)
        val finalCost = if (calculatedCost < 0 || calculatedCost > Long.MaxValue) 10L else calculatedCost.toLong
        println("final cost of vanilla join is " + finalCost)
        finalCost
      }
      
      case _: RemoveFilter.NoopFilter =>
        // To make planner choose the tree that has applied rule PushFilterToScan.
        0L
      case ProjectExec(projectList, _) if projectList.forall(isCheapExpression) =>
        // Make trivial ProjectExec has the same cost as ProjectExecTransform to reduce unnecessary
        // c2r and r2c.
        10L
      case exec @ (_: ColumnarToRowExec | _: RowToColumnarExec) => {
        println(s"Node: ${node.nodeName} (${node.getClass.getSimpleName})")
        var cost = 0L
        node.children.zipWithIndex.foreach { case (child, index) =>
          child.logicalLink.foreach { childLogicalPlan =>
            println(s"Child $index Logical Plan: ${childLogicalPlan.getClass.getSimpleName}")
            val calculatedCost = childLogicalPlan.stats.sizeInBytes * 1000
            cost = if (calculatedCost >= Long.MaxValue || calculatedCost <0 ) 10L else calculatedCost.toLong
          }
        }
        println("cost of Vanilla R2C/C2R " + cost)
        cost
      }
      case ColumnarToRowLike(_) => 10L
      case RowToColumnarLike(_) => 10L
      case p if PlanUtil.isGlutenColumnarOp(p) => 10L
      case p if PlanUtil.isVanillaColumnarOp(p) => 1000L
      // Other row ops. Usually a vanilla row op.
      case _ => 1L
    }
  }

  private def isCheapExpression(ne: NamedExpression): Boolean = ne match {
    case Alias(_: Attribute, _) => true
    case _: Attribute => true
    case _ => false
  }
}
