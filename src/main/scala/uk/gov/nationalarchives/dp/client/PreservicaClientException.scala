package uk.gov.nationalarchives.dp.client

import sttp.model.{Method, StatusCode, Uri}

/** A custom exception to represent an error from Preservica
  * @param message
  *   The exception message
  * @param err
  *   The cause of the exception
  */
class PreservicaClientException(message: String, err: Throwable) extends Exception(message, err)

/** Custom apply methods for the exception class
  */
object PreservicaClientException:

  /** Generate an exception from HTTP request parameters
    * @param method
    *   The request method
    * @param url
    *   The request url
    * @param statusCode
    *   The status code of the response
    * @param msg
    *   The response message
    * @return
    *   A `PreservicaClientException`
    */
  def apply(method: Method, url: Uri, statusCode: StatusCode, msg: String): PreservicaClientException =
    val message = s"Status code $statusCode calling $url with method $method $msg"
    new PreservicaClientException(message, new RuntimeException(msg))

  /** Creates an exception from an existing message
    * @param msg
    *   The exception message
    * @return
    *   A `PreservicaClientException`
    */
  def apply(msg: String) = new PreservicaClientException(msg, new RuntimeException(msg))
