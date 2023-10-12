package uk.gov.nationalarchives.dp.client

import cats.MonadError
import cats.effect.Sync
import cats.implicits._
import sttp.client3._
import sttp.model.Method
import uk.gov.nationalarchives.dp.client.Client._
import uk.gov.nationalarchives.dp.client.WorkflowClient.StartWorkflowRequest

trait WorkflowClient[F[_]] {
  def startWorkflow(startWorkflowRequest: StartWorkflowRequest): F[Int]
}

object WorkflowClient {

  def createWorkflowClient[F[_], S](clientConfig: ClientConfig[F, S])(implicit
      me: MonadError[F, Throwable],
      sync: Sync[F]
  ): WorkflowClient[F] = new WorkflowClient[F] {
    private val apiBaseUrl: String = clientConfig.apiBaseUrl
    private val client: Client[F, S] = Client(clientConfig)

    import client._

    override def startWorkflow(startWorkflowRequest: StartWorkflowRequest): F[Int] = {
      val startWorkflowUrl = uri"$apiBaseUrl/api/workflow/instances"

      val workflowContextIdNode = startWorkflowRequest.workflowContextId
        .map { workflowId =>
          s"<WorkflowContextId>${workflowId}</WorkflowContextId>"
        }
        .getOrElse("")

      val workflowContextNameNode = startWorkflowRequest.workflowContextName
        .map { workflowName =>
          s"""
          <WorkflowContextName>${workflowName}</WorkflowContextName>"""
        }
        .getOrElse("")

      val parameterNodes = startWorkflowRequest.parameters
        .map { parameter =>
          s"""
          <Parameter>
              <Key>${parameter.key}</Key>
              <Value>${parameter.value}</Value>
          </Parameter>"""
        }
        .mkString("")

      val correlationIdNode =
        startWorkflowRequest.correlationId
          .map { correlationId =>
            s"""
          <CorrelationId>$correlationId</CorrelationId>"""
          }
          .mkString("")

      val requestNodes =
        List(workflowContextIdNode, workflowContextNameNode, parameterNodes, correlationIdNode).mkString

      val requestBody =
        s"""
          <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
          <StartWorkflowRequest xmlns="http://workflow.preservica.com">
          ${requestNodes}
          </StartWorkflowRequest>"""

      for {
        _ <-
          if (startWorkflowRequest.workflowContextName.isEmpty && startWorkflowRequest.workflowContextId.isEmpty) {
            me.raiseError(
              PreservicaClientException(
                "You must pass in either a workflowContextName or a workflowContextId!"
              )
            )
          } else me.unit
        token <- getAuthenticationToken
        startWorkflowResponse <- sendXMLApiRequest(startWorkflowUrl.toString, token, Method.POST, Some(requestBody))
        id <- dataProcessor.childNodeFromWorkflowInstance(startWorkflowResponse, "Id")
      } yield id.toInt
    }
  }

  case class Parameters(key: String, value: String)

  case class StartWorkflowRequest(
      workflowContextName: Option[String] = None,
      workflowContextId: Option[Int] = None,
      parameters: List[Parameters] = Nil,
      correlationId: Option[String] = None
  )
}
