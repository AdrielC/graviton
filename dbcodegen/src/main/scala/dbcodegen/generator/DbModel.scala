package dbcodegen.generator

import dbcodegen.DataSchema

final case class DbModel(
  schemas: Seq[DataSchema],
)

