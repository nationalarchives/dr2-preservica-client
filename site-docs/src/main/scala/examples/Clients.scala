package examples

object Clients {
  // #entity_client
  object EntityClients {
    import cats.effect.IO
    import sttp.capabilities.fs2.Fs2Streams
    import sttp.capabilities.zio.ZioStreams
    import uk.gov.nationalarchives.dp.client.EntityClient
    import uk.gov.nationalarchives.dp.client.fs2.Fs2Client
    import uk.gov.nationalarchives.dp.client.zio.ZioClient
    import zio.Task

    import scala.concurrent.duration._

    val preservicaUrl = "https://test.preservica.com"
    val secretName = "nameOfSecretsManagerSecretContainingAPICredentials"

    val fs2EntityClientWithDefaults: IO[EntityClient[IO, Fs2Streams[IO]]] = Fs2Client.entityClient(preservicaUrl, secretName)
    val fs2EntityClientWithCustomCacheDuration: IO[EntityClient[IO, Fs2Streams[IO]]] = Fs2Client.entityClient(preservicaUrl, secretName, 30.minutes)
    val fs2EntityClientWithCustomSecretsManagerEndpoint: IO[EntityClient[IO, Fs2Streams[IO]]] = Fs2Client.entityClient(preservicaUrl, secretName, ssmEndpointUri = "https://private.ssm.endpoint")
    val zioEntityClientWithDefaults: Task[EntityClient[Task, ZioStreams]] = ZioClient.entityClient(preservicaUrl, secretName)
  }
  // #entity_client
  // #content_client
  object ContentClients {
    import cats.effect.IO
    import uk.gov.nationalarchives.dp.client.ContentClient
    import uk.gov.nationalarchives.dp.client.fs2.Fs2Client
    import uk.gov.nationalarchives.dp.client.zio.ZioClient
    import zio.Task

    import scala.concurrent.duration._

    val preservicaUrl = "https://test.preservica.com"
    val secretName = "nameOfSecretsManagerSecretContainingAPICredentials"

    val fs2ContentClientWithDefaults: IO[ContentClient[IO]] = Fs2Client.contentClient(preservicaUrl, secretName)
    val fs2ContentClientWithCustomCacheDuration: IO[ContentClient[IO]] = Fs2Client.contentClient(preservicaUrl, secretName, 30.minutes)
    val fs2ContentClientWithCustomSecretsManagerEndpoint: IO[ContentClient[IO]] = Fs2Client.contentClient(preservicaUrl, secretName, ssmEndpointUri = "https://private.ssm.endpoint")
    val zioContentClientWithDefaults: Task[ContentClient[Task]] = ZioClient.contentClient(preservicaUrl, secretName)
  }
  // #content_client

  // #admin_client
  object AdminClients {
    import cats.effect.IO
    import uk.gov.nationalarchives.dp.client.AdminClient
    import uk.gov.nationalarchives.dp.client.fs2.Fs2Client
    import uk.gov.nationalarchives.dp.client.zio.ZioClient
    import zio.Task

    import scala.concurrent.duration._

    val preservicaUrl = "https://test.preservica.com"
    val secretName = "nameOfSecretsManagerSecretContainingAPICredentials"

    val fs2AdminClientWithDefaults: IO[AdminClient[IO]] = Fs2Client.adminClient(preservicaUrl, secretName)
    val fs2AdminClientWithCustomCacheDuration: IO[AdminClient[IO]] = Fs2Client.adminClient(preservicaUrl, secretName, 30.minutes)
    val fs2AdminClientWithCustomSecretsManagerEndpoint: IO[AdminClient[IO]] = Fs2Client.adminClient(preservicaUrl, secretName, ssmEndpointUri = "https://private.ssm.endpoint")
    val zioAdminClientWithDefaults: Task[AdminClient[Task]] = ZioClient.adminClient(preservicaUrl, secretName)
  }
  // #admin_client

  // #workflow_client
  object WorkflowClients {
    import cats.effect.IO
    import uk.gov.nationalarchives.dp.client.WorkflowClient
    import uk.gov.nationalarchives.dp.client.fs2.Fs2Client
    import uk.gov.nationalarchives.dp.client.zio.ZioClient
    import zio.Task

    import scala.concurrent.duration._

    val preservicaUrl = "https://test.preservica.com"
    val secretName = "nameOfSecretsManagerSecretContainingAPICredentials"

    val fs2WorkflowClientWithDefaults: IO[WorkflowClient[IO]] = Fs2Client.workflowClient(preservicaUrl, secretName)
    val fs2WorkflowClientWithCustomCacheDuration: IO[WorkflowClient[IO]] = Fs2Client.workflowClient(preservicaUrl, secretName, 30.minutes)
    val fs2WorkflowClientWithCustomSecretsManagerEndpoint: IO[WorkflowClient[IO]] = Fs2Client.workflowClient(preservicaUrl, secretName, ssmEndpointUri = "https://private.ssm.endpoint")
    val zioWorkflowClientWithDefaults: Task[WorkflowClient[Task]] = ZioClient.workflowClient(preservicaUrl, secretName)
  }
  // #workflow_client

  // #process_monitor_client
  object ProcessMonitorClients {
    import cats.effect.IO
    import uk.gov.nationalarchives.dp.client.ProcessMonitorClient
    import uk.gov.nationalarchives.dp.client.fs2.Fs2Client
    import uk.gov.nationalarchives.dp.client.zio.ZioClient
    import zio.Task

    import scala.concurrent.duration._

    val preservicaUrl = "https://test.preservica.com"
    val secretName = "nameOfSecretsManagerSecretContainingAPICredentials"

    val fs2ProcessMonitorClientWithDefaults: IO[ProcessMonitorClient[IO]] = Fs2Client.processMonitorClient(preservicaUrl, secretName)
    val fs2ProcessMonitorClientWithCustomCacheDuration: IO[ProcessMonitorClient[IO]] = Fs2Client.processMonitorClient(preservicaUrl, secretName, 30.minutes)
    val fs2ProcessMonitorClientWithCustomSecretsManagerEndpoint: IO[ProcessMonitorClient[IO]] = Fs2Client.processMonitorClient(preservicaUrl, secretName, ssmEndpointUri = "https://private.ssm.endpoint")
    val zioProcessMonitorClientWithDefaults: Task[ProcessMonitorClient[Task]] = ZioClient.processMonitorClient(preservicaUrl, secretName)
  }
  // #process_monitor_client
}
