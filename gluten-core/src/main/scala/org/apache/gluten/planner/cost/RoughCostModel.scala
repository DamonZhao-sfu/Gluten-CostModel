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

import org.apache.gluten.execution.{BroadcastHashJoinExecTransformerBase, ColumnarToRowExecBase, HashAggregateExecBaseTransformer, ProjectExecTransformer, RowToColumnarExecBase, ShuffledHashJoinExecTransformerBase}
import org.apache.gluten.extension.columnar.enumerated.RemoveFilter
import org.apache.gluten.extension.columnar.transition.{ColumnarToRowLike, RowToColumnarLike}
import org.apache.gluten.utils.PlanUtil
import org.apache.spark.sql.catalyst.optimizer.{BuildLeft, BuildRight}
import org.apache.spark.sql.catalyst.expressions.{Alias, Attribute, NamedExpression}
import org.apache.spark.sql.catalyst.plans.{ExistenceJoin, JoinType, LeftSemi}
import org.apache.spark.sql.catalyst.plans.logical.statsEstimation.JoinEstimation
import org.apache.spark.sql.catalyst.plans.logical.{Join, JoinHint}
import org.apache.spark.sql.catalyst.trees.TreeNodeTag
import org.apache.spark.sql.execution.joins.{BroadcastHashJoinExec, ShuffledHashJoinExec}
import org.apache.spark.sql.execution.{ColumnarToRowExec, ProjectExec, RowToColumnarExec, SparkPlan}

