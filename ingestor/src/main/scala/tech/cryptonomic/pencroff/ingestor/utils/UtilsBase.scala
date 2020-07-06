package tech.cryptonomic.pencroff.ingestor.utils

import cats.effect.Effect
import com.typesafe.scalalogging.LazyLogging
import tech.cryptonomic.pencroff.ingestor.model.ChainTypes.{Attribute, HostConfig}
import tech.cryptonomic.pencroff.ingestor.model.IngestorTypes.{AttributeMap, HttpResult}

abstract class HttpUtil[F[_]](val config: HostConfig) extends LazyLogging {

  def getUrl(path: String): String =
    s"${config.protocol}://${config.host}:${config.port}${if (path.startsWith("/")) path else s"/$path"}"

  def fetchAll(paths: List[String], attempts: Int = 1): F[List[HttpResult]]
  def fetch(path: String, attempts: Int = 1): F[HttpResult]
}

trait JsonUtil[F[_]] {
  def deriveFields(source: String, jsonData: String, attributes: List[Attribute]): F[AttributeMap]
}

trait HashUtil {
  def hash(input: String, method: String = "SHA-512"): String
}
