# Use with ZIO

@@dependency[sbt,Maven,Gradle] {
group="uk.gov.nationalarchives" artifact="preservica-client-zio_2.13" version=$version$
}

Most of the methods in the list will work with either Cats effect or ZIO with the only difference being the effect type of `cats.effect.IO` vs `zio.Task`

The exception is the method to stream bitstream content.

@@snip [Content.scala](../../../scala/examples/Entity.scala) { #zio }
