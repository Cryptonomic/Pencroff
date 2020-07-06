package tech.cryptonomic.pencroff.ingestor

import java.time.Instant
import java.util.concurrent.TimeUnit

import cats.data.OptionT
import cats.effect.{ContextShift, IO, Resource, Timer}
import cats.implicits._
import pureconfig._
import pureconfig.generic.auto._
import com.typesafe.scalalogging.LazyLogging

import scala.concurrent.ExecutionContext.global
import scala.concurrent.duration._
import tech.cryptonomic.pencroff.ingestor.storage.{KuduConfig, KuduStorage}
import tech.cryptonomic.pencroff.ingestor.model.IngestorTypes._
import tech.cryptonomic.pencroff.ingestor.model.ChainTypes.{Attribute, ChainConfig, HostConfig, Source}
import tech.cryptonomic.pencroff.ingestor.model.{ChainTypes, IngestorTypes}
import tech.cryptonomic.pencroff.ingestor.utils.Exceptions.{HeadSourceNotFoundException, InvalidStartingHeightException}
import tech.cryptonomic.pencroff.ingestor.utils.{BuiltInHashUtil, CirceJsonUtil, Http4sUtil}

import scala.annotation.tailrec

/**
  * Ingests data from various urls provided by config and plonks them into some storage
  *
  * Major unknowns:
  * 1. Do we always assume json media type?
  * 2. Do we want to handle non json media type?
  * 3. How to prove to an external party, the immutability of the store?
  *
  *
  * Major features / items to do:
  * 1. Alias support (DONE)
  * 2. Validator process (side car or built in?)
  * 3. metrics
  * 4. Validation chain
  * 5. Meta data end point
  * 6. Recheck idempotentency of process, e.g chain reset to HEAD - N scenario
  * 7. display page for backend
  * 8. Baseline load test
  * 9. Handle special tokesn (height) better in configuration.
  * 10. Head symlinking
  * 11. Start up check for user supplied chain definitions
  * 12. Maintenance tools (Mostly removing entries functionality)
  */
object BlockIngestor extends App with LazyLogging {
  logger.info("Block Ingestor starting up")
  logger.debug("Debug logging is enabled")
  logger.info("Loading configuration")

  implicit val cs: ContextShift[IO] = IO.contextShift(global)
  implicit val timer: Timer[IO] = IO.timer(global)

  val configSource = ConfigSource.default
  val chainConfigSource = ConfigSource.resources("tezos.json")
  val hostCfg = configSource.at("host-config").loadOrThrow[HostConfig]
  val kuduConfiguration = configSource.at("dao").loadOrThrow[KuduConfig]
  val chainConfiguration = chainConfigSource.loadOrThrow[ChainConfig]
  val genesisMetaRecord: ChainMetaRecord = ChainMetaRecord(chainConfiguration.name, 0, Instant.now)
  val httpUtil = new Http4sUtil(hostCfg)
  val jsonUtil = new CirceJsonUtil
  val hashUtil = new BuiltInHashUtil

  sys.addShutdownHook {
    Thread.currentThread()
    logger.info("Shutdown called")
  }

  implicit def attributeValueToString(i: AttributeValue): String =
    i match {
      case Left(v) => v.toString
      case Right(v) => v
    }

  implicit def attributeValueToLong(i: AttributeValue): Long =
    i match {
      case Left(v) => v
      case Right(_) => Long.MinValue
    }

  case class UrlDerivationException() extends Exception("Unable to derive urls")
  case class IncompleteSourceException(name: String) extends Exception(s"Unable to fully compute Source: $name")

  def cartesian[A](input: List[List[A]]): List[List[A]] =
    input.tail.foldLeft(List(input.head)) {
      case (a, b) =>
        for {
          i <- a
          j <- b
        } yield i :+ j
    }

  val storageResource = Resource.make { //TODO this should part of some base class somewhere
    for {
      kuduStorage <- IO(new KuduStorage(kuduConfiguration))
      _ <- kuduStorage.init()
      _ <- kuduStorage.dropTable(kuduConfiguration.table)
      _ <- kuduStorage.dropTable(kuduConfiguration.metaTable)
      _ <- kuduStorage.dropTable(kuduConfiguration.aliasTable)
      state <- kuduStorage.isStorageInitialized()
      _ <- if (state) IO.unit else kuduStorage.initStorage() *> kuduStorage.putMetaData(genesisMetaRecord)
    } yield {
      logger.info("Storage is ready")
      kuduStorage
    }
  } { kuduStorage =>
    kuduStorage.close()
  }

