package uk.gov.nationalarchives.dp.client

import cats.MonadError
import cats.effect.Async
import cats.implicits.*
import com.github.benmanes.caffeine.cache.{Caffeine, Cache as CCache}
import io.circe
import io.circe.Decoder
import io.circe.generic.auto.*
import scalacache.*
import scalacache.caffeine.*
import scalacache.memoization.*
import software.amazon.awssdk.http.async.SdkAsyncHttpClient
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.secretsmanager.SecretsManagerAsyncClient
import sttp.client3.*
import sttp.client3.circe.*
import sttp.model.Method
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
  * @param clientConfig
  *   The [[ClientConfig]] instance with the config details
  * @param me
  *   An implicit `MonadError` instance
  * @param async
  *   An implicit `Async` instance
  * @tparam F
  *   The type of Monad wrapper
  * @tparam S
  *   The type of the sttp Stream
  */
private[client] class Client[F[_], S](clientConfig: ClientConfig[F, S])(using
    me: MonadError[F, Throwable],
    async: Async[F]
) {
  private val underlying: CCache[String, Entry[String]] =
    Caffeine.newBuilder().maximumSize(10000L).build[String, Entry[String]]
  given caffeineCache: Cache[F, String, String] = CaffeineCache[F, String, String](underlying)
  val secretName: String = clientConfig.secretName
  private[client] val asXml: ResponseAs[Either[String, Elem], Any] =
    asString.mapRight(XML.loadString)

  private[client] val dataProcessor: DataProcessor[F] = DataProcessor[F]()

  private[client] val backend: SttpBackend[F, S] = clientConfig.backend
  private val duration: FiniteDuration = clientConfig.duration
  private[client] val apiBaseUrl: String = clientConfig.apiBaseUrl
  private val loginEndpointUri = uri"$apiBaseUrl/api/accesstoken/login"
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

    me.flatMap(backend.send(requestWithBody)) { res =>
      me.fromEither(
        res.body.left.map(err => PreservicaClientException(method, apiUri, res.code, err.getMessage))
      )
    }
  }

  private[client] def getAuthDetails(stage: Stage = Current): F[AuthDetails] = {
    val httpClient: SdkAsyncHttpClient = NettyNioAsyncHttpClient.builder().build()
    val secretsManagerAsyncClient: SecretsManagerAsyncClient = SecretsManagerAsyncClient.builder
      .region(Region.EU_WEST_2)
      .endpointOverride(URI.create(secretsManagerEndpointUri))
      .httpClient(httpClient)
      .build()
    for {
      secretMap <- DASecretsManagerClient[F](secretsManagerAsyncClient, secretName)
        .getSecretValue[Map[String, String]](stage)
    } yield {
      secretMap.map { case (username, password) =>
        AuthDetails(username, password)
      }.head
    }
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
      me.fromEither(responseOrError)
    }
  } yield token

  private[client] def getAuthenticationToken: F[String] =
    memoizeF[F, String](Some(duration)) {
      for {
        authDetails <- getAuthDetails()
        token <- generateToken(authDetails)
      } yield token
    }
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
    * @param fixity
    *   The fixity of the bitstream
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
      fixity: Fixity,
      generationVersion: Int,
      generationType: GenerationType,
      potentialCoTitle: Option[String],
      parentRef: Option[UUID]
  )

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

  case class Fixity(algorithm: String, value: String)

  /** Creates a new `Client` instance.
    * @param clientConfig
    *   Configuration parameters needed to create the client
    * @param me
    *   An implicit instance of cats.MonadError
    * @param async
    *   An implicit instance of cats.effect.Async
    * @tparam F
    *   The type of the effect
    * @tparam S
    *   The type of the Stream to be used for the streaming methods.
    * @return
    */
  def apply[F[_], S](clientConfig: ClientConfig[F, S])(using
      me: MonadError[F, Throwable],
      async: Async[F]
  ) = new Client[F, S](clientConfig)
}
