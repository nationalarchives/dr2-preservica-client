package uk.gov.nationalarchives.dp.client

import cats.effect.Async
import cats.implicits.*
import io.circe.{Decoder, HCursor}
import sttp.client3.*
import sttp.model.Method
import uk.gov.nationalarchives.dp.client.Client.*
import uk.gov.nationalarchives.dp.client.ProcessMonitorClient.*

/** A client to retrieve Preservica Monitors
  * @tparam F
  *   Type of the effect
  */
trait ProcessMonitorClient[F[_]]:

  /** Gets Preservica monitors
    * @param getMonitorsRequest
    *   An instance of [[ProcessMonitorClient.getMonitors]] It contains details used to get Monitors
    * @return
    *   The Monitors requested, wrapped in the F effect.
    */
  def getMonitors(getMonitorsRequest: GetMonitorsRequest): F[Seq[Monitors]]

  /** Gets Preservica messages
    * @param getMessagesRequest
    *   An instance of [[ProcessMonitorClient.getMessages]] It contains details used to get Messages
    * @param start
    *   The message number to start at.
    * @param max
    *   The maximum number of messages that should be returned (1000 is optimal, more than that could cause issues).
    * @return
    *   The Messages requested, wrapped in the F effect.
    */
  def getMessages(getMessagesRequest: GetMessagesRequest, start: Int = 0, max: Int = 1000): F[Seq[Message]]

/** An object containing a method which returns an implementation of the ProcessMonitorClient trait
  */
