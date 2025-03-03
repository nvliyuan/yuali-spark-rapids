/*
 * Copyright (c) 2024, NVIDIA CORPORATION.
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

/*** spark-rapids-shim-json-lines
{"spark": "350db143"}
{"spark": "400"}
spark-rapids-shim-json-lines ***/
package com.nvidia.spark.rapids.shims

import com.google.common.base.Objects
import com.nvidia.spark.rapids.GpuScan

import org.apache.spark.SparkException
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.{AttributeReference, DynamicPruningExpression, Expression, Literal, SortOrder}
import org.apache.spark.sql.catalyst.plans.QueryPlan
import org.apache.spark.sql.catalyst.plans.physical.{KeyGroupedPartitioning, Partitioning, SinglePartition}
import org.apache.spark.sql.catalyst.util.{truncatedString, InternalRowComparableWrapper}
import org.apache.spark.sql.connector.catalog.Table
import org.apache.spark.sql.connector.read._
import org.apache.spark.sql.execution.datasources.rapids.DataSourceStrategyUtils
import org.apache.spark.sql.execution.datasources.v2.{DataSourceRDD, StoragePartitionJoinParams}

case class GpuBatchScanExec(
    output: Seq[AttributeReference],
    @transient scan: GpuScan,
    runtimeFilters: Seq[Expression] = Seq.empty,
    ordering: Option[Seq[SortOrder]] = None,
    @transient table: Table,
    spjParams: StoragePartitionJoinParams = StoragePartitionJoinParams()
  ) extends GpuBatchScanExecBase(scan, runtimeFilters) {

  @transient override lazy val batch: Batch = if (scan == null) null else scan.toBatch
  // TODO: unify the equal/hashCode implementation for all data source v2 query plans.
  override def equals(other: Any): Boolean = other match {
    case other: GpuBatchScanExec =>
      this.batch != null && this.batch == other.batch &&
        this.runtimeFilters == other.runtimeFilters &&
        this.spjParams == other.spjParams
    case _ =>
      false
  }

  override def hashCode(): Int = Objects.hashCode(batch, runtimeFilters)

  @transient override lazy val inputPartitions: Seq[InputPartition] =
    batch.planInputPartitions()

  @transient override protected lazy val filteredPartitions: Seq[Seq[InputPartition]] = {
    val dataSourceFilters = runtimeFilters.flatMap {
      case DynamicPruningExpression(e) => DataSourceStrategyUtils.translateRuntimeFilter(e)
      case _ => None
    }

    if (dataSourceFilters.nonEmpty) {
      val originalPartitioning = outputPartitioning

      // the cast is safe as runtime filters are only assigned if the scan can be filtered
      val filterableScan = scan.asInstanceOf[SupportsRuntimeV2Filtering]
      filterableScan.filter(dataSourceFilters.toArray)

      // call toBatch again to get filtered partitions
      val newPartitions = scan.toBatch.planInputPartitions()

      originalPartitioning match {
        case p: KeyGroupedPartitioning =>
          if (newPartitions.exists(!_.isInstanceOf[HasPartitionKey])) {
            throw new SparkException("Data source must have preserved the original partitioning " +
              "during runtime filtering: not all partitions implement HasPartitionKey after " +
              "filtering")
          }

          val newPartitionValues = newPartitions.map(partition =>
            InternalRowComparableWrapper(partition.asInstanceOf[HasPartitionKey], p.expressions))
            .toSet
          val oldPartitionValues = p.partitionValues
            .map(partition => InternalRowComparableWrapper(partition, p.expressions)).toSet
          // We require the new number of partition values to be equal or less than the old number
          // of partition values here. In the case of less than, empty partitions will be added for
          // those missing values that are not present in the new input partitions.
          if (oldPartitionValues.size < newPartitionValues.size) {
            throw new SparkException("During runtime filtering, data source must either report " +
              "the same number of partition values, or a subset of partition values from the " +
              s"original. Before: ${oldPartitionValues.size} partition values. " +
              s"After: ${newPartitionValues.size} partition values")
          }

          if (!newPartitionValues.forall(oldPartitionValues.contains)) {
            throw new SparkException("During runtime filtering, data source must not report new " +
              "partition values that are not present in the original partitioning.")
          }
          groupPartitions(newPartitions)
            .map(_.groupedParts.map(_.parts)).getOrElse(Seq.empty)

        case _ =>
          // no validation is needed as the data source did not report any specific partitioning
          newPartitions.map(Seq(_))
      }

    } else {
      partitions
    }
  }

  override def outputPartitioning: Partitioning = {
    super.outputPartitioning match {
      case k: KeyGroupedPartitioning if spjParams.commonPartitionValues.isDefined =>
        // We allow duplicated partition values if
        // `spark.sql.sources.v2.bucketing.partiallyClusteredDistribution.enabled` is true
        val newPartValues = spjParams.commonPartitionValues.get.flatMap {
          case (partValue, numSplits) => Seq.fill(numSplits)(partValue)
        }
        val expressions = spjParams.joinKeyPositions match {
          case Some(projectionPositions) => projectionPositions.map(i => k.expressions(i))
          case _ => k.expressions
        }
        k.copy(expressions = expressions, numPartitions = newPartValues.length,
          partitionValues = newPartValues)
      case p => p
    }
  }

  override lazy val readerFactory: PartitionReaderFactory = batch.createReaderFactory()

  override lazy val inputRDD: RDD[InternalRow] = {
    scan.metrics = allMetrics
    val rdd = if (filteredPartitions.isEmpty && outputPartitioning == SinglePartition) {
      // return an empty RDD with 1 partition if dynamic filtering removed the only split
      sparkContext.parallelize(Array.empty[InternalRow], 1)
    } else {
      val finalPartitions = outputPartitioning match {
        case p: KeyGroupedPartitioning =>
          assert(spjParams.keyGroupedPartitioning.isDefined)
          val expressions = spjParams.keyGroupedPartitioning.get

          // Re-group the input partitions if we are projecting on a subset of join keys
          val (groupedPartitions, partExpressions) = spjParams.joinKeyPositions match {
            case Some(projectPositions) =>
              val projectedExpressions = projectPositions.map(i => expressions(i))
              val parts = filteredPartitions.flatten.groupBy(part => {
                val row = part.asInstanceOf[HasPartitionKey].partitionKey()
                val projectedRow = KeyGroupedPartitioning.project(
                  expressions, projectPositions, row)
                InternalRowComparableWrapper(projectedRow, projectedExpressions)
              }).map { case (wrapper, splits) => (wrapper.row, splits) }.toSeq
              (parts, projectedExpressions)
            case _ =>
              val groupedParts = filteredPartitions.map(splits => {
                assert(splits.nonEmpty && splits.head.isInstanceOf[HasPartitionKey])
                (splits.head.asInstanceOf[HasPartitionKey].partitionKey(), splits)
              })
              (groupedParts, expressions)
          }

          // When partially clustered, the input partitions are not grouped by partition
          // values. Here we'll need to check `commonPartitionValues` and decide how to group
          // and replicate splits within a partition.
          if (spjParams.commonPartitionValues.isDefined && spjParams.applyPartialClustering) {
            // A mapping from the common partition values to how many splits the partition
            // should contain.
            val commonPartValuesMap = spjParams.commonPartitionValues
                .get
                .map(t => (InternalRowComparableWrapper(t._1, partExpressions), t._2))
                .toMap
            val nestGroupedPartitions = groupedPartitions.map { case (partValue, splits) =>
              // `commonPartValuesMap` should contain the part value since it's the super set.
              val numSplits = commonPartValuesMap
                  .get(InternalRowComparableWrapper(partValue, partExpressions))
              assert(numSplits.isDefined, s"Partition value $partValue does not exist in " +
                  "common partition values from Spark plan")

              val newSplits = if (spjParams.replicatePartitions) {
                // We need to also replicate partitions according to the other side of join
                Seq.fill(numSplits.get)(splits)
              } else {
                // Not grouping by partition values: this could be the side with partially
                // clustered distribution. Because of dynamic filtering, we'll need to check if
                // the final number of splits of a partition is smaller than the original
                // number, and fill with empty splits if so. This is necessary so that both
                // sides of a join will have the same number of partitions & splits.
                splits.map(Seq(_)).padTo(numSplits.get, Seq.empty)
              }
              (InternalRowComparableWrapper(partValue, partExpressions), newSplits)
            }

            // Now fill missing partition keys with empty partitions
            val partitionMapping = nestGroupedPartitions.toMap
            spjParams.commonPartitionValues.get.flatMap {
              case (partValue, numSplits) =>
                // Use empty partition for those partition values that are not present.
                partitionMapping.getOrElse(
                  InternalRowComparableWrapper(partValue, partExpressions),
                  Seq.fill(numSplits)(Seq.empty))
            }
          } else {
            // either `commonPartitionValues` is not defined, or it is defined but
            // `applyPartialClustering` is false.
            val partitionMapping = groupedPartitions.map { case (partValue, splits) =>
              InternalRowComparableWrapper(partValue, partExpressions) -> splits
            }.toMap

            // In case `commonPartitionValues` is not defined (e.g., SPJ is not used), there
            // could exist duplicated partition values, as partition grouping is not done
            // at the beginning and postponed to this method. It is important to use unique
            // partition values here so that grouped partitions won't get duplicated.
            p.uniquePartitionValues.map { partValue =>
              // Use empty partition for those partition values that are not present
              partitionMapping.getOrElse(
                InternalRowComparableWrapper(partValue, partExpressions), Seq.empty)
            }
          }

        case _ => filteredPartitions
      }

      new DataSourceRDD(
        sparkContext, finalPartitions, readerFactory, supportsColumnar, customMetrics)
    }
    postDriverMetrics()
    rdd
  }

  override def keyGroupedPartitioning: Option[Seq[Expression]] =
    spjParams.keyGroupedPartitioning

  override def doCanonicalize(): GpuBatchScanExec = {
    this.copy(
      output = output.map(QueryPlan.normalizeExpressions(_, output)),
      runtimeFilters = QueryPlan.normalizePredicates(
        runtimeFilters.filterNot(_ == DynamicPruningExpression(Literal.TrueLiteral)),
        output))
  }

  override def simpleString(maxFields: Int): String = {
    val truncatedOutputString = truncatedString(output, "[", ", ", "]", maxFields)
    val runtimeFiltersString = s"RuntimeFilters: ${runtimeFilters.mkString("[", ",", "]")}"
    val result = s"$nodeName$truncatedOutputString ${scan.description()} $runtimeFiltersString"
    redact(result)
  }

  override def nodeName: String = {
    s"GpuBatchScan ${table.name()}".trim
  }
}
