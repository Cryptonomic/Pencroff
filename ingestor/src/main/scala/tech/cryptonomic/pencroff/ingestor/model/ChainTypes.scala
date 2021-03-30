package tech.cryptonomic.pencroff.ingestor.model

import cats.data.NonEmptyList

import scala.concurrent.duration._

object ChainTypes {

  case class HostConfig(
    host: String,
    port: Int,
    protocol: String,
    headers: Map[String, String] = Map.empty[String, String]
  )

  sealed trait Attribute {
    val name: String
  }

  case class PathAttribute(name: String, paths: List[String]) extends Attribute
  case class LeafAttribute(name: String, attributes: List[String]) extends Attribute

  val inputRegex = """\{([0-9a-zA-Z]+)\}""".r

  case class Source(
    name: String,
    pattern: String,
    order: Int = 0, // TODO : This should be automatically derived
    outputs: List[Attribute] = List.empty[Attribute],
    skipOnFailure: Boolean = false,
    //TODO: Add a pre or post data fetch alias derivation flag
    aliases: List[String] = List.empty[String] // Derived at the same time as the url pattern
  )

  //TODO: These need a home
  def getInputs(src: Source): List[String] = getInputs(src.pattern)
  def getInputs(input: String): List[String] = inputRegex.findAllMatchIn(input).map(_.group(1)).toList

  case class ChainConfig(
    name: String,
    chainVersion: String,
    headToken: String,
    headSourceIndex: Int = 0,
    sources: List[Source],
    expectedBlockTime: Duration = 5 seconds
  )

}
