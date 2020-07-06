package tech.cryptonomic.pencroff.ingestor.utils

import java.util.concurrent.TimeUnit

import cats.effect.{ContextShift, IO, Timer}
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging
import org.http4s.client.Client
import org.http4s.client.blaze.BlazeClientBuilder
import org.http4s.headers.Referer
import org.http4s.{Header, HeaderKey, Headers, Method, Request, Uri}
import tech.cryptonomic.pencroff.ingestor.model.ChainTypes.HostConfig
import tech.cryptonomic.pencroff.ingestor.model.IngestorTypes.HttpResult

import scala.concurrent.ExecutionContext.global
import scala.concurrent.duration._

/* TODO: This class needs some love */
class Http4sUtil(config: HostConfig)(implicit val cs: ContextShift[IO], val timer: Timer[IO])
    extends HttpUtil[IO](config)
    with LazyLogging {

  private val staticHeaders = Headers(config.headers.map { case (k, v) => Header(k, v) }.toList)
  private val contentLengthHeader = org.http4s.headers.`Content-Length`
  private val contentTypeHeader = org.http4s.headers.`Content-Type`

  private val clientResource = BlazeClientBuilder[IO](global) //TODO: Pass in an execution context
    .withMaxTotalConnections(4)
    .withRequestTimeout(Duration.apply(60, TimeUnit.SECONDS))
    .withIdleTimeout(Duration.apply(120, TimeUnit.SECONDS))
    .resource

  def fetchAll(paths: List[String], attempts: Int = 1): IO[List[HttpResult]] =
    clientResource.use(client => IO(paths.traverse(u => IO.shift *> retry(fetch(u, client), attempts))).flatten)

  def fetch(path: String, attempts: Int = 1): IO[HttpResult] =
    clientResource.use(client => retry(fetch(path, client), attempts))

  private def fetch(path: String, client: Client[IO]): IO[HttpResult] = {
    val uri = Uri.unsafeFromString(getUrl(path))
    logger.debug(s"Fetching data from url: ${uri.toString()}")
    client
      .run(Request[IO](Method.GET, uri, headers = staticHeaders))
      .use { resp =>
        resp.as[String].map { data =>
          HttpResult(
            data,
            resp.status.code,
            path,
            resp.headers.get(contentLengthHeader).map(_.length).getOrElse(0),
            resp.headers
              .get(contentTypeHeader)
              .map(h => s"${h.mediaType.mainType}/${h.mediaType.subType}")
              .getOrElse("text/plain"), // TODO: A fall back type, but is this a good idea?
            config.host
          )
        }
      }
  }

  private def retry[A](httpIo: IO[A], attempts: Int): IO[A] =
    httpIo.handleErrorWith { error =>
      logger.error(s"Unable to fetch data from remote host. Will retry attempt: $attempts", error)
      if (attempts > 0) retry(httpIo, attempts - 1)
      else IO.raiseError(error)
    }
}
