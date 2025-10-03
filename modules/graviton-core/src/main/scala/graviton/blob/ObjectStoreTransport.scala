package graviton.blob

import zio.*
import zio.stream.*

import java.io.IOException

trait ObjectStoreTransport:
  def head(bucket: String, path: String): IO[IOException, Option[(Long, Option[String])]]
  def list(bucket: String, prefix: String): ZStream[Any, IOException, String]
  def get(
    bucket: String,
    path: String,
    range: Option[(Long, Long)] = None,
  ): ZStream[Any, IOException, Byte]
  def put(bucket: String, path: String): ZSink[Any, IOException, Byte, Nothing, Unit]
  def putMultipart(bucket: String, path: String, minPart: Int): ZSink[Any, IOException, Byte, Nothing, Unit]
  def delete(bucket: String, path: String): IO[IOException, Unit]
  def copy(srcBucket: String, srcPath: String, dstBucket: String, dstPath: String): IO[IOException, Unit]
