package uk.gov.nationalarchives.dp.client
import cats.effect.{MonadCancel, Sync}
import cats.implicits._
import com.amazonaws.xray.AWSXRay
import sttp.capabilities.Effect
import sttp.client3.{DelegateSttpBackend, Request, Response, SttpBackend}

import scala.jdk.CollectionConverters._

class XRayWrapper[F[_]: Sync, P](delegate: SttpBackend[F, P])(implicit mc: MonadCancel[F, Throwable])
    extends DelegateSttpBackend[F, P](delegate) {
  override def send[T, R >: P with Effect[F]](request: Request[T, R]): F[Response[T]] = {
    val method = request.method.method
    val uri = request.uri.toString
    for {
      subSegment <- mc.pure(AWSXRay.beginSubsegment("preservica.requests"))
      _ <- mc.pure(subSegment.putHttp("request", Map("method" -> method, "url" -> uri).asJava))
      res <- delegate.send(request)
      _ <- mc.pure {
        val responseInformation: Map[String, Long] =
          Map("content_length" -> res.contentLength.getOrElse(0), "status" -> res.code.code)
        subSegment.putHttp("response", responseInformation.asJava)
      }
      _ <- mc.pure(AWSXRay.endSubsegment(subSegment))
    } yield res
  }
}
