package uk.gov.nationalarchives.dp.client

import cats.effect.Async
import cats.implicits.*
import com.github.benmanes.caffeine.cache.{Caffeine, Cache as CCache}
import io.circe
import io.circe.{Decoder, HCursor}
import org.typelevel.log4cats.{Logger, SelfAwareStructuredLogger}
import org.typelevel.log4cats.slf4j.Slf4jLogger
import retry.*
import scalacache.*
import scalacache.caffeine.*
import scalacache.memoization.*
import software.amazon.awssdk.http.async.SdkAsyncHttpClient
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.secretsmanager.SecretsManagerAsyncClient
import sttp.client3.*
import sttp.client3.circe.*
import sttp.model.{Method, StatusCode, Uri}
import uk.gov.nationalarchives.DASecretsManagerClient
import uk.gov.nationalarchives.DASecretsManagerClient.Stage
import uk.gov.nationalarchives.DASecretsManagerClient.Stage.*
import uk.gov.nationalarchives.dp.client.Client.*
import uk.gov.nationalarchives.dp.client.EntityClient.GenerationType

import java.net.URI
import java.util.UUID
import scala.concurrent.duration.*
import scala.xml.{Elem, XML}

/** A utility class containing methods common to all clients
  *
  * @param clientConfig
  *   The [[ClientConfig]] instance with the config details
  * @param async
  *   An implicit `Async` instance
  * @tparam F
  *   The type of Monad wrapper
  * @tparam S
  *   The type of the sttp Stream
  */
private[client] class Client[F[_], S](clientConfig: ClientConfig[F, S])(using
    async: Async[F]
) {
  private val underlying: CCache[String, Entry[String]] =
    Caffeine.newBuilder().maximumSize(10000L).build[String, Entry[String]]
  private val underlyingAuthDetails: CCache[String, Entry[AuthDetails]] =
    Caffeine.newBuilder().maximumSize(10000L).build[String, Entry[AuthDetails]]

  given caffeineCache: Cache[F, String, String] = CaffeineCache[F, String, String](underlying)

  given authDetailsCaffeineCache: Cache[F, String, AuthDetails] =
    CaffeineCache[F, String, AuthDetails](underlyingAuthDetails)

  val secretName: String = clientConfig.secretName
  private[client] val asXml: ResponseAs[Either[String, Elem], Any] =
    asString.mapRight(XML.loadString)

  private[client] val dataProcessor: DataProcessor[F] = DataProcessor[F]

  private[client] val backend: SttpBackend[F, S] = clientConfig.backend
  private val duration: FiniteDuration = clientConfig.duration
  private[client] val apiBaseUrl: String = clientConfig.apiBaseUrl
  private val loginEndpointUri = uri"$apiBaseUrl/api/accesstoken/login"
  private val secretsManagerEndpointUri: String = clientConfig.secretsManagerEndpointUri

  given Decoder[AuthDetails] = (c: HCursor) =>
    for {
      userName <- c.downField("userName").as[String]
      password <- c.downField("password").as[String]
      apiUrl <- c.downField("apiUrl").as[String]
    } yield {
      AuthDetails(userName, password, apiUrl)
    }

  given Decoder[Token] = (c: HCursor) => c.downField("token").as[String].map(Token.apply)
  given SelfAwareStructuredLogger[F] = Slf4jLogger.getLogger[F]

  private def retrySend[T, E](method: Method, apiUri: Uri, sendHttpRequest: F[Response[Either[E, T]]]): F[T] = {
    def liftEither(response: Response[Either[E, T]]): F[T] = Async[F].fromEither {
      response.body.left.map {
        case e: Throwable => PreservicaClientException(method, apiUri, response.code, e.getMessage)
        case e            => PreservicaClientException(method, apiUri, response.code, e.toString)
      }
    }
    retryingOnFailuresAndErrors(sendHttpRequest)(
      RetryPolicies.limitRetries[F](clientConfig.retryCount).join(RetryPolicies.exponentialBackoff[F](1.second)),
      (response, retryDetails) =>
        val currentRetry = retryDetails.retriesSoFar + 1
        val retryMessage = s"Retrying $currentRetry of ${clientConfig.retryCount} with cumulative delay ${retryDetails.cumulativeDelay} for request due to"

        response.map(_.code) match {
          case Left(e) => Logger[F].error(e)(s"$retryMessage exception ${e.getMessage}").as(HandlerDecision.Continue)
          case Right(code) if code == StatusCode.Unauthorized || code == StatusCode.Forbidden =>
            Logger[F]
              .warn(s"$retryMessage unauthorised response ${code.code}. Invalidating cache")
              .map { _ =>
                underlyingAuthDetails.invalidateAll()
                underlying.invalidateAll()
              }
              .as(HandlerDecision.Continue)
          case Right(code) if code != StatusCode.Ok =>
            Logger[F].warn(s"$retryMessage ${code.code} response").as(HandlerDecision.Continue)
          case _ => Async[F].pure(HandlerDecision.Stop)
        }
    ).flatMap {
      case Left(errResponse)      => liftEither(errResponse)
      case Right(successResponse) => liftEither(successResponse)
    }
  }

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
    retrySend(method, apiUri, backend.send(requestWithBody))
  }

  private[client] def sendJsonApiRequest[R: IsOption](
      url: String,
      token: String,
      method: Method,
      requestBody: Option[String] = None
  )(using decoder: Decoder[R]): F[R] = {
    val apiUri = uri"$url"
    val request = basicRequest
      .headers(Map("Preservica-Access-Token" -> token, "Content-Type" -> "application/json;charset=UTF-8"))
      .method(method, apiUri)
      .response(asJson[R])
    val requestWithBody: RequestT[Identity, Either[ResponseException[String, circe.Error], R], Any] =
      requestBody.map(request.body(_)).getOrElse(request)
    retrySend(method, apiUri, backend.send(requestWithBody))
  }

  private[client] def getAuthDetails(stage: Stage = Current): F[AuthDetails] =
    memoizeF[F, AuthDetails](Some(duration)) {
      val httpClient: SdkAsyncHttpClient = NettyNioAsyncHttpClient.builder().build()
      val secretsManagerAsyncClient: SecretsManagerAsyncClient = SecretsManagerAsyncClient.builder
        .region(Region.EU_WEST_2)
        .endpointOverride(URI.create(secretsManagerEndpointUri))
        .httpClient(httpClient)
        .build()
      DASecretsManagerClient[F](secretName, secretsManagerAsyncClient)
        .getSecretValue[AuthDetails](stage)
    }

  private[client] def generateToken(authDetails: AuthDetails): F[String] = for {
    res <- basicRequest
      .body(Map("username" -> authDetails.userName, "password" -> authDetails.password))
      .post(loginEndpointUri)
      .response(asJson[Token])
      .send(backend)
    token <- {
      val responseOrError = res.body.left
        .map(e => PreservicaClientException(Method.POST, loginEndpointUri, res.code, e.getMessage))
        .map(_.token)
      Async[F].fromEither(responseOrError)
    }
  } yield token

  private[client] def getAuthenticationToken: F[String] =
    memoizeF[F, String](Some(duration)) {
      for {
        authDetails <- getAuthDetails()
        token <- generateToken(authDetails)
      } yield token
    }

  private[client] def getApiUrl: F[String] =
    getAuthDetails().map(_.apiUrl)
}

