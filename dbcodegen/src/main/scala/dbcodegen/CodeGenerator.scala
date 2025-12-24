package dbcodegen

import java.nio.file.{Path, Files}
import java.io.File
import java.util.Locale
import scala.collection.immutable.Seq
import scala.collection.mutable.ListBuffer
import org.slf4j.LoggerFactory
import schemacrawler.tools.utility.SchemaCrawlerUtility
import schemacrawler.schemacrawler.SchemaCrawlerOptionsBuilder
import scala.util.boundary
import scala.jdk.CollectionConverters.given
import scala.meta.*
import scala.meta.prettyprinters.Syntax

import CheckConstraintParser.*

extension (file: File)
  def toOption: Option[File] = Option(file)

object CodeGenerator {

  private lazy val log = LoggerFactory.getLogger(getClass())

  enum Mode(override val toString: String):
    case Production extends Mode("production")
    case Development extends Mode("development")
  end Mode
  
  object Mode:
    given Conversion[Mode, String] = _.toString
    def fromString(s: String): Option[Mode] =
      Option(s).map(_.trim.toLowerCase(Locale.ROOT)).collect {
        case "production"  => Production
        case "development" => Development
      }
  end Mode

  sealed trait CodegenError:
    def message: String

  object CodegenError:
    final case class ParseError(message: String) extends CodegenError
    final case class WriteError(message: String) extends CodegenError

  case class SchemaCrawlerOptions(
    quoteIdentifiers: Boolean = true,
    excludeSchemaPattern: String = "pg_catalog",
    includeTables: Boolean = true,
    includeViews: Boolean = true,
    includeSequences: Boolean = true,
    includeSchemas: Boolean = true,
    includePackages: Boolean = true,
    includeConstraints: Boolean = true,
    includeCheckConstraints: Boolean = true,
    includeTableConstraints: Boolean = true,
  ):
    def toConfig: schemacrawler.tools.options.Config = {
      val map = 
        (productElementNames zip productIterator)
        .filter: 
          case (name, value) => value != null && value != ""
        .toMap.asJava
      schemacrawler.tools.options.Config(map)
    }

  object SchemaCrawlerOptions:
    val default = SchemaCrawlerOptions()
  end SchemaCrawlerOptions

  def generate(
    jdbcUrl: String,
    username: Option[String],
    password: Option[String],
    config: CodeGeneratorConfig,
  ): Either[CodegenError, Seq[Path]] = {
    boundary[Either[CodegenError, Seq[Path]]] {
      val debugProgress = sys.env.get("DBCODEGEN_DEBUG_PROGRESS").contains("1")
      log.debug(s"JDBC URL: $jdbcUrl, Username: $username")

      // Create database connection
      val ds = DbConnectionSource(jdbcUrl, username, password)

      // Set up schema crawler
      val crawlOpts = SchemaCrawlerOptionsBuilder.newSchemaCrawlerOptions()
      val retrOpts  = SchemaCrawlerUtility.matchSchemaRetrievalOptions(ds)
      val catalog   = SchemaCrawlerUtility.getCatalog(ds, retrOpts, crawlOpts, config.schemaCrawlerOptions.toConfig)

      // Group tables by schema
      val schemas = catalog.getSchemas().asScala.toSeq
      val tablesPerSchema = schemas.map { schema =>
        val tables = catalog.getTables(schema).asScala.toSeq
        schema -> tables
      }.toMap

      // Generate code for each schema (one file per schema).
      // IMPORTANT: schemas live under distinct subpackages to avoid name collisions (e.g. shared enums).
      val generated = ListBuffer.empty[Path]

      val iter = tablesPerSchema.toSeq.iterator
      while (iter.hasNext) {
        val (schema, tables) = iter.next()
        if debugProgress then println(s"[dbcodegen] schema=${schema.getName} tables=${tables.size}")
        val dataSchema = SchemaConverter.toDataSchema(schema, ds, tables, config)
        if debugProgress then println(s"[dbcodegen] converted schema=${dataSchema.name} tables=${dataSchema.tables.size} enums=${dataSchema.enums.size}")
        if (dataSchema.tables.nonEmpty || dataSchema.enums.nonEmpty) {
          if debugProgress then println(s"[dbcodegen] rendering schema=${dataSchema.name}")
          renderScalaCode(dataSchema, config) match
            case Left(err) => boundary.break(Left(err))
            case Right(output) =>
              val outputPath = outputPathFor(config, dataSchema)
              if (!config.dryRun) {
                try {
                  Files.createDirectories(outputPath.getParent)
                  Files.writeString(outputPath, output)
                  if debugProgress then println(s"[dbcodegen] wrote $outputPath")
                  ScalafmtRunner.formatFile(outputPath)
                  if debugProgress then println(s"[dbcodegen] formatted $outputPath")
                } catch {
                  case e: Throwable =>
                    boundary.break(Left(CodegenError.WriteError(s"Failed writing '$outputPath': ${e.getMessage}")))
                }
              }
              generated += outputPath
        }
      }

      if (config.inspectConstraints) inspectConstraints(tablesPerSchema)
      Right(generated.toSeq)
    }
  }

