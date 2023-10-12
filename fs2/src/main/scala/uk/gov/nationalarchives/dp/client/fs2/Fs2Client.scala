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

object Fs2Client {
  private val defaultSecretsManagerEndpoint = "https://secretsmanager.eu-west-2.amazonaws.com"

  def entityClient(
      url: String,
      secretName: String,
      duration: FiniteDuration = 15.minutes,
      ssmEndpointUri: String = defaultSecretsManagerEndpoint
  ): IO[EntityClient[IO, Fs2Streams[IO]]] =
    HttpClientFs2Backend.resource[IO]().use { backend =>
      IO(createEntityClient(ClientConfig(url, secretName, LoggingWrapper(backend), duration, ssmEndpointUri)))
    }

  def adminClient(
      url: String,
      secretName: String,
      duration: FiniteDuration = 15.minutes,
      ssmEndpointUri: String = defaultSecretsManagerEndpoint
  ): IO[AdminClient[IO]] =
    HttpClientFs2Backend.resource[IO]().use { backend =>
      IO(createAdminClient(ClientConfig(url, secretName, LoggingWrapper(backend), duration, ssmEndpointUri)))
    }

  def contentClient(
      url: String,
      secretName: String,
      duration: FiniteDuration = 15.minutes,
      ssmEndpointUri: String = defaultSecretsManagerEndpoint
  ): IO[ContentClient[IO]] =
    HttpClientFs2Backend.resource[IO]().use { backend =>
      IO(createContentClient(ClientConfig(url, secretName, LoggingWrapper(backend), duration, ssmEndpointUri)))
    }

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
