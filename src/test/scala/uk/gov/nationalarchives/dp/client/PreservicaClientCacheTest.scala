package uk.gov.nationalarchives.dp.client

import cats.Applicative
import cats.effect.kernel.Sync
import org.mockito.ArgumentMatchers.{any, eq as mockEq}
import org.mockito.Mockito
import org.mockito.Mockito.{verify, when}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers.*
import org.scalatestplus.mockito.MockitoSugar.*

import java.nio.file.Path
import scala.concurrent.duration.{Duration, DurationInt}

abstract class PreservicaClientCacheTest[F[_]: Sync: Applicative]() extends AnyFlatSpec with BeforeAndAfterEach:
  def defaultReadStringMock: Path => String =
    val readStringMock = mock[Path => String]
    when(readStringMock.apply(any[Path])).thenReturn("cachedValue")
    readStringMock
  def defaultGetAttributeMock: (Path, String) => Array[Byte] =
    val getAttributeMock = mock[(Path, String) => Array[Byte]]
    when(getAttributeMock.apply(any[Path], mockEq("user:ttl"))).thenReturn("10".getBytes)
    when(getAttributeMock.apply(any[Path], mockEq("user:entry"))).thenReturn("10".getBytes)
    getAttributeMock

  def defaultSetAttributeMock: (Path, String, Array[Byte]) => Path =
    val setAttributeMock = mock[(Path, String, Array[Byte]) => Path]
    when(setAttributeMock.apply(any[Path], mockEq("user:ttl"), any[Array[Byte]])).thenReturn(Path.of("/tmp"))
    when(setAttributeMock.apply(any[Path], mockEq("user:entry"), any[Array[Byte]])).thenReturn(Path.of("/tmp"))
    setAttributeMock

  def defaultWriteMock: (Path, Array[Byte]) => Path =
    val writeMock = mock[(Path, Array[Byte]) => Path]
    when(writeMock.apply(any[Path], any[Array[Byte]])).thenReturn(Path.of("/tmp"))
    writeMock

  def defaultDeleteMock: Path => Unit =
    val deleteMock = mock[Path => Unit]
    when(deleteMock.apply(any[Path])).thenReturn(())
    deleteMock

  override def beforeEach(): Unit =
    Mockito.reset(
      defaultWriteMock,
      defaultSetAttributeMock,
      defaultGetAttributeMock,
      defaultReadStringMock,
      defaultDeleteMock
    )

  val mockFiles: List[Path] = List(Path.of("path1"), Path.of("path2"))

  case class MockPreservicaClientCache(
      readStringMock: Path => String = defaultReadStringMock,
      getAttributeMock: (Path, String) => Array[Byte] = defaultGetAttributeMock,
      writeMock: (Path, Array[Byte]) => Path = defaultWriteMock,
      setAttributeMock: (Path, String, Array[Byte]) => Path = defaultSetAttributeMock,
      deleteMock: Path => Unit = defaultDeleteMock,
      listFilesMock: List[Path] = mockFiles,
      mockCurrentTime: Long = 10
  ) extends PreservicaClientCache[F]():

    override val readString: Path => String = readStringMock
    override val getFileAttribute: (Path, String) => AnyRef = getAttributeMock
    override val setAttribute: (Path, String, Array[Byte]) => Path = setAttributeMock
    override val write: (Path, Array[Byte]) => Path = writeMock
    override val delete: Path => Unit = deleteMock

    override def listFiles: List[Path] = listFilesMock

    override def currentTime: Long = mockCurrentTime

    override def doGet(key: String): F[Option[F[String]]] = super.doGet(key)

    override def doRemove(key: String): F[Unit] = super.doRemove(key)

    override def doPut(key: String, value: F[String], ttl: Option[Duration]): F[Unit] =
      super.doPut(key, value, ttl)

    override def doRemoveAll: F[Unit] = super.doRemoveAll

  def valueFromF[T](value: F[T]): T

  private val testKey = "key"
  private val testPath: Path = Path.of(s"/tmp/cache_$testKey")

  "doGet" should "return a value if the current time is less than the cache expiry" in {
    val mockPreservicaClientCache: MockPreservicaClientCache = MockPreservicaClientCache()
    val res = valueFromF(mockPreservicaClientCache.doGet(testKey))
    res.isDefined should be(true)
    valueFromF(res.get) should be("cachedValue")
  }

  "doGet" should "return nothing if the current time is greater than the cache expiry" in {
    val mockPreservicaClientCache: MockPreservicaClientCache = MockPreservicaClientCache(mockCurrentTime = 30)
    val res = valueFromF(mockPreservicaClientCache.doGet(testKey))
    res.isDefined should be(false)
  }

  "doGet" should "return nothing if there is an error reading the file" in {
    val readStringMock = mock[Path => String]

    when(readStringMock.apply(any[Path])).thenThrow(new RuntimeException("Error getting cache result"))

    val mockPreservicaClientCache: MockPreservicaClientCache = MockPreservicaClientCache(readStringMock)
    val res = valueFromF(mockPreservicaClientCache.doGet(testKey))
    res.isDefined should be(false)
  }

  "doGet" should "call the file API methods with the correct values" in {
    val mockPreservicaClientCache: MockPreservicaClientCache = MockPreservicaClientCache()
    valueFromF(mockPreservicaClientCache.doGet(testKey))
    verify(mockPreservicaClientCache.getAttributeMock).apply(testPath, "user:ttl")
    verify(mockPreservicaClientCache.getAttributeMock).apply(testPath, "user:entry")
    verify(mockPreservicaClientCache.readStringMock).apply(testPath)
  }

  "doPut" should "set the ttl attribute to zero if a duration is not passed" in {
    val mockPreservicaClientCache = MockPreservicaClientCache()
    valueFromF(mockPreservicaClientCache.doPut(testKey, Sync[F].pure("value"), None))
    verify(mockPreservicaClientCache.setAttributeMock).apply(testPath, "user:ttl", "0".getBytes())
  }

  "doPut" should "set the ttl attribute to the value of duration in milliseconds" in {
    val mockPreservicaClientCache = MockPreservicaClientCache()
    valueFromF(mockPreservicaClientCache.doPut(testKey, Sync[F].pure("value"), Option(1.seconds)))
    verify(mockPreservicaClientCache.setAttributeMock).apply(testPath, "user:ttl", "1000".getBytes())
  }

  "doPut" should "set the entry attribute to the current time" in {
    val mockPreservicaClientCache = MockPreservicaClientCache()
    valueFromF(mockPreservicaClientCache.doPut(testKey, Sync[F].pure("value"), None))
    verify(mockPreservicaClientCache.setAttributeMock).apply(testPath, "user:entry", "10".getBytes())
  }

  "doPut" should "throw an error if the write operation fails" in {
    val writeMock = mock[(Path, Array[Byte]) => Path]
    when(writeMock.apply(any[Path], any[Array[Byte]])).thenThrow(new RuntimeException("Error writing file"))
    val mockPreservicaClientCache = MockPreservicaClientCache(writeMock = writeMock)
    val ex = intercept[RuntimeException] {
      valueFromF(mockPreservicaClientCache.doPut(testKey, Sync[F].pure("value"), None))
    }
    ex.getMessage should equal("Error writing file")
  }

  "doPut" should "write a file with the correct contents" in {
    val mockPreservicaClientCache = MockPreservicaClientCache()
    valueFromF(mockPreservicaClientCache.doPut(testKey, Sync[F].pure("value"), Option(1.seconds)))
    verify(mockPreservicaClientCache.writeMock).apply(testPath, "value".getBytes())
  }

  "doRemove" should "call delete with the correct arguments" in {
    val mockPreservicaClientCache = MockPreservicaClientCache()
    valueFromF(mockPreservicaClientCache.doRemove(testKey))
    verify(mockPreservicaClientCache.deleteMock).apply(testPath)
  }

  "doRemove" should "throw an error if the file delete fails" in {
    val deleteMock = mock[Path => Unit]
    when(deleteMock.apply(any[Path])).thenThrow(new RuntimeException("Error deleting file"))
    val mockPreservicaClientCache = MockPreservicaClientCache(deleteMock = deleteMock)
    val ex = intercept[RuntimeException] {
      valueFromF(mockPreservicaClientCache.doRemove(testKey))
    }
    ex.getMessage should equal("Error deleting file")
  }

  "doRemoveAll" should "delete all files in the tmp directory" in {
    val mockPreservicaClientCache = MockPreservicaClientCache()
    valueFromF(mockPreservicaClientCache.doRemoveAll)
    verify(mockPreservicaClientCache.deleteMock).apply(mockFiles.head)
    verify(mockPreservicaClientCache.deleteMock).apply(mockFiles.last)
  }

  "doRemoveAll" should "throw an error if the file delete fails" in {
    val deleteMock = mock[Path => Unit]
    when(deleteMock.apply(mockFiles.head)).thenThrow(new RuntimeException("Error deleting file"))
    val mockPreservicaClientCache = MockPreservicaClientCache(deleteMock = deleteMock)
    val ex = intercept[RuntimeException] {
      valueFromF(mockPreservicaClientCache.doRemoveAll)
    }
    ex.getMessage should equal("Error deleting file")
  }