  private def inspectConstraints(tablesPerSchema: Map[schemacrawler.schema.Schema, Seq[schemacrawler.schema.Table]]): Unit = {
    println("\n🔍 === DATABASE CONSTRAINTS INSPECTION ===")
    
    tablesPerSchema.foreach { case (schema, tables) =>
      if (tables.nonEmpty) {
        println(s"📊 Schema: ${schema.getName}")
        
        tables.foreach { table =>
          println(s"  📋 Table: ${table.getName}")
          
          // Primary Keys
          val primaryKeys = table.getPrimaryKey
          if (primaryKeys != null) {
            println(s"    🔑 Primary Key: ${primaryKeys.getName} (${primaryKeys.getConstrainedColumns.asScala.map(_.getName).mkString(", ")})")
          }
          
          // Foreign Keys
          table.getForeignKeys.asScala.foreach { fk =>
            println(s"    🔗 Foreign Key: ${fk.getName} (${fk.getColumnReferences.asScala.map(ref => s"${ref.getForeignKeyColumn.getName} -> ${ref.getPrimaryKeyColumn.getParent.getName}.${ref.getPrimaryKeyColumn.getName}").mkString(", ")})")
          }
          
          // Check Constraints
          table.getTableConstraints.asScala.foreach { constraint =>
            println(s"    ✅ Check Constraint: ${constraint.getName} - ${constraint.getDefinition}")
          }
          
          // Column constraints (NOT NULL, etc.)
          table.getColumns.asScala.foreach { column =>
            val constraints = Seq(
              if (!column.isNullable) Some("NOT NULL") else None,
              if (column.isAutoIncremented) Some("AUTO_INCREMENT") else None,
              if (column.isGenerated) Some("GENERATED") else None,
              if (column.hasDefaultValue) Some(s"DEFAULT ${column.getDefaultValue}") else None
            ).flatten
            
            if (constraints.nonEmpty) {
              println(s"    📝 Column ${column.getName}: ${constraints.mkString(", ")}")
            }
          }
        }
      }
    }
    println("=== END CONSTRAINTS INSPECTION ===\n")
  }

  private[dbcodegen] def outputPathFor(config: CodeGeneratorConfig, schema: DataSchema): Path =
    config.outputLayout match
      case OutputLayout.PerSchemaDirectory => config.outDir.resolve(schema.name).resolve("schema.scala")
      case OutputLayout.FlatFiles          => config.outDir.resolve(s"${schema.name}.scala")

  private[dbcodegen] def renderScalaCode(schema: DataSchema, config: CodeGeneratorConfig): Either[CodegenError, String] = {
    boundary[Either[CodegenError, String]] {
      val validationResults = schema.tables.map { table =>
        table.scalaName -> buildTableValidations(table)
      }.toMap

      val validationWarnings = validationResults.valuesIterator.flatMap(_.warnings).toSeq.distinct
      validationWarnings.foreach { warning => println(s"⚠️  $warning") }

      implicit val dialect: Dialect = dialects.Scala3

      val stats = ListBuffer.empty[Stat]

      stats ++= renderImports()
      stats ++= renderEnums(schema.enums.toList)
      schema.tables.foreach { table =>
        stats ++= renderTableSource(schema.name, table)
      }

      val pkgTerm = renderPackageTerm(config.basePackage, schema.name)
      val pkg     = Pkg(pkgTerm, stats.toList)

      val header =
        s"""// Code generated by dbcodegen. DO NOT EDIT.
           |// Schema: ${schema.name}
           |
           |""".stripMargin

      val rendered = header + postProcessScala3(pkg.syntax) + "\n"
      Right(rendered)
    }
  }

  private def postProcessScala3(code: String): String =
    code.linesIterator
      .map { line =>
        val trimmed = line.trim

        if trimmed.startsWith("enum ") && trimmed.contains("(value: String)") then
          // scala.meta prints plain params; ensure Scala 3-style value member + Schema derivation
          val base = line.replace("(value: String)", "(val value: String)")
          if base.contains("derives Schema") then base
          else if base.contains("{") then
            val idx = base.indexOf('{')
            base.substring(0, idx).stripTrailing() + " derives Schema " + base.substring(idx)
          else base + " derives Schema"
        else if trimmed.startsWith("@Table(PostgresDbType) final case class ") && !trimmed.contains("derives ") then
          line + " derives DbCodec, Schema"
        else if trimmed.startsWith("final case class Creator(") && !trimmed.contains("derives ") then
          line + " derives DbCodec, Schema"
        else line
      }
      .mkString("\n")

  // ---------------------------------------------------------------------------
  // Scala 3 codegen with scala.meta AST construction (no quasiquotes, no StringBuilder)
  // ---------------------------------------------------------------------------

  private def renderImports()(using Dialect): List[Stat] = {
    List(
      Import(List(Importer(termRef("com.augustnagro.magnum"), List(Importee.Wildcard())))),
      Import(List(Importer(termRef("graviton.db"), List(Importee.Wildcard(), Importee.GivenAll())))),
      Import(List(Importer(termRef("zio"), List(Importee.Name(Name.Indeterminate("Chunk")))))),
      Import(List(Importer(termRef("zio.json.ast"), List(Importee.Name(Name.Indeterminate("Json")))))),
      Import(List(Importer(termRef("zio.schema"), List(Importee.Name(Name.Indeterminate("Schema")))))),
      Import(List(Importer(termRef("zio.schema.validation"), List(Importee.Name(Name.Indeterminate("Validation")))))),
    )
  }

  private def renderEnums(enums: List[DataEnum])(using Dialect): List[Stat] =
    enums.flatMap(renderEnum)

