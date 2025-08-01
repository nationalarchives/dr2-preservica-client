package uk.gov.nationalarchives.dp.client

import cats.effect.Async
import io.circe.{Decoder, Encoder, Printer}
import uk.gov.nationalarchives.dp.client.Client.ClientConfig
import sttp.model.Method
import cats.implicits.*
import io.circe.syntax.*
import sttp.client3.IsOption
import uk.gov.nationalarchives.DASecretsManagerClient.Stage.Pending
import uk.gov.nationalarchives.dp.client.UserClient.ResetPasswordRequest

trait UserClient[F[_]]:
  def resetPassword(changePasswordRequest: ResetPasswordRequest): F[Unit]

  def testNewPassword(): F[Unit]

object UserClient:

  given Encoder[ResetPasswordRequest] =
    Encoder.forProduct2("password", "newPassword")(req => (req.password, req.newPassword))

  case class ResetPasswordRequest(password: String, newPassword: String) {
    private val currentPasswordTrimmedAndLowerCased = password.toLowerCase().strip()
    private val newPasswordTrimmedAndLowerCased = newPassword.toLowerCase().strip()

    def arePasswordsEqual: Boolean = currentPasswordTrimmedAndLowerCased == newPasswordTrimmedAndLowerCased
    def passwordIsEmpty: Boolean = currentPasswordTrimmedAndLowerCased.isEmpty
    def newPasswordIsEmpty: Boolean = newPasswordTrimmedAndLowerCased.isEmpty
    def passwordFewerThan15Chars: Boolean = newPasswordTrimmedAndLowerCased.length < 15
  }

  def createUserClient[F[_]: Async](clientConfig: ClientConfig[F, ?]): UserClient[F] = new UserClient[F]:
    val client: Client[F, ?] = Client(clientConfig)

    override def testNewPassword(): F[Unit] = for {
      authDetails <- client.getAuthDetails(Pending)
      token <- client.generateToken(authDetails)
    } yield ()

    override def resetPassword(resetPasswordRequest: ResetPasswordRequest): F[Unit] = for {
      _ <- Async[F].raiseWhen(resetPasswordRequest.arePasswordsEqual)(
        PreservicaClientException("New password is equal to the old password")
      )
      _ <- Async[F].raiseWhen(resetPasswordRequest.passwordIsEmpty)(
        PreservicaClientException("The password to be changed is empty")
      )
      _ <- Async[F].raiseWhen(resetPasswordRequest.newPasswordIsEmpty)(
        PreservicaClientException("New password is empty")
      )
      _ <- Async[F].raiseWhen(resetPasswordRequest.passwordFewerThan15Chars)(
        PreservicaClientException("New password has fewer than 15 characters")
      )

      _ <- client.sendJsonApiRequest[Option[String]](
        s"${clientConfig.apiBaseUrl}/api/user/password",
        Method.PUT,
        resetPasswordRequest.asJson.printWith(Printer.noSpaces).some
      )
    } yield ()
