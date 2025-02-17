/*
 * Copyright (c) 2022-2025, NVIDIA CORPORATION.
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
{"spark": "320"}
{"spark": "321"}
{"spark": "321cdh"}
{"spark": "322"}
{"spark": "323"}
{"spark": "324"}
{"spark": "330"}
{"spark": "330cdh"}
{"spark": "331"}
{"spark": "332"}
{"spark": "332cdh"}
{"spark": "333"}
{"spark": "334"}
{"spark": "340"}
{"spark": "341"}
{"spark": "342"}
{"spark": "343"}
{"spark": "344"}
{"spark": "350"}
{"spark": "351"}
{"spark": "352"}
{"spark": "353"}
{"spark": "354"}
{"spark": "400"}
spark-rapids-shim-json-lines ***/
package com.nvidia.spark.rapids.shims

import com.nvidia.spark.rapids.SparkShims
import org.apache.hadoop.fs.FileStatus

import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.NamedExpression
import org.apache.spark.sql.catalyst.plans.physical.BroadcastMode
import org.apache.spark.sql.execution.SparkPlan
import org.apache.spark.sql.execution.adaptive.{BroadcastQueryStageExec, ShuffleQueryStageExec}
import org.apache.spark.sql.execution.datasources.PartitioningAwareFileIndex
import org.apache.spark.sql.execution.exchange.ReusedExchangeExec
import org.apache.spark.sql.execution.python.WindowInPandasExec

/**
 * Shim methods that can be compiled with every supported 3.2.0+ except Databricks versions
 */
trait Spark320PlusNonDBShims extends SparkShims {

  override final def broadcastModeTransform(mode: BroadcastMode, rows: Array[InternalRow]): Any =
    mode.transform(rows)

  override final def newBroadcastQueryStageExec(
      old: BroadcastQueryStageExec,
      newPlan: SparkPlan): BroadcastQueryStageExec =
    BroadcastQueryStageExec(old.id, newPlan, old._canonicalized)

  override final def filesFromFileIndex(fileIndex: PartitioningAwareFileIndex): Seq[FileStatus] = {
    fileIndex.allFiles()
  }

  def getWindowExpressions(winPy: WindowInPandasExec): Seq[NamedExpression] = winPy.windowExpression

  /**
   * Case class ShuffleQueryStageExec holds an additional field shuffleOrigin
   * affecting the unapply method signature
   */
  override def reusedExchangeExecPfn: PartialFunction[SparkPlan, ReusedExchangeExec] = {
    case ShuffleQueryStageExec(_, e: ReusedExchangeExec, _) => e
    case BroadcastQueryStageExec(_, e: ReusedExchangeExec, _) => e
  }
}
