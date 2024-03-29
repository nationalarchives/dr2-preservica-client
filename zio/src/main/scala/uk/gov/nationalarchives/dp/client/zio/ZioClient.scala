package uk.gov.nationalarchives.dp.client.zio

import sttp.capabilities.zio.ZioStreams
import sttp.client3.SttpBackendOptions
import sttp.client3.httpclient.zio.HttpClientZioBackend
import uk.gov.nationalarchives.dp.client.EntityClient._
import uk.gov.nationalarchives.dp.client.AdminClient._
import uk.gov.nationalarchives.dp.client.Client.ClientConfig
import uk.gov.nationalarchives.dp.client.ContentClient._
import uk.gov.nationalarchives.dp.client.ProcessMonitorClient.createProcessMonitorClient
import uk.gov.nationalarchives.dp.client.WorkflowClient.createWorkflowClient
import uk.gov.nationalarchives.dp.client.{
  AdminClient,
  ContentClient,
  EntityClient,
  LoggingWrapper,
  ProcessMonitorClient,
  WorkflowClient
}
import zio.Task
import zio.interop.catz._

import java.net.URI
import scala.concurrent.duration._

/** An object containing methods to create each of the four clients. This uses zio.Task and zio.ZioStream
  */
object ZioClient {
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
  ): Task[EntityClient[Task, ZioStreams]] =
    HttpClientZioBackend(httpClientOptions(potentialProxyUrl)).map { backend =>
      createEntityClient[Task, ZioStreams](
        ClientConfig(url, secretName, LoggingWrapper(backend), duration, ssmEndpointUri)
      )
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
  ): Task[AdminClient[Task]] =
    HttpClientZioBackend(httpClientOptions(potentialProxyUrl)).map { backend =>
      createAdminClient[Task, ZioStreams](ClientConfig(url, secretName, backend, duration, ssmEndpointUri))
    }

  /** Creates a content client
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
  ): Task[ContentClient[Task]] =
    HttpClientZioBackend(httpClientOptions(potentialProxyUrl)).map { backend =>
      createContentClient[Task, ZioStreams](ClientConfig(url, secretName, backend, duration, ssmEndpointUri))
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
  ): Task[WorkflowClient[Task]] =
    HttpClientZioBackend(httpClientOptions(potentialProxyUrl)).map { backend =>
      createWorkflowClient[Task, ZioStreams](ClientConfig(url, secretName, backend, duration, ssmEndpointUri))
    }

  def processMonitorClient(
      url: String,
      secretName: String,
      duration: FiniteDuration = 15.minutes,
      ssmEndpointUri: String = defaultSecretsManagerEndpoint,
      potentialProxyUrl: Option[URI] = None
  ): Task[ProcessMonitorClient[Task]] =
    HttpClientZioBackend(httpClientOptions(potentialProxyUrl)).map { backend =>
      createProcessMonitorClient[Task](ClientConfig(url, secretName, backend, duration, ssmEndpointUri))
    }

  private def httpClientOptions(potentialProxyUrl: Option[URI]): SttpBackendOptions = {
    potentialProxyUrl
      .map { proxyUrl =>
        SttpBackendOptions.Default.httpProxy(proxyUrl.getHost, proxyUrl.getPort)
      }
      .getOrElse(SttpBackendOptions.Default)
  }
}
