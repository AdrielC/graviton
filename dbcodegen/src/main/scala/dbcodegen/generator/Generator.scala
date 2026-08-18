package dbcodegen.generator

import zio.{Ref, UIO, ULayer, ZIO, ZLayer}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

/** Tiny generator service inspired by ZIO codegen repos.
  *
  * It holds a root + scalaVersion and provides a single primitive:
  * "render some content and write it under a Scala package directory".
  */
trait Generator:
  def setRoot(root: Path): UIO[Unit]
  def setScalaVersion(scalaVersion: String): UIO[Unit]

  def root: UIO[Path]
  def scalaVersion: UIO[String]

  def writeScalaPackage[E](
    basePackage: String,
    subPackage: String,
    fileName: String,
  )(
    render: ZIO[CodeFileGenerator, E, String],
  ): ZIO[CodeFileGenerator, GeneratorFailure[E], Path]

object Generator:

  private final case class Live(
    rootRef: Ref[Path],
    scalaVersionRef: Ref[String],
  ) extends Generator:
    override def setRoot(root: Path): UIO[Unit] =
      rootRef.set(root)

    override def setScalaVersion(scalaVersion: String): UIO[Unit] =
      scalaVersionRef.set(scalaVersion)

    override def root: UIO[Path] =
      rootRef.get

    override def scalaVersion: UIO[String] =
      scalaVersionRef.get

    override def writeScalaPackage[E](
      basePackage: String,
      subPackage: String,
      fileName: String,
    )(
      render: ZIO[CodeFileGenerator, E, String],
    ): ZIO[CodeFileGenerator, GeneratorFailure[E], Path] =
      for {
        content <- render.mapError(e => GeneratorFailure.UserError(e))
        root    <- rootRef.get
        dir     <- ZIO.succeed(packageDirFor(root, basePackage, subPackage))
        _       <- ZIO.attempt(Files.createDirectories(dir)).mapError { t =>
                     GeneratorFailure.IOError(dir, "Failed creating output directory", Some(t))
                   }
        outPath  = dir.resolve(fileName)
        _       <- ZIO.attempt(Files.write(outPath, content.getBytes(StandardCharsets.UTF_8))).mapError { t =>
                     GeneratorFailure.IOError(outPath, "Failed writing generated file", Some(t))
                   }
      } yield outPath

  def packageDirFor(root: Path, basePackage: String, subPackage: String): Path = {
    val baseParts = basePackage.split("\\.").iterator.map(_.trim).filter(_.nonEmpty)
    val subParts  = subPackage.split("\\.").iterator.map(_.trim).filter(_.nonEmpty)
    (baseParts ++ subParts).foldLeft(root) { case (p, part) => p.resolve(part) }
  }

  val live: ULayer[Generator] =
    ZLayer.fromZIO {
      for {
        rootRef         <- Ref.make(Path.of("."))
        scalaVersionRef <- Ref.make("3")
      } yield Live(rootRef, scalaVersionRef)
    }

  def setRoot(root: Path): ZIO[Generator, Nothing, Unit] =
    ZIO.serviceWithZIO[Generator](_.setRoot(root))

  def setScalaVersion(scalaVersion: String): ZIO[Generator, Nothing, Unit] =
    ZIO.serviceWithZIO[Generator](_.setScalaVersion(scalaVersion))

  def writeScalaPackage[E](
    basePackage: String,
    subPackage: String,
    fileName: String,
  )(
    render: ZIO[CodeFileGenerator, E, String],
  ): ZIO[Generator & CodeFileGenerator, GeneratorFailure[E], Path] =
    ZIO.serviceWithZIO[Generator](_.writeScalaPackage(basePackage, subPackage, fileName)(render))

