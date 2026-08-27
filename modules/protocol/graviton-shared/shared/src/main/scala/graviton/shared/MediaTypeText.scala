package graviton.shared

import zio.blocks.mediatype.MediaType
import zio.blocks.schema.{Schema, SchemaError}

/**
 * Canonical, bounded text boundary for ZIO Blocks media types.
 *
 * `MediaType.fullType` intentionally contains only the type and subtype. Wire
 * and metadata boundaries must use [[render]] so parameters are retained and
 * invalid programmatic values fail closed.
 */
object MediaTypeText:
  final val MaxWireLength = 256

  private val tokenPattern  = "[!#$%&'*+.^_`|~0-9A-Za-z-]+"
  private val MaxParameters = 50

  def parse(value: String): Either[String, MediaType] =
    if value == null then Left("Media type must not be null")
    else if value.length > MaxWireLength then Left(s"Media type exceeds $MaxWireLength characters")
    else if containsForbiddenControl(value) then Left("Media type must not contain control characters")
    else
      val normalized = trimOws(value)
      for
        _                  <- Either.cond(normalized.nonEmpty, (), "Media type must not be empty")
        separator           = normalized.indexOf(';')
        typeSegment         = if separator < 0 then normalized else trimOws(normalized.substring(0, separator))
        typeParts           = typeSegment.split("/", -1).toList
        tuple              <- typeParts match
                                case mainType :: subType :: Nil => validateType(mainType, subType)
                                case _                          => Left("Media type must contain exactly one '/' separator")
        (mainType, subType) = tuple
        parameters         <-
          if separator < 0 then Right(Map.empty[String, String])
          else parseParameters(normalized.substring(separator + 1))
        base               <- MediaType.parse(s"$mainType/$subType")
      yield base.copy(parameters = parameters)

  /** Render a valid canonical value or throw before emitting malformed text. */
  def render(value: MediaType): String =
    renderEither(value).fold(message => throw new IllegalArgumentException(message), identity)

  /** Validate and render a public `MediaType` case-class value. */
  def renderEither(value: MediaType): Either[String, String] =
    if value == null then Left("Media type must not be null")
    else if value.parameters == null then Left("Media type parameters must not be null")
    else if value.parameters.size > MaxParameters then Left(s"Media type has more than $MaxParameters parameters")
    else
      for
        tuple              <- validateType(value.mainType, value.subType)
        (mainType, subType) = tuple
        parameters         <- validateProgrammaticParameters(value.parameters)
        size                = parameters.foldLeft(mainType.length.toLong + subType.length.toLong + 1L) { case (total, (name, parameter)) =>
                                total + name.length.toLong + parameter.length.toLong + 3L
                              }
        _                  <- Either.cond(size <= MaxWireLength.toLong, (), s"Media type exceeds $MaxWireLength characters")
        rendered            = parameters.toList.sortBy(_._1).foldLeft(s"$mainType/$subType") { case (text, (name, parameter)) =>
                                s"$text; $name=$parameter"
                              }
      yield rendered

  given mediaTypeSchema: Schema[MediaType] =
    Schema[String].transform(
      value => parse(value).fold(message => throw SchemaError.validationFailed(message), identity),
      value => renderEither(value).fold(message => throw SchemaError.validationFailed(message), identity),
    )

  private def validateType(mainType: String, subType: String): Either[String, (String, String)] =
    if mainType == null || subType == null then Left("Media type and subtype must not be null")
    else if mainType.length > MaxWireLength || subType.length > MaxWireLength then
      Left(s"Media type or subtype exceeds $MaxWireLength characters")
    else
      val normalizedMain = asciiLower(mainType)
      val normalizedSub  = asciiLower(subType)
      Either.cond(
        normalizedMain.matches(tokenPattern) && normalizedSub.matches(tokenPattern),
        normalizedMain -> normalizedSub,
        "Media type and subtype must be valid tokens",
      )

  private def parseParameters(text: String): Either[String, Map[String, String]] =
    var index      = 0
    var parameters = Map.empty[String, String]

    while index < text.length do
      index = skipWhitespace(text, index)
      if index >= text.length then return Left("Media type parameter must follow ';'")

      val nameStart = index
      while index < text.length && text.charAt(index) != '=' && text.charAt(index) != ';' do index += 1
      if index >= text.length || text.charAt(index) != '=' then return Left("Media type parameter must contain '='")

      val name = asciiLower(trimOws(text.substring(nameStart, index)))
      if !name.matches(tokenPattern) then return Left(s"Invalid media type parameter name '$name'")
      if parameters.contains(name) then return Left(s"Duplicate media type parameter '$name'")

      index += 1
      index = skipWhitespace(text, index)
      if index >= text.length then return Left(s"Missing media type parameter value for '$name'")

      val valueStart = index
      val valueEnd   =
        if text.charAt(index) == '"' then
          scanQuotedValue(text, index) match
            case Left(message) => return Left(s"Invalid media type parameter value for '$name': $message")
            case Right(end)    => end
        else
          while index < text.length && text.charAt(index) != ';' do index += 1
          index

      if text.charAt(valueStart) == '"' then index = valueEnd
      val parameter = trimOws(text.substring(valueStart, valueEnd))
      validateParameterValue(parameter) match
        case Left(message) => return Left(s"Invalid media type parameter value for '$name': $message")
        case Right(_)      => ()

      index = skipWhitespace(text, index)
      if index < text.length then
        if text.charAt(index) != ';' then return Left(s"Unexpected character after media type parameter '$name'")
        index += 1
        if skipWhitespace(text, index) >= text.length then return Left("Media type parameter must follow ';'")

      if parameters.size >= MaxParameters then return Left(s"Media type has more than $MaxParameters parameters")
      parameters = parameters.updated(name, parameter)

    Right(parameters)

  private def validateProgrammaticParameters(parameters: Map[String, String]): Either[String, Map[String, String]] =
    parameters.toList.foldLeft[Either[String, Map[String, String]]](Right(Map.empty)) { case (result, (rawName, value)) =>
      result.flatMap { validated =>
        if rawName == null || value == null then Left("Media type parameter name and value must not be null")
        else if rawName.length > MaxWireLength || value.length > MaxWireLength then
          Left(s"Media type parameter exceeds $MaxWireLength characters")
        else
          val name = asciiLower(rawName)
          for
            _ <- Either.cond(name.matches(tokenPattern), (), s"Invalid media type parameter name '$name'")
            _ <- Either.cond(!validated.contains(name), (), s"Duplicate media type parameter '$name'")
            _ <- validateParameterValue(value).left.map(message => s"Invalid media type parameter value for '$name': $message")
          yield validated.updated(name, value)
      }
    }

  private def validateParameterValue(value: String): Either[String, Unit] =
    if value.matches(tokenPattern) then Right(())
    else if value.startsWith("\"") then
      scanQuotedValue(value, 0).flatMap(end => Either.cond(end == value.length, (), "characters follow the quoted value"))
    else Left("value must be a token or quoted string")

  private def scanQuotedValue(value: String, start: Int): Either[String, Int] =
    var index = start + 1
    while index < value.length do
      val current = value.charAt(index)
      if current == '"' then return Right(index + 1)
      else if current == '\\' then
        index += 1
        if index >= value.length then return Left("unterminated escape")
        if !isQuotedPairCharacter(value.charAt(index)) then return Left("escaped control character")
      else if !isQuotedTextCharacter(current) then return Left("control character")
      index += 1
    Left("unterminated quoted string")

  private def isQuotedTextCharacter(value: Char): Boolean =
    value == '\t' || value == ' ' || value == '!' ||
      (value >= '#' && value <= '[') || (value >= ']' && value <= '~') || value >= '\u0080'

  private def isQuotedPairCharacter(value: Char): Boolean =
    value == '\t' || value == ' ' || (value >= '!' && value <= '~') || value >= '\u0080'

  private def skipWhitespace(value: String, start: Int): Int =
    var index = start
    while index < value.length && (value.charAt(index) == ' ' || value.charAt(index) == '\t') do index += 1
    index

  private def trimOws(value: String): String =
    var start = 0
    var end   = value.length
    while start < end && (value.charAt(start) == ' ' || value.charAt(start) == '\t') do start += 1
    while end > start && (value.charAt(end - 1) == ' ' || value.charAt(end - 1) == '\t') do end -= 1
    value.substring(start, end)

  private def containsForbiddenControl(value: String): Boolean =
    value.exists(char => (char < ' ' && char != '\t') || char == '\u007f')

  /** RFC media tokens are ASCII; folding them must not depend on the host locale. */
  private def asciiLower(value: String): String =
    val builder = new StringBuilder(value.length)
    var index   = 0
    while index < value.length do
      val char = value.charAt(index)
      builder.append(if char >= 'A' && char <= 'Z' then (char + 32).toChar else char)
      index += 1
    builder.result()

end MediaTypeText