  private def renderEnum(dataEnum: DataEnum)(using Dialect): List[Stat] = {
    val enumName = Type.Name(stripBackticks(dataEnum.scalaName))
    val enumTerm = Term.Name(stripBackticks(dataEnum.scalaName))

    val ctor =
      Ctor.Primary(
        Nil,
        Name.Anonymous(),
        List(List(Term.Param(Nil, Name.Indeterminate("value"), Some(Type.Name("String")), None))),
      )

    val enumCases: List[Stat] =
      dataEnum.values.toList.map { v =>
        val caseName = Term.Name(stripBackticks(v.scalaName))
        val init     = Init(enumName, Name.Anonymous(), List(List(Lit.String(v.name))))
        Defn.EnumCase(
          Nil,
          caseName,
          Nil,
          Ctor.Primary(Nil, Name.Anonymous(), List.empty[List[Term.Param]]),
          List(init),
        )
      }

    // NOTE: scala.meta's Scala 3 `derives` support varies by version; we set derives via a tiny token patch step
    // if necessary. Here we create a standard enum and rely on Scalafmt + syntax for formatting.
    val enumDef =
      Defn.Enum(
        mods = Nil,
        name = enumName,
        tparams = Nil,
        ctor = ctor,
        templ = Template(Nil, Nil, Self(Name.Anonymous(), None), enumCases),
      )

    val byValueVal =
      Defn.Val(
        mods = List(Mod.Private(Name.Anonymous())),
        pats = List(Pat.Var(Term.Name("byValue"))),
        decltpe = Some(Type.Apply(Type.Name("Map"), List(Type.Name("String"), enumName))),
        rhs =
          Term.Select(
            Term.Apply(
              Term.Select(
                Term.Select(Term.Select(enumTerm, Term.Name("values")), Term.Name("iterator")),
                Term.Name("map"),
              ),
              List(
                Term.Function(
                  List(Term.Param(Nil, Name.Indeterminate("v"), None, None)),
                  Term.ApplyInfix(
                    Term.Select(Term.Name("v"), Term.Name("value")),
                    Term.Name("->"),
                    Nil,
                    List(Term.Name("v")),
                  ),
                ),
              ),
            ),
            Term.Name("toMap"),
          ),
      )

    val dbCodecGiven =
      givenAlias(
        name = Name.Anonymous(),
        decltpe = Type.Apply(Type.Name("DbCodec"), List(enumName)),
        rhs = Term.Apply(
          Term.Select(
            Term.ApplyType(Term.Name("DbCodec"), List(Type.Name("String"))),
            Term.Name("biMap"),
          ),
          List(
            Term.Function(
              List(Term.Param(Nil, Name.Indeterminate("str"), None, None)),
              Term.Apply(
                Term.Select(Term.Name("byValue"), Term.Name("getOrElse")),
                List(
                  Term.Name("str"),
                  Term.Throw(
                    Term.Apply(
                      Term.Name("IllegalArgumentException"),
                      List(
                        concatString(
                          Lit.String(s"Unknown ${dataEnum.name} value '"),
                          Term.Name("str"),
                          Lit.String("'"),
                        ),
                      ),
                    ),
                  ),
                ),
              ),
            ),
            Term.AnonymousFunction(Term.Select(Term.Placeholder(), Term.Name("value"))),
          ),
        ),
      )

    val enumObj =
      Defn.Object(
        mods = Nil,
        name = enumTerm,
        templ = Template(Nil, Nil, Self(Name.Anonymous(), None), List(byValueVal, dbCodecGiven)),
      )

    List(enumDef, enumObj)
  }

  private def concatString(prefix: Lit.String, middle: Term, suffix: Lit.String)(using Dialect): Term =
    Term.ApplyInfix(
      Term.ApplyInfix(prefix, Term.Name("+"), Nil, List(middle)),
      Term.Name("+"),
      Nil,
      List(suffix),
    )

