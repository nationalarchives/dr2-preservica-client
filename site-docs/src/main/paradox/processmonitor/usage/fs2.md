# Use with FS2

@@dependency[sbt,Maven,Gradle] {
group="uk.gov.nationalarchives" artifact="preservica-client-fs2_2.13" version=$version$
}

@@snip [ProcessMonitor.scala](../../../scala/examples/ProcessMonitor.scala) { #fs2 }
