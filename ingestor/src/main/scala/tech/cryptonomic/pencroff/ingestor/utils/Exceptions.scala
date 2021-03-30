package tech.cryptonomic.pencroff.ingestor.utils

object Exceptions {

  case class HeadSourceNotFoundException() extends Exception()
  case class InvalidStartingHeightException() extends Exception()
}
