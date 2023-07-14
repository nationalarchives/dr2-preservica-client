package uk.gov.nationalarchives.dp.client

import cats.effect.Sync
import cats.implicits._
import scalacache.AbstractCache
import scalacache.logging.Logger

import java.io.{File, FilenameFilter}
import java.nio.file.{Files, Path, Paths, StandardOpenOption}
import scala.concurrent.duration.Duration
import scala.util.Try

class PreservicaClientCache[F[_]: Sync] extends AbstractCache[F, String, F[String]] {
  implicit class PathUtils(key: String) {
    def toPath: Path = Paths.get(s"/tmp/cache_$key")
  }

  private def getAttribute(path: Path, name: String): Long =
    new String(Files.getAttribute(path, s"user:$name").asInstanceOf[Array[Byte]]).toLong
  protected val F: Sync[F] = Sync[F]

  override protected def logger: Logger[F] = Logger.getLogger[F](getClass.getName)

  override protected def doGet(key: String): F[Option[F[String]]] = {
    F.pure(Try(Files.readString(key.toPath)).toOption.flatMap(contents => {
      val ttl = getAttribute(key.toPath, "ttl")
      val entry = getAttribute(key.toPath, "entry")
      if (System.currentTimeMillis() > (ttl + entry)) None else Option(F.pure(contents))
    }))
  }

  override protected def doPut(key: String, value: F[String], ttl: Option[Duration]): F[Unit] = {
    value.map(s => {
      Files.write(key.toPath, s.getBytes(), StandardOpenOption.CREATE)
      val ttlMillis = ttl.map(_.toMillis).getOrElse(0)
      Files.setAttribute(key.toPath, "user:ttl", ttlMillis.toString.getBytes)
      Files.setAttribute(key.toPath, "user:entry", System.currentTimeMillis.toString.getBytes)
    })
  }

  override protected def doRemove(key: String): F[Unit] = {
    F.unit.map(_ => Files.delete(key.toPath))
  }

  override protected def doRemoveAll: F[Unit] = {
    F.pure {
      new File("/tmp")
        .listFiles(new FilenameFilter {
          override def accept(dir: File, name: String): Boolean = name.startsWith("cache_")
        })
        .toList
        .foreach(f => Files.delete(f.toPath))
    }
  }

  override def close: F[Unit] = F.unit
}