  private def renderTableSource(schemaName: String, table: DataTable)(using Dialect): List[Stat] = {
    val classNameT = Type.Name(stripBackticks(table.scalaName))
    val className  = Term.Name(stripBackticks(table.scalaName))

    val classMods = List(
      Mod.Annot(Init(Type.Name("Table"), Name.Anonymous(), List(List(Term.Name("PostgresDbType"))))),
      Mod.Final(),
      Mod.Case(),
    )

    val ctorParams = table.columns.toList.map { col =>
      val mods = ListBuffer.empty[Mod]
      if col.db.isPartOfPrimaryKey then mods += Mod.Annot(Init(Type.Name("Id"), Name.Anonymous(), List.empty[List[Term]]))
      mods += Mod.Annot(Init(Type.Name("SqlName"), Name.Anonymous(), List(List(Lit.String(col.name)))))

      val tpe = parseType(renderColumnType(col))
      Term.Param(mods.toList, Name.Indeterminate(stripBackticks(col.scalaName)), Some(tpe), None)
    }

    val cls =
      Defn.Class(
        mods = classMods,
        name = classNameT,
        tparams = Nil,
        ctor = Ctor.Primary(Nil, Name.Anonymous(), List(ctorParams)),
        templ = Template(Nil, Nil, Self(Name.Anonymous(), None), Nil),
      )

    val pkCols = table.columns.filter(_.db.isPartOfPrimaryKey).toList
    val companionStats = ListBuffer.empty[Stat]

    pkCols match
      case Nil =>
        companionStats += Defn.Type(Nil, Type.Name("Id"), Nil, Type.Name("Null"))
      case single :: Nil =>
        val baseTpe = parseType(renderColumnType(single, forceRequired = true))
        val colName = Term.Name(stripBackticks(single.scalaName))

        companionStats += Defn.Type(List(Mod.Opaque()), Type.Name("Id"), Nil, baseTpe)
        companionStats += Defn.Object(
          mods = Nil,
          name = Term.Name("Id"),
          templ = Template(
            Nil,
            Nil,
            Self(Name.Anonymous(), None),
            List(
              Defn.Def(
                Nil,
                Term.Name("apply"),
                Nil,
                List(List(Term.Param(Nil, Name.Indeterminate(colName.value), Some(baseTpe), None))),
                Some(Type.Name("Id")),
                Term.Name(colName.value),
              ),
              Defn.Def(
                Nil,
                Term.Name("unwrap"),
                Nil,
                List(List(Term.Param(Nil, Name.Indeterminate("id"), Some(Type.Name("Id")), None))),
                Some(baseTpe),
                Term.Name("id"),
              ),
            ),
          ),
        )
        companionStats += givenAlias(
          name = Name.Indeterminate("given_DbCodec_Id"),
          decltpe = Type.Apply(Type.Name("DbCodec"), List(Type.Name("Id"))),
          rhs = summonInlineBiMap(Type.Name("DbCodec"), baseTpe, Term.Name("Id"), Term.Name("Id"), Term.Name("unwrap")),
        )
        companionStats += givenAlias(
          name = Name.Indeterminate("given_Schema_Id"),
          decltpe = Type.Apply(Type.Name("Schema"), List(Type.Name("Id"))),
          rhs = summonInlineTransform(baseTpe, Term.Name("Id"), Term.Name("Id"), Term.Name("unwrap")),
        )
      case many =>
        val namedTupleTpe = parseType(renderNamedTuple(many))
        val plainTupleTpe = parseType(renderTupleType(many))

        companionStats += Defn.Type(List(Mod.Opaque()), Type.Name("Id"), Nil, namedTupleTpe)
        companionStats += Defn.Object(
          mods = Nil,
          name = Term.Name("Id"),
          templ = Template(
            Nil,
            Nil,
            Self(Name.Anonymous(), None),
            List(
              Defn.Def(
                Nil,
                Term.Name("apply"),
                Nil,
                List(
                  many.map { c =>
                    Term.Param(Nil, Name.Indeterminate(stripBackticks(c.scalaName)), Some(parseType(renderColumnType(c, forceRequired = true))), None)
                  },
                ),
                Some(Type.Name("Id")),
                Term.Tuple(
                  many.map { c =>
                    val n = Term.Name(stripBackticks(c.scalaName))
                    Term.Assign(n, n)
                  },
                ),
              ),
            ),
          ),
        )
        companionStats += givenAlias(
          name = Name.Indeterminate("given_DbCodec_Id"),
          decltpe = Type.Apply(Type.Name("DbCodec"), List(Type.Name("Id"))),
          rhs = summonInlineNamedTupleBiMap(plainTupleTpe, many),
        )
        companionStats += givenAlias(
          name = Name.Indeterminate("given_Schema_Id"),
          decltpe = Type.Apply(Type.Name("Schema"), List(Type.Name("Id"))),
          rhs = summonInlineNamedTupleTransform(plainTupleTpe, many),
        )

    if !table.isView then
      // Creator: keep current heuristics, but build as AST
      val autoPrimaryKey =
        pkCols.nonEmpty && pkCols.forall { c =>
          val db = c.db
          db.isGenerated || db.isAutoIncremented || db.hasDefaultValue
        }

      val creatorColumns = table.columns.filterNot { c =>
        val db = c.db
        val skipAutoPk = autoPrimaryKey && db.isPartOfPrimaryKey
        val skipGen    = db.isGenerated && !db.hasDefaultValue
        skipAutoPk || skipGen
      }.toList

      val creatorParams = ListBuffer.empty[Term.Param]
      if autoPrimaryKey then
        creatorParams += Term.Param(
          Nil,
          Name.Indeterminate("id"),
          Some(Type.Apply(Type.Name("Option"), List(Type.Select(className, Type.Name("Id"))))),
          Some(Term.Name("None")),
        )

      creatorColumns.foreach { c =>
        val base = parseType(baseColumnType(c))
        val db   = c.db
        val optionalFromDefault = db.hasDefaultValue || db.isGenerated || db.isAutoIncremented
        val optionalFromNull    = db.isNullable && !db.isPartOfPrimaryKey
        val isOptional          = optionalFromDefault || optionalFromNull
        val tpe = if isOptional then Type.Apply(Type.Name("Option"), List(base)) else base
        val default = if isOptional then Some(Term.Name("None")) else None
        creatorParams += Term.Param(Nil, Name.Indeterminate(stripBackticks(c.scalaName)), Some(tpe), default)
      }

      if creatorParams.nonEmpty then
        companionStats += Defn.Class(
          mods = List(Mod.Final(), Mod.Case()),
          name = Type.Name("Creator"),
          tparams = Nil,
          ctor = Ctor.Primary(Nil, Name.Anonymous(), List(creatorParams.toList)),
          templ = Template(Nil, Nil, Self(Name.Anonymous(), None), Nil),
        )
      else
        companionStats += Defn.Type(Nil, Type.Name("Creator"), Nil, Type.Name("Unit"))

    val repoVal =
      Defn.Val(
        mods = Nil,
        pats = List(Pat.Var(Term.Name("repo"))),
        decltpe = None,
        rhs =
          if table.isView then
            Term.ApplyType(Term.Name("ImmutableRepo"), List(classNameT, Type.Select(className, Type.Name("Id"))))
          else
            Term.ApplyType(
              Term.Name("Repo"),
              List(Type.Select(className, Type.Name("Creator")), classNameT, Type.Select(className, Type.Name("Id"))),
            ),
      )
    companionStats += repoVal

    // Tiny metadata surface for macros / tooling.
    val metaObj =
      Defn.Object(
        mods = Nil,
        name = Term.Name("Meta"),
        templ = Template(
          Nil,
          Nil,
          Self(Name.Anonymous(), None),
          List(
            Defn.Val(
              mods = List(Mod.Inline()),
              pats = List(Pat.Var(Term.Name("schema"))),
              decltpe = Some(Type.Name("String")),
              rhs = Lit.String(schemaName),
            ),
            Defn.Val(
              mods = List(Mod.Inline()),
              pats = List(Pat.Var(Term.Name("table"))),
              decltpe = Some(Type.Name("String")),
              rhs = Lit.String(table.name),
            ),
            Defn.Val(
              mods = List(Mod.Inline()),
              pats = List(Pat.Var(Term.Name("columns"))),
              decltpe = Some(
                Type.Apply(
                  Type.Name("List"),
                  List(Type.Tuple(List(Type.Name("String"), Type.Name("String")))),
                ),
              ),
              rhs = Term.Apply(
                Term.Name("List"),
                table.columns.toList.map { c =>
                  Term.ApplyInfix(Lit.String(stripBackticks(c.scalaName)), Term.Name("->"), Nil, List(Lit.String(c.name)))
                },
              ),
            ),
          ),
        ),
      )

    companionStats.prepend(metaObj)

    val companion =
      Defn.Object(
        mods = Nil,
        name = className,
        templ = Template(Nil, Nil, Self(Name.Anonymous(), None), companionStats.toList),
      )

    List(cls, companion)
  }

  private def termRef(path: String): Term.Ref =
    path.split('.').toList match
      case Nil => Term.Name("<?>")
      case head :: tail =>
        tail.foldLeft[Term.Ref](Term.Name(head)) { case (acc, part) => Term.Select(acc, Term.Name(part)) }

  private def parseType(typeStr: String)(using Dialect): Type =
    dialects.Scala3(typeStr).parse[Type] match
      case Parsed.Success(tpe) => tpe
      case Parsed.Error(pos, message, _) =>
        // last resort: keep compilation going with a placeholder type
        Type.Name(s"/*parse-error ${pos.startLine}:${pos.startColumn} $message*/ Any")