class RoughCostModel extends LongCostModel {
  private final val MemReadBandwidth: Long = 130*1024*1024*1024
  private final val MemWriteBandwidth: Long = 160*1024*1024*1024
  private final val CacheLineSize: Long = 64
  private final val NetworkBandwidth: Long = 1.25*1024*1024*1024
  private final val L1CacheMissRate: Double = 0.15
  private final val L2CacheMissRate: Double = 0.40
  private final val L3CacheMissRate: Double = 0.40
  private final val L1ReadBandwidth: Long = 3311*1024*1024*1024
  private final val L2ReadBandwidth: Long = 2289*1024*1024*1024
  private final val L3ReadBandwidth: Long = 490*1024*1024*1024
  private final val L1WriteBandwidth: Long = 3109*1024*1024*1024
  private final val L2WriteBandwidth: Long = 2278*1024*1024*1024
  private final val L3WriteBandwidth: Long = 354*1024*1024*1024
  private final val L1Latency: Double = 1.4 * 1e-9
  private final val L2Latency: Double = 7.5 * 1e-9
  private final val L3Latency: Double = 84.2 * 1e-9
  private final val MemLatency: Double = 130 * 1e-9
  private final val L1CacheSize: Double = 32*1024
  private final val L2CacheSize: Double = 1024*1024
  private final val L3CacheSize: Double = 16.5*1024*1024
  private final val MemSize: Double = 187*1024*1024*1024
  private final val parallelism: Int = 176


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
      case _: BroadcastHashJoinExecTransformerBase | _: ShuffledHashJoinExecTransformerBase | _: BroadcastHashJoinExec | _: ShuffledHashJoinExec =>
        true
    }
  }

  override def selfLongCostOf(node: SparkPlan): Long = {
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
      case join@(_: BroadcastHashJoinExecTransformerBase | _: ShuffledHashJoinExecTransformerBase) => {
        {
          var buildSize = 0L
          var probeSize = 0L
          var finalCost = 0L
          val logicalPlan = node.logicalLink.get
          val stats = logicalPlan.stats
          var maxNdv = 0L
          var leftNdv = 0L
          var rightNdv = 0L
          var probeNdv = 0L
          var networkCost = 0L

          var estimatedOutputRow = stats.rowCount.map(_.toLong).getOrElse(0L)
          def getDistinctCount(attr: Attribute, stats: Statistics): BigInt = {
            stats.attributeStats.get(attr).flatMap(_.distinctCount).getOrElse(
              stats.rowCount.getOrElse(BigInt(0))
            )
          }
          join match {
            case shj: ShuffledHashJoinExecTransformerBase => {
              shj.joinBuildSide match {
                case BuildLeft =>
                  // Get the left child's logical plan and stats
                  val (buildcostTemp, leftNdvTemp) = shj.left.logicalLink.map { leftLogicalPlan =>
                    val leftStats = leftLogicalPlan.stats
                    val leftNdv = shj.leftKeys.collect { case attr: Attribute => getDistinctCount(attr, leftStats) }.max
                    (leftStats.sizeInBytes.toLong, leftNdv)
                  }.getOrElse((0L, BigInt(0)))
                  buildSize += buildcostTemp
                  leftNdv = leftNdvTemp.toLong

                  val (probecostTemp, rightNdvTemp) = shj.right.logicalLink.map { rightLogicalPlan =>
                    val rightStats = rightLogicalPlan.stats
                    val rightNdv = shj.leftKeys.collect { case attr: Attribute => getDistinctCount(attr, rightStats) }.max
                    (rightStats.sizeInBytes.toLong, rightNdv)
                  }.getOrElse((0L, BigInt(0)))
                  probeSize += probecostTemp
                  rightNdv = rightNdvTemp.toLong
                  probeNdv = rightNdv

                case BuildRight =>
                  // Similar logic for BuildRight
                  val (buildcostTemp, rightNdvTemp) = shj.right.logicalLink.map { rightLogicalPlan =>
                    val rightStats = rightLogicalPlan.stats
                    val rightNdv = shj.leftKeys.collect { case attr: Attribute => getDistinctCount(attr, rightStats) }.max
                    (rightStats.sizeInBytes.toLong, rightNdv)
                  }.getOrElse((0L, BigInt(0)))
                  buildSize += buildcostTemp
                  rightNdv = rightNdvTemp.toLong

                  val (probecostTemp, leftNdvTemp) = shj.left.logicalLink.map { leftLogicalPlan =>
                    val leftStats = leftLogicalPlan.stats
                    val leftNdv = shj.leftKeys.collect { case attr: Attribute => getDistinctCount(attr, leftStats) }.max
                    (leftStats.sizeInBytes.toLong, leftNdv)
                  }.getOrElse((0L, BigInt(0)))
                  probeSize += probecostTemp
                  leftNdv = leftNdvTemp.toLong
                  probeNdv = leftNdv
                  maxNdv = Math.max(leftNdv, rightNdv)
                }
                networkCost = 1.0 * (parallelism) * (buildSize / NetworkBandwidth)
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
                    val (buildcostTemp, leftNdvTemp) = bhj.left.logicalLink.map { leftLogicalPlan =>
                      val leftStats = leftLogicalPlan.stats
                      val leftNdv = bhj.leftKeys.collect { case attr: Attribute => getDistinctCount(attr, leftStats) }.max
                      (leftStats.sizeInBytes.toLong, leftNdv)
                    }.getOrElse((0L, BigInt(0)))
                    buildSize += buildcostTemp
                    leftNdv = leftNdvTemp.toLong

                    val (probecostTemp, rightNdvTemp) = bhj.right.logicalLink.map { rightLogicalPlan =>
                      val rightStats = rightLogicalPlan.stats
                      val rightNdv = bhj.leftKeys.collect { case attr: Attribute => getDistinctCount(attr, rightStats) }.max
                      (rightStats.sizeInBytes.toLong, rightNdv)
                    }.getOrElse((0L, BigInt(0)))
                    probeSize += probecostTemp
                    rightNdv = rightNdvTemp.toLong
                    probeNdv = rightNdv
                  case BuildRight =>
                    // Similar logic for BuildRight if needed
                    // build table
                    val (buildcostTemp, rightNdvTemp) = bhj.right.logicalLink.map { rightLogicalPlan =>
                      val rightStats = rightLogicalPlan.stats
                      val rightNdv = bhj.leftKeys.collect { case attr: Attribute => getDistinctCount(attr, rightStats) }.max
                      (rightStats.sizeInBytes.toLong, rightNdv)
                    }.getOrElse((0L, BigInt(0)))
                    buildSize += buildcostTemp
                    rightNdv = rightNdvTemp.toLong

                    val (probecostTemp, leftNdvTemp) = bhj.left.logicalLink.map { leftLogicalPlan =>
                      val leftStats = leftLogicalPlan.stats
                      val leftNdv = bhj.leftKeys.collect { case attr: Attribute => getDistinctCount(attr, leftStats) }.max
                      (leftStats.sizeInBytes.toLong, leftNdv)
                    }.getOrElse((0L, BigInt(0)))
                    probeSize += probecostTemp
                    leftNdv = leftNdvTemp.toLong
                    buildNdv = leftNdv
                    maxNdv = Math.max(leftNdv, rightNdv)
                }
                networkCost = 1.0 * (buildSize + probeSize) / NetworkBandwidth
                maxNdv = Math.max(leftNdv, rightNdv)
              }
            }
            if (maxNdv == 0) maxNdv = 1
            estimatedOutputRow = buildSize * probeSize / maxNdv
            val buildCost = buildSize / MemReadBandwidth * 1.0
            val probeCost = 0L
            if (buildSize < L1CacheSize) {
              probeCost = 1.0 * (probeSize / MemReadBandwidth)
            } else if (buildSize < L2CacheSize) {
              probeCost = 1.0 * (probeSize / MemReadBandwidth + Math.min(1, L1CacheSize/buildSize) * (probeNdv * CacheLineSize) / L2ReadBandwidth)
            } else if (buildSize < L3CacheSize) {
              probeCost = 1.0 * (probeSize / MemReadBandwidth + Math.min(1, L2CacheSize/buildSize) * (probeNdv * CacheLineSize) / L3ReadBandwidth)
            } else {
              probeCost = 1.0 * (probeSize / MemReadBandwidth + Math.min(1, L3CacheSize/buildSize) * (probeNdv * CacheLineSize) / MemReadBandwidth)
            }

            if (calculatedCost.compareTo(1000L) > 0) finalCost = 1000L
            else if (calculatedCost.compareTo(10L) < 0) finalCost = 10L
            else finalCost = calculatedCost
            finalCost
          }
        }
        case join@(_: BroadcastHashJoinExec | _: ShuffledHashJoinExec) =>
        def countJoinsInPath(node: SparkPlan): Int = {
          node match {
            case _: BroadcastHashJoinExecTransformerBase | _: ShuffledHashJoinExecTransformerBase  | _: BroadcastHashJoinExec | _: ShuffledHashJoinExec =>
              1 + node.children.map(countJoinsInPath).sum
            case _ => node.children.map(countJoinsInPath).sum
          }
        }

        var finalCost = 0L

        def setJoinCosts(node: SparkPlan, shouldSetCost: Boolean, depth: Int = 0): Long = {
          if (depth > 20) { // Add a depth limit to prevent infinite recursion
            return 0L
          }
          val calculatedCost = 0L
          if (shouldSetCost && node.getTagValue(new TreeNodeTag[AnyVal]("cost")).isDefined) {
            node.getTagValue(new TreeNodeTag[AnyVal]("cost")).get match {
              case l: Long => l
              case d: Double => d.toLong
              case _ => 0L
            }
          } else {
            node match {
              case _: BroadcastHashJoinExecTransformerBase | _: ShuffledHashJoinExecTransformerBase  | _: BroadcastHashJoinExec | _: ShuffledHashJoinExec => {
                var buildsize = 0L
                var probesize = 0L
                val logicalPlan = node.logicalLink.get
                val stats = logicalPlan.stats
                var estimatedRow = 0L
                join match {
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
                    shj.buildSide match {
                      case BuildLeft =>
                        buildsize = shj.left.logicalLink.map(_.stats.sizeInBytes.toLong).getOrElse(0L)
                        probesize = shj.right.logicalLink.map(_.stats.sizeInBytes.toLong).getOrElse(0L)
                      case BuildRight =>
                        buildsize = shj.right.logicalLink.map(_.stats.sizeInBytes.toLong).getOrElse(0L)
                        probesize = shj.left.logicalLink.map(_.stats.sizeInBytes.toLong).getOrElse(0L)
                    }
                    calculatedCost = buildsize
                    
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
                        buildsize = bhj.left.logicalLink.map(_.stats.sizeInBytes.toLong).getOrElse(0L)
                        probesize = bhj.right.logicalLink.map(_.stats.sizeInBytes.toLong).getOrElse(0L)
                      case BuildRight =>
                        buildsize = bhj.right.logicalLink.map(_.stats.sizeInBytes.toLong).getOrElse(0L)
                        probesize = bhj.left.logicalLink.map(_.stats.sizeInBytes.toLong).getOrElse(0L)
                    }


                }

                //val calculatedCost = -4 * math.log(buildcost + 1) + 22 * math.log(probecost + 1) + {
                //  val firstTwoDigits = estimatedRow.toString.take(2).toLong
                //  if (firstTwoDigits < 10) firstTwoDigits else firstTwoDigits / 10
                //}
                
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
        if (finalCost > 1000L) finalCost = 1000L
        if (finalCost < 1L) finalCost = 1L
        finalCost

        case _: RemoveFilter.NoopFilter =>
          // To make planner choose the tree that has applied rule PushFilterToScan.
          0L
        case nativeProject: ProjectExecTransformer =>
          nativeProject.child match {
            case _: BroadcastHashJoinExec  | _: BroadcastHashJoinExecTransformerBase  | _: ShuffledHashJoinExec | _:ShuffledHashJoinExecTransformerBase => {
              println("Join + Project Detected")
              100000L
            }
            case _ => 10L
          }
        case nativeAgg: HashAggregateExecBaseTransformer =>
          def countJoinsInPath(node: SparkPlan): Int = {
            node match {
              case _: BroadcastHashJoinExecTransformerBase | _: ShuffledHashJoinExecTransformerBase  | _: BroadcastHashJoinExec | _: ShuffledHashJoinExec =>
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
        10L
        case exec @ (_: ColumnarToRowExec) => {
          var cost = 0L
          node.children.zipWithIndex.foreach { case (child, index) =>
            child.logicalLink.foreach { childLogicalPlan =>
              val calculatedCost = childLogicalPlan.stats.sizeInBytes / MemReadBandwidth
              if (calculatedCost > 1000L) cost = 1000L
              else if (calculatedCost < 1L) cost = 1L
              else cost = calculatedCost.toLong
            }
          }
          cost
        }

        case exec @ (_: RowToColumnarExec) => {
          var cost = 0L
          node.children.zipWithIndex.foreach { case (child, index) =>
            child.logicalLink.foreach { childLogicalPlan =>
              val calculatedCost = childLogicalPlan.stats.sizeInBytes / MemReadBandwidth
              if (calculatedCost > 1000L) cost = 1000L
              else if (calculatedCost < 1L) cost = 1L
              else cost = calculatedCost.toLong
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
