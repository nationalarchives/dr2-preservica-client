package examples

import examples.Clients.ContentClients.{secretName}
import uk.gov.nationalarchives.dp.client.ContentClient

import java.net.URI

object Clients {
  // #entity_client
  object EntityClients {
    import cats.effect.IO
    import sttp.capabilities.fs2.Fs2Streams
    import uk.gov.nationalarchives.dp.client.EntityClient
    import uk.gov.nationalarchives.dp.client.fs2.Fs2Client

    import scala.concurrent.duration.*

    val preservicaUrl = "https://test.preservica.com"
    val secretName = "nameOfSecretsManagerSecretContainingAPICredentials"

    val fs2EntityClientWithDefaults: IO[EntityClient[IO, Fs2Streams[IO]]] =
      Fs2Client.entityClient(secretName)
    val fs2EntityClientWithCustomCacheDuration: IO[EntityClient[IO, Fs2Streams[IO]]] =
      Fs2Client.entityClient(secretName, 30.minutes)
    val fs2EntityClientWithCustomSecretsManagerEndpoint: IO[EntityClient[IO, Fs2Streams[IO]]] =
      Fs2Client.entityClient(secretName, ssmEndpointUri = "https://private.ssm.endpoint")
    val fs2EntityClientWithCustomProxy: IO[EntityClient[IO, Fs2Streams[IO]]] =
      Fs2Client.entityClient(secretName, potentialProxyUrl = Option(URI.create("http://proxy.url")))
  }
  // #entity_client
  // #content_client
  object ContentClients {
    import cats.effect.IO
    import uk.gov.nationalarchives.dp.client.ContentClient
    import uk.gov.nationalarchives.dp.client.fs2.Fs2Client

    import scala.concurrent.duration.*

    val preservicaUrl = "https://test.preservica.com"
    val secretName = "nameOfSecretsManagerSecretContainingAPICredentials"

    val fs2ContentClientWithDefaults: IO[ContentClient[IO]] = Fs2Client.contentClient(secretName)
    val fs2ContentClientWithCustomCacheDuration: IO[ContentClient[IO]] =
      Fs2Client.contentClient(secretName, 30.minutes)
    val fs2ContentClientWithCustomSecretsManagerEndpoint: IO[ContentClient[IO]] =
      Fs2Client.contentClient(secretName, ssmEndpointUri = "https://private.ssm.endpoint")
    val fs2ContentClientWithCustomProxy: IO[ContentClient[IO]] =
      Fs2Client.contentClient(secretName, potentialProxyUrl = Option(URI.create("http://proxy.url")))
  }
  // #content_client

  // #workflow_client
  object WorkflowClients {
    import cats.effect.IO
    import uk.gov.nationalarchives.dp.client.WorkflowClient
    import uk.gov.nationalarchives.dp.client.fs2.Fs2Client

    import scala.concurrent.duration.*

    val preservicaUrl = "https://test.preservica.com"
    val secretName = "nameOfSecretsManagerSecretContainingAPICredentials"

    val fs2WorkflowClientWithDefaults: IO[WorkflowClient[IO]] = Fs2Client.workflowClient(secretName)
    val fs2WorkflowClientWithCustomCacheDuration: IO[WorkflowClient[IO]] =
      Fs2Client.workflowClient(secretName, 30.minutes)
    val fs2WorkflowClientWithCustomSecretsManagerEndpoint: IO[WorkflowClient[IO]] =
      Fs2Client.workflowClient(secretName, ssmEndpointUri = "https://private.ssm.endpoint")
    val fs2WorkflowClientWithCustomProxy: IO[WorkflowClient[IO]] =
      Fs2Client.workflowClient(secretName, potentialProxyUrl = Option(URI.create("http://proxy.url")))
  }
  // #workflow_client

  // #process_monitor_client
  object ProcessMonitorClients {
    import cats.effect.IO
    import uk.gov.nationalarchives.dp.client.ProcessMonitorClient
    import uk.gov.nationalarchives.dp.client.fs2.Fs2Client

    import scala.concurrent.duration.*

    val preservicaUrl = "https://test.preservica.com"
    val secretName = "nameOfSecretsManagerSecretContainingAPICredentials"

    val fs2ProcessMonitorClientWithDefaults: IO[ProcessMonitorClient[IO]] =
      Fs2Client.processMonitorClient(secretName)
    val fs2ProcessMonitorClientWithCustomCacheDuration: IO[ProcessMonitorClient[IO]] =
      Fs2Client.processMonitorClient(secretName, 30.minutes)
    val fs2ProcessMonitorClientWithCustomSecretsManagerEndpoint: IO[ProcessMonitorClient[IO]] =
      Fs2Client.processMonitorClient(secretName, ssmEndpointUri = "https://private.ssm.endpoint")
    val fs2ProcessMonitorClientWithCustomProxy: IO[ProcessMonitorClient[IO]] = Fs2Client.processMonitorClient(
      secretName,
      potentialProxyUrl = Option(URI.create("http://proxy.url"))
    )
  }
  // #process_monitor_client
}
