package tech.cryptonomic.pencroff.ingestor.storage

/**
  * Configuration for Kudu Storage driver
  *
  * @param masters  The list of KUDU master servers in the following format "host1:port1,host2:port2,..."
  * @param maxMobSize The maximum size of the object to store within Kudu, defaults to 12 Megabytes
  */
case class KuduConfig(
  masters: String,
  maxMobSize: Long = 12582912,
  database: String = "tezos",
  table: String = "tezos",
  metaTable: String = "metaTezos",
  aliasTable: String = "aliasTezos",
  compressionScheme: String = "lz4"
)
