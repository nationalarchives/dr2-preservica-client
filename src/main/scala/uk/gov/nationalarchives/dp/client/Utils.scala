package uk.gov.nationalarchives.dp.client

import cats.MonadError
import cats.effect.Sync
import cats.implicits._
import com.github.benmanes.caffeine.cache.Caffeine
import scalacache._
import scalacache.caffeine._
import scalacache.memoization._
import sttp.client3._
import sttp.client3.upicklejson._
import uk.gov.nationalarchives.dp.client.Utils._
import upickle.default._

import scala.concurrent.duration._
import scala.xml.{Elem, XML}

class Utils[F[_], S](apiBaseUrl: String, backend: SttpBackend[F, S], duration: FiniteDuration)(
    implicit
    me: MonadError[F, Throwable],
    sync: Sync[F]
) {
  private[client] val asXml: ResponseAs[Either[String, Elem], Any] =
    asString.mapRight(XML.loadString)
  private[client] val dataProcessor: DataProcessor[F] = DataProcessor[F]()

  implicit val responsePayloadRW: ReadWriter[Token] = macroRW[Token]

  implicit val cache: Cache[F, String, F[String]] = CaffeineCache(
    Caffeine.newBuilder.build[String, Entry[F[String]]]
  )

  implicit class EitherUtils[T](e: Either[String, T]) {
    def bodyLift: F[T] = me.fromTry(e.left.map(err => new RuntimeException(err)).toTry)
  }

  private[client] def getApiResponseXml(url: String, token: String): F[Elem] = {
    val request = basicRequest
      .get(uri"$url")
      .headers(Map("Preservica-Access-Token" -> token))
      .response(asXml)

    me.flatMap(backend.send(request))(_.body.bodyLift)
  }

  private[client] def getAuthenticationToken(authDetails: AuthDetails): F[String] =
    memoize[F, F[String]](Some(duration)) {
      val response = basicRequest
        .body(Map("username" -> authDetails.userName, "password" -> authDetails.password))
        .post(uri"$apiBaseUrl/api/accesstoken/login")
        .response(asJson[Token])
        .send(backend)
      me.flatMap(response) { res =>
        me.fromTry(res.body.map(t => t.token).toTry)
      }
    }.flatten

}
object Utils {
  case class Token(token: String)

  case class AuthDetails(userName: String, password: String)

  case class BitStreamInfo(name: String, url: String)

  def apply[F[_], S](apiBaseUrl: String, backend: SttpBackend[F, S], duration: FiniteDuration)(
      implicit
      me: MonadError[F, Throwable],
      sync: Sync[F]
  ) = new Utils[F, S](apiBaseUrl, backend, duration)
}
