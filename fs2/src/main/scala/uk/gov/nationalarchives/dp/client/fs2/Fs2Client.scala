package uk.gov.nationalarchives.dp.client.fs2

import cats.effect._
import sttp.capabilities.fs2.Fs2Streams
import sttp.client3.httpclient.fs2.HttpClientFs2Backend
import uk.gov.nationalarchives.dp.client.EntityClient._
import uk.gov.nationalarchives.dp.client.AdminClient._
import uk.gov.nationalarchives.dp.client.ContentClient.createContentClient
import uk.gov.nationalarchives.dp.client.{AdminClient, ContentClient, EntityClient, LoggingWrapper, WorkflowClient}
import uk.gov.nationalarchives.dp.client.Client.ClientConfig
import uk.gov.nationalarchives.dp.client.WorkflowClient.createWorkflowClient

import scala.concurrent.duration._

/** An object containing methods to create each of the four clients. This uses cats.effect.IO and fs2.Stream
  */
object Fs2Client {
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
      ssmEndpointUri: String = defaultSecretsManagerEndpoint
  ): IO[EntityClient[IO, Fs2Streams[IO]]] =
    HttpClientFs2Backend.resource[IO]().use { backend =>
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
      ssmEndpointUri: String = defaultSecretsManagerEndpoint
  ): IO[AdminClient[IO]] =
    HttpClientFs2Backend.resource[IO]().use { backend =>
      IO(createAdminClient(ClientConfig(url, secretName, LoggingWrapper(backend), duration, ssmEndpointUri)))
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
      ssmEndpointUri: String = defaultSecretsManagerEndpoint
  ): IO[ContentClient[IO]] =
    HttpClientFs2Backend.resource[IO]().use { backend =>
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
      ssmEndpointUri: String = defaultSecretsManagerEndpoint
  ): IO[WorkflowClient[IO]] =
    HttpClientFs2Backend.resource[IO]().use { backend =>
      IO(createWorkflowClient(ClientConfig(url, secretName, LoggingWrapper(backend), duration, ssmEndpointUri)))
    }
}
