package dbcodegen

import org.scalafmt.interfaces.Scalafmt

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.concurrent.{Callable, Executors, ThreadFactory, TimeUnit, TimeoutException}

/** Runs Scalafmt on generated sources (best-effort). */
object ScalafmtRunner:

  /** Formats a single file in-place, if possible. */
  def formatFile(path: Path): Unit =
    val debug = sys.env.get("DBCODEGEN_DEBUG_PROGRESS").contains("1")
    if debug then System.err.println(s"[dbcodegen] scalafmt config lookup start $path")
    val config = configPath(start = path.getParent)
    if debug then System.err.println(s"[dbcodegen] scalafmt config lookup done $path -> $config")
    config.foreach { conf =>
      val input = Files.readString(path, StandardCharsets.UTF_8)
      if debug then System.err.println(s"[dbcodegen] scalafmt start $path using $conf")

      // NOTE: scalafmt-interfaces may need to resolve an engine the first time it runs.
      // In constrained CI environments this can hang; keep this best-effort and bounded.
      val tf: ThreadFactory = (r: Runnable) =>
        val t = new Thread(r)
        t.setDaemon(true)
        t.setName(s"dbcodegen-scalafmt-${path.getFileName.toString}")
        t

      val executor = Executors.newSingleThreadExecutor(tf)
      try
        val fut = executor.submit(new Callable[String] {
          override def call(): String =
            val fmt: Scalafmt = Scalafmt.create(getClass.getClassLoader)
            fmt.format(conf, path, input)
        })

        try
          if debug then System.err.println(s"[dbcodegen] scalafmt waiting $path")
          val output = fut.get(5, TimeUnit.SECONDS)
          if debug then System.err.println(s"[dbcodegen] scalafmt done $path")
          if output != input then Files.writeString(path, output, StandardCharsets.UTF_8)
        catch
          case _: TimeoutException =>
            fut.cancel(true)
            System.err.println(s"[dbcodegen] scalafmt timed out for $path; leaving unformatted")
          case e: Throwable =>
            System.err.println(s"[dbcodegen] scalafmt failed for $path: ${e.getMessage}; leaving unformatted")
      finally executor.shutdownNow()
    }

  private def configPath(start: Path): Option[Path] =
    sys.props
      .get("dbcodegen.scalafmt.conf")
      .orElse(sys.env.get("DBCODEGEN_SCALAFMT_CONF"))
      .map(Path.of(_))
      .filter(Files.isRegularFile(_))
      .orElse(findConfig(Path.of(".").toAbsolutePath.normalize()))

  private def findConfig(start: Path): Option[Path] =
    val name = ".scalafmt.conf"
    Iterator
      .iterate(Option(start))(_.flatMap(p => Option(p.getParent)))
      .flatten
      .take(20)
      .map(_.resolve(name))
      .find(Files.isRegularFile(_))