  def isStopSignal(): IO[Boolean] = IO.pure(false)

  def validateStartHeight(startAt: Option[Long], meta: ChainMetaRecord): IO[Long] =
    startAt match {
      case None =>
        IO(meta.height)
      case Some(h) =>
        if (h > meta.height)
          IO.raiseError(InvalidStartingHeightException())
        else
          IO.pure(h)
    }
  val maxErrors: Long = 5

  ingestor(storageResource)

  def ingestor(storage: Resource[IO, KuduStorage], startAt: Option[Long] = None) =
    storage.use { s =>
      for {
        // Where is the ingestor at?
        mHead <- s.getMetaData()
        // Where should the ingestor be at?
        height <- validateStartHeight(startAt, mHead)
        _ = logger.info(s"Metadata for chain ${mHead.name}, height is : ${mHead.height} , asof: ${mHead.lastUpdated}")
        _ = logger.info(s"Starting height override is defined: ${startAt.isDefined}. Will start at height : $height")
        _ <- processChain(s, height)
      } yield ()
    }.handleErrorWith { t =>
      logger.error("Unable to continue due to errors", t)
      IO.unit
    }.unsafeRunSync()

  def processChain(storage: KuduStorage, currentHeight: Long = 0, headHeight: Long = 0, errors: Long = 0): IO[Unit] = {
    val process = for {
      _ <- IO.shift
      // Where is the head of the chain at?
      headKv <-
        if (currentHeight >= headHeight) fetchBlockHead(chainConfiguration).map(_.kv) else IO.pure(NO_ATTRIBUTE_MAP)
      //TODO Fix the assumption that source configuration provides an output field called height.
      computedHeadHeight = headKv.get("height").flatMap(_.headOption) match {
        case Some(Left(l)) => l
        case _ => headHeight
      }
      _ = logger.debug(s"Head is at height $computedHeadHeight")
      // Check if i can fetch more data i.e my current level is below the chain head
      canFetch = computedHeadHeight > currentHeight
      //TODO hash token should be from configuration
      inputKv = NO_ATTRIBUTE_MAP.updated("height", List(Left(currentHeight)))
      // Fetch results for all sources defined for this chain
      results <-
        if (canFetch) fetchBlockData(chainConfiguration.sources.sortBy(_.order), inputKv)
        else IO.pure(NO_SOURCE_RESULTS)
      // Write the results to the database
      writes <- storage.putRecords(results.records, true)
      _ <- storage.putAliasRecords(results.aliases, true)
      _ <- storage.putMetaData(ChainMetaRecord(chainConfiguration.name, currentHeight, Instant.now()))
      // Do some logging, sleep, rinse repeat
      _ = logger.info(s"Processed block at height : $currentHeight . Wrote ${writes.length} documents fetched.")
      _ <- IO.sleep(Duration.apply(25, TimeUnit.MILLISECONDS)) //TODO: Move to configuration
      _ <- processChain(storage, currentHeight + 1, computedHeadHeight, 0)
    } yield ()

    process.handleErrorWith { t =>
      logger.error(s"Failed to process block at height $currentHeight. Encountered ${errors + 1} consecutive errors", t)
      if (errors + 1 < maxErrors)
        IO.sleep(Duration.apply(5, TimeUnit.SECONDS)) *> //TODO: Add to configuration
          processChain(storage = storage, currentHeight = currentHeight, headHeight = headHeight, errors = errors + 1)
      else {
        logger.error("Too many errors, giving up.")
        IO.unit
      }
    }
  }

  def fetchBlockHead(config: ChainConfig): IO[SourceResult] =
    IO {
      logger.info("Fetching block head to figure out chain height")
      config.sources.get(config.headSourceIndex) match {
        case Some(src) => fetchBlockData(List(src), NO_ATTRIBUTE_MAP.updated("height", List(Right(config.headToken))))
        case None => IO.raiseError(HeadSourceNotFoundException())
      }
    }.flatten

  def fetchBlockData(
    sources: List[Source],
    inputKv: AttributeMap,
    records: List[Record] = NO_RECORDS,
    aliases: List[AliasRecord] = NO_ALIAS_RECORDS
  ): IO[SourceResult] =
    if (sources.nonEmpty)
      fetchSourceData(sources.head, inputKv).flatMap(
        result =>
          fetchBlockData(sources.tail, inputKv ++ result.kv, records ++ result.records, aliases ++ result.aliases)
      )
    else
      IO.pure(SourceResult(records, inputKv, aliases))

