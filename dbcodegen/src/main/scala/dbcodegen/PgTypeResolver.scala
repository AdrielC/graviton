package dbcodegen

import java.sql.{Connection, PreparedStatement}
import scala.collection.mutable

/** Resolves Postgres specific type information such as domains,
  * enums, arrays and ranges by querying pg_catalog directly.
  */
object PgTypeResolver {
  
  final case class ColumnInfo(
      typtype: String,
      typcategory: String,
      typname: String,
      typeSchema: String,
      arrayElemType: Option[String],
      arrayElemSchema: Option[String],
      enumLabels: Option[Seq[String]],
      rangeSubType: Option[String],
      rangeSubTypeSchema: Option[String],
      domainName: Option[String],
  )

  private val columnQuery =
    """|
       |WITH RECURSIVE col AS (
       |  SELECT c.oid AS relid,
       |         a.attnum,
       |         a.attname AS columnName,
       |         t.oid AS type_oid,
       |         t.typtype,
       |         t.typcategory,
       |         t.typname,
       |         t.typbasetype,
       |         t.typelem,
       |         0 AS depth
       |  FROM pg_class c
       |  JOIN pg_namespace n ON n.oid = c.relnamespace
       |  JOIN pg_attribute a ON a.attrelid = c.oid
       |  JOIN pg_type t ON t.oid = a.atttypid
       |  WHERE n.nspname = ? AND c.relname = ? AND a.attnum > 0 AND NOT a.attisdropped
       |  UNION ALL
       |  SELECT col.relid, col.attnum, col.columnName,
       |         base.oid, base.typtype, base.typcategory, base.typname,
       |         base.typbasetype, base.typelem, col.depth + 1
       |  FROM col
       |  JOIN pg_type base ON col.typtype = 'd' AND base.oid = col.typbasetype
       |),
       |resolved AS (
       |  SELECT DISTINCT ON (relid, attnum)
       |    relid, attnum, columnName, type_oid, typtype, typcategory, typname, typelem
       |  FROM col
       |  ORDER BY relid, attnum, depth DESC
       |)
       |SELECT r.columnName,
       |       r.typtype,
       |       r.typcategory,
       |       r.typname,
       |       tn.nspname AS type_schema,
       |       CASE WHEN r.typcategory = 'A' THEN (
       |         SELECT elem.typname FROM pg_type elem WHERE elem.oid = r.typelem
       |       ) END AS array_elem_type,
       |       CASE WHEN r.typcategory = 'A' THEN (
       |         SELECT en.nspname
       |         FROM pg_type elem
       |         JOIN pg_namespace en ON en.oid = elem.typnamespace
       |         WHERE elem.oid = r.typelem
       |       ) END AS array_elem_schema,
       |       CASE WHEN r.typtype = 'r' THEN (
       |         SELECT sub.typname
       |         FROM pg_range rg JOIN pg_type sub ON sub.oid = rg.rngsubtype
       |         WHERE rg.rngtypid = r.type_oid
       |       ) END AS range_subtype,
       |       CASE WHEN r.typtype = 'r' THEN (
       |         SELECT sn.nspname
       |         FROM pg_range rg
       |         JOIN pg_type sub ON sub.oid = rg.rngsubtype
       |         JOIN pg_namespace sn ON sn.oid = sub.typnamespace
       |         WHERE rg.rngtypid = r.type_oid
       |       ) END AS range_subtype_schema
       |FROM resolved r
       |JOIN pg_type rt ON rt.oid = r.type_oid
       |JOIN pg_namespace tn ON tn.oid = rt.typnamespace
       |""".stripMargin

  private val enumQuery =
    """SELECT e.enumlabel
       |FROM pg_type t
       |JOIN pg_namespace n ON n.oid = t.typnamespace
       |JOIN pg_enum e ON e.enumtypid = t.oid
       |WHERE n.nspname = ? AND t.typname = ?
       |ORDER BY e.enumsortorder
       |""".stripMargin