  private def stripBackticks(name: String): String =
    if name.startsWith("`") && name.endsWith("`") && name.length >= 2 then name.drop(1).dropRight(1) else name

  private def givenAlias(name: Name, decltpe: Type, rhs: Term)(using Dialect): Stat =
    Defn.GivenAlias(Nil, name, Nil, decltpe, rhs)

  private def summonInlineBiMap(typeClass: Type, baseTpe: Type, idObj: Term.Name, idCtor: Term.Name, unwrap: Term.Name)(using Dialect): Term = {
    // scala.compiletime.summonInline[TypeClass[Base]].biMap(value => Id(value), id => Id.unwrap(id))
    val summon = Term.ApplyType(Term.Select(termRef("scala.compiletime"), Term.Name("summonInline")), List(Type.Apply(typeClass, List(baseTpe))))
    Term.Apply(
      Term.Select(summon, Term.Name("biMap")),
      List(
        Term.Function(
          List(Term.Param(Nil, Name.Indeterminate("value"), None, None)),
          Term.Apply(idCtor, List(Term.Name("value"))),
        ),
        Term.Function(
          List(Term.Param(Nil, Name.Indeterminate("id"), None, None)),
          Term.Apply(Term.Select(idObj, unwrap), List(Term.Name("id"))),
        ),
      ),
    )
  }

  private def summonInlineTransform(baseTpe: Type, idCtor: Term.Name, idObj: Term.Name, unwrap: Term.Name)(using Dialect): Term = {
    val summon = Term.ApplyType(
      Term.Select(termRef("scala.compiletime"), Term.Name("summonInline")),
      List(Type.Apply(Type.Name("Schema"), List(baseTpe))),
    )
    Term.Apply(
      Term.Select(summon, Term.Name("transform")),
      List(
        Term.Function(
          List(Term.Param(Nil, Name.Indeterminate("value"), None, None)),
          Term.Apply(idCtor, List(Term.Name("value"))),
        ),
        Term.Function(
          List(Term.Param(Nil, Name.Indeterminate("id"), None, None)),
          Term.Apply(Term.Select(idObj, unwrap), List(Term.Name("id"))),
        ),
      ),
    )
  }

  private def summonInlineNamedTupleBiMap(plainTupleTpe: Type, cols: List[DataColumn])(using Dialect): Term = {
    val summon = Term.ApplyType(
      Term.Select(termRef("scala.compiletime"), Term.Name("summonInline")),
      List(Type.Apply(Type.Name("DbCodec"), List(plainTupleTpe))),
    )
    Term.Apply(
      Term.Select(summon, Term.Name("biMap")),
      List(
        Term.Function(
          List(Term.Param(Nil, Name.Indeterminate("value"), None, None)),
          Term.Tuple(
            cols.zipWithIndex.map { case (c, idx) =>
              val n = Term.Name(stripBackticks(c.scalaName))
              Term.Assign(n, Term.Select(Term.Name("value"), Term.Name(s"_${idx + 1}")))
            },
          ),
        ),
        Term.Function(
          List(Term.Param(Nil, Name.Indeterminate("id"), None, None)),
          Term.Tuple(cols.map(c => Term.Select(Term.Name("id"), Term.Name(stripBackticks(c.scalaName))))),
        ),
      ),
    )
  }

  private def summonInlineNamedTupleTransform(plainTupleTpe: Type, cols: List[DataColumn])(using Dialect): Term = {
    val summon = Term.ApplyType(
      Term.Select(termRef("scala.compiletime"), Term.Name("summonInline")),
      List(Type.Apply(Type.Name("Schema"), List(plainTupleTpe))),
    )
    Term.Apply(
      Term.Select(summon, Term.Name("transform")),
      List(
        Term.Function(
          List(Term.Param(Nil, Name.Indeterminate("value"), None, None)),
          Term.Tuple(
            cols.zipWithIndex.map { case (c, idx) =>
              val n = Term.Name(stripBackticks(c.scalaName))
              Term.Assign(n, Term.Select(Term.Name("value"), Term.Name(s"_${idx + 1}")))
            },
          ),
        ),
        Term.Function(
          List(Term.Param(Nil, Name.Indeterminate("id"), None, None)),
          Term.Tuple(cols.map(c => Term.Select(Term.Name("id"), Term.Name(stripBackticks(c.scalaName))))),
        ),
      ),
    )
  }

  private def renderPackageTerm(basePackage: String, schemaName: String): Term.Ref = {
    val parts =
      basePackage
        .split("\\.")
        .iterator
        .map(_.trim)
        .filter(_.nonEmpty)
        .toList

    val all = parts :+ schemaName
    all match
      case Nil =>
        Term.Name("generated")
      case head :: tail =>
        tail.foldLeft[Term.Ref](Term.Name(head)) { case (acc, part) =>
          Term.Select(acc, Term.Name(part))
        }
  }

  private def renderColumnType(column: DataColumn, forceRequired: Boolean = false): String = {
    val base = baseColumnType(column)
    val isOptional = !forceRequired && column.db.isNullable && !column.db.isPartOfPrimaryKey
    if (isOptional) s"Option[$base]" else base
  }

  private def renderCreatorField(column: DataColumn): String = {
    val baseType            = baseColumnType(column)
    val dbColumn            = column.db
    val optionalFromDefault = dbColumn.hasDefaultValue || dbColumn.isGenerated || dbColumn.isAutoIncremented
    val optionalFromNull    = dbColumn.isNullable && !dbColumn.isPartOfPrimaryKey
    val isOptional          = optionalFromDefault || optionalFromNull
    val renderedType        = if (isOptional) s"Option[$baseType]" else baseType
    val defaultValue        = if (isOptional) " = None" else ""
    s"${column.scalaName}: $renderedType$defaultValue"
  }

