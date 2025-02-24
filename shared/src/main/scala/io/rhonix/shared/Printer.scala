package io.rhonix.shared
import scala.util.Try

object Printer {
  val OUTPUT_CAPPED: Option[Int] = Try(System.getenv("PRETTY_PRINTER_OUTPUT_TRIM_AFTER"))
    .map(Integer.parseInt(_))
    .toOption
    .flatMap {
      case n if n >= 0 => Some(n)
      case _           => None
    }
}
