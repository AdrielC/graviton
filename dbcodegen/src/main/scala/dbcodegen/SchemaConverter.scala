package dbcodegen

import schemacrawler.crawl.SchemaCrawlerExt
import schemacrawler.schema._
import schemacrawler.tools.utility.SchemaCrawlerUtility
import us.fatehi.utility.datasource.DatabaseConnectionSource

import java.sql.{JDBCType, SQLType, Types}
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
          finally connection.releaseConnection(conn)
        }

        val (columns, columnEnums) = usableColumns.collect { case column =>
          val pgInfo                 = pgColumnInfo.get(column.getName)
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
            val targetType = localTypeNameToSqlType(typeName).getOrElse(column.getColumnDataType.getJavaSqlType)
            val scalaTypeClassGuess   = sqlToScalaType(targetType)
            val scalaTypeStringGuess  = scalaTypeClassGuess.map(_.toString.replaceFirst("java\\.lang\\.", ""))
            val scalaTypeString       =
              config.typeMapping(targetType, scalaTypeStringGuess).getOrElse(throw new Exception(s"Cannot map sql type '$targetType'"))
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
            val columnDataType         = SchemaCrawlerExt.newColumnDataType(schema, elementType, DataTypeType.system)
            val conn                   = connection.get()
            val schemaRetrievalOptions = SchemaCrawlerUtility.matchSchemaRetrievalOptions(connection)
            val enumType               = schemaRetrievalOptions.getEnumDataTypeHelper.getEnumDataTypeInfo(column, columnDataType, conn)
            connection.releaseConnection(conn)
            (enumType.getEnumValues, Some(elementType))
          case (_, _) =>
            (tpe.getEnumValues, None)
        }

        val (baseScalaType, dataEnum) = enumValues match {
          case enumValues if enumValues.isEmpty =>
            val targetType =
              arrayElementType.flatMap(localTypeNameToSqlType).orElse(localTypeNameToSqlType(tpe.getName)).getOrElse(tpe.getJavaSqlType)
            val scalaTypeClassGuess   = sqlToScalaType(targetType)
            val scalaTypeStringGuess  = scalaTypeClassGuess.map(_.toString.replaceFirst("java\\.lang\\.", ""))
            val scalaTypeStringMapped = config.typeMapping(targetType, scalaTypeStringGuess)
            val scalaTypeString       = scalaTypeStringMapped.getOrElse(throw new Exception(s"Cannot map sql type '${targetType}'"))
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

  def localTypeNameToSqlType(localTypeName: String): Option[SQLType] = localTypeName.toUpperCase match {
    // TODO: specific to Postgres
    case "TEXT" | "UUID"  => Some(JDBCType.LONGVARCHAR)
    case "JSON" | "JSONB" => Some(JDBCType.OTHER)
    case "INT2"           => Some(JDBCType.SMALLINT)
    case "INT" | "INT4"   => Some(JDBCType.INTEGER)
    case "INT8"           => Some(JDBCType.BIGINT)
    case "FLOAT4"         => Some(JDBCType.FLOAT)
    case "FLOAT8"         => Some(JDBCType.DOUBLE)
    case "MONEY"          => Some(JDBCType.DECIMAL)
    case other            => Try(JDBCType.valueOf(other)).toOption
  }
}
