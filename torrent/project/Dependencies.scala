import sbt.*

object Dependencies {
  object Versions {
    // ZIO ecosystem
    val zio        = "2.1.14"
    val zioSchema  = "1.7.2"
    val zioLogging = "2.4.0"
    val zioPrelude = "1.0.0-RC31"
    val zioNio     = "2.0.2"
    val zioAws     = "7.30.16.1"
    val zioJson    = "0.7.43"

    // Data processing
    val iron         = "3.0.1"
    val scodecBits   = "1.2.1"
    val scodecCore   = "2.2.1"
    val commonsCodec = "1.17.1"
    val blake3       = "3.1.2"

    // File parsing
    val pdfbox = "3.0.3"

    // Database
    val magnum     = "2.0.0-M2"
    val postgresql = "42.7.5"
  }
}
