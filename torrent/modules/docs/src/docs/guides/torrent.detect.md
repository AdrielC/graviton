# Content Detection

## Overview

Torrent provides built-in content type detection capabilities to help identify and process different file formats appropriately.

## Usage

```scala
import torrent.detect._

// Detect content type from bytes
val contentType = ContentTypeDetector.detect(bytes)

// Detect from file extension
val contentType = ContentTypeDetector.fromExtension(".pdf")

// Detect with confidence score
val (contentType, confidence) = ContentTypeDetector.detectWithConfidence(bytes)
```

## Supported Types

### Documents
- PDF (application/pdf)
- Word (application/msword)
- DOCX (application/vnd.openxmlformats-officedocument.wordprocessingml.document)
- XLSX (application/vnd.openxmlformats-officedocument.spreadsheetml.sheet)
- PPTX (application/vnd.openxmlformats-officedocument.presentationml.presentation)

### Images
- TIFF (image/tiff)
- JPEG (image/jpeg)
- PNG (image/png)

## Custom Detection

Implement your own detector:

```scala
trait ContentTypeDetector:
  def detect(bytes: Bytes): Option[ContentType]
  def confidence(bytes: Bytes): ConfidenceScore
```

## Related Topics

- [Storage Guide](../guides/storage.md)
- [API Reference](../api-reference/index.md) 