package uk.gov.nationalarchives.dp.client.zio
import zio.interop.catz.core._
import sttp.capabilities.zio.ZioStreams
import sttp.client3.SttpBackend
import sttp.client3.httpclient.zio.HttpClientZioBackend
import uk.gov.nationalarchives.dp.client.{EntityClientTest, EntityClient}
import uk.gov.nationalarchives.dp.client.zio.ZioClient._
import zio._

class ZioClientTest extends EntityClientTest[Task, ZioStreams](9001, ZioStreams) {
  val runtime: Runtime[Any] = Runtime.default

  override def valueFromF[T](value: Task[T]): T = Unsafe.unsafe { implicit unsafe =>
    runtime.unsafe.run(value).getOrThrow()
  }

  override def createClient(url: String): Task[EntityClient[Task, ZioStreams]] = entityClient(url)
}
