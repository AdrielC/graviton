object Dependencies {

  /**
   * Central version catalog.
   *
   * Keep this in sync with the constraints of the existing codebase:
   * - Scala 3.8.4, aligned with the current ZIO Blocks schema and media-type modules.
   *   Until an official release includes zio-blocks PR #1578, shared Blocks
   *   codecs use primitive wire records and validate conversion into Iron types.
   * - ZIO 2.x
   * - zio-http 3.x
   * - iron 3.x (required by `graviton.core.types` refinements)
   */
  object V {
    val scala3     = "3.8.4"
    val zio        = "2.1.26"
    val zioJson    = "0.10.0"
    val zioSchema  = "1.8.6"
    val zioPrelude = "1.0.0-RC48"
    val zioGrpc    = "0.6.3"
    val zioHttp    = "3.11.4"
    val zioNio     = "2.0.2"

    // Kyo
    val kyo = "1.0-RC1"

    // ZIO Blocks publishes the current release as 0.0.51. Maven metadata
    // misorders an accidental 0.017 duplicate of the 0.0.17 code line above
    // 0.0.51, even though 0.017 was published months earlier.
    val zioBlocks = "0.0.51"
    val zioPdf    = "0.2.0-RC7"

    // ZIO Config
    val zioConfig = "4.0.6"
    val shardcake = "2.8.1"

    // Misc
    val iron       = "3.3.2"
    val awsV2      = "2.54.7"
    val blake3     = "3.1.2"
    val rocksdbJni = "8.11.3"
    val pg         = "42.7.13"
    val embeddedPg = "2.2.2"
    val laminar    = "17.1.0"
    val waypoint   = "8.0.0"
    val scalajsDom = "2.8.0"
    val grpc       = "1.83.1"
    val netty      = "4.2.17.Final"
    val nettyGrpc  = "4.1.137.Final"
    val protobuf   = "3.25.9"

    // Runtime logging
    val log4j = "2.25.5"

    // Security
    val zioJwt     = "0.2.0"
  }
}
