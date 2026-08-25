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
    frameSingle(block, index, plan, context)

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
      algorithm                                    = FrameAlgorithm.Plain
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
      case CompressionPlan.Disabled => Right(payload)

  private def applyEncryption(
    payload: Chunk[Byte],
    encryption: EncryptionPlan,
  ): Either[String, (Chunk[Byte], Option[Chunk[Byte]], Option[String], Option[Chunk[Byte]])] =
    encryption match
      case EncryptionPlan.Disabled => Right((payload, None, None, None))

  private def aadPlanFor(encryption: EncryptionPlan): FrameAadPlan =
    encryption match
      case EncryptionPlan.Disabled => FrameAadPlan()
