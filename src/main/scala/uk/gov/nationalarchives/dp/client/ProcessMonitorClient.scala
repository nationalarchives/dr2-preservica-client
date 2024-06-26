package uk.gov.nationalarchives.dp.client

import cats.MonadError
import cats.effect.Async
import cats.implicits.*
import io.circe.generic.auto.*
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
    * @param me
    *   An implicit instance of cats.MonadError
    * @param sync
    *   An implicit instance of cats.Sync
    * @tparam F
    *   The type of the effect
    * @return
    *   A new `ProcessMonitorClient`
    */
  def createProcessMonitorClient[F[_]](clientConfig: ClientConfig[F, ?])(using
      me: MonadError[F, Throwable],
      sync: Async[F]
  ): ProcessMonitorClient[F] = new ProcessMonitorClient[F]:
    private val apiBaseUrl: String = clientConfig.apiBaseUrl
    private val client: Client[F, ?] = Client(clientConfig)

    import client.*

    override def getMonitors(getMonitorsRequest: GetMonitorsRequest): F[Seq[Monitors]] =
      val relevantQueryParamsAsString: Map[String, String] = getQueryParamsAsMap(getMonitorsRequest)

      val getMonitorsUrl = uri"$apiBaseUrl/api/processmonitor/monitors?$relevantQueryParamsAsString"

      for
        _ <-
          me.raiseWhen(!relevantQueryParamsAsString("name").startsWith("opex"))(
            PreservicaClientException("The monitor name must start with 'opex'")
          )
        token <- getAuthenticationToken
        getMonitorsResponse <- sendJsonApiRequest[MonitorsResponse](
          getMonitorsUrl.toString,
          token,
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

      for
        token <- getAuthenticationToken
        messages <- monitorMessages(getMessagesUrl.toString, token, Nil)
      yield messages

    private def monitorMessages(url: String, token: String, amassedMessages: Seq[Message]): F[Seq[Message]] =
      if url.isEmpty then me.pure(amassedMessages)
      else
        for
          getMessagesResponse <- sendJsonApiRequest[MessagesResponse](
            url,
            token,
            Method.GET
          )
          messagesResponse = getMessagesResponse.value.messages
          potentialNextPageUrl = getMessagesResponse.value.paging.next.getOrElse("")
          allMessages <- monitorMessages(potentialNextPageUrl, token, amassedMessages ++ messagesResponse)
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

  case class MonitorsResponse(success: Boolean, version: Int, value: MonitorsValue)

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
      size: Int,
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

  case class MessagesResponse(success: Boolean, version: Int, value: MessagesValue)

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
