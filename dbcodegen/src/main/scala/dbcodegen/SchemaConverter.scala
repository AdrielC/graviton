package dbcodegen

import schemacrawler.schema._
import schemacrawler.tools.utility.SchemaCrawlerUtility
import us.fatehi.utility.datasource.DatabaseConnectionSource

import java.sql.{JDBCType, SQLType, Types}
import java.util.Locale
import scala.jdk.CollectionConverters._
import scala.reflect.{classTag, ClassTag}
import scala.util.Try

object SchemaConverter {
  def toDataSchema(
    schema: Schema,
    connection: DatabaseConnectionSource,
    schemaTables: Seq[Table],
    config: CodeGeneratorConfig,
  ): DataSchema = {
    val schemaName = Option(schema.getName).filter(_.nonEmpty).getOrElse("schema")

    val (tables, enums) = schemaTables.collect {
      case table if config.schemaTableFilter(schemaName, table.getName) =>
        val usableColumns = table.getColumns.asScala.filter(column => !column.isHidden)

        val pgColumnInfo = {
          val conn = connection.get()
          try PgTypeResolver.resolveColumns(schemaName, table.getName, conn)
          finally { val _ = connection.releaseConnection(conn); () }
        }

        val (columns, columnEnums) = usableColumns.collect { case column =>
          val colName               = column.getName
          val pgInfo                 = pgColumnInfo
            .get(colName)
            .orElse(pgColumnInfo.get(colName.toLowerCase))
            .orElse(pgColumnInfo.get(colName.toUpperCase))
          val (scalaType, dataEnum) = columnToScalaType(schema, connection, column, pgInfo, config)
          val dataColumn = DataColumn(
            column.getName,
            scalaType,
            column,
          )

          (dataColumn, dataEnum)
        }.unzip

        val columnsMap = columns.map(c => c.name -> c).toMap

        val indices = table.getIndexes.asScala.map { index =>
          val indexColumns = index.getColumns.asScala.flatMap(column => columnsMap.get(column.getName))

          DataIndex(
            index.getShortName.stripPrefix(table.getName + "."),
            indexColumns.toSeq,
            index,
          )
        }

        val dataTable = DataTable(
          table.getName,
          columns.toSeq,
          indices.toSeq,
          table,
        )

        (dataTable, columnEnums)
    }.unzip

    DataSchema(
      schemaName,
      tables.distinct,
      enums.flatMap(_.flatten).distinct,
      schema,
    )
  }

  def columnToScalaType(
    schema: Schema,
    connection: DatabaseConnectionSource,
    column: Column,
    pgInfo: Option[PgTypeResolver.ColumnInfo],
    config: CodeGeneratorConfig,
  ): (String, Option[DataEnum]) = {
    val _ = schema
    pgInfo match {
      case Some(info) =>
        info.enumLabels match {
          case Some(labels) =>
            val enumName = info.arrayElemType.getOrElse(info.typname)
            val dataEnum = DataEnum(enumName, labels.map(DataEnumValue(_)))
            val base     = dataEnum.scalaName
            val withArr  = if (info.typcategory == "A") s"Vector[$base]" else base
            val scalaT   = if (column.isNullable && !column.isPartOfPrimaryKey) s"Option[$withArr]" else withArr
            (scalaT, Some(dataEnum))
          case None =>
            val typeName = info.arrayElemType.orElse(info.rangeSubType).getOrElse(info.typname)
            val targetType =
              localTypeNameToSqlType(connection, typeName).getOrElse(column.getColumnDataType.getJavaSqlType)
            val scalaTypeClassGuess   = sqlToScalaType(targetType)
            val scalaTypeStringGuess  = scalaTypeClassGuess.map(_.toString.replaceFirst("java\\.lang\\.", ""))
            val scalaTypeString       =
              config.typeMapping(targetType, scalaTypeStringGuess).orElse(scalaTypeStringGuess).getOrElse("String")
            val withRange = if (info.rangeSubType.isDefined) s"PgRange[$scalaTypeString]" else scalaTypeString
            val withArr   = if (info.typcategory == "A") s"Vector[$withRange]" else withRange
            val scalaT    = if (column.isNullable && !column.isPartOfPrimaryKey) s"Option[$withArr]" else withArr
            (scalaT, None)
        }
      case None =>
        val tpe = column.getColumnDataType
        val (enumValues, arrayElementType) = (tpe.getJavaSqlType.getVendorTypeNumber.intValue(), tpe.getName) match {
          case (Types.ARRAY, elementTypeString) if elementTypeString.startsWith("_") =>
            val elementType            = elementTypeString.stripPrefix("_")
            val schemaRetrievalOptions = SchemaCrawlerUtility.matchSchemaRetrievalOptions(connection)
            val enumType               = schemaRetrievalOptions.getEnumDataTypeHelper.getEnumDataTypeInfo(column, tpe, connection.get())
            (enumType.getEnumValues, Some(elementType))
          case (_, _) =>
            (tpe.getEnumValues, None)
        }

        val (baseScalaType, dataEnum) = enumValues match {
          case enumValues if enumValues.isEmpty =>
            val targetType =
              arrayElementType
                .flatMap(localTypeNameToSqlType(connection, _))
                .orElse(localTypeNameToSqlType(connection, tpe.getName))
                .getOrElse(tpe.getJavaSqlType)
            val scalaTypeClassGuess   = sqlToScalaType(targetType)
            val scalaTypeStringGuess  = scalaTypeClassGuess.map(_.toString.replaceFirst("java\\.lang\\.", ""))
            val scalaTypeStringMapped = config.typeMapping(targetType, scalaTypeStringGuess)
            val scalaTypeString       = scalaTypeStringMapped.orElse(scalaTypeStringGuess).getOrElse("String")
            (scalaTypeString, None)
          case enumValues =>
            val targetTypeName = arrayElementType.getOrElse(tpe.getName)
            val dataEnum       = DataEnum(targetTypeName, enumValues.asScala.map(DataEnumValue(_)).toSeq)
            (dataEnum.scalaName, Some(dataEnum))
        }

        val scalaTypeWithArray = if (arrayElementType.isDefined) s"Vector[$baseScalaType]" else baseScalaType
        val scalaType          = if (column.isNullable && !column.isPartOfPrimaryKey) s"Option[$scalaTypeWithArray]" else scalaTypeWithArray
        (scalaType, dataEnum)
    }
  }

