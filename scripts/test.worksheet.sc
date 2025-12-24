//> using scala "3.7.4"
// import the local keys package

//> using options -Xprint:typer

//> using dep "io.graviton::graviton-core:0.0.0+306-1904437e"

import graviton.core.macros.Interpolators.*
import graviton.core.bytes.*

val readmeFile = java.io.File("README.md")

val b = hex"deadbeef"
val b2 = bin"101010101"
val loc = locator"s3://my-bucket/path/to/object"

val range = span"0..42"

println(scala.util.Properties.versionNumberString)

println(b)
println(b2)
println(loc)
println(range)

