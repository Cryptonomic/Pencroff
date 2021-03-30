package tech.cryptonomic.pencroff.ingestor.utils

import java.security.MessageDigest

class BuiltInHashUtil extends HashUtil {
  def hash(input: String, method: String): String =
    MessageDigest
      .getInstance(method)
      .digest(input.getBytes("UTF-8"))
      .map("%02x".format(_))
      .mkString
}
