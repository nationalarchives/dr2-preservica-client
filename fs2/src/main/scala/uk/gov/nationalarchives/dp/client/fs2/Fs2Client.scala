package uk.gov.nationalarchives.dp.client.fs2

import cats.effect.*
import sttp.capabilities.fs2.Fs2Streams
import sttp.client3.SttpBackendOptions
import sttp.client3.httpclient.fs2.HttpClientFs2Backend
import uk.gov.nationalarchives.dp.client.EntityClient.*
import uk.gov.nationalarchives.dp.client.ContentClient.createContentClient
import uk.gov.nationalarchives.dp.client.{
  Client,
  ContentClient,
  EntityClient,
  LoggingWrapper,
  ProcessMonitorClient,
  UserClient,
  ValidateXmlAgainstXsd,
  WorkflowClient
}
import uk.gov.nationalarchives.dp.client.Client.ClientConfig
import uk.gov.nationalarchives.dp.client.ProcessMonitorClient.createProcessMonitorClient
import uk.gov.nationalarchives.dp.client.UserClient.createUserClient
import uk.gov.nationalarchives.dp.client.ValidateXmlAgainstXsd.PreservicaSchema
import uk.gov.nationalarchives.dp.client.WorkflowClient.createWorkflowClient

import java.net.URI
import scala.concurrent.duration.*

/** An object containing methods to create each of the four clients. This uses cats.effect.IO and fs2.Stream
  */
object Fs2Client:
  private val defaultSecretsManagerEndpoint = "https://secretsmanager.eu-west-2.amazonaws.com"

  /** Creates an entity client
    * @param secretName
    *   The name of the AWS secrets manager secret containing API credentials
    * @param duration
    *   The length of time to cache the credentials and token
    * @param ssmEndpointUri
    *   The endpoint of secrets manager to use
    * @return
    *   An entity client
    */
  def entityClient(
      secretName: String,
      duration: FiniteDuration = 15.minutes,
      ssmEndpointUri: String = defaultSecretsManagerEndpoint,
      potentialProxyUrl: Option[URI] = None
  ): IO[EntityClient[IO, Fs2Streams[IO]]] =
    HttpClientFs2Backend.resource[IO](httpClientOptions(potentialProxyUrl)).use { backend =>
      val clientConfig = ClientConfig("", secretName, LoggingWrapper(backend), duration, ssmEndpointUri)
      Client(clientConfig).getAuthDetails().map { authDetails =>
        createEntityClient(clientConfig.copy(apiBaseUrl = authDetails.apiUrl))
      }
    }

  /** Creates a content client
    *
    * @param secretName
    *   The name of the AWS secrets manager secret containing API credentials
    * @param duration
    *   The length of time to cache the credentials and token
    * @param ssmEndpointUri
    *   The endpoint of secrets manager to use
    * @return
    *   A content client
    */
  def contentClient(
      secretName: String,
      duration: FiniteDuration = 15.minutes,
      ssmEndpointUri: String = defaultSecretsManagerEndpoint,
      potentialProxyUrl: Option[URI] = None
  ): IO[ContentClient[IO]] =
    HttpClientFs2Backend.resource[IO](httpClientOptions(potentialProxyUrl)).use { backend =>
      val clientConfig = ClientConfig("", secretName, LoggingWrapper(backend), duration, ssmEndpointUri)
      Client(clientConfig).getApiUrl.map { apiUrl =>
        createContentClient(clientConfig.copy(apiBaseUrl = apiUrl))
      }
    }

  /** Creates a workflow client
    * @param secretName
    *   The name of the AWS secrets manager secret containing API credentials
    * @param duration
    *   The length of time to cache the credentials and token
    * @param ssmEndpointUri
    *   The endpoint of secrets manager to use
    * @return
    *   A workflow client
    */
  def workflowClient(
      secretName: String,
      duration: FiniteDuration = 15.minutes,
      ssmEndpointUri: String = defaultSecretsManagerEndpoint,
      potentialProxyUrl: Option[URI] = None
  ): IO[WorkflowClient[IO]] =
    HttpClientFs2Backend.resource[IO](httpClientOptions(potentialProxyUrl)).use { backend =>
      val clientConfig = ClientConfig("", secretName, LoggingWrapper(backend), duration, ssmEndpointUri)
      Client(clientConfig).getApiUrl.map { apiUrl =>
        createWorkflowClient(clientConfig.copy(apiBaseUrl = apiUrl))
      }

    }

  /** Creates a process monitor client
    *
    * @param secretName
    *   The name of the AWS secrets manager secret containing API credentials
    * @param duration
    *   The length of time to cache the credentials and token
    * @param ssmEndpointUri
    *   The endpoint of secrets manager to use
    * @return
    *   A a process monitor client
    */
  def processMonitorClient(
      secretName: String,
      duration: FiniteDuration = 15.minutes,
      ssmEndpointUri: String = defaultSecretsManagerEndpoint,
      potentialProxyUrl: Option[URI] = None
  ): IO[ProcessMonitorClient[IO]] =
    HttpClientFs2Backend.resource[IO](httpClientOptions(potentialProxyUrl)).use { backend =>
      val clientConfig = ClientConfig("", secretName, LoggingWrapper(backend), duration, ssmEndpointUri)
      Client(clientConfig).getApiUrl.map { apiUrl =>
        createProcessMonitorClient(clientConfig.copy(apiBaseUrl = apiUrl))
      }
    }

  def userClient(
      secretName: String,
      duration: FiniteDuration = 15.minutes,
      ssmEndpointUri: String = defaultSecretsManagerEndpoint,
      potentialProxyUrl: Option[URI] = None
  ): IO[UserClient[IO]] =
    HttpClientFs2Backend.resource[IO](httpClientOptions(potentialProxyUrl)).use { backend =>
      val clientConfig = ClientConfig("", secretName, LoggingWrapper(backend), duration, ssmEndpointUri)
      Client(clientConfig).getApiUrl.map { apiUrl =>
        createUserClient(clientConfig.copy(apiBaseUrl = apiUrl))
      }

    }

  def xmlValidator(schema: PreservicaSchema): ValidateXmlAgainstXsd[IO] = ValidateXmlAgainstXsd[IO](schema)

  private def httpClientOptions(potentialProxyUrl: Option[URI]): SttpBackendOptions =
    potentialProxyUrl
      .map { proxyUrl =>
        SttpBackendOptions.Default.httpProxy(proxyUrl.getHost, proxyUrl.getPort)
      }
      .getOrElse(SttpBackendOptions.Default)