  private def renderNamedTuple(columns: Seq[DataColumn]): String = {
    columns.toList match
      case Nil => "Unit"
      case single :: Nil =>
        s"(${single.scalaName}: ${renderColumnType(single, forceRequired = true)})"
      case many =>
        many
          .map { column =>
            s"    ${column.scalaName}: ${renderColumnType(column, forceRequired = true)}"
          }
          .mkString("(\n", ",\n", "\n  )")
  }

  private def renderTupleType(columns: Seq[DataColumn]): String = {
    columns.toList match
      case Nil => "EmptyTuple"
      case single :: Nil => s"Tuple1[${renderColumnType(single, forceRequired = true)}]"
      case many => many.map(column => renderColumnType(column, forceRequired = true)).mkString("(", ", ", ")")
  }

  private def renderTupleCtor(columns: Seq[DataColumn]): String =
    columns
      .map(column => s"${column.scalaName}: ${renderColumnType(column, forceRequired = true)}")
      .mkString(", ")

  private def baseColumnType(column: DataColumn): String = {
    val domainOverride =
      column.pgType
        .flatMap(info => domainTypeMapping.get(info.typname.toLowerCase(Locale.ROOT)))
        .orElse(
          Option(column.db.getColumnDataType.getName)
            .map(_.toLowerCase(Locale.ROOT))
            .flatMap(domainTypeMapping.get)
        )

    val underlying =
      if (column.scalaType.startsWith("Option["))
        column.scalaType.stripPrefix("Option[").stripSuffix("]")
      else column.scalaType

    val byDomain = domainOverride.getOrElse {
      val dbTypeName = Option(column.db.getColumnDataType.getName).map(_.toLowerCase(Locale.ROOT)).getOrElse("")
      dbSpecificTypes.getOrElse(dbTypeName, underlying)
    }

    val normalized = byDomain match
      case "java.sql.Blob" | "Blob" => "Chunk[Byte]"
      case other                     => other

    refineNumericTypes(normalized, column)
  }

  private val domainTypeMapping: Map[String, String] = Map(
    // legacy domains (previous public schema)
    "store_key"        -> "StoreKey",
    "hash_bytes"       -> "HashBytes",
    "small_bytes"      -> "SmallBytes",
    "store_status_t"   -> "StoreStatus",
    "replica_status_t" -> "ReplicaStatus",
    // new core domains
    "byte_size"        -> "NonNegLong",
    "nonempty_text"    -> "String",
  )

  private val dbSpecificTypes: Map[String, String] = Map(
    "bytea"        -> "Chunk[Byte]",
    "store_key"    -> "StoreKey",
    "hash_bytes"   -> "HashBytes",
    "small_bytes"  -> "SmallBytes",
    "json"         -> "Json",
    "jsonb"        -> "Json",
    "uuid"         -> "java.util.UUID",
    "timestamptz"  -> "java.time.OffsetDateTime",
    "timestamp"    -> "java.time.OffsetDateTime",
    "int8range"    -> "DbRange[Long]",
  )

  private def refineNumericTypes(baseType: String, column: DataColumn): String = {
    val lowered = column.name.toLowerCase(Locale.ROOT)
    baseType match {
      // In this schema, sizes/bytes are generally >= 0 (core.byte_size).
      case "Long" if lowered.contains("size") || lowered.contains("bytes") => "NonNegLong"
      case "Long" if lowered.contains("offset")                             => "NonNegLong"
      case "Long" if lowered.contains("version")                            => "NonNegLong"
      case other                                                             => other
    }
  }

  private def renderNamedTupleLiteralFromParams(columns: Seq[DataColumn]): String =
    renderNamedTupleLiteral(columns, _.scalaName)

  private def renderNamedTupleLiteral(columns: Seq[DataColumn], valueFor: DataColumn => String): String =
    columns.toList match
      case Nil => "EmptyTuple"
      case single :: Nil => s"(${single.scalaName} = ${valueFor(single)})"
      case many =>
        many
          .map(column => s"${column.scalaName} = ${valueFor(column)}")
          .mkString("(", ", ", ")")

  private def renderIdCodecSource(tableName: String, columns: Seq[DataColumn]): String = {
    val codecType = renderCodecPlainType(columns)
    val toId      = renderCodecToId(tableName, columns, "value")
    val fromId    = renderCodecFromId(tableName, columns, "id")
    s"scala.compiletime.summonInline[DbCodec[$codecType]].biMap(value => $toId, id => $fromId)"
  }

  private def renderCodecPlainType(columns: Seq[DataColumn]): String =
    columns.toList match
      case Nil => "Unit"
      case single :: Nil => renderColumnType(single, forceRequired = true)
      case _ => renderTupleType(columns)

  private def renderCodecToId(tableName: String, columns: Seq[DataColumn], valueExpr: String): String =
    columns.toList match
      case Nil => "()"
      case single :: Nil =>
        renderNamedTupleLiteral(columns, _ => valueExpr)
      case _ =>
        renderNamedTupleLiteralFromTuple(columns, valueExpr)

  private def renderCodecFromId(tableName: String, columns: Seq[DataColumn], idExpr: String): String =
    columns.toList match
      case Nil => "()"
      case single :: Nil => s"$idExpr.${single.scalaName}"
      case _ =>
        renderPlainTupleFromNamed(idExpr, columns)

  private def renderIdSchemaSource(tableName: String, columns: Seq[DataColumn]): String = {
    val schemaType = renderCodecPlainType(columns)
    val toId       = renderCodecToId(tableName, columns, "value")
    val fromId     = renderCodecFromId(tableName, columns, "id")
    s"scala.compiletime.summonInline[Schema[$schemaType]].transform(value => $toId, id => $fromId)"
  }

  private def renderNamedTupleLiteralFromTuple(columns: Seq[DataColumn], tupleExpr: String): String =
    columns.toList match
      case Nil => "EmptyTuple"
      case single :: Nil => s"(${single.scalaName} = $tupleExpr)"
      case many =>
        many
          .zipWithIndex
          .map { case (column, idx) => s"${column.scalaName} = $tupleExpr._${idx + 1}" }
          .mkString("(", ", ", ")")

