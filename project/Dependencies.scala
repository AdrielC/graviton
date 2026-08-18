object Dependencies {

  /**
   * Central version catalog.
   *
   * Keep this in sync with the constraints of the existing codebase:
   * - Scala 3.8.2 (bumped 2026-04-20 to consume zio-blocks-schema 0.0.32.
   *   The Iron `RefinedSubtype` + zio-json `derives JsonCodec` regression
   *   this bump exposes is worked around by switching graviton-shared's
   *   JSON codecs to zio-schema-json's Schema-based derivation.)
   * - ZIO 2.x
   * - zio-http 3.x
   * - iron 3.x (required by `graviton.core.types` refinements)
   */
  object V {
    val scala3     = "3.8.2"
    val zio        = "2.1.23"
    val zioSchema  = "1.7.6"
    val zioPrelude = "1.0.0-RC44"
    val zioGrpc    = "0.6.3"
    val zioHttp    = "3.7.4"
    val zioNio     = "2.0.2"

    // Kyo
    val kyo = "1.0-RC1"

    // zio-blocks (published to Maven Central, TASTy 28.3 stable = 3.3 LTS,
    // consumable by our 3.7.4 compiler). Optional sibling codec artifacts
    // share this version:
    //   zio-blocks-schema-avro, zio-blocks-schema-toon,
    //   zio-blocks-schema-messagepack, zio-blocks-schema-thrift,
    //   zio-blocks-schema-bson
    val zioBlocks = "0.0.32"

    // ZIO Config
    val zioConfig = "4.0.6"

    // Misc
    val iron       = "3.2.2"
    val awsV2      = "2.25.54"
    val blake3     = "3.1.2"
    val rocksdbJni = "8.11.3"
    val pg         = "42.7.4"
    val embeddedPg = "2.0.4"
    val laminar    = "17.1.0"
    val waypoint   = "8.0.0"
    val scalajsDom = "2.8.0"
    val grpc       = "1.65.1"

    // Security
    val zioJwt     = "0.2.0"
  }
}
