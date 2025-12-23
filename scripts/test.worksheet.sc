//> using scala "3.7.4"
// import the local keys package

//> using options -Xprint:typer

//> using dep "io.graviton::graviton-core:0.0.0+304-22d90748"



import graviton.core.macros.Interpolators.*
import graviton.core.bytes.*
val file = java.io.File("README.md")

val b = hex"${file}"
val b2 = bin"0101010101"
val loc = locator"s3://my-bucket/path/to/object"

println(scala.util.Properties.versionNumberString)

println(b)
println(b2)
println(loc)


