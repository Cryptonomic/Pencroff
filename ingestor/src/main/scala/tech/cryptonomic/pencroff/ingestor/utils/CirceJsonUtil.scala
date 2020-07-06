package tech.cryptonomic.pencroff.ingestor.utils

import io.circe.Json
import io.circe.parser.parse
import io.circe.optics.JsonPath.root
import cats.data.NonEmptyList
import cats.effect.IO
import com.typesafe.scalalogging.LazyLogging
import tech.cryptonomic.pencroff.ingestor.model.IngestorTypes.{AttributeMap, AttributeValue}
import tech.cryptonomic.pencroff.ingestor.model.ChainTypes.{Attribute, LeafAttribute, PathAttribute}

class CirceJsonUtil extends JsonUtil[IO] with LazyLogging {

  def deriveFields(source: String, jsonData: String, attributes: List[Attribute]): IO[AttributeMap] =
    IO {
      logger.trace(s"Deriving fields for source: $source")
      val json = parse(jsonData).getOrElse {
        logger.warn(s"No json data was parsed for source: `$source`")
        Json.Null
      }
      attributes.map { f =>
        val values = f match {
          case p: PathAttribute => parsePath(json, p)
          case l: LeafAttribute => parseLeaf(json, l)
        }
        f.name -> values
      }.filter(_._2.nonEmpty).toMap
    }

  private def getValue(name: String, json: Json): Option[AttributeValue] =
    json match {
      case j if j.isString =>
        j.asString.map(Right(_))
      case j if j.isNumber =>
        j.asNumber.flatMap(_.toLong).map(Left(_))
      case _ =>
        logger.warn(s"Attribute : `$name` cannot be extracted since it neither STRING or INT")
        None
    }

  private def parseLeaf(json: Json, jlf: LeafAttribute): List[AttributeValue] =
    jlf.attributes.flatMap(attr => json.findAllByKey(attr).flatMap(leaf => List(getValue(jlf.name, leaf)))).flatten

  private def parsePath(json: Json, jpa: PathAttribute): List[AttributeValue] =
    jpa.paths.flatMap { jp =>
      val path = jp.split('.').foldLeft(root)((dynPath, seg) => dynPath.selectDynamic(seg))
      path.json.getOption(json) match {
        case Some(j) if j.isArray =>
          path.each.json.getAll(json).map(x => getValue(jpa.name, x))
        case Some(j) =>
          List(getValue(jpa.name, j))
        case None =>
          List.empty[Option[AttributeValue]]
      }
    }.flatten
}
