//> using scala "3.7.4"
// import the local keys package

//> using options -Xprint:typer

//> using dep "io.graviton::graviton-core:0.0.0+294-acbd604a"


import graviton.core.keys.*
import graviton.core.bytes.Digest

val b: Digest = hex"deadbeef"
val b2 = bin"1010101010"

println(scala.util.Properties.versionNumberString)

println(b)
println(b2)


