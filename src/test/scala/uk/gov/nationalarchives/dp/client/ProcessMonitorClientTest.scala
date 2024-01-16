package uk.gov.nationalarchives.dp.client

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers._
import org.scalatest.{Assertion, BeforeAndAfterEach}
import uk.gov.nationalarchives.dp.client.ProcessMonitorClient._

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters._

abstract class ProcessMonitorClientTest[F[_]](preservicaPort: Int, secretsManagerPort: Int)
    extends AnyFlatSpec
    with BeforeAndAfterEach {

  val zeroSeconds: FiniteDuration = FiniteDuration(0, TimeUnit.SECONDS)
  val secretsManagerServer = new WireMockServer(secretsManagerPort)
  val secretsResponse = """{"SecretString":"{\"username\":\"test\",\"password\":\"test\"}"}"""
  val preservicaServer = new WireMockServer(preservicaPort)
  private val tokenResponse: String = """{"token": "abcde"}"""
  private val tokenUrl = "/api/accesstoken/login"
  private val getMonitorsResponse = """{
    "success": true,
    "version": 1,
    "value": {
      "paging": {
      "totalResults": 1
    },
      "monitors": [ {
      "mappedId": "aacb79d7c91db789ce0f7c5abe02bf7a",
      "name": "opex/20ab81ce-759c-4a31-b58b-0b0f768e5716-e85222c9-7eb8-4f13-b8ed-1c8a2bc50253",
      "status": "Succeeded",
      "started": "2024-01-07T12:02:04.000Z",
      "completed": "2024-01-07T12:02:48.000Z",
      "category": "Ingest",
      "subcategory": "OPEX",
      "filesPending": 3,
      "size": 6083,
      "filesProcessed": 1,
      "warnings": 0,
      "errors": 0,
      "canRetry": true
    }
      ]
    }
  }"""

  def valueFromF[T](value: F[T]): T

  def createClient(url: String): F[ProcessMonitorClient[F]]

  def testClient: ProcessMonitorClient[F] = valueFromF(createClient(s"http://localhost:$preservicaPort"))

  override def beforeEach(): Unit = {
    preservicaServer.start()
    preservicaServer.resetAll()
    secretsManagerServer.start()
    secretsManagerServer.stubFor(post(urlEqualTo("/")).willReturn(okJson(secretsResponse)))
  }

  override def afterEach(): Unit = {
    preservicaServer.stop()
    secretsManagerServer.stop()
  }

  def checkServerCall(url: String): Assertion =
    preservicaServer.getAllServeEvents.asScala.count(_.getRequest.getUrl == url) should equal(1)

  private def stubPreservicaResponse = {
    preservicaServer.stubFor(post(urlEqualTo(tokenUrl)).willReturn(ok(tokenResponse)))
    preservicaServer.stubFor(
      get(
        urlEqualTo(
          s"/api/processmonitor/monitors" +
            "?status=Pending" +
            "&name=opex/20ab81ce-759c-4a31-b58b-0b0f768e5716-e85222c9-7eb8-4f13-b8ed-1c8a2bc50253" +
            "&category=Ingest"
        )
      ).willReturn(ok(getMonitorsResponse))
    )
  }

  "getMonitors" should s"return an exception if the name does not start with 'opex'" in {
    val getMonitorsRequest = {
      GetMonitorsRequest(
        Nil,
        Some("nopex"),
        Nil
      )
    }

    val client = testClient
    val error = intercept[PreservicaClientException] {
      valueFromF(client.getMonitors(getMonitorsRequest))
    }

    error.getMessage should equal("The monitor name must start with 'opex'")
  }

  "getMonitors" should s"make an empty body request" in {
    stubPreservicaResponse

    val getMonitorsRequest = GetMonitorsRequest(
      List(Pending),
      Some("opex/20ab81ce-759c-4a31-b58b-0b0f768e5716-e85222c9-7eb8-4f13-b8ed-1c8a2bc50253"),
      List(Ingest)
    )

    val client = testClient
    val monitorsResponse: F[Seq[Monitors]] = client.getMonitors(getMonitorsRequest)

    val _ = valueFromF(monitorsResponse)

    val requestMade = getRequestMade(preservicaServer)

    requestMade should be("")
  }

  "getMonitors" should s"make an request for multiple statuses and categories if multiple are provided" in {
    preservicaServer.stubFor(post(urlEqualTo(tokenUrl)).willReturn(ok(tokenResponse)))
    preservicaServer.stubFor(
      get(
        urlEqualTo(
          s"/api/processmonitor/monitors" +
            "?status=Pending,Running" +
            "&name=opex/20ab81ce-759c-4a31-b58b-0b0f768e5716-e85222c9-7eb8-4f13-b8ed-1c8a2bc50253" +
            "&category=Ingest,Export"
        )
      ).willReturn(ok(getMonitorsResponse))
    )

    val getMonitorsRequest = GetMonitorsRequest(
      List(Pending, Running),
      Some("opex/20ab81ce-759c-4a31-b58b-0b0f768e5716-e85222c9-7eb8-4f13-b8ed-1c8a2bc50253"),
      List(Ingest, Export)
    )

    val client = testClient
    val monitorsResponse: F[Seq[Monitors]] = client.getMonitors(getMonitorsRequest)

    val _ = valueFromF(monitorsResponse)
  }

  "getMonitors" should s"return the monitor if the request was successful" in {
    stubPreservicaResponse

    val getMonitorsRequest = GetMonitorsRequest(
      List(Pending),
      Some("opex/20ab81ce-759c-4a31-b58b-0b0f768e5716-e85222c9-7eb8-4f13-b8ed-1c8a2bc50253"),
      List(Ingest)
    )

    val client = testClient
    val monitorsResponse: F[Seq[Monitors]] = client.getMonitors(getMonitorsRequest)

    val monitors = valueFromF(monitorsResponse)

    monitors should equal(
      List(
        Monitors(
          "aacb79d7c91db789ce0f7c5abe02bf7a",
          "opex/20ab81ce-759c-4a31-b58b-0b0f768e5716-e85222c9-7eb8-4f13-b8ed-1c8a2bc50253",
          "Succeeded",
          "2024-01-07T12:02:04.000Z",
          "2024-01-07T12:02:48.000Z",
          "Ingest",
          "OPEX",
          3,
          6083,
          1,
          0,
          0,
          true
        )
      )
    )
  }

  "getMonitors" should s"return an exception if the API call does" in {
    preservicaServer.stubFor(post(urlEqualTo(tokenUrl)).willReturn(ok(tokenResponse)))
    preservicaServer.stubFor(
      get(
        urlEqualTo(
          s"/api/processmonitor/monitors" +
            "?status=Pending" +
            "&name=opex/20ab81ce-759c-4a31-b58b-0b0f768e5716-e85222c9-7eb8-4f13-b8ed-1c8a2bc50253" +
            "&category=Ingest"
        )
      ).willReturn(badRequest)
    )

    val getMonitorsRequest = GetMonitorsRequest(
      List(Pending),
      Some("opex/20ab81ce-759c-4a31-b58b-0b0f768e5716-e85222c9-7eb8-4f13-b8ed-1c8a2bc50253"),
      List(Ingest)
    )

    val client = testClient

    val error = intercept[PreservicaClientException] {
      valueFromF(client.getMonitors(getMonitorsRequest))
    }

    error.getMessage should equal(
      s"Status code 400 calling http://localhost:$preservicaPort/api/processmonitor/monitors" +
        s"?status=Pending" +
        s"&name=opex/20ab81ce-759c-4a31-b58b-0b0f768e5716-e85222c9-7eb8-4f13-b8ed-1c8a2bc50253" +
        s"&category=Ingest with method GET statusCode: 400, response: "
    )
  }

  private def getRequestMade(preservicaServer: WireMockServer) =
    preservicaServer.getServeEvents.getServeEvents.get(0).getRequest.getBodyAsString
}
