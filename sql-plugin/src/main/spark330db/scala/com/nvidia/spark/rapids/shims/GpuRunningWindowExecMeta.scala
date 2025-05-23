/*
 * Copyright (c) 2021-2024, NVIDIA CORPORATION.
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
{"spark": "330db"}
{"spark": "332db"}
{"spark": "341db"}
{"spark": "350db143"}
spark-rapids-shim-json-lines ***/
package com.nvidia.spark.rapids.shims

import com.databricks.sql.execution.window.RunningWindowFunctionExec
import com.nvidia.spark.rapids.{DataFromReplacementRule, RapidsConf, RapidsMeta}
import com.nvidia.spark.rapids.window.GpuBaseWindowExecMeta

import org.apache.spark.sql.catalyst.expressions.{Expression, NamedExpression, SortOrder}

/**
 * GPU-based window-exec implementation, analogous to RunningWindowFunctionExec.
 */
class GpuRunningWindowExecMeta(runningWindowFunctionExec: RunningWindowFunctionExec,
    conf: RapidsConf,
    parent: Option[RapidsMeta[_, _, _]],
    rule: DataFromReplacementRule)
    extends GpuBaseWindowExecMeta[RunningWindowFunctionExec](runningWindowFunctionExec, conf,
      parent, rule) {

  override def getInputWindowExpressions: Seq[NamedExpression] =
    runningWindowFunctionExec.windowExpressionList
  override def getPartitionSpecs: Seq[Expression] = runningWindowFunctionExec.partitionSpec
  override def getOrderSpecs: Seq[SortOrder] = runningWindowFunctionExec.orderSpec
  override def getResultColumnsOnly: Boolean = true
}
