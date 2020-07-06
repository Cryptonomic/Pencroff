package tech.cryptonomic.pencroff.ingestor.model

import java.time.Instant
import java.util.UUID

/**
  * TODO: Redo all of this
  */
object IngestorTypes {

  type Path = String
  type Alias = Path
  type AliasMap = Map[String, Record]
  type AttributeValue = Either[Long, String]
  type AttributeMap = Map[String, List[AttributeValue]] //TODO: Switch to NEL, will reduce code significantly
  type ErrorOr[+A] = Either[Throwable, A]

  case class Record( //TODO Field names are garbage, fix this
    key: String,
    url: String,
    hash: String,
    data: String,
    height: Long,
    contentType: String = "",
    contentLength: Long = 0,
    nodeUrl: String = "",
    nodeVersion: String = "UNKNOWN",
    at: Instant = Instant.now()
  )

  case class ChainMetaRecord(name: String, height: Long, lastUpdated: Instant, uuid: UUID = UUID.randomUUID())
  case class AliasRecord(key: String, targetKey: String, urlFrom: String, urlTo: String, height: Long)
  case class DerivedPath(
    value: Path,
    aliases: List[Alias],
    inputKv: Map[String, AttributeValue] = Map.empty[String, AttributeValue]
  )
  case class SourceResult(records: List[Record], kv: AttributeMap, aliases: List[AliasRecord])
  case class HttpResult(data: String, statusCode: Int, path: String, length: Long, cType: String, host: String)

  // Empty defaults for ease of use
  val NO_ALIAS_INPUTS = List.empty[Alias]
  val NO_ALIAS_RECORDS = List.empty[AliasRecord]
  val NO_DERIVED_PATHS = List.empty[DerivedPath]
  val NO_ATTRIBUTE_MAP: AttributeMap = Map.empty[String, List[AttributeValue]]
  val NO_RECORDS: List[Record] = List.empty[Record]
  val NO_SOURCE_RESULTS: SourceResult = SourceResult(NO_RECORDS, NO_ATTRIBUTE_MAP, NO_ALIAS_RECORDS)

}
