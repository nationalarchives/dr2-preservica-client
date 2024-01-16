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

/** A utility class containing methods common to all clients
  * @param clientConfig
  *   The [[ClientConfig]] instance with the config details
  * @param me
  *   An implicit `MonadError` instance
  * @param sync
  *   An implicit `Sync` instance
  * @tparam F
  *   The type of Monad wrapper
  * @tparam S
  *   The type of the sttp Stream
  */
private[client] class Client[F[_], S](clientConfig: ClientConfig[F, S])(implicit
    me: MonadError[F, Throwable],
    sync: Sync[F]
) {
  val secretName: String = clientConfig.secretName
  private[client] val asXml: ResponseAs[Either[String, Elem], Any] =
    asString.mapRight(XML.loadString)

  private[client] val dataProcessor: DataProcessor[F] = DataProcessor[F]()

  private implicit val responsePayloadRW: ReadWriter[Token] = macroRW[Token]

  implicit val cache: Cache[F, String, F[String]] = new PreservicaClientCache()

  private[client] val backend: SttpBackend[F, S] = clientConfig.backend
  private val duration: FiniteDuration = clientConfig.duration
  private[client] val apiBaseUrl: String = clientConfig.apiBaseUrl
  private val secretsManagerEndpointUri: String = clientConfig.secretsManagerEndpointUri

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

  private[client] def sendJsonApiRequest[R](
      url: String,
      token: String,
      method: Method,
      requestBody: Option[String] = None,
      responseSchema: ResponseAs[Either[ResponseException[String, Exception], R], Any]
  ): F[R] = {
    val apiUri = uri"$url"
    val request = basicRequest
      .headers(Map("Preservica-Access-Token" -> token, "Content-Type" -> "application/json;charset=UTF-8"))
      .method(method, apiUri)
      .response(responseSchema)
    val requestWithBody: RequestT[Identity, Either[ResponseException[String, Exception], R], Any] =
      requestBody.map(request.body(_)).getOrElse(request)
    me.flatMap(backend.send(requestWithBody)) { res =>
      me.fromEither(
        res.body.left.map(err => PreservicaClientException(method, apiUri, res.code, err.getMessage))
      )
    }
  }

  private def getAuthDetails: F[AuthDetails] = {
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

  private[client] def getAuthenticationToken: F[String] =
    memoize[F, F[String]](Some(duration)) {
      val apiUri = uri"$apiBaseUrl/api/accesstoken/login"
      for {
        authDetails <- getAuthDetails
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

/** Case classes common to several clients
  */
object Client {
  private[client] case class Token(token: String)

  private[client] case class AuthDetails(userName: String, password: String)

  /** Represents bitstream information from a content object
    * @param name
    *   The name of the bitstream
    * @param fileSize
    *   The size of the bitstream
    * @param url
    *   The url to download the bitstream
    */
  case class BitStreamInfo(name: String, fileSize: Long, url: String)

  /** Configuration for the clients
    * @param apiBaseUrl
    *   The Preservica service url
    * @param secretName
    *   The name of the AWS secret storing the API username and password
    * @param backend
    *   The STTP backend used to send the API requests
    * @param duration
    *   The timeout of the cache. Defaults to 15 minutes
    * @param secretsManagerEndpointUri
    *   The endpoint for communicating with secrets manager
    * @tparam F
    *   The effect type for the client
    * @tparam S
    *   The type of the Stream for the client.
    */
  case class ClientConfig[F[_], S](
      apiBaseUrl: String,
      secretName: String,
      backend: SttpBackend[F, S],
      duration: FiniteDuration,
      secretsManagerEndpointUri: String
  )

  /** Creates a new `Client` instance.
    * @param clientConfig
    *   Configuration parameters needed to create the client
    * @param me
    *   An implicit instance of cats.MonadError
    * @param sync
    *   An implicit instance of cats.Sync
    * @tparam F
    *   The type of the effect
    * @tparam S
    *   The type of the Stream to be used for the streaming methods.
    * @return
    */
  def apply[F[_], S](clientConfig: ClientConfig[F, S])(implicit
      me: MonadError[F, Throwable],
      sync: Sync[F]
  ) = new Client[F, S](clientConfig)
}
