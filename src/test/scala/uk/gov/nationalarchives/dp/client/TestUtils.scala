package uk.gov.nationalarchives.dp.client

import java.io.{File, FilenameFilter}
import java.nio.file.Files

object TestUtils {
  def deleteCacheFiles(): Unit = new File("/tmp")
    .listFiles(new FilenameFilter {
      override def accept(dir: File, name: String): Boolean = name.startsWith("cache_")
    })
    .toList
    .foreach(f => Files.delete(f.toPath))
}
