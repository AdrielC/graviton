# Graviton Frontend

Interactive Scala.js frontend for the Graviton documentation site.

## Overview

This module provides an interactive demonstration of Graviton's capabilities, built with:

- **Scala.js**: Type-safe JavaScript from Scala
- **Laminar**: Reactive UI with FRP (Functional Reactive Programming) via Airstream
- **ZIO**: Effect system for async operations
- **Waypoint**: Type-safe routing
- **Shared Models**: Cross-compiled protocol models between JVM and JS

## Architecture

### Module Structure

```
modules/
├── protocol/graviton-shared/    # Cross-compiled JVM + JS protocol models
│   ├── ApiModels.scala          # JSON-serializable data types
│   └── HttpClient.scala         # HTTP client abstraction
└── frontend/                     # Scala.js frontend application
    ├── BrowserHttpClient.scala  # Fetch API implementation
    ├── GravitonApi.scala        # High-level API client
    ├── GravitonApp.scala        # Main application with routing
    ├── Main.scala               # Entry point
    └── components/              # Interactive UI components
        ├── StatsPanel.scala     # System statistics dashboard
        ├── BlobExplorer.scala   # Blob metadata and manifest viewer
        └── HealthCheck.scala    # Health status indicator
```

### Features

#### 🏠 Dashboard
- Introduction to Graviton's capabilities
- Quick navigation to interactive features
- Feature highlights with key benefits

#### 🔍 Blob Explorer
- Search and inspect blob metadata
- View blob manifests and chunk information
- Explore checksums and content types

#### 📊 Statistics Panel
- Real-time system metrics
- Deduplication ratios
- Storage statistics
- Performance indicators

## Building

### Compile the Frontend

```bash
sbt frontend/compile
```

### Build and Copy to Docs

```bash
sbt buildFrontend
```

This compiles the Scala.js application and copies the generated JavaScript to `docs/public/js/`.

### Build All Documentation Assets

```bash
sbt buildDocsAssets
```

This builds both Scaladoc and the frontend.

## Development

### Local Development

1. Start a Graviton server:
   ```bash
   sbt "server/run"
   ```

2. Build the frontend:
   ```bash
   sbt buildFrontend
   ```

3. Start the documentation dev server:
   ```bash
   cd docs
   npm install
   npm run docs:dev
   ```

4. Navigate to `http://localhost:5173/graviton/demo` to see the interactive demo.

### Fast Iteration

For faster development iteration:

```bash
# In one terminal - watch mode for Scala.js
sbt ~frontend/fastLinkJS

# In another terminal - watch mode for VitePress
cd docs && npm run docs:dev
```

### Configuring API URL

The frontend reads the API base URL from a meta tag:

```html
<meta name="graviton-api-url" content="http://localhost:8080" />
```

Update this in `docs/demo.md` to point to your Graviton server.

## API Client

### Shared Protocol Models

The `graviton-shared` module defines cross-compiled types:

```scala
final case class BlobMetadata(
    id: BlobId,
    size: Long,
    contentType: Option[String],
    createdAt: Long,
    checksums: Map[String, String]
) derives JsonCodec
```

These models work identically on JVM and JS.

### HTTP Client

The frontend uses a browser-based HTTP client:

```scala
class BrowserHttpClient(baseUrl: String) extends HttpClient {
  def get(path: String): Task[String]
  def post(path: String, body: String): Task[String]
  // ...
}
```

It wraps the browser's Fetch API with ZIO effects.

### High-Level API

The `GravitonApi` class provides typed methods:

```scala
class GravitonApi(client: HttpClient) {
  def getHealth: Task[HealthResponse]
  def getStats: Task[SystemStats]
  def getBlobMetadata(blobId: BlobId): Task[BlobMetadata]
  def getBlobManifest(blobId: BlobId): Task[BlobManifest]
  // ...
}
```

## Component Architecture

### Reactive UI with Laminar

Components use Laminar's reactive primitives:

```scala
val statsVar = Var[Option[SystemStats]](None)
val loadingVar = Var(false)

div(
  child <-- statsVar.signal.map {
    case Some(stats) => renderStats(stats)
    case None => emptyNode
  },
  child <-- loadingVar.signal.map { loading =>
    if (loading) div("Loading...") else emptyNode
  }
)
```

### ZIO Integration

Components use ZIO's runtime to execute effects:

```scala
val runtime = Runtime.default

Unsafe.unsafe { implicit unsafe =>
  runtime.unsafe.runToFuture(api.getStats).onComplete {
    case scala.util.Success(stats) => statsVar.set(Some(stats))
    case scala.util.Failure(error) => errorVar.set(Some(error.getMessage))
  }
}
```

### Routing with Waypoint

Type-safe routing with Waypoint:

```scala
sealed trait Page
object Page {
  case object Dashboard extends Page
  case object Explorer extends Page
  case object Stats extends Page
}

val router = new Router[Page](
  routes = List(dashboardRoute, explorerRoute, statsRoute),
  // ...
)
```

## Styling

The demo includes comprehensive CSS styles defined in `docs/demo.md`:

- Modern gradient themes with neon green accents
- Responsive grid layouts
- Smooth transitions and hover effects
- Dark mode support via VitePress theme variables

## Testing

### Manual Testing

1. Verify all routes work:
   - `#/` - Dashboard
   - `#/explorer` - Blob Explorer
   - `#/stats` - Statistics

2. Test API connectivity:
   - Health check should show status
   - Stats panel should load metrics
   - Blob explorer should accept blob IDs

### Browser Console

Check for errors in the browser console:

```javascript
// All Scala.js modules loaded
console.log("Graviton app loaded")
```

## Deployment

### Production Build

```bash
# Full link for production
sbt frontend/fullLinkJS

# Copy to docs
sbt buildFrontend

# Build documentation site
cd docs && npm run docs:build
```

The generated site will be in `docs/.vitepress/dist/`.

## Future Enhancements

- [ ] WebSocket support for real-time updates
- [ ] gRPC-Web client generation from `.proto` files
- [ ] File upload UI with progress tracking
- [ ] Blob download with streaming
- [ ] Advanced search and filtering
- [ ] Chunk-level deduplication visualization
- [ ] Performance metrics charts
- [ ] Admin operations (repair, compaction)

## See Also

- [Laminar Documentation](https://laminar.dev/)
- [Waypoint Documentation](https://github.com/raquo/Waypoint)
- [Scala.js Documentation](https://www.scala-js.org/)
- [ZIO Documentation](https://zio.dev/)
