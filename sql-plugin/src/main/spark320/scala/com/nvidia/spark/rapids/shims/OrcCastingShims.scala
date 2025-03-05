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
{"spark": "322"}
{"spark": "323"}
{"spark": "324"}
{"spark": "330"}
{"spark": "330db"}
{"spark": "331"}
{"spark": "332"}
{"spark": "332db"}
{"spark": "333"}
{"spark": "334"}
{"spark": "340"}
{"spark": "341"}
{"spark": "341db"}
{"spark": "342"}
{"spark": "343"}
{"spark": "344"}
{"spark": "350"}
{"spark": "350db143"}
{"spark": "351"}
{"spark": "352"}
{"spark": "353"}
{"spark": "354"}
{"spark": "355"}
{"spark": "400"}
spark-rapids-shim-json-lines ***/
package com.nvidia.spark.rapids.shims

import ai.rapids.cudf.{ColumnView, DType}
import com.nvidia.spark.rapids.GpuOrcScan


object OrcCastingShims {

  def castIntegerToTimestamp(col: ColumnView, colType: DType): ColumnView = {
    // For spark >= 320 (except spark-321-cdh), they consider the integers in `col` as seconds
    GpuOrcScan.castIntegersToTimestamp(col, colType, DType.TIMESTAMP_SECONDS)
  }
}
