package graviton.runtime.model

import graviton.core.keys.BinaryKey
import zio.Chunk

object BlockFramer:
  val FrameVersion: Byte = 1

  def synthesizeBlock(
    block: CanonicalBlock,
    index: Long,
    plan: BlockWritePlan,
    context: FrameContext,
  ): Either[String, BlockFrame] =
    plan.frame.layout match
      case FrameLayout.BlockPerFrame => frameSingle(block, index, plan, context)
      case FrameLayout.Aggregate(_)  => Left("Frame layout Aggregate is not implemented yet")

  private def frameSingle(
    block: CanonicalBlock,
    index: Long,
    plan: BlockWritePlan,
    context: FrameContext,
  ): Either[String, BlockFrame] =
    for
      _                                           <- ensureNonNegative(index, "blockIndex")
      aadPlan                                      = aadPlanFor(plan.frame.encryption)
      aadModel                                     = buildAad(index, context, aadPlan)
      aadBytes                                    <- BlockFrameCodec.renderAadBytes(aadModel)
      compressed                                  <- applyCompression(block.bytes, plan.frame.compression)
      (ciphertext, tag, headerKeyId, headerNonce) <- applyEncryption(compressed, plan.frame.encryption)
      algorithm                                    = algorithmFor(plan.frame)
      header                                       = FrameHeader(
                                                       version = FrameVersion,
                                                       frameType = FrameType.Block,
                                                       algorithm = algorithm,
                                                       payloadLength = ciphertext.length.toLong,
                                                       aadLength = aadBytes.length,
                                                       keyId = headerKeyId,
                                                       nonce = headerNonce,
                                                     )
    yield BlockFrame(header, aadModel, aadBytes, ciphertext, tag)

  private def ensureNonNegative(value: Long, field: String): Either[String, Unit] =
    if value < 0 then Left(s"$field cannot be negative: $value") else Right(())

  private def buildAad(
    index: Long,
    context: FrameContext,
    aadPlan: FrameAadPlan,
  ): FrameAad =
    val blobKey: Option[BinaryKey] = context.blobKey.map(identity[BinaryKey])
    val blockIndex                 = Option.when(aadPlan.includeBlockIndex)(index)
    val orgId                      = context.orgId.filter(_ => aadPlan.includeOrgId)
    val extras                     =
      if aadPlan.extra.isEmpty then Chunk.empty
      else aadPlan.extra.map { case (k, v) => FrameAadEntry(k, v) }

    FrameAad(orgId, blobKey, blockIndex, context.policyTag, extras)

  private def applyCompression(
    payload: Chunk[Byte],
    compression: CompressionPlan,
  ): Either[String, Chunk[Byte]] =
    compression match
      case CompressionPlan.Disabled   => Right(payload)
      case CompressionPlan.Zstd(_, _) =>
        Left("Zstd compression is not implemented yet for frame synthesis")

  private def applyEncryption(
    payload: Chunk[Byte],
    encryption: EncryptionPlan,
  ): Either[String, (Chunk[Byte], Option[Chunk[Byte]], Option[String], Option[Chunk[Byte]])] =
    encryption match
      case EncryptionPlan.Disabled                => Right((payload, None, None, None))
      case EncryptionPlan.Aead(mode, keyId, _, _) =>
        Left(s"Encryption mode $mode is not implemented yet for frame synthesis")

  private def algorithmFor(frame: FrameSynthesis): FrameAlgorithm =
    (frame.compression, frame.encryption) match
      case (CompressionPlan.Disabled, EncryptionPlan.Disabled) => FrameAlgorithm.Plain
      case (CompressionPlan.Disabled, _: EncryptionPlan.Aead)  => FrameAlgorithm.Encrypted
      case (_: CompressionPlan.Zstd, EncryptionPlan.Disabled)  => FrameAlgorithm.Compressed
      case (_: CompressionPlan.Zstd, _: EncryptionPlan.Aead)   => FrameAlgorithm.CompressedThenEncrypted

  private def aadPlanFor(encryption: EncryptionPlan): FrameAadPlan =
    encryption match
      case EncryptionPlan.Disabled               => FrameAadPlan()
      case EncryptionPlan.Aead(_, _, _, aadPlan) => aadPlan
