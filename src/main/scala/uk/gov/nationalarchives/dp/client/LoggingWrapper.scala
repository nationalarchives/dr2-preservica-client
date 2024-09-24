package uk.gov.nationalarchives.dp.client

import cats.MonadError
import cats.effect.Sync
import cats.implicits.*
import org.typelevel.log4cats.slf4j.Slf4jLogger
import sttp.capabilities
import sttp.client3.{DelegateSttpBackend, Request, Response, SttpBackend}

private[client] class LoggingWrapper[F[_], P](delegate: SttpBackend[F, P])(using
    val me: MonadError[F, Throwable],
    sync: Sync[F]
) extends DelegateSttpBackend[F, P](delegate):

  override def send[T, R >: P & capabilities.Effect[F]](request: Request[T, R]): F[Response[T]] =
    val method = request.method.method
    val url = request.uri.toString
    val ctx = Map("url" -> url, "method" -> method)
    (for
      logger <- Slf4jLogger.create[F]
      res <- delegate.send(request)
      _ <- logger
        .debug(ctx + ("code" -> res.code.code.toString))(s"Sending $method request to $url. Response ${res.code.code}")
    yield res).onError(err =>
      for
        logger <- Slf4jLogger.create[F]
        _ <- logger.error(ctx, err)(s"Error sending $method request to $url ")
      yield ()
    )

/** A companion object with an apply method
  */
private[client] object LoggingWrapper:

  def apply[F[_], P](delegate: SttpBackend[F, P])(using me: MonadError[F, Throwable], sync: Sync[F]) =
    new LoggingWrapper[F, P](delegate)
