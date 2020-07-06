package tech.cryptonomic.pencroff.ingestor.storage

import cats.data.OptionT
import tech.cryptonomic.pencroff.ingestor.model.IngestorTypes.{AliasRecord, ChainMetaRecord, Record}

trait LowLevelStorageFunctions[F[_]] {

  def init(): F[Unit]

  def initStorage(): F[Unit]

  def dropTable(name: String): F[Unit]

  def createTable(name: String, schema: Map[String, String]): F[Unit]

  def isStorageInitialized(): F[Boolean]

  def close(): F[Unit]

}

abstract class StorageBase[F[_]](
  val database: String,
  val dataTable: String,
  val metaTable: String,
  val aliasTable: String
) extends LowLevelStorageFunctions[F] {

  def putRecord(record: Record, overwrite: Boolean = true): F[Unit]

  def putRecords(record: List[Record], overwrite: Boolean = true): F[List[Unit]] //TODO: Fix this

  def getRecord(key: String): OptionT[F, Record]

  def getAllRecords(limit: Long = 10): F[List[Record]]

  def putMetaData(update: ChainMetaRecord, overwrite: Boolean = false): F[Unit]

  def getMetaData(): F[ChainMetaRecord]

  def getAllMetaData(): F[List[ChainMetaRecord]]

  def getRecordCount(): F[Long]

  def putAliasRecord(update: AliasRecord, overwrite: Boolean = false): F[Unit]

  def putAliasRecords(updates: List[AliasRecord], overwrite: Boolean = false): F[List[Unit]]

  def getAliasRecord(key: String): OptionT[F, AliasRecord]
}

object StorageBase {
  case class RecordInsertionException() extends Exception()
  case class NotImplementedException() extends Exception()
  case class RecordNotFoundException() extends Exception()
  case class MOBSizeException() extends Exception()
  case class StorageNotInitializedException() extends Exception()
}
