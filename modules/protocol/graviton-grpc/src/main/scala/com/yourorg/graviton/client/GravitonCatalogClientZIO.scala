package com.yourorg.graviton.client

import io.grpc.Status
import io.graviton.blobstore.v1.catalog.*
import io.graviton.blobstore.v1.common.*
import zio.*
import zio.stream.*

final class GravitonCatalogClientZIO(stub: GravitonCatalogClientZIO.CatalogClient):

  import GravitonCatalogClientZIO.*

  def search(filters: SearchFilters): ZStream[Any, CatalogClientError, SearchResult] =
    val request = filters.toProto
    stub.search(request).mapError(CatalogClientError.TransportFailure.apply)

  def list(pageSize: Option[Int], pageAfter: Option[String]): IO[CatalogClientError, ListResponse] =
    val request = ListRequest(pageSize = pageSize, pageAfter = pageAfter)
    stub.list(request).mapError(CatalogClientError.TransportFailure.apply)

  def get(hash: String): IO[CatalogClientError, SearchResult] =
    stub
      .get(GetRequest(blobHash = hash))
      .mapError(CatalogClientError.TransportFailure.apply)
      .flatMap {
        case GetResponse(result = GetResponse.Result.Item(item))   => ZIO.succeed(item)
        case GetResponse(result = GetResponse.Result.Error(error)) => ZIO.fail(CatalogClientError.RemoteFailure(error))
        case _                                                     => ZIO.fail(CatalogClientError.NotFound(hash))
      }

  def findDuplicates(request: FindDuplicatesRequest): ZStream[Any, CatalogClientError, DuplicateGroup] =
    stub.findDuplicates(request).mapError(CatalogClientError.TransportFailure.apply)

  def exportData(request: ExportPlan): ZStream[Any, CatalogClientError, ExportChunk] =
    stub.`export`(request.toProto).mapError(CatalogClientError.TransportFailure.apply)

  def subscribe(topics: Chunk[String]): ZStream[Any, CatalogClientError, CatalogEvent] =
    stub.subscribe(SubscribeRequest(topics = topics.toList)).mapError(CatalogClientError.TransportFailure.apply)

object GravitonCatalogClientZIO:

  trait CatalogClient:
    def search(request: SearchRequest): ZStream[Any, Status, SearchResult]
    def list(request: ListRequest): IO[Status, ListResponse]
    def get(request: GetRequest): IO[Status, GetResponse]
    def findDuplicates(request: FindDuplicatesRequest): ZStream[Any, Status, DuplicateGroup]
    def `export`(request: ExportRequest): ZStream[Any, Status, ExportChunk]
    def subscribe(request: SubscribeRequest): ZStream[Any, Status, CatalogEvent]

  final case class SearchFilters(
    hashes: Chunk[String] = Chunk.empty,
    contentTypes: Chunk[String] = Chunk.empty,
    sizeRange: Option[(Long, Long)] = None,
    namespaces: Chunk[String] = Chunk.empty,
    tags: Chunk[String] = Chunk.empty,
    pageSize: Option[Int] = None,
    pageAfter: Option[String] = None,
    textQuery: Option[String] = None,
  ):
    def toProto: SearchRequest =
      val hashFilters      = hashes.map(value => Filter(field = Filter.Field.FIELD_HASH, op = "=", value = value))
      val contentFilters   = contentTypes.map(value => Filter(field = Filter.Field.FIELD_CONTENT_TYPE, op = "=", value = value))
      val namespaceFilters = namespaces.map(value => Filter(field = Filter.Field.FIELD_NAMESPACE, op = "=", value = value))
      val rangeFilters     = sizeRange.fold(Chunk.empty[Filter]) { case (min, max) =>
        Chunk(
          Filter(field = Filter.Field.FIELD_SIZE_BYTES, op = ">=", value = min.toString),
          Filter(field = Filter.Field.FIELD_SIZE_BYTES, op = "<=", value = max.toString),
        )
      }
      SearchRequest(
        filters = (hashFilters ++ contentFilters ++ namespaceFilters ++ rangeFilters).toList,
        pageSize = pageSize,
        pageAfter = pageAfter,
        textQuery = textQuery,
        tags = tags.toList,
      )

  final case class ExportPlan(
    blobHashes: Chunk[String] = Chunk.empty,
    documentIds: Chunk[String] = Chunk.empty,
    query: Option[SearchFilters] = None,
    includeFrames: Boolean = true,
  ):
    def toProto: ExportRequest =
      ExportRequest(
        blobHashes = blobHashes.toList,
        documentIds = documentIds.toList,
        query = query.map(_.toProto),
        includeFrames = includeFrames,
      )

  sealed trait CatalogClientError extends Throwable:
    def message: String
    override def getMessage: String = message

  object CatalogClientError:
    final case class TransportFailure(status: Status) extends CatalogClientError:
      override def message: String = Option(status.getDescription).getOrElse(status.getCode.name())

    final case class RemoteFailure(error: Error) extends CatalogClientError:
      override def message: String = s"${error.code.name}: ${error.message}"

    final case class NotFound(hash: String) extends CatalogClientError:
      override def message: String = s"Blob $hash not found"