  private val enumsInSchemaQuery =
    """SELECT t.typname, e.enumlabel
       |FROM pg_type t
       |JOIN pg_namespace n ON n.oid = t.typnamespace
       |JOIN pg_enum e ON e.enumtypid = t.oid
       |WHERE n.nspname = ?
       |ORDER BY t.typname, e.enumsortorder
       |""".stripMargin

  private val domainQuery =
    "SELECT domain_name FROM information_schema.columns WHERE table_schema = ? AND table_name = ? AND column_name = ?"

  def resolveColumns(schema: String, table: String, source: Connection): Map[String, ColumnInfo] = {
    val ps: PreparedStatement = source.prepareStatement(columnQuery)
    ps.setString(1, schema)
    ps.setString(2, table)
    val rs = ps.executeQuery()
    val domainPs = source.prepareStatement(domainQuery)
    val buf = mutable.Map.empty[String, ColumnInfo]
    while (rs.next()) {
      val column        = rs.getString("columnName")
      val typtype       = rs.getString("typtype")
      val typcategory   = rs.getString("typcategory")
      val typname       = rs.getString("typname")
      val typeSchema    = rs.getString("type_schema")
      val arrayElemType = Option(rs.getString("array_elem_type"))
      val arrayElemSchema = Option(rs.getString("array_elem_schema"))
      val rangeSubtype  = Option(rs.getString("range_subtype"))
      val rangeSubtypeSchema = Option(rs.getString("range_subtype_schema"))
      val enumLabels =
        if (typtype == "e") enumLabelsFor(typeSchema, typname, source)
        else
          (for
            elemName   <- arrayElemType
            elemSchema <- arrayElemSchema
          yield enumLabelsFor(elemSchema, elemName, source)).flatten
      val domainName   = domainNameFor(domainPs, schema, table, column)
      buf += column -> ColumnInfo(
        typtype = typtype,
        typcategory = typcategory,
        typname = typname,
        typeSchema = typeSchema,
        arrayElemType = arrayElemType,
        arrayElemSchema = arrayElemSchema,
        enumLabels = enumLabels,
        rangeSubType = rangeSubtype,
        rangeSubTypeSchema = rangeSubtypeSchema,
        domainName = domainName,
      )
    }
    rs.close()
    ps.close()
    domainPs.close()
    buf.toMap
  }

  def resolveEnums(schema: String, source: Connection): Seq[(String, Seq[String])] = {
    val ps = source.prepareStatement(enumsInSchemaQuery)
    ps.setString(1, schema)
    val rs = ps.executeQuery()
    try {
      val buf = mutable.LinkedHashMap.empty[String, mutable.ListBuffer[String]]
      while (rs.next()) {
        val tpe   = rs.getString(1)
        val label = rs.getString(2)
        buf.getOrElseUpdate(tpe, mutable.ListBuffer.empty) += label
      }
      buf.iterator.map { case (name, labels) => (name, labels.toList) }.toList
    } finally {
      rs.close()
      ps.close()
    }
  }

  private def enumLabelsFor(schema: String, tpe: String, conn: Connection): Option[Seq[String]] =
    Option(tpe).flatMap { name =>
      val ps   = conn.prepareStatement(enumQuery)
      ps.setString(1, schema)
      ps.setString(2, name)
      val rs   = ps.executeQuery()
      val list = mutable.ListBuffer.empty[String]
      while (rs.next()) list += rs.getString(1)
      rs.close()
      ps.close()
      if (list.isEmpty) None else Some(list.toList)
    }

  private def domainNameFor(ps: PreparedStatement, schema: String, table: String, column: String): Option[String] = {
    ps.setString(1, schema)
    ps.setString(2, table)
    ps.setString(3, column)
    val rs = ps.executeQuery()
    try if (rs.next()) Option(rs.getString(1)).filter(_.nonEmpty) else None
    finally rs.close()
  }
}
