/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package shark.execution

import java.util.{HashMap => JavaHashMap, Properties, Map => JavaMap}

import scala.collection.JavaConversions._
import scala.collection.mutable.ArrayBuffer

import org.apache.hadoop.fs.{Path, PathFilter}
import org.apache.hadoop.hive.conf.HiveConf
import org.apache.hadoop.hive.ql.{Context, DriverContext}
import org.apache.hadoop.hive.ql.exec.{Task => HiveTask, Utilities}
import org.apache.hadoop.hive.ql.metadata.{Hive, Partition, Table => HiveTable}
import org.apache.hadoop.hive.ql.plan.TableDesc
import org.apache.hadoop.hive.ql.plan.api.StageType
import org.apache.hadoop.hive.serde.Constants;
import org.apache.hadoop.hive.serde2.Deserializer
import org.apache.hadoop.hive.serde2.objectinspector.{ObjectInspector, StructObjectInspector}
import org.apache.hadoop.io.Writable
import org.apache.hadoop.mapred.{FileInputFormat, InputFormat}

import org.apache.spark.broadcast.Broadcast
import org.apache.spark.rdd.RDD
import org.apache.spark.SerializableWritable
import org.apache.spark.storage.StorageLevel

import shark.{LogHelper, SharkEnv}
import shark.execution.serialization.KryoSerializer
import shark.memstore2._
import shark.util.HiveUtils


private[shark]
class SparkLoadWork(
    val databaseName: String,
    val tableName: String,
    val partSpecOpt: Option[JavaMap[String, String]],
    val commandType: SparkLoadWork.CommandTypes.Type,
    val storageLevel: StorageLevel,
    val pathFilter: Option[PathFilter])
  extends java.io.Serializable

object SparkLoadWork {
  object CommandTypes extends Enumeration {
    type Type = Value
    val OVERWRITE, INSERT, NEW_ENTRY = Value
  }
}

private[shark]
class SparkLoadTask extends HiveTask[SparkLoadWork] with Serializable with LogHelper {

  override def execute(driveContext: DriverContext): Int = {
    logDebug("Executing " + this.getClass.getName)

    val databaseName = work.databaseName
    val tableName = work.tableName
    val hiveTable = Hive.get(conf).getTable(databaseName, tableName)
    val oi = hiveTable.getDeserializer().getObjectInspector().asInstanceOf[StructObjectInspector]

    val hadoopReader = new HadoopTableReader(Utilities.getTableDesc(hiveTable), conf)

    work.partSpecOpt match {
      case Some(partSpec) => {
        loadPartitionedTable(
          hiveTable,
          partSpec,
          hadoopReader,
          work.pathFilter)
      }
      case None => {
        loadMemoryTable(
          hiveTable,
          hadoopReader,
          work.pathFilter)
      }
    }
    // Success!
    0
  }

  def transformAndMaterializeInput(
      inputRdd: RDD[_],
      serDeProps: Properties,
      storageLevel: StorageLevel,
      broadcastedHiveConf: Broadcast[SerializableWritable[HiveConf]],
      oi: StructObjectInspector) = {
    val statsAcc = SharkEnv.sc.accumulableCollection(ArrayBuffer[(Int, TablePartitionStats)]())
    val serializedOI = KryoSerializer.serialize(oi)
    val transformedRdd = inputRdd.mapPartitionsWithIndex { case (partIndex, partIter) =>
      val serde = new ColumnarSerDe
      serde.initialize(broadcastedHiveConf.value.value, serDeProps)
      val oi = KryoSerializer.deserialize[ObjectInspector](serializedOI)
      var builder: Writable = null
      partIter.foreach { row =>
        builder = serde.serialize(row.asInstanceOf[AnyRef], oi)
      }
      if (builder == null) {
        // Empty partition.
        statsAcc += Tuple2(partIndex, new TablePartitionStats(Array(), 0))
        Iterator(new TablePartition(0, Array()))
      } else {
        statsAcc += Tuple2(partIndex, builder.asInstanceOf[TablePartitionBuilder].stats)
        Iterator(builder.asInstanceOf[TablePartitionBuilder].build)
      }
    }
    transformedRdd.persist(storageLevel)
    transformedRdd.context.runJob(
      transformedRdd, (iter: Iterator[TablePartition]) => iter.foreach(_ => Unit))
    (transformedRdd, statsAcc.value)
  }

