/*
 * Copyright (c) 2023-2025, NVIDIA CORPORATION.
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
{"spark": "341db"}
{"spark": "350"}
{"spark": "350db143"}
{"spark": "351"}
{"spark": "352"}
{"spark": "353"}
{"spark": "354"}
{"spark": "400"}
spark-rapids-shim-json-lines ***/
package org.apache.spark.sql.hive.rapids.shims

import org.apache.hadoop.hive.ql.exec.UDF
import org.apache.hadoop.hive.ql.udf.generic.GenericUDF

import org.apache.spark.sql.hive.{HiveGenericUDF, HiveSimpleUDF}

trait CreateFunctions {
  def createFunction(a: HiveSimpleUDF): UDF = {
    a.funcWrapper.createFunction[UDF]()
  }

  def createFunction(a: HiveGenericUDF): GenericUDF = {
    a.funcWrapper.createFunction[GenericUDF]()
  }
}
