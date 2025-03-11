package uk.gov.nationalarchives.dp.client

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers.*
import io.circe.parser.decode
import io.circe.generic.auto.*
import org.scalatest.{BeforeAndAfterEach, EitherValues}
import software.amazon.awssdk.services.secretsmanager.model.SecretsManagerException
import uk.gov.nationalarchives.dp.client.UserClient.ResetPasswordRequest

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters.*

abstract class UserClientTest[F[_]](preservicaPort: Int, secretsManagerPort: Int)
    extends AnyFlatSpec
    with BeforeAndAfterEach
    with EitherValues:

  case class SecretRequest(SecretId: String, VersionStage: String)

  val zeroSeconds: FiniteDuration = FiniteDuration(0, TimeUnit.SECONDS)
  val secretsManagerServer = new WireMockServer(secretsManagerPort)
  val secretsResponse = """{"SecretString":"{\"username\":\"password\"}"}"""
  val secretsNewPasswordResponse = """{"SecretString":"{\"username\":\"newPassword\"}"}"""
  val preservicaServer = new WireMockServer(preservicaPort)
  private val tokenResponse: String = """{"token": "abcde"}"""
  private val tokenUrl = "/api/accesstoken/login"

  def valueFromF[T](value: F[T]): T

  def createClient(url: String): F[UserClient[F]]

  def testClient: UserClient[F] = valueFromF(createClient(s"http://localhost:$preservicaPort"))

  override def beforeEach(): Unit =
    preservicaServer.start()
    preservicaServer.resetAll()
    secretsManagerServer.resetAll()
    secretsManagerServer.start()
    secretsManagerServer.stubFor(
      post(urlEqualTo("/"))
        .withRequestBody(equalToJson("{\"SecretId\":\"secret\",\"VersionStage\":\"AWSPENDING\"}"))
        .willReturn(okJson(secretsNewPasswordResponse))
    )
    secretsManagerServer.stubFor(
      post(urlEqualTo("/"))
        .withRequestBody(equalToJson("{\"SecretId\":\"secret\",\"VersionStage\":\"AWSCURRENT\"}"))
        .willReturn(okJson(secretsResponse))
    )
    preservicaServer.stubFor(post(urlEqualTo(tokenUrl)).willReturn(ok(tokenResponse)))

  override def afterEach(): Unit =
    preservicaServer.stop()
    secretsManagerServer.stop()

  "resetPassword" should s"return an exception if API returns an error" in {
    val client = testClient

    preservicaServer.stubFor(put(urlEqualTo("/api/user/password")).willReturn(serverError()))

    val error = intercept[PreservicaClientException] {
      valueFromF(client.resetPassword(ResetPasswordRequest("old", "newValidPassword")))
    }
    error.getMessage should equal(
      "Status code 500 calling http://localhost:9010/api/user/password with method PUT statusCode: 500, response: "
    )
  }

  "resetPassword" should s"pass the correct credentials to the API" in {
    preservicaServer.stubFor(put(urlEqualTo("/api/user/password")).willReturn(ok()))

    valueFromF(testClient.resetPassword(ResetPasswordRequest("oldPassword", "newValidPassword")))

    val bodyString = preservicaServer.getAllServeEvents.asScala.head.getRequest.getBodyAsString
    val request = decode[ResetPasswordRequest](bodyString).value

    request.password should equal("oldPassword")
    request.newPassword should equal("newValidPassword")
  }

  "resetPassword" should "return an error if the old and new password are equal" in {
    val error = intercept[PreservicaClientException] {
      valueFromF(testClient.resetPassword(ResetPasswordRequest("password", "password")))
    }
    error.getMessage should equal("New password is equal to the old password")
  }

  "resetPassword" should "return an error if the old and new password are equal, but have different letter casing" in {
    val error = intercept[PreservicaClientException] {
      valueFromF(testClient.resetPassword(ResetPasswordRequest("PASSWORD", "password")))
    }
    error.getMessage should equal("New password is equal to the old password")
  }

  "resetPassword" should "return an error if the old and new password are equal, even if they have leading/trailing spaces" in {
    val error = intercept[PreservicaClientException] {
      valueFromF(testClient.resetPassword(ResetPasswordRequest("    password", "password   ")))
    }
    error.getMessage should equal("New password is equal to the old password")
  }

  "resetPassword" should "return an error if the current password is empty" in {
    val error = intercept[PreservicaClientException] {
      valueFromF(testClient.resetPassword(ResetPasswordRequest("", "password")))
    }
    error.getMessage should equal("The password to be changed is empty")
  }

  "resetPassword" should "return an error if the new password is empty" in {
    val error = intercept[PreservicaClientException] {
      valueFromF(testClient.resetPassword(ResetPasswordRequest("password", "")))
    }
    error.getMessage should equal("New password is empty")
  }

  "resetPassword" should "return an error if the new password has fewer than 15 characters" in {
    val error = intercept[PreservicaClientException] {
      valueFromF(testClient.resetPassword(ResetPasswordRequest("password", "passHas14Chars")))
    }
    error.getMessage should equal("New password has fewer than 15 characters")
  }

  "testNewPassword" should "call secrets manager for the pending secret" in {
    valueFromF(testClient.testNewPassword())
    val requestBody = secretsManagerServer.getAllServeEvents.asScala.head.getRequest.getBodyAsString
    val secretRequest = decode[SecretRequest](requestBody).value

    secretRequest.SecretId should equal("secret")
    secretRequest.VersionStage should equal("AWSPENDING")
  }

  "testNewPassword" should "call the Preservica auth url with the pending password" in {
    valueFromF(testClient.testNewPassword())
    val body = preservicaServer.getAllServeEvents.asScala.head.getRequest.getBodyAsString

    body should equal("username=username&password=newPassword")
  }

  "testNewPassword" should "return an error if secrets manager returns an error" in {
    secretsManagerServer.resetAll()
    secretsManagerServer.stubFor(post(urlEqualTo("/")).willReturn(serverError()))
    val ex = intercept[SecretsManagerException] {
      valueFromF(testClient.testNewPassword())
    }
    ex.getMessage should equal(
      "Service returned HTTP status code 500 (Service: SecretsManager, Status Code: 500, Request ID: null)"
    )
  }

  "testNewPassword" should "return an error if Preservica returns an error" in {
    preservicaServer.resetAll()
    preservicaServer.stubFor(post(urlEqualTo("/api/accesstoken/login")).willReturn(forbidden()))
    val ex = intercept[PreservicaClientException] {
      valueFromF(testClient.testNewPassword())
    }
    ex.getMessage should equal(
      s"Status code 403 calling http://localhost:$preservicaPort/api/accesstoken/login with method POST statusCode: 403, response: "
    )
  }
