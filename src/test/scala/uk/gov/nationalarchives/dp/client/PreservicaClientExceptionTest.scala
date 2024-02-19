package uk.gov.nationalarchives.dp.client

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers.*
import sttp.client3.UriContext
import sttp.model.{Method, StatusCode}

class PreservicaClientExceptionTest extends AnyFlatSpec:
  "creating an exception with http details" should "create the correct message" in {
    val ex = PreservicaClientException(Method.POST, uri"https://example.com", StatusCode.Ok, "Test message")
    val expectedMessage = "Status code 200 calling https://example.com with method POST Test message"
    ex.getMessage should equal(expectedMessage)
  }

  "creating an exception with a message only" should "create the correct message" in {
    val ex = PreservicaClientException("Test message")
    ex.getMessage should equal("Test message")
  }