object ProcessMonitorClient:

  /** Creates a new `ProcessMonitorClient` instance.
    * @param clientConfig
    *   Configuration parameters needed to create the client
    * @tparam F
    *   The type of the effect
    * @return
    *   A new `ProcessMonitorClient`
    */
  def createProcessMonitorClient[F[_]: Async](clientConfig: ClientConfig[F, ?]): ProcessMonitorClient[F] =
    new ProcessMonitorClient[F]:
      private val apiBaseUrl: String = clientConfig.apiBaseUrl
      private val client: Client[F, ?] = Client(clientConfig)

      import client.*

      given Decoder[Monitors] = (c: HCursor) =>
        for
          mappedId <- c.downField("mappedId").as[String]
          name <- c.downField("name").as[String]
          status <- c.downField("status").as[String]
          started <- c.downField("started").as[Option[String]]
          completed <- c.downField("completed").as[Option[String]]
          category <- c.downField("category").as[String]
          subcategory <- c.downField("subcategory").as[String]
          progressText <- c.downField("progressText").as[Option[String]]
          percentComplete <- c.downField("percentComplete").as[Option[String]]
          filesPending <- c.downField("filesPending").as[Int]
          size <- c.downField("size").as[Long]
          filesProcessed <- c.downField("filesProcessed").as[Int]
          warnings <- c.downField("warnings").as[Int]
          errors <- c.downField("errors").as[Int]
          canRetry <- c.downField("canRetry").as[Boolean]
        yield Monitors(
          mappedId,
          name,
          status,
          started,
          completed,
          category,
          subcategory,
          progressText,
          percentComplete,
          filesPending,
          size,
          filesProcessed,
          warnings,
          errors,
          canRetry
        )

      given Decoder[Message] = (c: HCursor) =>
        for
          workflowInstanceId <- c.downField("workflowInstanceId").as[Int]
          monitorName <- c.downField("monitorName").as[String]
          path <- c.downField("path").as[String]
          date <- c.downField("date").as[String]
          status <- c.downField("status").as[String]
          displayMessage <- c.downField("displayMessage").as[String]
          workflowName <- c.downField("workflowName").as[String]
          mappedMonitorId <- c.downField("mappedMonitorId").as[String]
          message <- c.downField("message").as[String]
          mappedId <- c.downField("mappedId").as[String]
          securityDescriptor <- c.downField("securityDescriptor").as[Option[String]]
          entityTitle <- c.downField("entityTitle").as[Option[String]]
          entityRef <- c.downField("entityRef").as[Option[String]]
          sourceId <- c.downField("sourceId").as[Option[String]]
        yield Message(
          workflowInstanceId,
          monitorName,
          path,
          date,
          status,
          displayMessage,
          workflowName,
          mappedMonitorId,
          message,
          mappedId,
          securityDescriptor,
          entityTitle,
          entityRef,
          sourceId
        )

      given Decoder[Paging] = (c: HCursor) =>
        for
          next <- c.downField("next").as[Option[String]]
          totalResults <- c.downField("totalResults").as[Int]
        yield Paging(next, totalResults)

      given Decoder[MessagesValue] = (c: HCursor) =>
        for
          paging <- c.downField("paging").as[Paging]
          messages <- c.downField("messages").as[Seq[Message]]
        yield MessagesValue(paging, messages)

      given Decoder[MessagesResponse] = (c: HCursor) =>
        for
          success <- c.downField("success").as[Boolean]
          version <- c.downField("version").as[Int]
          value <- c.downField("value").as[MessagesValue]
        yield MessagesResponse(success, version, value)

      given Decoder[MonitorsValue] = (c: HCursor) =>
        for
          paging <- c.downField("paging").as[Paging]
          monitors <- c.downField("monitors").as[Seq[Monitors]]
        yield MonitorsValue(paging, monitors)

      given Decoder[MonitorsResponse] = (c: HCursor) =>
        for
          success <- c.downField("success").as[Boolean]
          version <- c.downField("version").as[Int]
          value <- c.downField("value").as[MonitorsValue]
        yield MonitorsResponse(success, version, value)

      override def getMonitors(getMonitorsRequest: GetMonitorsRequest): F[Seq[Monitors]] =
        val relevantQueryParamsAsString: Map[String, String] = getQueryParamsAsMap(getMonitorsRequest)

        val getMonitorsUrl = uri"$apiBaseUrl/api/processmonitor/monitors?$relevantQueryParamsAsString"

        for
          _ <-
            Async[F].raiseWhen(!relevantQueryParamsAsString("name").startsWith("opex"))(
              PreservicaClientException("The monitor name must start with 'opex'")
            )
          getMonitorsResponse <- sendJsonApiRequest[MonitorsResponse](
            getMonitorsUrl.toString,
            Method.GET
          )
        yield getMonitorsResponse.value.monitors

      override def getMessages(
          getMessagesRequest: GetMessagesRequest,
          start: Int = 0,
          max: Int = 1000
      ): F[Seq[Message]] =
        val relevantQueryParamsAsString: Map[String, String] = getQueryParamsAsMap(getMessagesRequest)
        val getMessagesUrl =
          uri"$apiBaseUrl/api/processmonitor/messages?$relevantQueryParamsAsString&start=$start&max=$max"

        monitorMessages(getMessagesUrl.toString, Nil)

      private def monitorMessages(url: String, amassedMessages: Seq[Message]): F[Seq[Message]] =
        if url.isEmpty then Async[F].pure(amassedMessages)
        else
          for
            getMessagesResponse <- sendJsonApiRequest[MessagesResponse](
              url,
              Method.GET
            )
            messagesResponse = getMessagesResponse.value.messages
            potentialNextPageUrl = getMessagesResponse.value.paging.next.getOrElse("")
            allMessages <- monitorMessages(potentialNextPageUrl, amassedMessages ++ messagesResponse)
          yield allMessages

      private def getQueryParamsAsMap(request: Product): Map[String, String] =
        val getMonitorsRequestAsMap: Map[String, IterableOnce[String]] =
          request.productElementNames
            .zip(request.productIterator.map(_.asInstanceOf[IterableOnce[String]]))
            .toMap

        getMonitorsRequestAsMap.collect {
          case (name, value) if value.iterator.nonEmpty => (name, value.iterator.mkString(","))
        }

  /** A Monitors request parameter
    * @param key
    *   The parameter key
    * @param value
    *   The parameter value
    */
  case class Parameter(key: String, value: String)

  /** A Monitors' request
    * @param status
    *   An optional monitor status.
    * @param name
    *   An optional monitor name.
    * @param category
    *   A list of categories. This can be empty
    */
  case class GetMonitorsRequest(
      status: List[MonitorsStatus] = Nil,
      name: Option[String] = None,
      category: List[MonitorCategory] = Nil
  ):
    val subcategory: List[String] = Nil

  private case class MonitorsResponse(success: Boolean, version: Int, value: MonitorsValue)

  case class MonitorsValue(paging: Paging, monitors: Seq[Monitors])

  case class Paging(next: Option[String] = None, totalResults: Int)

  case class Monitors(
      mappedId: String,
      name: String,
      status: String,
      started: Option[String],
      completed: Option[String],
      category: String,
      subcategory: String,
      progressText: Option[String],
      percentComplete: Option[String],
      filesPending: Int,
      size: Long,
      filesProcessed: Int,
      warnings: Int,
      errors: Int,
      canRetry: Boolean
  )

  enum MonitorsStatus:
    case Running, Pending, Succeeded, Failed, Suspended, Recoverable

  enum MonitorCategory:
    case Ingest, Export, DataManagement, Automated

  case class GetMessagesRequest(monitor: List[String] = Nil, status: List[MessageStatus] = Nil)

  private case class MessagesResponse(success: Boolean, version: Int, value: MessagesValue)

  case class MessagesValue(paging: Paging, messages: Seq[Message])

  case class Message(
      workflowInstanceId: Int,
      monitorName: String,
      path: String,
      date: String,
      status: String,
      displayMessage: String,
      workflowName: String,
      mappedMonitorId: String,
      message: String,
      mappedId: String,
      securityDescriptor: Option[String] = None,
      entityTitle: Option[String] = None,
      entityRef: Option[String] = None,
      sourceId: Option[String] = None
  )

  enum MessageStatus:
    case Error, Info, Debug, Warning
