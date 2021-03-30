package tech.cryptonomic.pencroff.ingestor.storage

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID

import cats.implicits._
import cats.data.OptionT
import cats.effect.{IO, Resource}
import com.typesafe.scalalogging.LazyLogging
import org.apache.kudu.ColumnSchema.{ColumnSchemaBuilder, CompressionAlgorithm}
import org.apache.kudu.client.KuduPredicate.ComparisonOp
import org.apache.kudu.client._
import org.apache.kudu.{ColumnSchema, Schema, Type}
import tech.cryptonomic.pencroff.ingestor.storage.StorageBase._
import tech.cryptonomic.pencroff.ingestor.model.IngestorTypes.{AliasRecord, ChainMetaRecord, Record}

import scala.collection.mutable
import scala.jdk.CollectionConverters._

final class KuduStorage(config: KuduConfig)
    extends StorageBase[IO](config.database, config.table, config.metaTable, config.aliasTable)
    with LazyLogging {

  private val MAX_COLUMN_DATA: Int = 62 * 1024
  private val UPPER_BOUND_COLUMN_DATA_SIZE: Int = 64 * 1024
  private val maxColumns = Math.ceil(config.maxMobSize * 1.0 / MAX_COLUMN_DATA).toInt
  val defaultCompression = CompressionAlgorithm.LZ4

  // Kudu builders are async and doesn't throw errors. Expect errors on invocation if client is not connected
  private val client = new KuduClient.KuduClientBuilder(config.masters).build()

  private def column(
    name: String,
    t: Type,
    isKey: Boolean = false,
    nullable: Boolean = false,
    compression: CompressionAlgorithm = defaultCompression
  ): ColumnSchema =
    new ColumnSchemaBuilder(name, t)
      .compressionAlgorithm(compression)
      .nullable(nullable)
      .key(isKey)
      .build()

  def dropTable(table: String): IO[Unit] =
    IO {
      client.deleteTable(table)
      logger.info(s"Dropped table `$table`")
    }

  def init(): IO[Unit] =
    if (MAX_COLUMN_DATA > UPPER_BOUND_COLUMN_DATA_SIZE) IO.raiseError(MOBSizeException()) else IO.unit

  def initStorage(): IO[Unit] =
    IO {
      if (!client.tableExists(dataTable)) {
        val dataTableSchema = mutable.ListBuffer(
          column("key", Type.STRING, true),
          column("hash", Type.STRING, true),
          column("height", Type.INT64, true),
          column("url", Type.STRING),
          column("contentType", Type.STRING),
          column("contentLength", Type.INT64),
          column("nodeUrl", Type.STRING),
          column("nodeVersion", Type.STRING, nullable = true),
          column("at", Type.INT64)
        )

        logger.info(s"Will create $maxColumns columns to stripe data over.")
        (0 to maxColumns).foreach { i =>
          dataTableSchema.addOne(column(s"v$i", Type.BINARY, nullable = true))
        }

        createTable(dataTable, dataTableSchema.toList, List("key"))
      } else
        logger.info("Data table exists")

      if (!client.tableExists(metaTable)) {
        val metaTableSchema = List(
          column("uuid", Type.STRING, true),
          column("name", Type.STRING),
          column("height", Type.INT64),
          column("lastUpdated", Type.INT64)
        )

        createTable(metaTable, metaTableSchema, List("uuid"))
      } else
        logger.info("Metadata table exists")

      if (!client.tableExists(aliasTable)) {
        val aliasTableSchema = List(
          column("key", Type.STRING, true),
          column("targetKey", Type.STRING),
          column("urlFrom", Type.STRING),
          column("urlTo", Type.STRING),
          column("height", Type.INT64)
        )

        createTable(aliasTable, aliasTableSchema, List("key"))
      } else
        logger.info("Alias table exists")

      logger.info("Storage Initialized.")
    }

  def createTable(name: String, schema: Map[String, String]): IO[Unit] =
    IO.raiseError(NotImplementedException())

  def isStorageInitialized(): IO[Boolean] =
    IO {
      val tables = client.getTablesList
      tables.getTablesList.contains(dataTable) && tables.getTablesList.contains(metaTable) && tables.getTablesList
        .contains(aliasTable)
    }

  def putRecords(records: List[Record], overwrite: Boolean = true): IO[List[Unit]] =
    if (records.isEmpty) IO.unit.map(_ => List.empty[Unit])
    else records.traverse(r => putRecord(r, overwrite))

  def putRecord(record: Record, overwrite: Boolean = true): IO[Unit] =
    put[Record](dataTable, record, overwrite)(recordUnpacker).map { _ =>
      logger.debug(s"Row with key `${record.key}` inserted.")
    }

  def getRecord(key: String): OptionT[IO, Record] = {
    val record = for {
      schema <- getSchema(dataTable)
      predicate = KuduPredicate.newComparisonPredicate(schema.getColumn("key"), ComparisonOp.EQUAL, key)
      records <- getData[Record](dataTable, List(predicate), Some(1))(recordPacker)
    } yield records.headOption
    OptionT(record)
  }

  def getAllRecords(limit: Long = 10): IO[List[Record]] =
    getData[Record](dataTable, List.empty[KuduPredicate], Some(limit))(recordPacker)

  def getRecordCount(): IO[Long] = //TODO this needs optimization
    //  count += scanner.nextRows.getNumRows
    getData[Record](dataTable, List.empty[KuduPredicate])(recordPacker).map(_.length)

  def getMetaData(): IO[ChainMetaRecord] =
    for {
      metadataRecords <- getAllMetaData()
      _ <- if (metadataRecords.isEmpty) IO.raiseError(RecordNotFoundException()) else IO.unit
    } yield metadataRecords.maxBy(_.lastUpdated)

  def getAllMetaData(): IO[List[ChainMetaRecord]] =
    getData[ChainMetaRecord](metaTable)(metaRecordPacker).map { mr =>
      logger.debug(s"Fetched ${mr.length} metadata records.")
      mr
    }

  def putMetaData(update: ChainMetaRecord, overwrite: Boolean = false): IO[Unit] =
    put[ChainMetaRecord](metaTable, update, overwrite)(metaRecordUnpacker).map { _ =>
      logger.debug(s"Metadata updated to height ${update.height}")
    }

  def putAliasRecord(update: AliasRecord, overwrite: Boolean = false): IO[Unit] =
    put[AliasRecord](aliasTable, update, overwrite)(aliasRecordUnpacker).map { _ =>
      logger.debug(s"Alias with key `${update.key}` inserted.")
    }

  def putAliasRecords(updates: List[AliasRecord], overwrite: Boolean = false): IO[List[Unit]] =
    if (updates.isEmpty) IO.unit.map(_ => List.empty[Unit])
    else updates.traverse(ar => putAliasRecord(ar, overwrite))

  def getAliasRecord(key: String): OptionT[IO, AliasRecord] = {
    val record = for {
      schema <- getSchema(aliasTable)
      predicate = KuduPredicate.newComparisonPredicate(schema.getColumn("key"), ComparisonOp.EQUAL, key)
      records <- getData[AliasRecord](aliasTable, List(predicate), Some(1))(aliasRecordPacker)
    } yield records.headOption
    OptionT(record)
  }

  override def close(): IO[Unit] =
    IO {
      logger.info("Closing Kudu storage")
      client.close()
    }

  def getAllAliases(limit: Long = 10): IO[List[AliasRecord]] =
    getData[AliasRecord](aliasTable, List.empty[KuduPredicate], Some(limit))(aliasRecordPacker)

  private def createTable(table: String, columnSchema: List[ColumnSchema], keys: List[String]): Unit = {
    val schema = new Schema(columnSchema.asJava)
    val cto = new CreateTableOptions
    cto.addHashPartitions(keys.asJava, 8)
    cto.setNumReplicas(1)
    client.createTable(table, schema, cto)
    logger.info(s"Created table `$table`")
  }

  private def splitArray(arr: Array[Byte], splitSize: Int): List[Array[Byte]] =
    arr.grouped(splitSize).toList

  private def getTable(name: String): Resource[IO, KuduTable] = Resource.make(IO(client.openTable(name)))(_ => IO.unit)

  private def getSchema(table: String) = getTable(table).use(t => IO(t.getSchema))

  private def getSession(): Resource[IO, KuduSession] =
    Resource.make(IO(client.newSession())) { s =>
      for {
        _ <- IO(s.close())
        _ <- if (s.countPendingErrors() != 0) IO.raiseError(RecordInsertionException()) else IO.unit
      } yield ()
    }

  private def getScanner(
    table: KuduTable,
    predicates: List[KuduPredicate] = List.empty[KuduPredicate],
    limit: Option[Long] = None
  ): Resource[IO, KuduScanner] =
    Resource.make {
      IO {
        predicates
          .foldLeft(client.newScannerBuilder(table))((s, p) => s.addPredicate(p))
          .limit(limit.getOrElse(Long.MaxValue))
          .build
      }
    }(s => IO(s.close()))

  private def getData[A](
    table: String,
    predicates: List[KuduPredicate] = List.empty[KuduPredicate],
    limit: Option[Long] = None
  )(packer: RowResult => A): IO[List[A]] = {
    val resource = for {
      table <- getTable(table)
      scanner <- getScanner(table, predicates)
    } yield scanner

    resource.use { scanner =>
      IO { // TODO: This needs improvement
        val rows = mutable.ListBuffer.empty[A]
        while (scanner.hasMoreRows) {
          val results = scanner.nextRows()
          while (results.hasNext)
            rows.addOne(packer(results.next()))
        }
        rows.toList
      }
    }
  }

  private def put[A](table: String, obj: A, overwrite: Boolean)(unpacker: (PartialRow, A) => Unit): IO[Unit] = {
    val insertResources = for {
      table <- getTable(table)
      session <- getSession()
    } yield (table, session)
    insertResources.use {
      case (t, s) =>
        IO {
          if (overwrite) {
            val insert = t.newUpsert()
            unpacker(insert.getRow, obj)
            s.apply(insert)
          } else {
            val insert = t.newInsert()
            unpacker(insert.getRow, obj)
            s.apply(insert)
          }
        }.flatMap(_ => IO.unit)
    }
  }

  private def recordPacker(result: RowResult): Record = {
    val bdata = (0 to maxColumns).map(i => s"v$i").map { i =>
      if (result.isNull(i)) Array.empty[Byte]
      else result.getBinaryCopy(i)
    }
    logger.debug(s"Row contains ${bdata.count(_.length > 0)} stripes.")
    Record(
      result.getString("key"),
      result.getString("url"),
      result.getString("hash"),
      new String(bdata.flatten.toArray[Byte], StandardCharsets.UTF_8), //TODO: Handle other string encoding
      result.getLong("height"),
      result.getString("contentType"),
      result.getLong("contentLength"),
      result.getString("nodeUrl"),
      result.getString("nodeVersion"),
      at = Instant.ofEpochMilli(result.getLong("at"))
    )
  }

  private def recordUnpacker(row: PartialRow, record: Record): Unit = {
    row.addString("key", record.key)
    row.addString("url", record.url)
    row.addString("hash", record.hash)
    row.addLong("at", record.at.toEpochMilli)
    row.addLong("height", record.height)
    row.addString("contentType", record.contentType)
    row.addLong("contentLength", record.contentLength)
    row.addString("nodeUrl", record.nodeUrl)
    row.addString("nodeVersion", record.nodeVersion)

    val sdd = splitArray(record.data.getBytes(), MAX_COLUMN_DATA)
    logger.debug(s"Number of write stripes ${sdd.length}")
    if (sdd.length >= maxColumns)
      logger.warn(s"Number of stripes available is smaller than the MOB size. This will not end well.")

    sdd.zipWithIndex.foreach {
      case (v, i) =>
        row.addBinary(s"v$i", v)
    }
  }

  private def metaRecordPacker(result: RowResult): ChainMetaRecord =
    ChainMetaRecord(
      result.getString("name"),
      result.getLong("height"),
      Instant.ofEpochMilli(result.getLong("lastUpdated")),
      UUID.fromString(result.getString("uuid"))
    )

  private def metaRecordUnpacker(row: PartialRow, metaRecord: ChainMetaRecord): Unit = {
    row.addString("name", metaRecord.name)
    row.addLong("height", metaRecord.height)
    row.addLong("lastUpdated", metaRecord.lastUpdated.toEpochMilli)
    row.addString("uuid", metaRecord.uuid.toString)
  }

  private def aliasRecordUnpacker(row: PartialRow, aliasRecord: AliasRecord): Unit = {
    row.addString("key", aliasRecord.key)
    row.addString("targetKey", aliasRecord.targetKey)
    row.addString("urlFrom", aliasRecord.urlFrom)
    row.addString("urlTo", aliasRecord.urlTo)
    row.addLong("height", aliasRecord.height)
  }

  private def aliasRecordPacker(row: RowResult): AliasRecord =
    AliasRecord(
      row.getString("key"),
      row.getString("targetKey"),
      row.getString("urlFrom"),
      row.getString("urlTo"),
      row.getLong("height")
    )
}
