package uk.gov.nationalarchives.dp.client

import cats.MonadError
import cats.effect.Sync
import cats.implicits._
import sttp.client3._
import sttp.model.Method
import uk.gov.nationalarchives.dp.client.Client._
import uk.gov.nationalarchives.dp.client.WorkflowClient.StartWorkflowRequest

import scala.xml.PrettyPrinter

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
      val startWorkflowUrl = uri"$apiBaseUrl/sdb/rest/workflow/instances"

      val workflowContextIdNode = startWorkflowRequest.workflowContextId
        .map { workflowId =>
          <WorkflowContextId>{workflowId}</WorkflowContextId>
        }

      val workflowContextNameNode = startWorkflowRequest.workflowContextName
        .map { workflowName =>
          <WorkflowContextName>{workflowName}</WorkflowContextName>
        }

      val parameterNodes = startWorkflowRequest.parameters
        .map { parameter =>
          <Parameter>
              <Key>{parameter.key}</Key>
              <Value>{parameter.value}</Value>
          </Parameter>
        }

      val correlationIdNode =
        startWorkflowRequest.correlationId
          .map { correlationId =>
            <CorrelationId>{correlationId}</CorrelationId>
          }

      val requestNodes =
        List(workflowContextIdNode, workflowContextNameNode, correlationIdNode).flatten ++ parameterNodes

      val requestBody =
        <StartWorkflowRequest xmlns="http://workflow.preservica.com">
          {
          requestNodes.map { requestNode =>
            requestNode
          }
        }
        </StartWorkflowRequest>

      val requestBodyString =
        s"<?xml version='1.0' encoding='UTF-8' standalone='yes'?>\n${new PrettyPrinter(100, 2).format(requestBody)}"
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
        startWorkflowResponse <- sendXMLApiRequest(
          startWorkflowUrl.toString,
          token,
          Method.POST,
          Some(requestBodyString)
        )
        id <- dataProcessor.childNodeFromWorkflowInstance(startWorkflowResponse, "Id")
      } yield id.toInt
    }
  }

  case class Parameter(key: String, value: String)

  case class StartWorkflowRequest(
      workflowContextName: Option[String] = None,
      workflowContextId: Option[Int] = None,
      parameters: List[Parameter] = Nil,
      correlationId: Option[String] = None
  )
}