  def toRecord(res: HttpResult, height: Long): Record =
    Record(
      key = hashUtil.hash(res.path),
      url = res.path,
      hash = hashUtil.hash(res.data),
      data = res.data,
      height = height,
      contentType = res.cType,
      contentLength = res.length,
      nodeUrl = res.host
    )

  def fetchSourceData(src: Source, kv: AttributeMap): IO[SourceResult] = {
    val sourceData =
      for {
        // Compute the paths we need to fetch from the node for this source definition
        derivedPaths <- derivePaths(src, kv)
        // Fetch json documents for all those paths with results encoded in the HttpResult case class
        httpResults <- httpUtil.fetchAll(derivedPaths.map(_.value))
        // Split the results into two sets, valid and invalid
        (validData, invalidData) = httpResults.partition(_.statusCode == 200) //TODO: Other valid codes?
        // Throw an error if we have invalid results, for now we assume a source has to be processed in its entirety
        _ <- if (invalidData.nonEmpty) IO.raiseError(IncompleteSourceException(src.name)) else IO.unit
        // Derive the output fields from each valid results and then combine all the values into a giant KV
        derivedKvList <- validData.traverse(d => jsonUtil.deriveFields(src.name, d.data, src.outputs))
        // Smoosh the previous KV with the newly derived KV
        updatedKv = kv ++ derivedKvList.foldLeft(NO_ATTRIBUTE_MAP)(_ ++ _) //TODO: Check clobbering ordering of dup keys
        // Compute Records from the valid set of json documents
        height = updatedKv("height").head //TODO: Fix this
        records = validData.map(toRecord(_, height))
        // Compute Aliases
        aliases <- deriveAliases(records, derivedPaths, updatedKv)
      } yield SourceResult(records, updatedKv, aliases)

    sourceData.handleErrorWith { t =>
      logger.error(s"Encountered while processing source ${src.name}", t)
      if (src.skipOnFailure) {
        logger.info(s"Skipping source ${src.name} due to failures")
        IO.pure(NO_SOURCE_RESULTS)
      } else IO.raiseError(t)
    }

  }

  private def fillValues(pattern: String, kv: Map[String, AttributeValue]): String =
    kv.keysIterator.foldLeft(pattern)((p, k) => p.replaceAll(s"\\{$k\\}", kv(k)))

  def deriveAliases(records: List[Record], derivedPaths: List[DerivedPath], kv: AttributeMap) =
    IO {
      records.flatMap { r =>
        derivedPaths.find(_.value.contentEquals(r.url)).map { dp =>
          val aliasPaths = dp.aliases.flatMap { aliasPattern =>
            val aliasInputs = ChainTypes.getInputs(aliasPattern)
            val constrainedKv = dp.inputKv.filter { case (k, _) => aliasInputs.contains(k) }
            val aliasInputKv = kv.filter { case (k, _) => aliasInputs.contains(k) }
            val aliasKv = aliasInputKv ++ constrainedKv.map { case (k, v) => k -> List(v) }
            val comb = cartesian[AttributeValue](aliasKv.values.toList)
            val aliasPath = comb.map(c => fillValues(aliasPattern, aliasInputs.zip(c).toMap))
            aliasPath.map(alias => AliasRecord(hashUtil.hash(alias), r.key, alias, r.url, r.height))
          }
          logger.debug(s"Computed ${aliasPaths.length} aliases for source")
          aliasPaths
        }
      }.flatten
    }

  def derivePaths(src: Source, kv: AttributeMap): IO[List[DerivedPath]] =
    IO {
      val inputs = ChainTypes.getInputs(src)
      (inputs, inputs.forall(kv.contains), inputs.nonEmpty) match {
        case (_, _, false) =>
          logger.debug(s"$Source ${src.name} has no inputs")
          List(DerivedPath(src.pattern, NO_ALIAS_INPUTS))
        case (i, true, true) =>
          val combinations = cartesian[AttributeValue](kv.filter(t => i.contains(t._1)).values.map(_.distinct).toList)
          val derivedPaths = combinations.map { c =>
            val inputKv = i.zip(c).toMap
            DerivedPath(fillValues(src.pattern, inputKv), src.aliases, inputKv)
          }
          logger.debug(s"Derived for Source: ${src.name} a total of ${derivedPaths.length} paths.")
          derivedPaths
        case (i, false, true) =>
          val missing = i.filterNot(kv.contains)
          logger.error(s"Source ${src.name} requires inputs [${missing.mkString(", ")}] which are missing from KV")
          List.empty[DerivedPath]
      }
    }

}