  def sqlToScalaType(tpe: SQLType): Option[ClassTag[?]] = Some(tpe.getVendorTypeNumber.intValue()).collect {
    case Types.OTHER | Types.VARCHAR | Types.CHAR | Types.LONGVARCHAR | Types.NVARCHAR | Types.LONGNVARCHAR => classTag[String]
    case Types.DISTINCT                                                                                     => classTag[String]
    case Types.BOOLEAN | Types.BIT                                                                          => classTag[Boolean]
    case Types.INTEGER                                                                                      => classTag[Int]
    case Types.TINYINT                                                                                      => classTag[Byte]
    case Types.SMALLINT                                                                                     => classTag[Short]
    case Types.BIGINT                                                                                       => classTag[Long]
    case Types.REAL | Types.FLOAT | Types.DOUBLE                                                            => classTag[Double]
    case Types.DECIMAL | Types.NUMERIC                                                                      => classTag[java.math.BigDecimal]
    case Types.TIME                                                                                         => classTag[java.time.LocalTime]
    case Types.DATE                                                                                         => classTag[java.time.LocalDate]
    case Types.TIMESTAMP                                                                                    => classTag[java.time.LocalDateTime]
    case Types.TIME_WITH_TIMEZONE                                                                           => classTag[java.time.OffsetTime]
    case Types.TIMESTAMP_WITH_TIMEZONE                                                                      => classTag[java.time.OffsetDateTime]
    case Types.BLOB | Types.VARBINARY | Types.LONGVARBINARY | Types.BINARY                                  => classTag[java.sql.Blob]
    case Types.CLOB                                                                                         => classTag[java.sql.Clob]
    case Types.NCLOB                                                                                        => classTag[java.sql.NClob]
    case Types.ROWID                                                                                        => classTag[java.sql.RowId]
    case Types.ARRAY                                                                                        => classTag[java.sql.Array]
    case Types.STRUCT                                                                                       => classTag[java.sql.Struct]
    case Types.DATALINK                                                                                     => classTag[java.net.URL]
  }

