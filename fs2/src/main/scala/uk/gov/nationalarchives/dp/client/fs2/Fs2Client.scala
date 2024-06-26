package uk.gov.nationalarchives.dp.client.fs2

import cats.effect.*
import sttp.capabilities.fs2.Fs2Streams
import sttp.client3.SttpBackendOptions
import sttp.client3.httpclient.fs2.HttpClientFs2Backend
import uk.gov.nationalarchives.dp.client.EntityClient.*
import uk.gov.nationalarchives.dp.client.AdminClient.*
import uk.gov.nationalarchives.dp.client.ContentClient.createContentClient
import uk.gov.nationalarchives.dp.client.{
  AdminClient,
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
    * @param url
    *   The Preservica instance url
    * @param secretName
    *   The of the AWS secrets manager secret containing API credentials
    * @param duration
    *   The length of time to cache the credentials and token
    * @param ssmEndpointUri
    *   The endpoint of secrets manager to use
    * @return
    *   An entity client
    */
  def entityClient(
      url: String,
      secretName: String,
      duration: FiniteDuration = 15.minutes,
      ssmEndpointUri: String = defaultSecretsManagerEndpoint,
      potentialProxyUrl: Option[URI] = None
  ): IO[EntityClient[IO, Fs2Streams[IO]]] =
    HttpClientFs2Backend.resource[IO](httpClientOptions(potentialProxyUrl)).use { backend =>
      IO(createEntityClient(ClientConfig(url, secretName, LoggingWrapper(backend), duration, ssmEndpointUri)))
    }

  /** Creates an admin client
    * @param url
    *   The Preservica instance url
    * @param secretName
    *   The of the AWS secrets manager secret containing API credentials
    * @param duration
    *   The length of time to cache the credentials and token
    * @param ssmEndpointUri
    *   The endpoint of secrets manager to use
    * @return
    *   An admin client
    */
  def adminClient(
      url: String,
      secretName: String,
      duration: FiniteDuration = 15.minutes,
      ssmEndpointUri: String = defaultSecretsManagerEndpoint,
      potentialProxyUrl: Option[URI] = None
  ): IO[AdminClient[IO]] =
    HttpClientFs2Backend.resource[IO](httpClientOptions(potentialProxyUrl)).use { backend =>
      IO(createAdminClient(ClientConfig(url, secretName, LoggingWrapper(backend), duration, ssmEndpointUri)))
    }

  /** Creates a content client
    *
    * @param url
    *   The Preservica instance url
    * @param secretName
    *   The of the AWS secrets manager secret containing API credentials
    * @param duration
    *   The length of time to cache the credentials and token
    * @param ssmEndpointUri
    *   The endpoint of secrets manager to use
    * @return
    *   A content client
    */
  def contentClient(
      url: String,
      secretName: String,
      duration: FiniteDuration = 15.minutes,
      ssmEndpointUri: String = defaultSecretsManagerEndpoint,
      potentialProxyUrl: Option[URI] = None
  ): IO[ContentClient[IO]] =
    HttpClientFs2Backend.resource[IO](httpClientOptions(potentialProxyUrl)).use { backend =>
      IO(createContentClient(ClientConfig(url, secretName, LoggingWrapper(backend), duration, ssmEndpointUri)))
    }

  /** Creates a workflow client
    * @param url
    *   The Preservica instance url
    * @param secretName
    *   The of the AWS secrets manager secret containing API credentials
    * @param duration
    *   The length of time to cache the credentials and token
    * @param ssmEndpointUri
    *   The endpoint of secrets manager to use
    * @return
    *   A workflow client
    */
  def workflowClient(
      url: String,
      secretName: String,
      duration: FiniteDuration = 15.minutes,
      ssmEndpointUri: String = defaultSecretsManagerEndpoint,
      potentialProxyUrl: Option[URI] = None
  ): IO[WorkflowClient[IO]] =
    HttpClientFs2Backend.resource[IO](httpClientOptions(potentialProxyUrl)).use { backend =>
      IO(createWorkflowClient(ClientConfig(url, secretName, LoggingWrapper(backend), duration, ssmEndpointUri)))
    }

  def processMonitorClient(
      url: String,
      secretName: String,
      duration: FiniteDuration = 15.minutes,
      ssmEndpointUri: String = defaultSecretsManagerEndpoint,
      potentialProxyUrl: Option[URI] = None
  ): IO[ProcessMonitorClient[IO]] =
    HttpClientFs2Backend.resource[IO](httpClientOptions(potentialProxyUrl)).use { backend =>
      IO(createProcessMonitorClient(ClientConfig(url, secretName, LoggingWrapper(backend), duration, ssmEndpointUri)))
    }

  def userClient(
      url: String,
      secretName: String,
      duration: FiniteDuration = 15.minutes,
      ssmEndpointUri: String = defaultSecretsManagerEndpoint,
      potentialProxyUrl: Option[URI] = None
  ): IO[UserClient[IO]] =
    HttpClientFs2Backend.resource[IO](httpClientOptions(potentialProxyUrl)).use { backend =>
      IO(createUserClient(ClientConfig(url, secretName, LoggingWrapper(backend), duration, ssmEndpointUri)))
    }

  def xmlValidator(schema: PreservicaSchema): ValidateXmlAgainstXsd[IO] = ValidateXmlAgainstXsd[IO](schema)

  private def httpClientOptions(potentialProxyUrl: Option[URI]): SttpBackendOptions =
    potentialProxyUrl
      .map { proxyUrl =>
        SttpBackendOptions.Default.httpProxy(proxyUrl.getHost, proxyUrl.getPort)
      }
      .getOrElse(SttpBackendOptions.Default)
