package uk.gov.nationalarchives.dp.client.fs2

import cats.effect.IO
import cats.effect.std.Dispatcher
import sttp.capabilities.fs2.Fs2Streams
import sttp.client3.httpclient.fs2.HttpClientFs2Backend
import sttp.client3.impl.cats.implicits._
import cats.effect.unsafe.implicits.global
import sttp.client3.SttpBackend
import uk.gov.nationalarchives.dp.client.{Client, ClientTest}
import uk.gov.nationalarchives.dp.client.fs2.Fs2Client._
import cats.effect.IO.asyncForIO
import cats.effect.kernel.Resource
import cats.effect.syntax.dispatcher

import java.net.http.HttpClient

class Fs2ClientTest extends ClientTest[IO, Fs2Streams[IO]](9002, Fs2Streams[IO]) {
  override def valueFromF[T](value: IO[T]): T = value.unsafeRunSync()

  override def createClient(url: String): IO[Client[IO, Fs2Streams[IO]]] = client(url)
}