  private val vendorSpecificTypeMappings: Map[String, SQLType] = Map(
    // PostgreSQL-specific aliases
    "TEXT"                 -> JDBCType.LONGVARCHAR,
    "UUID"                 -> JDBCType.LONGVARCHAR,
    "JSON"                 -> JDBCType.OTHER,
    "JSONB"                -> JDBCType.OTHER,
    "INT2"                 -> JDBCType.SMALLINT,
    "INT"                  -> JDBCType.INTEGER,
    "INT4"                 -> JDBCType.INTEGER,
    "INT8"                 -> JDBCType.BIGINT,
    "FLOAT4"               -> JDBCType.FLOAT,
    "FLOAT8"               -> JDBCType.DOUBLE,
    "MONEY"                -> JDBCType.DECIMAL,
    "BPCHAR"               -> JDBCType.CHAR,
    "NAME"                 -> JDBCType.VARCHAR,
    "OID"                  -> JDBCType.BIGINT,
    "REGCLASS"             -> JDBCType.BIGINT,
    "BYTEA"                -> JDBCType.BINARY,
    "TIMESTAMPTZ"          -> JDBCType.TIMESTAMP_WITH_TIMEZONE,
    "TIMETZ"               -> JDBCType.TIME_WITH_TIMEZONE,
    "BOOL"                 -> JDBCType.BOOLEAN,
    "SERIAL"               -> JDBCType.BIGINT,
    "BIGSERIAL"            -> JDBCType.BIGINT,
    "SMALLSERIAL"          -> JDBCType.INTEGER,
    // MySQL variants
    "TINYTEXT"             -> JDBCType.LONGVARCHAR,
    "MEDIUMTEXT"           -> JDBCType.LONGVARCHAR,
    "LONGTEXT"             -> JDBCType.LONGVARCHAR,
    "TINYBLOB"             -> JDBCType.BINARY,
    "MEDIUMBLOB"           -> JDBCType.BLOB,
    "LONGBLOB"             -> JDBCType.BLOB,
    "DATETIME"             -> JDBCType.TIMESTAMP,
    "YEAR"                 -> JDBCType.SMALLINT,
    // Oracle variants
    "VARCHAR2"             -> JDBCType.VARCHAR,
    "NVARCHAR2"            -> JDBCType.NVARCHAR,
    "NUMBER"               -> JDBCType.NUMERIC,
    "RAW"                  -> JDBCType.BINARY,
    "TIMESTAMP WITH TIME ZONE"       -> JDBCType.TIMESTAMP_WITH_TIMEZONE,
    "TIMESTAMP WITH LOCAL TIME ZONE" -> JDBCType.TIMESTAMP,
    // SQL Server variants
    "UNIQUEIDENTIFIER"     -> JDBCType.OTHER,
    "DATETIME2"            -> JDBCType.TIMESTAMP,
    "SMALLDATETIME"        -> JDBCType.TIMESTAMP,
    "DATETIMEOFFSET"       -> JDBCType.TIMESTAMP_WITH_TIMEZONE,
    "NTEXT"                -> JDBCType.LONGNVARCHAR,
    "NVARCHAR"             -> JDBCType.NVARCHAR,
    "NCHAR"                -> JDBCType.NCHAR,
    "IMAGE"                -> JDBCType.LONGVARBINARY,
    // Generic aliases
    "VARCHAR"              -> JDBCType.VARCHAR,
    "DECIMAL"              -> JDBCType.DECIMAL,
    "NUMERIC"              -> JDBCType.NUMERIC,
    "BOOLEAN"              -> JDBCType.BOOLEAN,
    "TIME"                 -> JDBCType.TIME,
    "DATE"                 -> JDBCType.DATE,
    "TIMESTAMP"            -> JDBCType.TIMESTAMP,
  )

  def localTypeNameToSqlType(
    connection: DatabaseConnectionSource,
    localTypeName: String,
  ): Option[SQLType] = {
    val trimmed = Option(localTypeName).map(_.trim).filter(_.nonEmpty)
    trimmed.flatMap { name =>
      val upper = name.toUpperCase(Locale.ROOT)

      vendorSpecificTypeNameToSqlType(upper)
        .orElse(Try(JDBCType.valueOf(upper)).toOption)
        .orElse(typeInfoSqlType(connection, name))
    }
  }

  private def vendorSpecificTypeNameToSqlType(typeName: String): Option[SQLType] =
    vendorSpecificOverrides.get(typeName).orElse {
      if (typeName.startsWith("_")) Some(JDBCType.ARRAY) else None
    }

  private def typeInfoSqlType(
    connection: DatabaseConnectionSource,
    typeName: String,
  ): Option[SQLType] = {
    val conn = connection.get()
    try {
      val typeInfo = conn.getMetaData.getTypeInfo
      try {
        Iterator
          .continually(())
          .takeWhile(_ => typeInfo.next())
          .collectFirst { _ =>
            Option(typeInfo.getString("TYPE_NAME"))
              .filter(_.equalsIgnoreCase(typeName))
              .flatMap(_ => Try(JDBCType.valueOf(typeInfo.getInt("DATA_TYPE"))).toOption)
          }
          .flatten
      } finally typeInfo.close()
    } finally {
      val _ = connection.releaseConnection(conn)
      ()
    }
  
  def localTypeNameToSqlType(localTypeName: String): Option[SQLType] = {
    val upper            = localTypeName.toUpperCase(Locale.ROOT)
    lazy val normalized  = if (upper.startsWith("_")) upper.drop(1) else upper
    if (upper.startsWith("_")) Some(JDBCType.ARRAY)
    else
      vendorSpecificTypeMappings
        .get(upper)
        .orElse(vendorSpecificTypeMappings.get(normalized))
        .orElse(Try(JDBCType.valueOf(upper)).toOption)
  }
}
