# Content Detection API

## Overview

The Content Detection API provides functionality for identifying and handling different file formats. It includes built-in detectors for common formats and allows for custom implementations.

## Core Types

### ContentTypeDetector

The main interface for content type detection:

```scala
trait ContentTypeDetector:
  def detect(bytes: Bytes): Option[ContentType]
  def confidence(bytes: Bytes): ConfidenceScore
```

### ContentType

Represents a detected content type:

```scala
sealed trait ContentType:
  def mainType: MediaPart
  def subType: MediaPart
  def extensions: List[String]
  def category: MediaCategory

case object Pdf extends ContentType
case object Doc extends ContentType
case object Docx extends ContentType
case object Xlsx extends ContentType
case object Pptx extends ContentType
case object Tiff extends ContentType
```

### ConfidenceScore

Represents the confidence level of a detection:

```scala
case class ConfidenceScore(value: Double):
  def isHigh: Boolean = value >= 0.8
  def isMedium: Boolean = value >= 0.5
  def isLow: Boolean = value < 0.5
```

## Usage Examples

### Basic Detection

```scala
val detector = ContentTypeDetector.default

// Detect from bytes
val contentType = detector.detect(bytes)

// Detect with confidence
val (contentType, confidence) = detector.detectWithConfidence(bytes)

// Detect from extension
val contentType = detector.fromExtension(".pdf")
```

### Custom Detection

```scala
class CustomDetector extends ContentTypeDetector:
  def detect(bytes: Bytes): Option[ContentType] = ???
  def confidence(bytes: Bytes): ConfidenceScore = ???
```

## Related Topics

- [Binary Storage](../api-reference/binary-store.md)
- [Chunking](../api-reference/chunking.md) 