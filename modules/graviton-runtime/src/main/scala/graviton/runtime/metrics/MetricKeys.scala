package graviton.runtime.metrics

object MetricKeys:
  val BytesIngested        = "graviton_bytes_ingested"
  val BlocksIngested       = "graviton_blocks_ingested"
  val ScanOutputs          = "graviton_ingest_scan_outputs"
  val UploadDuration       = "graviton_upload_duration_seconds"
  val BackendFailures      = "graviton_backend_failures_total"
  val BlobIngestsTotal     = "graviton_blob_ingests_total"
  val BytesIngestedTotal   = "graviton_bytes_ingested_total"
  val FreshBlocksTotal     = "graviton_fresh_blocks_total"
  val DuplicateBlocksTotal = "graviton_duplicate_blocks_total"
