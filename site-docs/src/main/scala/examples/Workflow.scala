package examples

object Workflow {
  // #fs2
  object WorkflowFs2 {
    import cats.effect.IO
    import uk.gov.nationalarchives.dp.client.WorkflowClient.{Parameter, StartWorkflowRequest}
    import uk.gov.nationalarchives.dp.client.fs2.Fs2Client

    val url = "https://test.preservica.com"
    val parameters: List[Parameter] = List(Parameter("param1", "value1"))
    val startWorkflowRequestWithName: StartWorkflowRequest = StartWorkflowRequest(Option("contextName"), None, parameters, Option("correlationId"))
    val startWorkflowRequestWithId: StartWorkflowRequest = StartWorkflowRequest(None, Option(1), parameters, Option("correlationId"))
    val startWorkflowRequestNoCorrelationId: StartWorkflowRequest = StartWorkflowRequest(None, Option(1), parameters, None)
    val startWorkflowRequestNoParameters: StartWorkflowRequest = StartWorkflowRequest(None, Option(1), Nil, Option("correlationId"))

    def searchEntities(): IO[Unit] = {
      for {
        client <- Fs2Client.workflowClient(url, "secretName")
        _ <- client.startWorkflow(startWorkflowRequestWithName)
        _ <- client.startWorkflow(startWorkflowRequestWithId)
        _ <- client.startWorkflow(startWorkflowRequestNoCorrelationId)
        _ <- client.startWorkflow(startWorkflowRequestNoParameters)
      } yield ()
    }
  }
  // #fs2

  // #zio
  object WorkflowZio {
    import uk.gov.nationalarchives.dp.client.WorkflowClient.{Parameter, StartWorkflowRequest}
    import uk.gov.nationalarchives.dp.client.zio.ZioClient
    import zio._

    val url = "https://test.preservica.com"
    val parameters: List[Parameter] = List(Parameter("param1", "value1"))
    val startWorkflowRequestWithName: StartWorkflowRequest = StartWorkflowRequest(Option("contextName"), None, parameters, Option("correlationId"))
    val startWorkflowRequestWithId: StartWorkflowRequest = StartWorkflowRequest(None, Option(1), parameters, Option("correlationId"))
    val startWorkflowRequestNoCorrelationId: StartWorkflowRequest = StartWorkflowRequest(None, Option(1), parameters, None)
    val startWorkflowRequestNoParameters: StartWorkflowRequest = StartWorkflowRequest(None, Option(1), Nil, Option("correlationId"))

    def searchEntities(): Task[Unit] = {
      for {
        client <- ZioClient.workflowClient(url, "secretName")
        _ <- client.startWorkflow(startWorkflowRequestWithName)
        _ <- client.startWorkflow(startWorkflowRequestWithId)
        _ <- client.startWorkflow(startWorkflowRequestNoCorrelationId)
        _ <- client.startWorkflow(startWorkflowRequestNoParameters)
      } yield ()
    }
  }
  // #zio
}
