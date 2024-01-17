package uk.gov.nationalarchives.dp.client

import cats.MonadError
import cats.effect.Sync
import cats.implicits._
import sttp.client3._
import sttp.client3.upicklejson.asJson
import sttp.model.Method
import uk.gov.nationalarchives.dp.client.Client._
import uk.gov.nationalarchives.dp.client.ProcessMonitorClient.{GetMonitorsRequest, Monitors}
import upickle.default._

/** A client to retrieve Preservica Monitors
  * @tparam F
  *   Type of the effect
  */
trait ProcessMonitorClient[F[_]] {

  /** Gets a preservica monitors
    * @param getMonitorsRequest
    *   An instance of [[ProcessMonitorClient.getMonitors]] It contains details used to get Monitors
    * @return
    *   The Monitors requested, wrapped in the F effect.
    */
  def getMonitors(getMonitorsRequest: GetMonitorsRequest): F[Seq[Monitors]]
}

/** An object containing a method which returns an implementation of the ProcessMonitorClient trait
  */
object ProcessMonitorClient {

  /** Creates a new `ProcessMonitorClient` instance.
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
    *   A new `ProcessMonitorClient`
    */
  def createProcessMonitorClient[F[_], S](clientConfig: ClientConfig[F, S])(implicit
      me: MonadError[F, Throwable],
      sync: Sync[F]
  ): ProcessMonitorClient[F] = new ProcessMonitorClient[F] {
    private val apiBaseUrl: String = clientConfig.apiBaseUrl
    private val client: Client[F, S] = Client(clientConfig)

    implicit val monitorsPayloadRW: ReadWriter[Monitors] = macroRW[Monitors]

    implicit val pagingPayloadRW: ReadWriter[Paging] = macroRW[Paging]

    implicit val valuePayloadRW: ReadWriter[Value] = macroRW[Value]

    implicit val monitorsResponsePayloadRW: ReadWriter[MonitorsResponse] = macroRW[MonitorsResponse]

    import client._

    override def getMonitors(getMonitorsRequest: GetMonitorsRequest): F[Seq[Monitors]] = {
      val getMonitorsRequestAsMap: Map[String, IterableOnce[String]] =
        getMonitorsRequest.productElementNames
          .zip(
            getMonitorsRequest.productIterator.map(_.asInstanceOf[IterableOnce[String]])
          )
          .toMap
      val relevantQueryParamsAsString: Map[String, String] = getMonitorsRequestAsMap.collect {
        case (name, value) if value.iterator.nonEmpty => (name, value.iterator.mkString(","))
      }

      val getMonitorsUrl = uri"$apiBaseUrl/api/processmonitor/monitors?$relevantQueryParamsAsString"

      for {
        _ <-
          if (!relevantQueryParamsAsString("name").startsWith("opex"))
            me.raiseError(
              PreservicaClientException(
                "The monitor name must start with 'opex'"
              )
            )
          else me.unit
        token <- getAuthenticationToken
        getMonitorsResponse <- sendJsonApiRequest[MonitorsResponse](
          getMonitorsUrl.toString,
          token,
          Method.GET,
          responseSchema = asJson[MonitorsResponse]
        )
      } yield getMonitorsResponse.value.monitors
    }
  }

  sealed trait MonitorsStatus {
    override def toString: String = getClass.getSimpleName.dropRight(1)
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
  ) {
    val subcategory: List[String] = Nil
  }

  case class MonitorsResponse(success: Boolean, version: Int, value: Value)

  case class Value(paging: Paging, monitors: Seq[Monitors])

  case class Paging(totalResults: Int)

  case class Monitors(
      mappedId: String,
      name: String,
      status: String,
      started: String,
      completed: String,
      category: String,
      subcategory: String,
      filesPending: Int,
      size: Int,
      filesProcessed: Int,
      warnings: Int,
      errors: Int,
      canRetry: Boolean
  )

  case object Running extends MonitorsStatus

  case object Pending extends MonitorsStatus

  case object Succeeded extends MonitorsStatus

  case object Failed extends MonitorsStatus

  case object Suspended extends MonitorsStatus

  case object Recoverable extends MonitorsStatus

  sealed trait MonitorCategory {
    override def toString: String = getClass.getSimpleName.dropRight(1)
  }

  case object Ingest extends MonitorCategory
  case object Export extends MonitorCategory
  case object DataManagement extends MonitorCategory
  case object Automated extends MonitorCategory

}
