package uk.gov.nationalarchives.dp.client

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.stubbing.ServeEvent
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.{Assertion, BeforeAndAfterEach}
import uk.gov.nationalarchives.dp.client.ProcessMonitorClient.*
import uk.gov.nationalarchives.dp.client.ProcessMonitorClient.MonitorsStatus.*
import uk.gov.nationalarchives.dp.client.ProcessMonitorClient.MonitorCategory.*
import uk.gov.nationalarchives.dp.client.ProcessMonitorClient.MessageStatus.*

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters.*

abstract class ProcessMonitorClientTest[F[_]](preservicaPort: Int, secretsManagerPort: Int)
    extends AnyFlatSpec
    with BeforeAndAfterEach:

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
      "progressText": "",
      "percentComplete": "",
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

  private def getMessagesResponse(nextPage: String = "") = raw"""{
    "success": true,
    "version": 1,
    "value": {
      "paging": {
        $nextPage
        "totalResults": 1500
      },
      "messages": [
      {
        "workflowInstanceId": 1,
        "monitorName": "opex/20ab81ce-759c-4a31-b58b-0b0f768e5716-e85222c9-7eb8-4f13-b8ed-1c8a2bc50253",
        "path": "9c433a2c-69da-4b43-b0a6-bfad4223b000",
        "date": "2023-10-23T10:52:54.000Z",
        "status": "Info",
        "displayMessage": "Matched directory 9c433a2c-69da-4b43-b0a6-bfad4223b000 to existing SO f66589d2-1040-407e-baf8-1bdeffbecd8b via Source ID TEST",
        "workflowName": "Ingest OPEX (Incremental)",
        "mappedMonitorId": "aacb79d7c91db789ce0f7c5abe02bf7a",
        "message": "monitor.info.directory.skip|{\"matchText\":\"Source ID TEST\"}",
        "mappedId": "d6676f9cbf9697fb6df629039c3311c8",
        "securityDescriptor": "open",
        "entityTitle": "entity title",
        "entityRef": "f66589d2-1040-407e-baf8-1bdeffbecd8b",
        "sourceId": "TEST"
      }
      ]
    }
  }"""

  private val getMessagesResponsePage2 = raw"""{
    "success": true,
    "version": 1,
    "value": {
      "paging": {
      "previous": "http://localhost:$preservicaPort/api/processmonitor/messages?monitor=1a84d902d9d993c348e06fbce21ac37f&status=Info&start=0&max=1000",
      "totalResults": 1500
      },
      "messages": [
      {
        "mappedId": "d6676f9cbf9697fb6df629039c3311c8",
        "mappedMonitorId": "aacb79d7c91db789ce0f7c5abe02bf7a",
        "monitorName": "opex/20ab81ce-759c-4a31-b58b-0b0f768e5716-e85222c9-7eb8-4f13-b8ed-1c8a2bc50253",
        "path": "9c433a2c-69da-4b43-b0a6-bfad4223b000",
        "status": "Info",
        "date": "2023-10-23T10:52:59.000Z",
        "message": "monitor.info.directory.skip|{\"matchText\":\"Source ID TEST\"}",
        "displayMessage": "Matched directory 9c433a2c-69da-4b43-b0a6-bfad4223b000 to existing SO 59fe27b2-e5ce-4b9c-b79d-d81c3647b190 via Source ID TEST",
        "sourceId": "TEST",
        "entityRef": "59fe27b2-e5ce-4b9c-b79d-d81c3647b190",
        "entityTitle": "entity title2",
        "workflowInstanceId": 1,
        "workflowName": "Ingest OPEX (Incremental)"
      }
      ]
    }
  }"""

  def valueFromF[T](value: F[T]): T

  def createClient(url: String): F[ProcessMonitorClient[F]]

  def testClient: ProcessMonitorClient[F] = valueFromF(createClient(s"http://localhost:$preservicaPort"))

  override def beforeEach(): Unit =
    preservicaServer.start()
    preservicaServer.resetAll()
    secretsManagerServer.start()
    secretsManagerServer.stubFor(post(urlEqualTo("/")).willReturn(okJson(secretsResponse)))

  override def afterEach(): Unit =
    preservicaServer.stop()
    secretsManagerServer.stop()

  def checkServerCall(url: String): Assertion =
    preservicaServer.getAllServeEvents.asScala.count(_.getRequest.getUrl == url) should equal(1)

  private def stubPreservicaMonitorsResponse =
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

  private def stubPreservicaMessagesResponse(start: Int = 0, messagesResponse: String = getMessagesResponse()) =
    preservicaServer.stubFor(post(urlEqualTo(tokenUrl)).willReturn(ok(tokenResponse)))
    preservicaServer.stubFor(
      get(
        urlEqualTo(
          "/api/processmonitor/messages" +
            "?monitor=1a84d902d9d993c348e06fbce21ac37f" +
            "&status=Info" +
            s"&start=$start" +
            "&max=1000"
        )
      ).willReturn(ok(messagesResponse))
    )

  "getMonitors" should s"return an exception if the name does not start with 'opex'" in {
    val getMonitorsRequest =
      GetMonitorsRequest(
        Nil,
        Some("nopex"),
        Nil
      )

    val client = testClient
    val error = intercept[PreservicaClientException] {
      valueFromF(client.getMonitors(getMonitorsRequest))
    }

    error.getMessage should equal("The monitor name must start with 'opex'")
  }

  "getMonitors" should s"make an empty body request" in {
    stubPreservicaMonitorsResponse

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
    stubPreservicaMonitorsResponse

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
          "",
          "",
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

  "getMessages" should s"make an empty body request" in {
    stubPreservicaMessagesResponse()

    val getMessagesRequest = GetMessagesRequest(
      List("1a84d902d9d993c348e06fbce21ac37f"),
      List(Info)
    )

    val client = testClient
    val messagesResponse: F[Seq[Message]] = client.getMessages(getMessagesRequest)

    val _ = valueFromF(messagesResponse)

    val requestMade = getRequestMade(preservicaServer)

    requestMade should be("")
  }

  "getMessages" should s"make an request for multiple mappedMonitorId and statuses if multiple are provided" in {
    preservicaServer.stubFor(post(urlEqualTo(tokenUrl)).willReturn(ok(tokenResponse)))
    preservicaServer.stubFor(
      get(
        urlEqualTo(
          s"/api/processmonitor/messages" +
            "?monitor=1a84d902d9d993c348e06fbce21ac37f,7abc1bb88b7f02806bf6a1f073bf80a3" +
            "&status=Info,Error" +
            "&start=0" +
            "&max=1000"
        )
      ).willReturn(ok(getMessagesResponse()))
    )

    val getMessagesRequest = GetMessagesRequest(
      List("1a84d902d9d993c348e06fbce21ac37f", "7abc1bb88b7f02806bf6a1f073bf80a3"),
      List(Info, Error)
    )

    val client = testClient
    val messagesResponse: F[Seq[Message]] = client.getMessages(getMessagesRequest)

    val _ = valueFromF(messagesResponse)
  }

  "getMessages" should s"return only one page of messages if the request was successful and the first page's response" +
    "did not include a url to the next page" in {
      stubPreservicaMessagesResponse()

      val getMessagesRequest = GetMessagesRequest(
        List("1a84d902d9d993c348e06fbce21ac37f"),
        List(Info)
      )

      val client = testClient
      val messagesResponse: F[Seq[Message]] = client.getMessages(getMessagesRequest)

      val messages = valueFromF(messagesResponse)

      val allRequests = getAllRequests(preservicaServer)

      val requestUrls = allRequests.map(_.getRequest.getAbsoluteUrl)

      allRequests.length should equal(3)
      requestUrls should equal(
        List(
          s"http://localhost:$preservicaPort/api/processmonitor/messages?monitor=1a84d902d9d993c348e06fbce21ac37f&status=Info&start=0&max=1000",
          s"http://localhost:$preservicaPort/api/accesstoken/login",
          s"http://localhost:$preservicaPort/api/accesstoken/login"
        )
      )

      messages should equal(
        List(
          Message(
            1,
            "opex/20ab81ce-759c-4a31-b58b-0b0f768e5716-e85222c9-7eb8-4f13-b8ed-1c8a2bc50253",
            "9c433a2c-69da-4b43-b0a6-bfad4223b000",
            "2023-10-23T10:52:54.000Z",
            "Info",
            "Matched directory 9c433a2c-69da-4b43-b0a6-bfad4223b000 to existing SO f66589d2-1040-407e-baf8-1bdeffbecd8b via Source ID TEST",
            "Ingest OPEX (Incremental)",
            "aacb79d7c91db789ce0f7c5abe02bf7a",
            "monitor.info.directory.skip|{\"matchText\":\"Source ID TEST\"}",
            "d6676f9cbf9697fb6df629039c3311c8",
            Option("open"),
            Option("entity title"),
            Option("f66589d2-1040-407e-baf8-1bdeffbecd8b"),
            Option("TEST")
          )
        )
      )
    }

  "getMessages" should s"return 2 pages of messages if the request was successful and the first page's response" +
    "includes a url to the next page" in {

      stubPreservicaMessagesResponse(
        0,
        getMessagesResponse(
          s""""next": "http://localhost:$preservicaPort/api/processmonitor/messages?"""
            + """monitor=1a84d902d9d993c348e06fbce21ac37f&status=Info&start=1000&max=1000","""
        )
      )
      stubPreservicaMessagesResponse(1000, getMessagesResponsePage2)

      val getMessagesRequest = GetMessagesRequest(
        List("1a84d902d9d993c348e06fbce21ac37f"),
        List(Info)
      )

      val client = testClient
      val messagesResponse: F[Seq[Message]] = client.getMessages(getMessagesRequest)

      val messages = valueFromF(messagesResponse)

      val allRequests = getAllRequests(preservicaServer)
      val requestUrls = allRequests.map(_.getRequest.getAbsoluteUrl)

      allRequests.length should equal(4)
      requestUrls should equal(
        List(
          s"http://localhost:$preservicaPort/api/processmonitor/messages?monitor=1a84d902d9d993c348e06fbce21ac37f&status=Info&start=1000&max=1000",
          s"http://localhost:$preservicaPort/api/processmonitor/messages?monitor=1a84d902d9d993c348e06fbce21ac37f&status=Info&start=0&max=1000",
          s"http://localhost:$preservicaPort/api/accesstoken/login",
          s"http://localhost:$preservicaPort/api/accesstoken/login"
        )
      )

      messages should equal(
        List(
          Message(
            1,
            "opex/20ab81ce-759c-4a31-b58b-0b0f768e5716-e85222c9-7eb8-4f13-b8ed-1c8a2bc50253",
            "9c433a2c-69da-4b43-b0a6-bfad4223b000",
            "2023-10-23T10:52:54.000Z",
            "Info",
            "Matched directory 9c433a2c-69da-4b43-b0a6-bfad4223b000 to existing SO f66589d2-1040-407e-baf8-1bdeffbecd8b via Source ID TEST",
            "Ingest OPEX (Incremental)",
            "aacb79d7c91db789ce0f7c5abe02bf7a",
            "monitor.info.directory.skip|{\"matchText\":\"Source ID TEST\"}",
            "d6676f9cbf9697fb6df629039c3311c8",
            Option("open"),
            Option("entity title"),
            Option("f66589d2-1040-407e-baf8-1bdeffbecd8b"),
            Option("TEST")
          ),
          Message(
            1,
            "opex/20ab81ce-759c-4a31-b58b-0b0f768e5716-e85222c9-7eb8-4f13-b8ed-1c8a2bc50253",
            "9c433a2c-69da-4b43-b0a6-bfad4223b000",
            "2023-10-23T10:52:59.000Z",
            "Info",
            "Matched directory 9c433a2c-69da-4b43-b0a6-bfad4223b000 to existing SO 59fe27b2-e5ce-4b9c-b79d-d81c3647b190 via Source ID TEST",
            "Ingest OPEX (Incremental)",
            "aacb79d7c91db789ce0f7c5abe02bf7a",
            "monitor.info.directory.skip|{\"matchText\":\"Source ID TEST\"}",
            "d6676f9cbf9697fb6df629039c3311c8",
            None,
            Option("entity title2"),
            Option("59fe27b2-e5ce-4b9c-b79d-d81c3647b190"),
            Option("TEST")
          )
        )
      )
    }

  "getMessages" should s"return an exception if the API call does" in {
    preservicaServer.stubFor(post(urlEqualTo(tokenUrl)).willReturn(ok(tokenResponse)))
    preservicaServer.stubFor(
      get(
        urlEqualTo(
          s"/api/processmonitor/messages" +
            "?monitor=1a84d902d9d993c348e06fbce21ac37f" +
            "&status=Info" +
            "&start=0" +
            "&max=1000"
        )
      ).willReturn(badRequest)
    )

    val getMessagesRequest = GetMessagesRequest(
      List("1a84d902d9d993c348e06fbce21ac37f"),
      List(Info)
    )

    val client = testClient

    val error = intercept[PreservicaClientException] {
      valueFromF(client.getMessages(getMessagesRequest))
    }

    error.getMessage should equal(
      s"Status code 400 calling http://localhost:$preservicaPort/api/processmonitor/messages" +
        "?monitor=1a84d902d9d993c348e06fbce21ac37f" +
        "&status=Info" +
        "&start=0" +
        "&max=1000 with method GET statusCode: 400, response: "
    )
  }

  private def getAllRequests(preservicaServer: WireMockServer): List[ServeEvent] =
    println(preservicaServer.getAllServeEvents)
    preservicaServer.getServeEvents.getServeEvents.asScala.iterator.toList

  private def getRequestMade(preservicaServer: WireMockServer) =
    getAllRequests(preservicaServer).head.getRequest.getBodyAsString
