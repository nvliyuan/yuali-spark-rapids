This doc describes how to dump an Exec runtime meta and column batch data to a local directory,
and replay the Exec with dumped meta and data. When encountering a perf issue, then use this tool
can dump the runtime meta/data and replay. Because only dump one column batch, it will spend 
less time to collect NSYS and NCU information when replaying the dumped runtime meta/data.

# Dump a Exec meta and a column batch data

## compile Spark-Rapids jar
e.g.: compile for Spark330
```
mvn clean install -DskipTests -pl dist -am -DallowConventionalDistJar=true -Dbuildver=330 
```

## enable dump
e.g.: 
```
// specify the Exec for dumping, currently only supports `project` 
spark.conf.set("spark.rapids.sql.test.replay.exec.type", "project")

// specify the local dump directory, default value is "/tmp"
// should first create this directory
spark.conf.set("spark.rapids.sql.test.replay.exec.dumpDir", "/tmp/replay-exec")

// Only dump the column bach when executing time against it exceeds this threshold time in MS
spark.conf.set("spark.rapids.sql.test.replay.exec.threshold.timeMS", 100)

// If sepcified, only dump when the Exec SQL contains this filter pattern
// Default value is empty which means no filter
// This example means enable dumping when Exec SQL contains "get_json_object"
spark.conf.set("spark.rapids.sql.test.replay.exec.filter.include", "get_json_object")
```

## run a Spark job 
This dumping only happens when GPU Exec is running, so should enable Spark-Rapids.
After the job is done, check the dump path will have files like:
```
/tmp/replay-exec:
  - GpuTieredProject.meta      // this is serialized GPU Tiered Project case class  
  - cb_types.meta              // this is types for column batch
  - cb_data_101656570.parquet  // this is data for column batch
```

# Replay saved Exec runtime meta and data

## Set environment variable
```
export PLUGIN_JAR=path_to_spark_rapids_jar
export SPARK_HOME=path_to_spark_home_330
```

## replay command

### Project replay
```
$SPARK_HOME/bin/spark-submit \
  --class com.nvidia.spark.rapids.ProjectExecReplayer \
  --conf spark.rapids.sql.explain=ALL \
  --master local[*] \
  --jars ${PLUGIN_JAR} \
  ${PLUGIN_JAR} <path_to_saved_replay_dir>
```