  private def renderPlainTupleFromNamed(namedExpr: String, columns: Seq[DataColumn]): String =
    columns.toList match
      case Nil => "EmptyTuple"
      case single :: Nil => s"$namedExpr.${single.scalaName}"
      case many => many.map(column => s"$namedExpr.${column.scalaName}").mkString("(", ", ", ")")

  private final case class TableValidationRender(
    expressions: Seq[String],
    warnings: Seq[String],
  )

  private def buildTableValidations(table: DataTable): TableValidationRender = {
    val expressions = ListBuffer.empty[String]
    val warnings    = ListBuffer.empty[String]

    table.checkConstraints.filter(_.scope != CheckScope.Column).foreach { constraint =>
      val definition = constraint.expression
      if definition.nonEmpty then
        warnings += s"Unprocessed table-level check '${constraint.name}' on ${table.name}: $definition"
    }

    table.columns.foreach { column =>
      column.checkConstraints.foreach { constraint =>
        val definition = constraint.expression
        if definition.nonEmpty then
          CheckConstraintParser.parse(definition, column.name) match
            case Right(parsedConstraint) =>
              renderValidationFromPlan(table, column, parsedConstraint.plan) match
                case Some(rendered) => expressions += rendered
                case None =>
                  warnings += s"Unsupported column check '${constraint.name}' on ${table.name}.${column.name}: ${parsedConstraint.normalizedExpression}"
            case Left(reason) =>
              warnings += s"Failed to parse column check '${constraint.name}' on ${table.name}.${column.name}: $reason"
      }
    }

    TableValidationRender(expressions.distinct.toSeq, warnings.distinct.toSeq)
  }

  private def renderValidationFromPlan(
    table: DataTable,
    column: DataColumn,
    plan: ValidationPlan,
  ): Option[String] = {
    val columnType = renderColumnType(column)
    val baseType   = renderColumnType(column, forceRequired = true)
    val isOptional = columnType.startsWith("Option[")

    plan match
      case ValidationPlan.NumericComparison(_, operator, value) =>
        renderNumericComparison(table, column, baseType, isOptional, operator, value)
      case ValidationPlan.Between(_, lower, upper, lowerInclusive, upperInclusive) =>
        renderBetweenComparison(table, column, baseType, isOptional, lower, upper, lowerInclusive, upperInclusive)
      case ValidationPlan.LengthComparison(_, operator, value) =>
        renderLengthValidation(table, column, baseType, isOptional, operator, value)
      case ValidationPlan.Inclusion(_, values, negated) =>
        renderInclusionValidation(table, column, baseType, isOptional, values, negated)
      case ValidationPlan.NotNull(_, negated) =>
        renderNotNullValidation(table, column, baseType, isOptional, negated)
  }

  private def renderNumericComparison(
    table: DataTable,
    column: DataColumn,
    baseType: String,
    isOptional: Boolean,
    operator: ComparisonOperator,
    value: LiteralValue,
  ): Option[String] =
    literalFor(baseType, value).map { literal =>
      val baseValidation = operator match
        case ComparisonOperator.GreaterThan        => s"Validation.greaterThan[$baseType]($literal)"
        case ComparisonOperator.GreaterThanOrEqual =>
          s"(Validation.greaterThan[$baseType]($literal) || Validation.equalTo[$baseType]($literal))"
        case ComparisonOperator.LessThan           => s"Validation.lessThan[$baseType]($literal)"
        case ComparisonOperator.LessThanOrEqual    =>
          s"(Validation.lessThan[$baseType]($literal) || Validation.equalTo[$baseType]($literal))"
        case ComparisonOperator.Equal              => s"Validation.equalTo[$baseType]($literal)"
        case ComparisonOperator.NotEqual           => s"!Validation.equalTo[$baseType]($literal)"

      val optionalApplied = applyOptional(baseValidation, isOptional)
      wrapContramap(optionalApplied, table.scalaName, column.scalaName)
    }

  private def renderBetweenComparison(
    table: DataTable,
    column: DataColumn,
    baseType: String,
    isOptional: Boolean,
    lower: LiteralValue,
    upper: LiteralValue,
    lowerInclusive: Boolean,
    upperInclusive: Boolean,
  ): Option[String] =
    for {
      lowerLiteral <- literalFor(baseType, lower)
      upperLiteral <- literalFor(baseType, upper)
    } yield {
      val lowerExpr =
        if lowerInclusive then
          s"(Validation.greaterThan[$baseType]($lowerLiteral) || Validation.equalTo[$baseType]($lowerLiteral))"
        else
          s"Validation.greaterThan[$baseType]($lowerLiteral)"

      val upperExpr =
        if upperInclusive then
          s"(Validation.lessThan[$baseType]($upperLiteral) || Validation.equalTo[$baseType]($upperLiteral))"
        else
          s"Validation.lessThan[$baseType]($upperLiteral)"

      val combined =
        s"Validation.allOf[$baseType](\n${indentLines(lowerExpr, 2)},\n${indentLines(upperExpr, 2)}\n)"

      val optionalApplied = applyOptional(combined, isOptional)
      wrapContramap(optionalApplied, table.scalaName, column.scalaName)
    }

  private def renderLengthValidation(
    table: DataTable,
    column: DataColumn,
    baseType: String,
    isOptional: Boolean,
    operator: ComparisonOperator,
    value: Int,
  ): Option[String] =
    if normalizeBaseType(baseType) != "String" then None
    else
      val baseExprOpt = operator match
        case ComparisonOperator.GreaterThan => Some(s"Validation.minLength(${value + 1})")
        case ComparisonOperator.GreaterThanOrEqual => Some(s"Validation.minLength($value)")
        case ComparisonOperator.LessThan => Some(s"Validation.maxLength(${math.max(0, value - 1)})")
        case ComparisonOperator.LessThanOrEqual => Some(s"Validation.maxLength($value)")
        case ComparisonOperator.Equal =>
          val minExpr = s"Validation.minLength($value)"
          val maxExpr = s"Validation.maxLength($value)"
          Some(s"Validation.allOf[String](\n${indentLines(minExpr, 2)},\n${indentLines(maxExpr, 2)}\n)")
        case ComparisonOperator.NotEqual => None

