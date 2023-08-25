package uk.gov.nationalarchives.dp.client

import cats.MonadError
import cats.effect.Sync
import cats.implicits._
import scalacache._
import scalacache.memoization._
import software.amazon.awssdk.http.apache.ApacheHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest
import sttp.client3._
import sttp.client3.upicklejson._
import sttp.model.Method
import uk.gov.nationalarchives.dp.client.Client._
import upickle.default._

import java.net.URI
import scala.concurrent.duration._
import scala.xml.{Elem, XML}

class Client[F[_], S](clientConfig: ClientConfig[F, S])(implicit
    me: MonadError[F, Throwable],
    sync: Sync[F]
) {
  private[client] val asXml: ResponseAs[Either[String, Elem], Any] =
    asString.mapRight(XML.loadString)

  private[client] val dataProcessor: DataProcessor[F] = DataProcessor[F]()

  implicit val responsePayloadRW: ReadWriter[Token] = macroRW[Token]

  implicit val cache: Cache[F, String, F[String]] = new PreservicaClientCache()

  val backend: SttpBackend[F, S] = clientConfig.backend
  val duration: FiniteDuration = clientConfig.duration
  val apiBaseUrl: String = clientConfig.apiBaseUrl
  val secretsManagerEndpointUri: String = clientConfig.secretsManagerEndpointUri

  private[client] def sendXMLApiRequest(
      url: String,
      token: String,
      method: Method,
      requestBody: Option[String] = None
  ) = {
    val apiUri = uri"$url"
    val request = basicRequest
      .headers(Map("Preservica-Access-Token" -> token, "Content-Type" -> "application/xml"))
      .method(method, apiUri)
      .response(asXml)
    val requestWithBody = requestBody.map(request.body(_)).getOrElse(request)
    me.flatMap(backend.send(requestWithBody)) { res =>
      me.fromEither(
        res.body.left.map(err => PreservicaClientException(method, apiUri, res.code, err))
      )
    }
  }

  private def getAuthDetails(secretName: String): F[AuthDetails] = {
    val valueRequest = GetSecretValueRequest
      .builder()
      .secretId(secretName)
      .build()
    val secretsManager = SecretsManagerClient.builder
      .httpClient(ApacheHttpClient.builder.build())
      .endpointOverride(URI.create(secretsManagerEndpointUri))
      .region(Region.EU_WEST_2)
      .build()

    val response = secretsManager.getSecretValue(valueRequest)
    val (username, password) = upickle.default.read[Map[String, String]](response.secretString).head
    me.pure(AuthDetails(username, password))
  }

  private[client] def getAuthenticationToken(secretName: String): F[String] =
    memoize[F, F[String]](Some(duration)) {
      val apiUri = uri"$apiBaseUrl/api/accesstoken/login"
      for {
        authDetails <- getAuthDetails(secretName)
        res <- basicRequest
          .body(Map("username" -> authDetails.userName, "password" -> authDetails.password))
          .post(apiUri)
          .response(asJson[Token])
          .send(backend)
        token <- {
          val responseOrError = res.body.left
            .map(e => PreservicaClientException(Method.POST, apiUri, res.code, e.getMessage))
            .map(_.token)
          me.fromEither(responseOrError)
        }
      } yield token
    }.flatten
}
object Client {
  case class Token(token: String)

  case class AuthDetails(userName: String, password: String)

  case class BitStreamInfo(name: String, fileSize: Long, url: String)

  case class ClientConfig[F[_], S](
      apiBaseUrl: String,
      backend: SttpBackend[F, S],
      duration: FiniteDuration,
      secretsManagerEndpointUri: String
  )

  def apply[F[_], S](clientConfig: ClientConfig[F, S])(implicit
      me: MonadError[F, Throwable],
      sync: Sync[F]
  ) = new Client[F, S](clientConfig)
}
