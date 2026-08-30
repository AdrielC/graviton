package graviton.server.console

import graviton.core.types.FileSize
import graviton.server.RuntimeHealth
import graviton.protocol.http.BlobIngest
import graviton.runtime.catalog.*
import graviton.runtime.stores.{BlobStore, StoreError, StoreOperation}
import graviton.runtime.upload.{TenantId, UploadIntent, UploadNode, UploadSessionId, UploadSessionKey}
import graviton.shared.{ApiJson, ApiJsonCodec, MediaTypeText}
import zio.*
import zio.blocks.mediatype.{MediaType, MediaTypes}
import zio.blocks.schema.Schema
import zio.http.*
import zio.json.EncoderOps
import zio.json.ast.Json
import zio.stream.ZStream

import java.io.IOException
import java.net.{URI, URLEncoder}
import java.nio.charset.StandardCharsets

/** Local operator UI backed by the exact same streaming ingest and CAS services as the public API. */
final class ConsoleApi(
  catalog: Catalog,
  blobStore: BlobStore,
  blobIngest: BlobIngest,
  shardcakeNode: Option[UploadNode],
  runtimeHealth: RuntimeHealth,
  version: String,
):
  import ConsoleApi.*

  val routes: Routes[Any, Nothing] = Routes(
    Method.GET / "console"                                   -> Handler.fromFunctionZIO[Request](request => guard(request)(page(request))),
    Method.GET / "console" / "library"                       -> Handler.fromFunctionZIO[Request](request => guard(request)(library(request))),
    Method.GET / "console" / "runtime"                       -> Handler.fromFunctionZIO[Request](request => guard(request)(runtimePage)),
    Method.GET / "console" / "runtime" / "panel"             -> Handler.fromFunctionZIO[Request](request => guard(request)(runtimePanel(request))),
    Method.GET / "console" / "assets" / "datastar-v1.0.2.js" -> Handler.fromFunctionZIO[Request](request => guard(request)(datastarAsset)),
    Method.GET / "console" / "assets" / "graviton-logo.svg"  -> Handler.fromFunctionZIO[Request](request => guard(request)(logoAsset)),
    Method.POST / "console" / "folders"                      -> Handler.fromFunctionZIO[Request](request => guard(request)(createFolder(request))),
    Method.DELETE / "console" / "folders" / string("id")     -> Handler.fromFunctionZIO[(String, Request)] { case input @ (_, request) =>
      guard(request)(removeFolder(input))
    },
    Method.DELETE / "console" / "files" / string("id")       -> Handler.fromFunctionZIO[(String, Request)] { case input @ (_, request) =>
      guard(request)(removeFile(input))
    },
    Method.POST / "console" / "api" / "uploads"              -> Handler.fromFunctionZIO[Request](request => guard(request)(upload(request))),
  )

  val app: Handler[Any, Nothing, Request, Response] = routes.toHandler

  private def guard(request: Request)(effect: => UIO[Response]): UIO[Response] =
    if sameOrigin(request) then effect
    else
      ZIO.succeed(
        Response
          .text("Cross-origin console access is forbidden")
          .copy(status = Status.Forbidden)
          .addHeader(Header.Custom("Cache-Control", "no-store"))
      )

  private def sameOrigin(request: Request): Boolean =
    val fetchSiteAllowed = request.headers
      .get("Sec-Fetch-Site")
      .forall(value => Set("none", "same-origin").contains(value.trim.toLowerCase(java.util.Locale.ROOT)))
    val originAllowed    = request.headers.get("Origin") match
      case None         => true
      case Some(origin) =>
        val parsed = scala.util.Try(URI.create(origin)).toOption
        val host   = request.headers.get("Host")
        parsed.exists(uri =>
          Set("http", "https").contains(Option(uri.getScheme).fold("")(_.toLowerCase(java.util.Locale.ROOT))) &&
            host.exists(_.equalsIgnoreCase(Option(uri.getRawAuthority).getOrElse("")))
        )
    fetchSiteAllowed && originAllowed

  private val datastarAsset: UIO[Response] =
    classpathAsset("console/datastar-v1.0.2.js", "text/javascript; charset=utf-8")

  private val logoAsset: UIO[Response] =
    classpathAsset("console/graviton-logo.svg", "image/svg+xml")

  private def classpathAsset(resource: String, contentType: String): UIO[Response] =
    val stream = ZStream.fromInputStreamZIO(
      ZIO
        .attempt(
          Option(getClass.getClassLoader.getResourceAsStream(resource))
            .getOrElse(throw new IOException(s"Bundled console asset is missing: $resource"))
        )
        .refineToOrDie[IOException],
      chunkSize = 8192,
    )
    ZIO.succeed(
      Response(
        status = Status.Ok,
        headers = Headers(
          Header.Custom("Content-Type", contentType),
          Header.Custom("Cache-Control", "public, max-age=31536000, immutable"),
          Header.Custom("X-Content-Type-Options", "nosniff"),
        ),
        body = Body.fromStreamChunked(stream),
      )
    )

  private def page(request: Request): UIO[Response] =
    (for
      folder  <- parseFolder(request)
      listing <- catalog.list(folder)
      session <- Random.nextUUID
    yield htmlResponse(renderPage(renderWorkspace(listing, session.toString)))).catchAll(errorResponse)

  private def library(request: Request): UIO[Response] =
    (for
      folder  <- parseFolder(request)
      listing <- catalog.list(folder)
      session <- parseSession(request).orElse(Random.nextUUID.map(_.toString))
    yield fragmentResponse(renderWorkspace(listing, session))).catchAll(error => mutationError(request, error))

  private def runtimePage: UIO[Response] =
    for
      session  <- Random.nextUUID.map(_.toString)
      snapshot <- runtimeHealth.refresh
    yield htmlResponse(renderPage(renderRuntime(snapshot, session)))

  private def runtimePanel(request: Request): UIO[Response] =
    for
      session  <- parseSession(request).orElse(Random.nextUUID.map(_.toString))
      snapshot <- runtimeHealth.refresh
    yield fragmentResponse(renderRuntime(snapshot, session))

  private def createFolder(request: Request): UIO[Response] =
    (for
      parent  <- parseFolder(request)
      name    <- parseName(request)
      _       <- catalog.createFolder(parent, name)
      listing <- catalog.list(parent)
      session <- parseSession(request).orElse(Random.nextUUID.map(_.toString))
    yield fragmentResponse(renderWorkspace(listing, session))).catchAll(error => mutationError(request, error))

  private def removeFolder(input: (String, Request)): UIO[Response] =
    val (rawId, request) = input
    (for
      id      <- ZIO.fromEither(CatalogFolderId.parse(rawId)).mapError(CatalogError.InvalidInput(_))
      parent  <- parseFolder(request)
      _       <- catalog.removeFolder(id)
      listing <- catalog.list(parent)
      session <- parseSession(request).orElse(Random.nextUUID.map(_.toString))
    yield fragmentResponse(renderWorkspace(listing, session))).catchAll(error => mutationError(request, error))

  private def removeFile(input: (String, Request)): UIO[Response] =
    val (rawId, request) = input
    (for
      id      <- ZIO.fromEither(CatalogFileId.parse(rawId)).mapError(CatalogError.InvalidInput(_))
      folder  <- parseFolder(request)
      _       <- catalog.removeFile(id)
      listing <- catalog.list(folder)
      session <- parseSession(request).orElse(Random.nextUUID.map(_.toString))
    yield fragmentResponse(renderWorkspace(listing, session))).catchAll(error => mutationError(request, error))

  private def upload(request: Request): UIO[Response] =
    val effect = for
      folder                  <- parseFolder(request)
      name                    <- parseName(request)
      expectedSize            <- parseContentLength(request)
      mediaType               <- parseMediaType(request)
      session                 <- uploadSession(request)
      result                  <- blobIngest.upload(session, UploadIntent(mediaType, expectedSize), request.body.asStream)
      _                       <- blobStore
                                   .stat(result.key)
                                   .someOrFail(
                                     StoreError.CorruptData(
                                       StoreOperation.StatBlob,
                                       "persisted upload is missing its manifest summary",
                                     )
                                   )
                                   .mapError(BlobIngest.Error.Storage(_))
      attached                <- attachIdempotently(folder, name, result.key, mediaType, result.stats)
      (file, referenceCreated) = attached
      owner                    = result.owner.map(_.id.value)
      response                 = UploadResponse(
                                   file.id.value.toString,
                                   result.key.bits.render,
                                   result.stats.totalBytes,
                                   result.stats.blockCount,
                                   result.stats.freshBlocks,
                                   result.stats.duplicateBlocks,
                                   owner,
                                   referenceCreated,
                                 )
    yield Response.json(ApiJson.encode(response)).copy(status = if referenceCreated then Status.Created else Status.Ok)

    effect.catchAll(uploadErrorResponse)

  private def attachIdempotently(
    folder: Option[CatalogFolderId],
    name: CatalogName,
    blob: graviton.core.keys.BinaryKey.Blob,
    mediaType: MediaType,
    stats: graviton.core.attributes.IngestStats,
  ): IO[CatalogError, (CatalogFile, Boolean)] =
    catalog.attachFile(folder, name, blob, mediaType, stats).map(_ -> true).catchSome { case conflict: CatalogError.NameConflict =>
      catalog.list(folder).flatMap { listing =>
        ZIO
          .fromOption(
            listing.files.find(file => file.name.value.equalsIgnoreCase(name.value) && file.blob == blob)
          )
          .map(_ -> false)
          .orElseFail(conflict)
      }
    }

  private def mutationError(request: Request, error: CatalogError): UIO[Response] =
    (for
      folder  <- parseFolder(request).orElseSucceed(None)
      listing <- catalog.list(folder).orElse(catalog.list(None))
      session <- parseSession(request).orElse(Random.nextUUID.map(_.toString))
    yield fragmentResponse(renderWorkspace(listing, session, Some(errorMessage(error))))).catchAll(_ =>
      ZIO.succeed(
        fragmentResponse(
          s"<section id=\"workspace\" class=\"workspace fatal\"><div class=\"result-strip error\" role=\"alert\">${escape(errorMessage(error))}</div></section>"
        )
      )
    )

  private def parseFolder(request: Request): IO[CatalogError, Option[CatalogFolderId]] =
    request.url.queryParam("folder").filter(_.nonEmpty) match
      case None        => ZIO.none
      case Some(value) => ZIO.fromEither(CatalogFolderId.parse(value)).mapBoth(CatalogError.InvalidInput(_), Some(_))

  private def parseName(request: Request): IO[CatalogError, CatalogName] =
    ZIO
      .fromOption(request.url.queryParam("name"))
      .orElseFail(CatalogError.InvalidInput("name is required"))
      .flatMap(value => ZIO.fromEither(CatalogName.parse(value)).mapError(CatalogError.InvalidInput(_)))

  private def parseSession(request: Request): IO[CatalogError, String] =
    ZIO
      .fromOption(request.url.queryParam("session"))
      .orElseFail(CatalogError.InvalidInput("session is required"))
      .flatMap(value => ZIO.fromEither(UploadSessionId.either(value)).mapError(CatalogError.InvalidInput(_)).as(value))

  private def uploadSession(request: Request): IO[CatalogError, Option[UploadSessionKey]] =
    shardcakeNode match
      case None    => ZIO.none
      case Some(_) =>
        parseSession(request).flatMap { value =>
          ZIO
            .fromEither(UploadSessionId.either(value))
            .mapError(CatalogError.InvalidInput(_))
            .map(session => Some(UploadSessionKey(ConsoleTenant, session)))
        }

  private def parseContentLength(request: Request): IO[CatalogError, Option[FileSize]] =
    request.headers.get("Content-Length") match
      case None      => ZIO.none
      case Some(raw) =>
        ZIO
          .fromEither(
            raw.toLongOption
              .toRight("Content-Length must be a non-negative decimal integer")
              .flatMap(FileSize.either)
          )
          .mapBoth(CatalogError.InvalidInput(_), Some(_))

  private def parseMediaType(request: Request): IO[CatalogError, MediaType] =
    request.headers.get("Content-Type") match
      case None      => ZIO.succeed(MediaTypes.application.`octet-stream`)
      case Some(raw) => ZIO.fromEither(MediaTypeText.parse(raw)).mapError(CatalogError.InvalidInput(_))

  private def renderPage(workspace: String): String =
    s"""<!doctype html>
       |<html lang="en">
       |<head>
       |  <meta charset="utf-8">
       |  <meta name="viewport" content="width=device-width, initial-scale=1">
       |  <meta name="theme-color" content="#06110e">
       |  <title>Graviton</title>
       |  <style>${ConsoleAssets.css}</style>
       |  <script type="module" src="/console/assets/datastar-v1.0.2.js"></script>
       |</head>
       |<body>
       |  <canvas id="matrix" aria-hidden="true"></canvas>
       |  <div class="shell">
       |    <header class="topbar">
       |      <div class="brand"><img class="brand-logo" src="/console/assets/graviton-logo.svg" alt=""><span>Graviton</span></div>
       |      <div class="top-status"><span class="live-dot" aria-hidden="true"></span><span>${escape(statusText)}</span></div>
       |    </header>
       |    <section id="transfer-rail" class="transfer-rail" aria-live="polite" hidden>
       |      <div id="upload-progress" class="upload-progress" role="status"><span id="progress-copy" class="progress-copy"></span><span id="progress-value" class="progress-value">0%</span><div class="progress-track"><div id="progress-bar" class="progress-bar"></div></div></div>
       |      <div id="upload-result" class="result-strip" role="status" hidden></div>
       |    </section>
       |    $workspace
       |  </div>
       |  <script>${ConsoleAssets.javascript}</script>
       |</body>
       |</html>""".stripMargin

  private def renderWorkspace(listing: CatalogListing, session: String, notice: Option[String] = None): String =
    val folderId    = listing.folder.map(_.id.value.toString).getOrElse("")
    val refreshUrl  = consoleUrl("/console/library", listing.folder.map(_.id), Some(session))
    val totalBytes  = listing.files.foldLeft(0L)((total, file) => total + file.blob.bits.size)
    val totalBlocks = listing.files.foldLeft(0)((total, file) => total + file.blockCount)
    val duplicates  = listing.files.foldLeft(0)((total, file) => total + file.duplicateBlocks)
    val reused      = if totalBlocks == 0 then 0 else Math.round(duplicates.toDouble / totalBlocks.toDouble * 100.0).toInt
    val title       = listing.folder.fold("All files")(_.name.value)
    val rootActive  = if listing.folder.isEmpty then " active" else ""
    val folderRows  = listing.folders.map(renderFolder(_, listing.folder.map(_.id), session)).mkString
    val fileRows    = listing.files.map(renderFile(_, listing.folder.map(_.id), session)).mkString
    val rows        = if folderRows.isEmpty && fileRows.isEmpty then renderEmpty else folderRows + fileRows
    val noticeHtml  = notice.fold("")(message => s"<div class=\"result-strip error\" role=\"alert\">${escape(message)}</div>")
    val rootCurrent = if listing.folder.isEmpty then " aria-current=\"page\"" else ""
    val rootPage    = consolePageUrl(None)
    val rootLibrary = consoleUrl("/console/library", None, Some(session))

    s"""<section id="workspace" class="workspace" data-folder="${escape(folderId)}" data-session="${escape(
        session
      )}" data-refresh="${escape(refreshUrl)}">
       |  <div class="commandbar">
       |    <div class="command-title">${viewTabs(View.Library, session)}<h1>${escape(title)}</h1></div>
       |    <div class="command-actions">
       |      <button class="button" type="button" ${ConsoleDatastar.click(
        s"@get('${js(refreshUrl)}')"
      )} title="Refresh" aria-label="Refresh library">${icon("refresh")}</button>
       |      <label class="upload-button">Upload<input id="file-input" type="file" multiple></label>
       |    </div>
       |  </div>
       |  <div class="content">
       |    <aside class="sidebar">
       |      <p class="sidebar-title">Folders</p>
       |      <nav class="sidebar-nav" aria-label="Folder navigation">
       |        <a class="tree-button$rootActive" ${navigationAttributes(rootLibrary, rootPage)}$rootCurrent><span class="tree-icon">${icon(
        "home"
      )}</span> All files</a>
       |        ${listing.folders.map { folder =>
        val libraryUrl = consoleUrl("/console/library", Some(folder.id), Some(session))
        s"<a class=\"tree-button\" ${navigationAttributes(libraryUrl, consolePageUrl(Some(folder.id)))}><span class=\"tree-icon\">${icon("folder")}</span> ${escape(folder.name.value)}</a>"
      }.mkString}
       |      </nav>
       |      <form class="folder-form" data-signals:folder-name="''" ${ConsoleDatastar.submit(
        s"@post('${js(consoleUrl("/console/folders", listing.folder.map(_.id), Some(session)))}&name=' + encodeURIComponent($$folderName))"
      )}>
       |        <input type="text" maxlength="255" placeholder="New folder" aria-label="New folder name" data-bind:folder-name required>
       |        <button type="submit" aria-label="Create folder">${icon("plus")}</button>
       |      </form>
       |    </aside>
       |    <div class="library">
       |      ${renderBreadcrumbs(listing, session)}
       |      $noticeHtml
       |      <div class="summary" aria-label="Storage summary">
       |        <div class="summary-item"><span>Files</span><strong>${listing.files.length}</strong></div>
       |        <div class="summary-item"><span>Logical size</span><strong>${formatBytes(totalBytes)}</strong></div>
       |        <div class="summary-item"><span>Block spans</span><strong>$totalBlocks</strong></div>
       |        <div class="summary-item reuse"><span>Blocks reused</span><strong>$reused%</strong><div class="reuse-track" aria-hidden="true"><i style="--reuse: $reused%"></i></div></div>
       |      </div>
       |      <div class="entries">
       |        <div class="entries-head" aria-hidden="true"><span>Name and content ID</span><span>Size</span><span>Reuse</span><span>Actions</span></div>
       |        $rows
       |      </div>
       |    </div>
       |  </div>
       |</section>""".stripMargin

  private def renderBreadcrumbs(listing: CatalogListing, session: String): String =
    if listing.breadcrumbs.isEmpty then ""
    else
      val root =
        s"<a class=\"crumb\" ${navigationAttributes(consoleUrl("/console/library", None, Some(session)), consolePageUrl(None))}>All files</a>"
      val rest = listing.breadcrumbs.zipWithIndex.map { case (folder, index) =>
        val current = if index == listing.breadcrumbs.length - 1 then " aria-current=\"page\"" else ""
        s"<span class=\"sep\">/</span><a class=\"crumb\" ${navigationAttributes(consoleUrl("/console/library", Some(folder.id), Some(session)), consolePageUrl(Some(folder.id)))}$current>${escape(folder.name.value)}</a>"
      }.mkString
      s"<nav class=\"crumbs\" aria-label=\"Breadcrumb\">$root$rest</nav>"

  private def renderFolder(folder: CatalogFolder, parent: Option[CatalogFolderId], session: String): String =
    val openUrl   = consoleUrl("/console/library", Some(folder.id), Some(session))
    val deleteUrl = consoleUrl(s"/console/folders/${folder.id.value}", parent, Some(session))
    s"""<div class="entry folder">
       |  <div class="entry-name"><span class="entry-icon folder-icon">${icon(
        "folder"
      )}</span><div class="name-stack"><a class="crumb name" ${navigationAttributes(
        openUrl,
        consolePageUrl(Some(folder.id)),
      )}>${escape(folder.name.value)}</a><div class="hash">folder</div></div></div>
       |  <span class="metric"></span><span class="metric"></span>
       |  <div class="row-actions"><button class="icon-button danger" type="button" title="Remove empty folder" aria-label="Remove ${escape(
        folder.name.value
      )}" ${ConsoleDatastar.click(s"if (confirm('Remove this empty folder?')) @delete('${js(deleteUrl)}')")}>${icon("close")}</button></div>
       |</div>""".stripMargin

  private def renderFile(file: CatalogFile, folder: Option[CatalogFolderId], session: String): String =
    val download  = s"/api/v1/blobs/${path(file.blob.bits.render)}"
    val deleteUrl = consoleUrl(s"/console/files/${file.id.value}", folder, Some(session))
    val ratio     = if file.blockCount == 0 then 0 else Math.round(file.duplicateBlocks.toDouble / file.blockCount.toDouble * 100.0).toInt
    val kind      = file.name.value.lastIndexOf('.') match
      case index if index > 0 => file.name.value.substring(index + 1).take(4).toUpperCase(java.util.Locale.ROOT)
      case _                  => "BIN"
    s"""<div class="entry">
       |  <div class="entry-name"><span class="entry-icon">${escape(kind)}</span><div class="name-stack"><div class="name">${escape(
        file.name.value
      )}</div><div class="hash" title="${escape(file.blob.bits.render)}">${escape(file.blob.bits.render)}</div></div></div>
       |  <span class="metric">${formatBytes(file.blob.bits.size)}</span><span class="metric dedup">$ratio% blocks</span>
       |  <div class="row-actions"><a class="icon-button" href="${escape(download)}" download="${escape(
        file.name.value
      )}" title="Download" aria-label="Download ${escape(
        file.name.value
      )}">${icon("download")}</a><button class="icon-button danger" type="button" title="Remove reference" aria-label="Remove ${escape(
        file.name.value
      )}" ${ConsoleDatastar.click(
        s"if (confirm('Remove this file reference? The CAS content is retained.')) @delete('${js(deleteUrl)}')"
      )}>${icon("close")}</button></div>
       |</div>""".stripMargin

  private def renderRuntime(snapshot: RuntimeHealth.Snapshot, session: String): String =
    val refreshUrl = consoleUrl("/console/runtime/panel", None, Some(session))
    val healthy    = snapshot.ready
    val stateClass = if healthy then "ready" else "unavailable"
    val heading    = snapshot.shardcake match
      case Some(cluster) =>
        cluster.status match
          case graviton.integration.shardcake.ShardcakeHealth.Status.Healthy     => "Cluster ready"
          case graviton.integration.shardcake.ShardcakeHealth.Status.Rebalancing => "Rebalancing"
          case graviton.integration.shardcake.ShardcakeHealth.Status.Starting    => "Starting"
          case graviton.integration.shardcake.ShardcakeHealth.Status.Unassigned  => "Node unassigned"
          case graviton.integration.shardcake.ShardcakeHealth.Status.Unavailable => "Placement unavailable"
      case None          => if healthy then "Runtime ready" else "Storage unavailable"
    val detail     = snapshot.shardcake.fold(
      if healthy then "The blob store passed its operational check." else "The blob store did not pass its operational check."
    )(_.detail)
    val placement  = snapshot.shardcake.fold(renderSingleNode(snapshot))(renderPlacement)
    val process    = snapshot.process
    val reuse      = Math.round(process.reuseRatio * 100.0)
    val checked    = java.time.Instant.ofEpochMilli(snapshot.checkedAtMillis).toString

    s"""<section id="workspace" class="workspace runtime-workspace" data-session="${escape(session)}" data-refresh="${escape(
        refreshUrl
      )}" ${ConsoleDatastar.interval(5000L, s"@get('${js(refreshUrl)}')")}>
       |  <div class="commandbar">
       |    <div class="command-title">${viewTabs(View.Runtime, session)}<h1>Runtime</h1></div>
       |    <div class="command-actions">
       |      <a class="text-link" href="/metrics" target="_blank" rel="noreferrer">Prometheus</a>
       |      <button class="button" type="button" ${ConsoleDatastar.click(
        s"@get('${js(refreshUrl)}')"
      )} title="Check now" aria-label="Check runtime now">${icon(
        "refresh"
      )}</button>
       |    </div>
       |  </div>
       |  <div class="runtime-layout">
       |    <main class="runtime-main">
       |      <section class="health-summary $stateClass" aria-live="polite">
       |        <div class="health-heading"><span class="status-mark" aria-hidden="true"></span><h2>${escape(heading)}</h2></div>
       |        <p>${escape(detail)}</p>
       |        <div class="check-time">Checked <time datetime="${escape(checked)}">${escape(checked)}</time></div>
       |      </section>
       |      $placement
       |    </main>
       |    <aside class="telemetry" aria-label="Process metrics">
       |      <div class="telemetry-heading"><h2>Since start</h2><span>process lifetime</span></div>
       |      ${metricRow("Blob ingests", formatCount(process.blobIngests))}
       |      ${metricRow("Bytes accepted", formatBytes(process.bytesIngested))}
       |      ${metricRow("Fresh blocks", formatCount(process.freshBlocks))}
       |      ${metricRow("Duplicate blocks", formatCount(process.duplicateBlocks))}
       |      ${metricRow("Block reuse", s"$reuse%", "accent")}
       |      ${metricRow("Local routes", formatCount(process.localRoutes))}
       |      ${metricRow("Remote routes", formatCount(process.remoteRoutes))}
       |      ${metricRow("Routing failures", formatCount(process.localityFailures), if process.localityFailures > 0 then "danger" else "")}
       |    </aside>
       |  </div>
       |</section>""".stripMargin

  private def renderPlacement(snapshot: graviton.integration.shardcake.ShardcakeHealth.Snapshot): String =
    val assignedPercent = percent(snapshot.assignedShards, snapshot.configuredShards)
    val localPercent    = percent(snapshot.localAssignedShards, snapshot.configuredShards)
    s"""<section class="placement">
       |  <div class="section-heading"><h2>Shard placement</h2><span>${snapshot.assignedShards} / ${snapshot.configuredShards} assigned</span></div>
       |  <div class="assignment-track" aria-label="$assignedPercent% of shards assigned, $localPercent% owned by this node">
       |    <i class="assignment-local" style="--width: $localPercent%"></i><i class="assignment-remote" style="--width: ${(assignedPercent - localPercent)
        .max(0)}%"></i>
       |  </div>
       |  <dl class="runtime-facts">
       |    <div><dt>Node</dt><dd>${escape(snapshot.node.id.value)}</dd></div>
       |    <div><dt>Local ownership</dt><dd>${snapshot.localAssignedShards} shards</dd></div>
       |    <div><dt>Observed nodes</dt><dd>${snapshot.observedNodes}</dd></div>
       |    <div><dt>Tracked sessions</dt><dd>${snapshot.trackedSessions}</dd></div>
       |    <div><dt>Upload endpoint</dt><dd>${escape(snapshot.node.host.value)}:${snapshot.node.uploadPort.value}</dd></div>
       |    <div><dt>Control endpoint</dt><dd>${escape(snapshot.node.host.value)}:${snapshot.node.controlPort.value}</dd></div>
       |  </dl>
       |</section>""".stripMargin

  private def renderSingleNode(snapshot: RuntimeHealth.Snapshot): String =
    val storage = if snapshot.storage == RuntimeHealth.StorageStatus.Ready then "Ready" else "Unavailable"
    s"""<section class="placement single-node">
       |  <div class="section-heading"><h2>Local topology</h2><span>Shardcake disabled</span></div>
       |  <dl class="runtime-facts">
       |    <div><dt>Blob store</dt><dd>$storage</dd></div>
       |    <div><dt>Routing</dt><dd>Single node</dd></div>
       |  </dl>
       |</section>""".stripMargin

  private def viewTabs(active: View, session: String): String =
    val libraryClass = if active == View.Library then " active" else ""
    val runtimeClass = if active == View.Runtime then " active" else ""
    val libraryUrl   = consoleUrl("/console/library", None, Some(session))
    val runtimeUrl   = consoleUrl("/console/runtime/panel", None, Some(session))
    s"""<nav class="view-tabs" aria-label="Console view">
       |  <a class="view-tab$libraryClass" ${navigationAttributes(libraryUrl, "/console")} ${
        if active == View.Library then "aria-current=\"page\"" else ""
      }>Library</a>
       |  <a class="view-tab$runtimeClass" ${navigationAttributes(runtimeUrl, "/console/runtime")} ${
        if active == View.Runtime then "aria-current=\"page\"" else ""
      }>Runtime</a>
       |</nav>""".stripMargin

  private def metricRow(label: String, value: String, valueClass: String = ""): String =
    s"<div class=\"telemetry-row\"><span>${escape(label)}</span><strong class=\"${escape(valueClass)}\">${escape(value)}</strong></div>"

  private def percent(value: Int, total: Int): Int =
    if total <= 0 then 0 else Math.min(100, Math.round(value.toDouble / total.toDouble * 100.0).toInt)

  private def formatCount(value: Long): String =
    java.text.NumberFormat.getIntegerInstance(java.util.Locale.US).format(value)

  private val renderEmpty: String =
    "<div class=\"empty\"><div><strong>Drop files here</strong>or choose Upload files</div></div>"

  private def consoleUrl(path: String, folder: Option[CatalogFolderId], session: Option[String]): String =
    val parameters = List(
      folder.map(value => "folder" -> value.value.toString),
      session.map("session" -> _),
    ).flatten
    if parameters.isEmpty then path
    else path + parameters.map((name, value) => s"${query(name)}=${query(value)}").mkString("?", "&", "")

  private def consolePageUrl(folder: Option[CatalogFolderId]): String =
    consoleUrl("/console", folder, None)

  private def navigationAttributes(fragmentUrl: String, pageUrl: String): String =
    val expression =
      s"history.pushState(null, '', '${js(pageUrl)}'); @get('${js(fragmentUrl)}')"
    s"href=\"${escape(pageUrl)}\" ${ConsoleDatastar.clickPrevent(expression)}"

  private def statusText: String =
    shardcakeNode.fold(s"local CAS · $version")(node => s"Shardcake · ${node.id.value} · $version")

  private def icon(name: String): String =
    val path = name match
      case "refresh"  => "<path d=\"M20 11a8.1 8.1 0 1 0 2.1 5.5\"/><path d=\"M20 4v7h-7\"/>"
      case "home"     => "<path d=\"m3 11 9-8 9 8\"/><path d=\"M5 10v10h14V10\"/>"
      case "folder"   => "<path d=\"M3 7h7l2 2h9v10H3z\"/><path d=\"M3 7V5h7l2 2\"/>"
      case "plus"     => "<path d=\"M12 5v14M5 12h14\"/>"
      case "close"    => "<path d=\"m6 6 12 12M18 6 6 18\"/>"
      case "download" => "<path d=\"M12 3v12m0 0 5-5m-5 5-5-5\"/><path d=\"M5 19h14\"/>"
      case _          => ""
    s"<svg class=\"icon\" viewBox=\"0 0 24 24\" aria-hidden=\"true\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.75\" stroke-linecap=\"round\" stroke-linejoin=\"round\">$path</svg>"

  private def errorMessage(error: CatalogError): String =
    error match
      case _: CatalogError.Storage => "Catalog operation failed"
      case other                   => other.getMessage

  private def errorResponse(error: CatalogError): UIO[Response] =
    val (status, message) = error match
      case _: CatalogError.InvalidInput   => Status.BadRequest          -> errorMessage(error)
      case _: CatalogError.NotFound       => Status.NotFound            -> errorMessage(error)
      case _: CatalogError.NameConflict   => Status.Conflict            -> errorMessage(error)
      case _: CatalogError.FolderNotEmpty => Status.Conflict            -> errorMessage(error)
      case _: CatalogError.Storage        => Status.InternalServerError -> errorMessage(error)
    ZIO.succeed(jsonError(status, message))

  private def uploadErrorResponse(error: Exception): UIO[Response] =
    error match
      case catalogError: CatalogError           => errorResponse(catalogError)
      case _: BlobIngest.Error.InvalidInput     => ZIO.succeed(jsonError(Status.BadRequest, error.getMessage))
      case _: BlobIngest.Error.Rejected         => ZIO.succeed(jsonError(Status.RequestEntityTooLarge, error.getMessage))
      case BlobIngest.Error.LocalityUnavailable => ZIO.succeed(jsonError(Status.ServiceUnavailable, error.getMessage))
      case _: BlobIngest.Error.Locality         => ZIO.succeed(jsonError(Status.ServiceUnavailable, error.getMessage))
      case _: BlobIngest.Error.Storage          => ZIO.succeed(jsonError(Status.InternalServerError, "Blob ingest failed"))
      case _                                    => ZIO.succeed(jsonError(Status.InternalServerError, "Upload failed"))

  private def jsonError(status: Status, message: String): Response =
    Response.json(Json.Obj("message" -> Json.Str(message)).toJson).copy(status = status)

  private def htmlResponse(value: String): Response =
    Response(
      status = Status.Ok,
      headers = Headers(
        Header.Custom("Content-Type", "text/html; charset=utf-8"),
        Header.Custom("Cache-Control", "no-store"),
        Header.Custom(
          "Content-Security-Policy",
          "default-src 'none'; script-src 'self' 'unsafe-inline' 'unsafe-eval'; style-src 'unsafe-inline'; connect-src 'self'; img-src 'self' data:; font-src 'self'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'",
        ),
        Header.Custom("Cross-Origin-Opener-Policy", "same-origin"),
        Header.Custom("Referrer-Policy", "no-referrer"),
        Header.Custom("X-Content-Type-Options", "nosniff"),
      ),
      body = Body.fromString(value),
    )

  private def fragmentResponse(value: String): Response =
    htmlResponse(value)

object ConsoleApi:
  private val ConsoleTenant = TenantId.applyUnsafe("00000000-0000-4000-8000-000000000001")

  private enum View:
    case Library, Runtime

  final case class UploadResponse(
    fileId: String,
    blobId: String,
    bytes: Long,
    blockCount: Int,
    freshBlocks: Int,
    duplicateBlocks: Int,
    owner: Option[String],
    referenceCreated: Boolean,
  )

  object UploadResponse:
    given Schema[UploadResponse]       = Schema.derived
    given ApiJsonCodec[UploadResponse] = ApiJsonCodec.derived

  private def escape(value: String): String =
    value
      .replace("&", "&amp;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")
      .replace("\"", "&quot;")
      .replace("'", "&#39;")

  private def js(value: String): String =
    value.replace("\\", "\\\\").replace("'", "\\'")

  private def path(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

  private def query(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8)

  private def formatBytes(value: Long): String =
    if value < 1024L then s"$value B"
    else
      val units  = Array("KiB", "MiB", "GiB", "TiB")
      var amount = value.toDouble
      var index  = -1
      while amount >= 1024.0 && index < units.length - 1 do
        amount /= 1024.0
        index += 1
      f"$amount%.1f ${units(index)}"
