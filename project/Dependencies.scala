import sbt.*

object Dependencies {

    object V {
        lazy val zioV            = "2.1.21"
        lazy val zioPreludeV     = "1.0.0-RC41"
        lazy val ironV           = "3.2.0"
        lazy val zioSchemaV      = "1.7.4"
        lazy val zioJsonV        = "0.6.2"
        lazy val zioMetricsV     = "2.4.3"
        lazy val zioCacheV       = "0.2.4"
        lazy val zioRocksdbV     = "0.4.4"
        lazy val zioConfigV      = "4.0.4"
        lazy val zioHttpV        = "3.0.0"
        lazy val testContainersV = "1.19.7"
        lazy val zioLoggingV     = "2.2.4"
        lazy val magnumV         = "2.0.0-M2"
        lazy val postgresV       = "42.7.3"
        lazy val munitV          = "1.0.0"
        lazy val schemacrawlerV  = "16.27.1"
        lazy val embeddedPgV     = "2.0.4"
        lazy val scalateV        = "1.10.1"
        lazy val catsV           = "3.7-4972921"
        lazy val fs2V            = "3.13.0-M7"
    }

    object Libraries {
        lazy val zio = "dev.zio" %% "zio" % V.zioV
        lazy val zioPrelude = "dev.zio" %% "zio-prelude" % V.zioPreludeV
        lazy val iron = "io.github.rctcwyvrn" % "iron-zio" % V.ironV
        lazy val zioSchema = "dev.zio" %% "zio-schema" % V.zioSchemaV
        lazy val zioJson = "dev.zio" %% "zio-json" % V.zioJsonV
        lazy val zioMetrics = "dev.zio" %% "zio-metrics" % V.zioMetricsV
        lazy val zioCache = "dev.zio" %% "zio-cache" % V.zioCacheV
        lazy val zioRocksdb = "dev.zio" %% "zio-rocksdb" % V.zioRocksdbV
        lazy val magnum = "com.augustnagro" %% "magnum" % V.magnumV
        lazy val magnumpg = "com.augustnagro" %% "magnumpg" % V.magnumV
        lazy val magnumzio = "com.augustnagro" %% "magnumzio" % V.magnumV
        lazy val postgresql = "org.postgresql" % "postgresql" % V.postgresV
        lazy val hikari = "com.zaxxer" % "HikariCP" % "5.1.0"
        lazy val testcontainers = "org.testcontainers" % "postgresql" % V.testContainersV % Test
        lazy val catsCore = "org.typelevel" %% "cats-core" % V.catsV
        lazy val fs2Core = "co.fs2" %% "fs2-core" % V.fs2V
        lazy val fs2Io = "co.fs2" %% "fs2-io" % V.fs2V
        lazy val scodecFs2 = "co.fs2" %% "fs2-scodec" % V.fs2V
    }

    object TestLibraries {
        lazy val zioTest = "dev.zio" %% "zio-test" % V.zioV % Test
        lazy val zioTestSbt = "dev.zio" %% "zio-test-sbt" % V.zioV % Test
        lazy val zioTestMagnolia = "dev.zio" %% "zio-test-magnolia" % V.zioV % Test
    }
}