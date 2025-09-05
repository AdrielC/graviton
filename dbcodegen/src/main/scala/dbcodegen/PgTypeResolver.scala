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
      arrayElemType: Option[String],
      enumLabels: Option[Seq[String]],
      rangeSubType: Option[String]
  )

  private val columnQuery =
    """|
       |WITH RECURSIVE col AS (
       |  SELECT c.oid AS relid,
       |         a.attnum,
       |         a.attname AS column_name,
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
       |  SELECT col.relid, col.attnum, col.column_name,
       |         base.oid, base.typtype, base.typcategory, base.typname,
       |         base.typbasetype, base.typelem, col.depth + 1
       |  FROM col
       |  JOIN pg_type base ON col.typtype = 'd' AND base.oid = col.typbasetype
       |),
       |resolved AS (
       |  SELECT DISTINCT ON (relid, attnum)
       |    relid, attnum, column_name, type_oid, typtype, typcategory, typname, typelem
       |  FROM col
       |  ORDER BY relid, attnum, depth DESC
       |)
       |SELECT column_name,
       |       typtype,
       |       typcategory,
       |       typname,
       |       CASE WHEN typcategory = 'A' THEN format_type(typelem, NULL) END AS array_elem_type,
       |       CASE WHEN typtype = 'r' THEN (
       |         SELECT format_type(sub.oid, NULL)
       |         FROM pg_range rg JOIN pg_type sub ON sub.oid = rg.rngsubtype
       |         WHERE rg.rngtypid = type_oid
       |       ) END AS range_subtype
       |FROM resolved
       |""".stripMargin

  private val enumQuery =
    "SELECT enumlabel FROM pg_type t JOIN pg_enum e ON e.enumtypid = t.oid WHERE t.typname = ? ORDER BY e.enumsortorder"

  def resolveColumns(schema: String, table: String, source: Connection): Map[String, ColumnInfo] = {
    val ps: PreparedStatement = source.prepareStatement(columnQuery)
    ps.setString(1, schema)
    ps.setString(2, table)
    val rs = ps.executeQuery()
    val buf = mutable.Map.empty[String, ColumnInfo]
    while (rs.next()) {
      val column        = rs.getString("column_name")
      val typtype       = rs.getString("typtype")
      val typcategory   = rs.getString("typcategory")
      val typname       = rs.getString("typname")
      val arrayElemType = Option(rs.getString("array_elem_type"))
      val rangeSubtype  = Option(rs.getString("range_subtype"))
      val enumLabels =
        enumLabelsFor(if (typtype == "e") typname else arrayElemType.orNull, source)
      buf += column -> ColumnInfo(typtype, typcategory, typname, arrayElemType, enumLabels, rangeSubtype)
    }
    rs.close()
    ps.close()
    buf.toMap
  }

  private def enumLabelsFor(tpe: String, conn: Connection): Option[Seq[String]] =
    Option(tpe).flatMap { name =>
      val ps   = conn.prepareStatement(enumQuery)
      ps.setString(1, name)
      val rs   = ps.executeQuery()
      val list = mutable.ListBuffer.empty[String]
      while (rs.next()) list += rs.getString(1)
      rs.close()
      ps.close()
      if (list.isEmpty) None else Some(list.toList)
    }
}
