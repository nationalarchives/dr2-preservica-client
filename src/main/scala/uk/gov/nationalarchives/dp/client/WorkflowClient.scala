package uk.gov.nationalarchives.dp.client

import cats.MonadError
import cats.effect.Sync
import cats.implicits._
import sttp.client3._
import sttp.model.Method
import uk.gov.nationalarchives.dp.client.Client._
import uk.gov.nationalarchives.dp.client.WorkflowClient.StartWorkflowRequest

import scala.xml.PrettyPrinter

/** A client to start a Preservica workflow
  * @tparam F
  *   Type of the effect
  */
trait WorkflowClient[F[_]] {

  /** Starts a preservica workflow
    * @param startWorkflowRequest
    *   An instance of [[WorkflowClient.StartWorkflowRequest]] It contains details used to start the workflow
    * @return
    *   The id of the new workflow wrapped in the F effect.
    */
  def startWorkflow(startWorkflowRequest: StartWorkflowRequest): F[Int]
}

/** An object containing a method which returns an implementation of the WorkflowClient trait
  */
object WorkflowClient {

  /** Creates a new `WorkflowClient` instance.
    * @param clientConfig
    *   Configuration parameters needed to create the client
    * @param me
    *   An implicit instance of cats.MonadError
    * @param sync
    *   An implicit instance of cats.Sync
    * @tparam F
    *   The type of the effect
    * @tparam S
    *   The type of the Stream to be used for the streaming methods.
    * @return
    *   A new `WorkflowClient`
    */
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

  /** A workflow request parameter
    * @param key
    *   The parameter key
    * @param value
    *   The parameter value
    */
  case class Parameter(key: String, value: String)

  /** A workflow request
    * @param workflowContextName
    *   An optional workflow context name. Either this or the context id must be provided
    * @param workflowContextId
    *   An optional workflow context id. Either this or the context name must be provided.
    * @param parameters
    *   An list of parameters. This can be empty
    * @param correlationId
    *   An optional correlation id. This can be empty.
    */
  case class StartWorkflowRequest(
      workflowContextName: Option[String] = None,
      workflowContextId: Option[Int] = None,
      parameters: List[Parameter] = Nil,
      correlationId: Option[String] = None
  )
}
