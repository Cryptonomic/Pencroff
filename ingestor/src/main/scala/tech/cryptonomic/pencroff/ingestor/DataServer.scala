package tech.cryptonomic.pencroff.ingestor

import cats.data.NonEmptyList
import cats.effect._
import com.typesafe.scalalogging.LazyLogging
import org.http4s.CacheDirective.`no-cache`
import org.http4s.dsl.io._
import org.http4s.headers.{`Cache-Control`, `Content-Type`}
import org.http4s.implicits._
import org.http4s.server.Router
import org.http4s.server.blaze._
import org.http4s.{Header, HttpRoutes, MediaType}
import pureconfig.ConfigSource
import tech.cryptonomic.pencroff.ingestor.storage.StorageBase.{RecordNotFoundException, StorageNotInitializedException}
import tech.cryptonomic.pencroff.ingestor.storage.{KuduConfig, KuduStorage}
import tech.cryptonomic.pencroff.ingestor.utils.BuiltInHashUtil

import java.util.concurrent.Executors
import scala.concurrent.ExecutionContext

/** Provides an HTTP service for clients to hit.
  * URLs are treated as keys and are looked up into the database.
  *
  * Major features / items to do:
  * 1. Basic tuning
  * 2. Http Framework abstraction
  * 3. Query logging to discover usage patterns
  * 4. Switch to Vert-X backend
  */
object DataServer extends App with LazyLogging {

  val global = ExecutionContext.fromExecutor(Executors.newWorkStealingPool())
  val global2 = ExecutionContext.fromExecutor(Executors.newCachedThreadPool())

  implicit val cs: ContextShift[IO] = IO.contextShift(global)
  implicit val timer: Timer[IO] = IO.timer(global)

  val configSource = ConfigSource.default
  val kuduConfiguration = configSource.at("dao").loadOrThrow[KuduConfig]
  val hashUtil = new BuiltInHashUtil
  val defaultContentType = `Content-Type`.apply(MediaType.text.plain)

  val storage = new KuduStorage(kuduConfiguration)

//  val storageResource = Resource.make { //TODO this should part of some base class somewhere
//    for {
//      kuduStorage <- IO(new KuduStorage(kuduConfiguration))
//      _ <- kuduStorage.init()
//      state <- kuduStorage.isStorageInitialized()
//      _ <- if (state) IO.unit else IO.raiseError(StorageNotInitializedException())
//    } yield {
//      logger.info("Storage is ready")
//      kuduStorage
//    }
//  } { kuduStorage =>
//    kuduStorage.close()
//  }

  def checkWithAlias(key: String) =
    storage
      .getAliasRecord(hashUtil.hash(key))
      .flatMap(ar => storage.getRecord(ar.targetKey))
      .getOrElseF(IO.raiseError(RecordNotFoundException()))

  def getDataFromStorage(key: String) =
    storage.getRecord(hashUtil.hash(key)).getOrElseF(checkWithAlias(key))

  def toResponse(key: String) =
    for {
      record <- cs.evalOn(global2)(getDataFromStorage(key))
      contentHeader = `Content-Type`.parse(record.contentType).toOption.getOrElse(defaultContentType)
      response <- Ok(
        record.data,
        Header("X-Captured-Content-Length", record.contentLength.toString),
        Header("X-Captured-Node", record.nodeUrl),
        Header("X-Captured-Time", record.at.toString),
        Header("X-Captured-Node-Version", record.nodeVersion),
        Header("X-Captured-Hash", record.hash),
        `Cache-Control`(NonEmptyList(`no-cache`(), Nil)),
        contentHeader
      )
    } yield response

  def manageTilde(url: String): String = {
    val splitUrl = url.split("/")
    val blockIndex = splitUrl.indexOf("blocks")
    val blocks = splitUrl(blockIndex + 1)
    if(!blocks.contains("~")) return url

    val block = blocks.split("~")(0)
    val blockOffset = blocks.split("~")(1).toLong

    if(block == "head") {
      splitUrl(blockIndex + 1) = (storage.getMetaData().unsafeRunSync().height - blockOffset).toString
    } else if("[0-9]+".r.matches(block)) {
      splitUrl(blockIndex + 1) = (block.toLong - blockOffset).toString
    } else {
      splitUrl(blockIndex + 1) = (checkWithAlias("tezos/chains/main/blocks/" + block).unsafeRunSync().height - blockOffset).toString
    }

    splitUrl.mkString("/")
  }


  val lookupService = HttpRoutes.of[IO] {
    case GET -> "tezos" /: keyAsPath =>
      var key = keyAsPath.toList.mkString("/")
      if(key.contains("~")) {
        key = manageTilde(key)
      }
      IO.shift *>
        //IO(logger.info(s"Fetching data for `$key`")) *>
        toResponse(key).handleErrorWith(
          t => NotFound(s"`$key` not found. Error was ${t.getMessage}")
        ) //TODO: Don't spit out errors
  }

  //TODO: Productionize this
  val startupChecks = for {
    _ <- storage.init()
    status <- storage.isStorageInitialized()
    _ <- if (!status) IO.raiseError(StorageNotInitializedException()) else IO.unit
    count <- storage.getRecordCount()
    meta <- storage.getMetaData()
    aliasRecords <- storage.getAllAliases(1000)
    records <- storage.getAllRecords(100000).map(_.sortBy(_.height))
  } yield {
    println(s"Current meta data: ${meta.height}")
    println(s"Records in data base: ${count}")
    println(s"Alias records in database: ${aliasRecords.length}")
    val heights = records.map(_.height)
    val a = aliasRecords.filter(_.height < 2)
    println(a)
    println(s"Max Height from records: ${heights.max}")
  }

  startupChecks.unsafeRunSync()

  val httpApp = Router("/" -> lookupService).orNotFound
  BlazeServerBuilder[IO](global)
    .bindHttp(8080, "localhost")
    .withHttpApp(httpApp)
    .serve
    .compile
    .drain
    .as(ExitCode.Success)
    .unsafeRunAsyncAndForget()
}