/** Case classes common to several clients
  */
object Client {
  private[client] case class Token(token: String)

  private[client] case class AuthDetails(userName: String, password: String, apiUrl: String)

  /** Represents bitstream information from a content object
    *
    * @param name
    *   The name of the bitstream
    * @param fileSize
    *   The size of the bitstream
    * @param url
    *   The url to download the bitstream
    * @param fixities
    *   The list of fixities associated with the bitstream
    * @param generationVersion
    *   The version of the generation
    * @param potentialCoTitle
    *   The title of the CO
    * @param parentRef
    *   The parent ref of the CO
    */
  case class BitStreamInfo(
      name: String,
      fileSize: Long,
      url: String,
      fixities: List[Fixity],
      generationVersion: Int,
      generationType: GenerationType,
      potentialCoTitle: Option[String],
      parentRef: Option[UUID]
  )

  /** Configuration for the clients
    *
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
      secretsManagerEndpointUri: String,
      retryCount: Int
  )

  /** Represents fixity for an object
    *
    * @param algorithm
    *   Algorithm used to generate hash for this object (e.g. MD5, SHA256 etc.)
    * @param value
    *   Hash for this object calculated using the corresponding algorithm
    */
  case class Fixity(algorithm: String, value: String)

  /** Creates a new `Client` instance.
    *
    * @param clientConfig
    *   Configuration parameters needed to create the client
    * @param async
    *   An implicit instance of cats.effect.Async
    * @tparam F
    *   The type of the effect
    * @tparam S
    *   The type of the Stream to be used for the streaming methods.
    * @return
    */
  def apply[F[_], S](clientConfig: ClientConfig[F, S])(using
      async: Async[F]
  ) = new Client[F, S](clientConfig)
}
