package uk.gov.nationalarchives.dp.client

import sttp.model.{Method, StatusCode, Uri}

class PreservicaClientException(message: String, err: Throwable) extends Exception(message, err)
object PreservicaClientException {

  def apply(method: Method, url: Uri, statusCode: StatusCode, msg: String): PreservicaClientException = {
    val message = s"Status code $statusCode calling $url with method $method $msg"
    new PreservicaClientException(message, new RuntimeException(msg))
  }
  def apply(msg: String) = new PreservicaClientException(msg, new RuntimeException(msg))
}
