package uk.gov.nationalarchives.dp.client.zio

import sttp.capabilities.zio.ZioStreams
import sttp.client3.httpclient.zio.HttpClientZioBackend
import uk.gov.nationalarchives.dp.client.EntityClient._
import uk.gov.nationalarchives.dp.client.AdminClient._
import uk.gov.nationalarchives.dp.client.Client.ClientConfig
import uk.gov.nationalarchives.dp.client.ContentClient._
import uk.gov.nationalarchives.dp.client.WorkflowClient.createWorkflowClient
import uk.gov.nationalarchives.dp.client.{AdminClient, ContentClient, EntityClient, LoggingWrapper, WorkflowClient}
import zio.Task
import zio.interop.catz._

import scala.concurrent.duration._

object ZioClient {
  private val defaultSecretsManagerEndpoint = "https://secretsmanager.eu-west-2.amazonaws.com"

  def entityClient(
      url: String,
      secretName: String,
      duration: FiniteDuration = 15.minutes,
      ssmEndpointUri: String = defaultSecretsManagerEndpoint
  ): Task[EntityClient[Task, ZioStreams]] =
    HttpClientZioBackend().map { backend =>
      createEntityClient[Task, ZioStreams](
        ClientConfig(url, secretName, LoggingWrapper(backend), duration, ssmEndpointUri)
      )
    }

  def adminClient(
      url: String,
      secretName: String,
      duration: FiniteDuration = 15.minutes,
      ssmEndpointUri: String = defaultSecretsManagerEndpoint
  ): Task[AdminClient[Task]] =
    HttpClientZioBackend().map { backend =>
      createAdminClient[Task, ZioStreams](ClientConfig(url, secretName, backend, duration, ssmEndpointUri))
    }

  def contentClient(
      url: String,
      secretName: String,
      duration: FiniteDuration = 15.minutes,
      ssmEndpointUri: String = defaultSecretsManagerEndpoint
  ): Task[ContentClient[Task]] =
    HttpClientZioBackend().map { backend =>
      createContentClient[Task, ZioStreams](ClientConfig(url, secretName, backend, duration, ssmEndpointUri))
    }

  def workflowClient(
      url: String,
      secretName: String,
      duration: FiniteDuration = 15.minutes,
      ssmEndpointUri: String = defaultSecretsManagerEndpoint
  ): Task[WorkflowClient[Task]] =
    HttpClientZioBackend().map { backend =>
      createWorkflowClient[Task, ZioStreams](ClientConfig(url, secretName, backend, duration, ssmEndpointUri))
    }
}