  def createMemoryTable(
      databaseName: String,
      tableName: String,
      preferredStorageLevel: StorageLevel,
      defaultDiskSerDe: String,
      tblProps: JavaMap[String, String]): MemoryTable = {
    val cacheMode = CacheType.fromString(tblProps.get("shark.cache"))
    val newMemoryTable = SharkEnv.memoryMetadataManager.createMemoryTable(
      databaseName, tableName, cacheMode, preferredStorageLevel, unifyView = true)
    newMemoryTable.diskSerDe = defaultDiskSerDe
    HiveUtils.alterSerdeInHive(
      tableName,
      partitionSpecOpt = None,
      classOf[ColumnarSerDe].getName,
      conf)
    newMemoryTable
  }

  def loadMemoryTable(
      hiveTable: HiveTable,
      hadoopReader: HadoopTableReader,
      pathFilter: Option[PathFilter]) {
    val databaseName = hiveTable.getDbName
    val tableName = hiveTable.getTableName
    val tblProps = hiveTable.getParameters
    val preferredStorageLevel = MemoryMetadataManager.getStorageLevelFromString(
      tblProps.get("shark.cache.storageLevel"))
    val memoryTable = work.commandType match {
      case SparkLoadWork.CommandTypes.NEW_ENTRY => {
        createMemoryTable(
          databaseName,
          tableName,
          preferredStorageLevel,
          hiveTable.getDeserializer.getClass.getName,
          tblProps)
      }
      case _ => {
        SharkEnv.memoryMetadataManager.getTable(databaseName, tableName) match {
          case Some(table: MemoryTable) => table
          case _ => {
            throw new Exception("Invalid state: cached table being updated doesn't exist.")
          }
        }
      }
    }
    val tableSchema = hiveTable.getSchema
    val serDe = Class.forName(memoryTable.diskSerDe).newInstance.asInstanceOf[Deserializer]
    serDe.initialize(conf, tableSchema)
    val inputRDD = hadoopReader.makeRDDForTable(
      hiveTable,
      pathFilter,
      serDe.getClass)
    val (tablePartitionRDD, tableStats) = transformAndMaterializeInput(
      inputRDD,
      tableSchema,
      work.storageLevel,
      hadoopReader.broadcastedHiveConf,
      serDe.getObjectInspector.asInstanceOf[StructObjectInspector])
    memoryTable.tableRDD = work.commandType match {
      case (SparkLoadWork.CommandTypes.OVERWRITE
        | SparkLoadWork.CommandTypes.NEW_ENTRY) => tablePartitionRDD
      case SparkLoadWork.CommandTypes.INSERT => {
        val unionedRDD = RDDUtils.unionAndFlatten(tablePartitionRDD, memoryTable.tableRDD)
        SharkEnv.memoryMetadataManager.getStats(databaseName, tableName ) match {
          case Some(previousStatsMap) => unionStatsMaps(tableStats, previousStatsMap)
          case None => Unit
        }
        unionedRDD
      }
    }
    SharkEnv.memoryMetadataManager.putStats(databaseName, tableName, tableStats.toMap)
  }

  def createPartitionedTable(
      databaseName: String,
      tableName: String,
      preferredStorageLevel: StorageLevel,
      defaultDiskSerDe: String,
      tblProps: JavaMap[String, String],
      partSpecs: JavaMap[String, String]): PartitionedMemoryTable = {
    val cacheMode = CacheType.fromString(tblProps.get("shark.cache"))
    val newPartitionedTable = SharkEnv.memoryMetadataManager.createPartitionedMemoryTable(
      databaseName,
      tableName,
      cacheMode,
      preferredStorageLevel,
      unifyView = true,
      tblProps)
    newPartitionedTable.diskSerDe = defaultDiskSerDe
    HiveUtils.alterSerdeInHive(
      tableName,
      Some(partSpecs),
      classOf[ColumnarSerDe].getName,
      conf)
    newPartitionedTable
  }

