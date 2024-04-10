package uk.gov.nationalarchives.dp.client

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.prop.TableDrivenPropertyChecks.forAll
import org.scalatest.prop.TableFor3
import org.scalatest.prop.Tables.Table
import org.scalatest.{Assertion, BeforeAndAfterEach}
import uk.gov.nationalarchives.dp.client.WorkflowClient.{Parameter, StartWorkflowRequest}

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters.*

abstract class WorkflowClientTest[F[_]](preservicaPort: Int, secretsManagerPort: Int)
    extends AnyFlatSpec
    with BeforeAndAfterEach:

  def valueFromF[T](value: F[T]): T

  def createClient(url: String): F[WorkflowClient[F]]

  def testClient: WorkflowClient[F] = valueFromF(createClient(s"http://localhost:$preservicaPort"))

  val zeroSeconds: FiniteDuration = FiniteDuration(0, TimeUnit.SECONDS)

  val secretsManagerServer = new WireMockServer(secretsManagerPort)

  val secretsResponse = """{"SecretString":"{\"username\":\"test\",\"password\":\"test\"}"}"""

  override def beforeEach(): Unit =
    preservicaServer.start()
    preservicaServer.resetAll()
    secretsManagerServer.start()
    secretsManagerServer.stubFor(post(urlEqualTo("/")).willReturn(okJson(secretsResponse)))

  override def afterEach(): Unit =
    preservicaServer.stop()
    secretsManagerServer.stop()

  val preservicaServer = new WireMockServer(preservicaPort)

  def checkServerCall(url: String): Assertion =
    preservicaServer.getAllServeEvents.asScala.count(_.getRequest.getUrl == url) should equal(1)

  private val tokenResponse: String = """{"token": "abcde"}"""
  private val tokenUrl = "/api/accesstoken/login"

  private val startWorkflowRequestPermutations: TableFor3[StartWorkflowRequest, String, List[String]] = Table(
    ("Request", "request should not contain the node", "request should contain nodes"),
    (
      StartWorkflowRequest(None, Some(123), List(Parameter("key", "value")), Some("correlationTestId")),
      "<WorkflowContextName>",
      List("<WorkflowContextId>", "<Parameter>", "<CorrelationId>")
    ),
    (
      StartWorkflowRequest(
        Some("workflowContextName"),
        None,
        List(Parameter("key", "value")),
        Some("correlationTestId")
      ),
      "<WorkflowContextId>",
      List("<WorkflowContextName>", "<Parameter>", "<CorrelationId>")
    ),
    (
      StartWorkflowRequest(Some("workflowContextName"), Some(123), Nil, Some("correlationTestId")),
      "<Parameter>",
      List("<WorkflowContextName>", "<WorkflowContextId>", "<CorrelationId>")
    ),
    (
      StartWorkflowRequest(Some("workflowContextName"), Some(123), List(Parameter("key", "value")), None),
      "<CorrelationId>",
      List("<WorkflowContextName>", "<WorkflowContextId>", "<Parameter>")
    )
  )

  private val startWorkflowResponse =
    <WorkflowInstance xmlns="http://workflow.preservica.com">
      <Id>3</Id>
    </WorkflowInstance>.toString()

  "startWorkflow" should s"return an exception if a request has both workflowContextName and workflowContextId set to None" in {
    val startWorkflowRequest =
      StartWorkflowRequest(
        None,
        None,
        List(Parameter("key", "value"), Parameter("key2", "value2")),
        Some("correlationTestId")
      )

    val client = testClient
    val error = intercept[PreservicaClientException] {
      valueFromF(client.startWorkflow(startWorkflowRequest))
    }

    error.getMessage should equal(
      s"You must pass in either a workflowContextName or a workflowContextId!"
    )
  }

  "startWorkflow" should s"make a correct full request to start a workflow" in {
    preservicaServer.stubFor(post(urlEqualTo(tokenUrl)).willReturn(ok(tokenResponse)))
    preservicaServer.stubFor(post(urlEqualTo(s"/sdb/rest/workflow/instances")).willReturn(ok(startWorkflowResponse)))

    val startWorkflowRequest = StartWorkflowRequest(
      Some("workflowContextName"),
      Some(123),
      List(Parameter("key", "value"), Parameter("key2", "value2")),
      Some("correlationTestId")
    )

    val client = testClient
    val startedWorkflowIdResponse: F[Int] = client.startWorkflow(startWorkflowRequest)

    val _ = valueFromF(startedWorkflowIdResponse)

    val requestMade = getRequestMade(preservicaServer)

    requestMade should be(
      s"""<?xml version='1.0' encoding='UTF-8' standalone='yes'?>
         |<StartWorkflowRequest xmlns="http://workflow.preservica.com">
         |          <WorkflowContextId>123</WorkflowContextId>
         |  <WorkflowContextName>workflowContextName</WorkflowContextName>
         |  <CorrelationId>correlationTestId</CorrelationId>
         |  <Parameter>
         |          <Key>key</Key>
         |          <Value>value</Value>
         |        </Parameter>
         |  <Parameter>
         |          <Key>key2</Key>
         |          <Value>value2</Value>
         |        </Parameter>
         |        </StartWorkflowRequest>""".stripMargin
    )
  }

  forAll(startWorkflowRequestPermutations) {
    (startWorkflowRequest, nodeMissingFromRequest, nodesThatShouldBeInRequest) =>
      "startWorkflow" should s"make a request without the '$nodeMissingFromRequest' node but with these nodes: " +
        s"${nodesThatShouldBeInRequest.mkString(", ")} if the corresponding property was not passed in" in {
          preservicaServer.stubFor(post(urlEqualTo(tokenUrl)).willReturn(ok(tokenResponse)))
          preservicaServer.stubFor(
            post(urlEqualTo(s"/sdb/rest/workflow/instances")).willReturn(ok(startWorkflowResponse))
          )

          val client = testClient
          val startedWorkflowIdResponse: F[Int] = client.startWorkflow(startWorkflowRequest)

          val _ = valueFromF(startedWorkflowIdResponse)

          val requestMade = getRequestMade(preservicaServer)

          requestMade.contains(nodeMissingFromRequest) should equal(false)
          nodesThatShouldBeInRequest.foreach(node => requestMade.contains(node) should equal(true))
        }
  }

  "startWorkflow" should s"return the 'Id' of the Workflow if the request was successful" in {
    preservicaServer.stubFor(post(urlEqualTo(tokenUrl)).willReturn(ok(tokenResponse)))
    preservicaServer.stubFor(post(urlEqualTo(s"/sdb/rest/workflow/instances")).willReturn(ok(startWorkflowResponse)))

    val startWorkflowRequest = StartWorkflowRequest(
      Some("workflowContextName"),
      Some(123),
      List(Parameter("key", "value"), Parameter("key2", "value2")),
      Some("testCorrelationId")
    )

    val client = testClient
    val startedWorkflowIdResponse: F[Int] = client.startWorkflow(startWorkflowRequest)

    val id = valueFromF(startedWorkflowIdResponse)

    id should equal(3)
  }

  "startWorkflow" should s"return an exception if the API call does" in {
    preservicaServer.stubFor(post(urlEqualTo(tokenUrl)).willReturn(ok(tokenResponse)))
    preservicaServer.stubFor(post(urlEqualTo(s"/sdb/rest/workflow/instances")).willReturn(badRequest))

    val startWorkflowRequest = StartWorkflowRequest(
      Some("workflowContextName"),
      Some(123),
      List(Parameter("key", "value"), Parameter("key2", "value2")),
      Some("correlationTestId")
    )

    val client = testClient

    val error = intercept[PreservicaClientException] {
      valueFromF(client.startWorkflow(startWorkflowRequest))
    }

    error.getMessage should equal(
      s"Status code 400 calling http://localhost:$preservicaPort/sdb/rest/workflow/instances with method POST "
    )
  }

  private def getRequestMade(preservicaServer: WireMockServer) =
    preservicaServer.getServeEvents.getServeEvents.get(0).getRequest.getBodyAsString