      baseExprOpt.map { baseExpr =>
        val optionalApplied = applyOptional(baseExpr, isOptional)
        wrapContramap(optionalApplied, table.scalaName, column.scalaName)
      }

  private def renderInclusionValidation(
    table: DataTable,
    column: DataColumn,
    baseType: String,
    isOptional: Boolean,
    values: Seq[LiteralValue],
    negated: Boolean,
  ): Option[String] = {
    val renderedValues = values.flatMap(literalFor(baseType, _))
    if renderedValues.isEmpty then None
    else {
      val baseExpr = renderedValues.toList match
        case single :: Nil if !negated => s"Validation.equalTo[$baseType](${single})"
        case single :: Nil if negated  => s"!Validation.equalTo[$baseType](${single})"
        case many =>
          val terms =
            if negated then many.map(value => s"!Validation.equalTo[$baseType]($value)")
            else many.map(value => s"Validation.equalTo[$baseType]($value)")

          val builder = terms.map(expr => indentLines(expr, 2)).mkString(",\n")
          if negated then s"Validation.allOf[$baseType](\n$builder\n)" else s"Validation.anyOf[$baseType](\n$builder\n)"

      val optionalApplied = applyOptional(baseExpr, isOptional)
      Some(wrapContramap(optionalApplied, table.scalaName, column.scalaName))
    }
  }

  private def renderNotNullValidation(
    table: DataTable,
    column: DataColumn,
    baseType: String,
    isOptional: Boolean,
    negated: Boolean,
  ): Option[String] =
    if !isOptional then None
    else if negated then
      val baseExpr        = s"Validation.succeed[$baseType]"
      val optionalApplied = applyOptional(baseExpr, isOptional = true, allowNone = false)
      Some(wrapContramap(optionalApplied, table.scalaName, column.scalaName))
    else None

  private def literalFor(baseType: String, literal: LiteralValue): Option[String] = {
    val normalized = normalizeBaseType(baseType)
    normalized match
      case "Int" => literal match
          case LiteralValue.Numeric(value) => value.toBigIntExact.map(_.intValue).map(_.toString)
          case _                           => None
      case "Long" => literal match
          case LiteralValue.Numeric(value) => value.toBigIntExact.map(_.longValue).map(v => s"${v}L")
          case _                           => None
      case "Short" => literal match
          case LiteralValue.Numeric(value) => value.toBigIntExact.map(_.intValue).map(v => s"${v}.toShort")
          case _                           => None
      case "Byte" => literal match
          case LiteralValue.Numeric(value) => value.toBigIntExact.map(_.intValue).map(v => s"${v}.toByte")
          case _                           => None
      case "Double" => literal match
          case LiteralValue.Numeric(value) => Some(s"${value.toDouble}d")
          case _                           => None
      case "Float" => literal match
          case LiteralValue.Numeric(value) => Some(s"${value.toFloat}f")
          case _                           => None
      case "BigDecimal" => literal match
          case LiteralValue.Numeric(value)       => Some(s"""BigDecimal("${value.toString}")""")
          case LiteralValue.StringLiteral(value) => Some(s"""BigDecimal("${escapeScalaString(value)}")""")
          case _                                 => None
      case "Boolean" => literal match
          case LiteralValue.BooleanLiteral(value) => Some(value.toString)
          case _                                   => None
      case "String" => literal match
          case LiteralValue.StringLiteral(value) => Some(s"\"${escapeScalaString(value)}\"")
          case LiteralValue.Numeric(value)       => Some(s"\"${escapeScalaString(value.toString)}\"")
          case LiteralValue.BooleanLiteral(value) => Some(s"\"${escapeScalaString(value.toString)}\"")
          case LiteralValue.Raw(value)           => Some(s"\"${escapeScalaString(value)}\"")
      case _ => None
  }

  private def normalizeBaseType(baseType: String): String =
    baseType match
      case "scala.Int" | "java.lang.Integer" | "Int"   => "Int"
      case "scala.Long" | "java.lang.Long" | "Long"    => "Long"
      case "scala.Short" | "java.lang.Short" | "Short" => "Short"
      case "scala.Byte" | "java.lang.Byte" | "Byte"    => "Byte"
      case "scala.Double" | "java.lang.Double" | "Double" => "Double"
      case "scala.Float" | "java.lang.Float" | "Float"   => "Float"
      case "scala.Boolean" | "java.lang.Boolean" | "Boolean" => "Boolean"
      case "java.math.BigDecimal" | "BigDecimal" => "BigDecimal"
      case "java.lang.String" | "String"        => "String"
      case other                                   => other

  private def applyOptional(validationExpr: String, isOptional: Boolean, allowNone: Boolean = true): String =
    if isOptional then
      val methodCall = if allowNone then "optional()" else "optional(validNone = false)"
      s"($validationExpr).$methodCall"
    else validationExpr

  private def wrapContramap(validationExpr: String, tableType: String, accessor: String): String =
    s"($validationExpr).contramap[$tableType](_.${accessor})"

  private def combineValidationExpressions(tableName: String, expressions: Seq[String]): Option[String] =
    expressions.distinct.toList match
      case Nil           => None
      case single :: Nil => Some(single)
      case many          =>
        val body = many.map(expr => indentLines(expr, 2)).mkString(",\n")
        Some(s"Validation.allOf[$tableName](\n$body\n)")

  private def indentLines(value: String, indent: Int): String = {
    val padding = " " * indent
    value.linesIterator.mkString(padding, s"\n$padding", "")
  }

  private def escapeScalaString(value: String): String =
    value.flatMap {
      case '\\' => "\\\\"
      case '\"' => "\\\""
      case '\n' => "\\n"
      case '\r' => "\\r"
      case '\t' => "\\t"
      case c if c.isControl => f"\\u${c.toInt}%04x"
      case c => c.toString
    }

  // (schemaGivenName removed: we now use `derives Schema` for models)
}