  def loadPartitionedTable(
      hiveTable: HiveTable,
      partSpecs: JavaMap[String, String],
      hadoopReader: HadoopTableReader,
      pathFilter: Option[PathFilter]) {
    // TODO(harvey): Multiple partition specs...
    val databaseName = hiveTable.getDbName
    val tableName = hiveTable.getTableName
    val tblProps = hiveTable.getParameters
    val preferredStorageLevel = MemoryMetadataManager.getStorageLevelFromString(
      tblProps.get("shark.cache.storageLevel"))
    val partitionedTable = work.commandType match {
      case SparkLoadWork.CommandTypes.NEW_ENTRY => {
        createPartitionedTable(
          databaseName,
          tableName,
          preferredStorageLevel,
          hiveTable.getDeserializer.getClass.getName,
          tblProps,
          partSpecs)
      }
      case _ => {
        SharkEnv.memoryMetadataManager.getTable(databaseName, tableName) match {
          case Some(table: PartitionedMemoryTable) => table
          case _ => {
            throw new Exception("Invalid state: cached table being updated doesn't exist.")
          }
        }
      }
    }
    val partCols = hiveTable.getPartCols.map(_.getName)
    val partitionKey = MemoryMetadataManager.makeHivePartitionKeyStr(partCols, partSpecs)
    val partition = db.getPartition(hiveTable, partSpecs, false /* forceCreate */)
    val partSerDe = Class.forName(partitionedTable.getDiskSerDe(partitionKey).getOrElse(
      partitionedTable.diskSerDe)).newInstance.asInstanceOf[Deserializer]
    val partSchema = partition.getSchema
    partSerDe.initialize(conf, partSchema)
    val unionOI = HiveUtils.makeUnionOIForPartitionedTable(partSchema, partSerDe)
    val inputRDD = hadoopReader.makeRDDForPartitionedTable(
      Map(partition -> partSerDe.getClass), pathFilter)
    val (tablePartitionRDD, tableStats) = transformAndMaterializeInput(
      inputRDD,
      addPartitionInfoToSerDeProps(partCols, new Properties(partition.getSchema)),
      preferredStorageLevel,
      hadoopReader.broadcastedHiveConf,
      unionOI)
    work.commandType match {
      case SparkLoadWork.CommandTypes.OVERWRITE | SparkLoadWork.CommandTypes.NEW_ENTRY => {
        partitionedTable.putPartition(partitionKey, tablePartitionRDD)
      }
      case SparkLoadWork.CommandTypes.INSERT => {
        val previousRDD = partitionedTable.getPartition(partitionKey) match {
          case Some(previousRDD) => {
            partitionedTable.updatePartition(
            partitionKey, RDDUtils.unionAndFlatten(tablePartitionRDD, previousRDD))
            // Note: these matches have to be separate, since an empty partition is represented by
            // an empty RDD. If it's already cached in memory, then
            // PartitionedMemoryTable#updatePartition() must be called.
            // Union stats for the previous RDD with the new RDD loaded.
            SharkEnv.memoryMetadataManager.getStats(databaseName, tableName ) match {
              case Some(previousStatsMap) => unionStatsMaps(tableStats, previousStatsMap)
              case None => Unit
            }
          }
          case None => partitionedTable.putPartition(partitionKey, tablePartitionRDD)
        }
      }
    }
    SharkEnv.memoryMetadataManager.putStats(databaseName, tableName, tableStats.toMap)
  }


  def unionStatsMaps(
      targetStatsMap: ArrayBuffer[(Int, TablePartitionStats)],
      otherStatsMap: Iterable[(Int, TablePartitionStats)]
    ): ArrayBuffer[(Int, TablePartitionStats)] = {
    val targetStatsMapSize = targetStatsMap.size
    for ((otherIndex, tableStats) <- otherStatsMap) {
      targetStatsMap.append((otherIndex + targetStatsMapSize, tableStats))
    }
    targetStatsMap
  }

  def addPartitionInfoToSerDeProps(
    partCols: Seq[String],
    serDeProps: Properties): Properties = {
    // Delimited by ","
    var columnNameProperty: String = serDeProps.getProperty(Constants.LIST_COLUMNS)
    // NULL if column types are missing. By default, the SerDeParameters initialized by the
    // ColumnarSerDe will treat all columns as having string types.
    // Delimited by ":"
    var columnTypeProperty: String = serDeProps.getProperty(Constants.LIST_COLUMN_TYPES)

    for (partColName <- partCols) {
      columnNameProperty += "," + partColName
    }
    if (columnTypeProperty != null) {
      for (partColName <- partCols) {
        columnTypeProperty += ":" + Constants.STRING_TYPE_NAME
      }
    }
    serDeProps.setProperty(Constants.LIST_COLUMNS, columnNameProperty)
    serDeProps.setProperty(Constants.LIST_COLUMN_TYPES, columnTypeProperty)
    serDeProps
  }

  override def getType = StageType.MAPRED

  override def getName = "MAPRED-LOAD-SPARK"

  override def localizeMRTmpFilesImpl(ctx: Context) = Unit

}
