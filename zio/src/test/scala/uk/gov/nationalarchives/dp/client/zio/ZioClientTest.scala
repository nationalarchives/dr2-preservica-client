package uk.gov.nationalarchives.dp.client.zio
import zio.interop.catz.core.*
import sttp.capabilities.zio.ZioStreams
import sttp.client3.SttpBackend
import sttp.client3.httpclient.zio.HttpClientZioBackend
import uk.gov.nationalarchives.dp.client.{Client, ClientTest}
import uk.gov.nationalarchives.dp.client.zio.Client.*
import zio.*

class ZioClientTest extends ClientTest[Task, ZioStreams](9001, ZioStreams) {
  val runtime: Runtime[Any] = Runtime.default

  override def valueFromF[T](value: Task[T]): T = Unsafe.unsafe { implicit unsafe =>
    runtime.unsafe.run(value).getOrThrowFiberFailure()
  }

  override def createClient(url: String): Task[Client[Task, ZioStreams]] = zioClient(url)
}
