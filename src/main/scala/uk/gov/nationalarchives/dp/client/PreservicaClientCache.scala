package uk.gov.nationalarchives.dp.client

import cats.effect.Sync
import cats.implicits._
import scalacache.AbstractCache
import scalacache.logging.Logger

import java.io.{File, FilenameFilter}
import java.nio.file._
import scala.concurrent.duration.Duration
import scala.util.Try

private[client] class PreservicaClientCache[F[_]: Sync] extends AbstractCache[F, String, F[String]] {
  val getFileAttribute: (Path, String) => AnyRef = Files.getAttribute(_, _, LinkOption.NOFOLLOW_LINKS)
  val setAttribute: (Path, String, Array[Byte]) => Path = Files.setAttribute(_, _, _)
  val delete: Path => Unit = Files.delete
  val readString: Path => String = Files.readString
  val write: (Path, Array[Byte]) => Path = Files.write(_, _, StandardOpenOption.CREATE)
  def currentTime: Long = System.currentTimeMillis()
  def listFiles: List[Path] = new File("/tmp")
    .listFiles(new FilenameFilter {
      override def accept(dir: File, name: String): Boolean = name.startsWith("cache_")
    })
    .toList
    .map(_.toPath)

  implicit class PathUtils(key: String) {
    def toPath: Path = Paths.get(s"/tmp/cache_$key")
  }

  private def getAttribute(path: Path, name: String): Long =
    new String(getFileAttribute(path, s"user:$name").asInstanceOf[Array[Byte]]).toLong
  protected val F: Sync[F] = Sync[F]

  override protected def logger: Logger[F] = Logger.getLogger[F](getClass.getName)

  override protected def doGet(key: String): F[Option[F[String]]] = {
    F.pure {
      val path = key.toPath
      Try(readString(path)).toOption.flatMap { contents =>
        val ttl = getAttribute(path, "ttl")
        val entry = getAttribute(path, "entry")
        if (currentTime > (ttl + entry)) None else Option(F.pure(contents))
      }
    }
  }

  override protected def doPut(key: String, value: F[String], ttl: Option[Duration]): F[Unit] = {
    value.map(s => {
      val path = key.toPath
      write(path, s.getBytes())
      val ttlMillis = ttl.map(_.toMillis).getOrElse(0)
      setAttribute(path, "user:ttl", ttlMillis.toString.getBytes)
      setAttribute(path, "user:entry", currentTime.toString.getBytes)
    })
  }

  override protected def doRemove(key: String): F[Unit] = F.pure(delete(key.toPath))

  override protected def doRemoveAll: F[Unit] = {
    F.pure {
      listFiles
        .foreach(delete)
    }
  }

  override def close: F[Unit] = F.unit
}
