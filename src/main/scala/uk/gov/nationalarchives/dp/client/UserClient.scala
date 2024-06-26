package uk.gov.nationalarchives.dp.client

import cats.MonadError
import cats.effect.Async
import io.circe.Printer
import uk.gov.nationalarchives.dp.client.Client.ClientConfig
import sttp.model.Method
import cats.implicits.*
import io.circe.syntax.*
import io.circe.generic.auto.*
import sttp.client3.IsOption
import uk.gov.nationalarchives.DASecretsManagerClient.Stage.Pending
import uk.gov.nationalarchives.dp.client.UserClient.ResetPasswordRequest

trait UserClient[F[_]]:
  def resetPassword(changePasswordRequest: ResetPasswordRequest): F[Unit]

  def testNewPassword(): F[Unit]

object UserClient:

  case class ResetPasswordRequest(password: String, newPassword: String) {
    def arePasswordsEqual: Boolean = password == newPassword
  }

  def createUserClient[F[_]](clientConfig: ClientConfig[F, ?])(using
      me: MonadError[F, Throwable],
      sync: Async[F]
  ): UserClient[F] = new UserClient[F]:
    val client: Client[F, ?] = Client(clientConfig)

    override def testNewPassword(): F[Unit] = for {
      authDetails <- client.getAuthDetails(Pending)
      token <- client.generateToken(authDetails)
    } yield ()

    override def resetPassword(resetPasswordRequest: ResetPasswordRequest): F[Unit] = for {
      _ <- me.raiseWhen(resetPasswordRequest.arePasswordsEqual)(
        PreservicaClientException("New password is equal to the old password")
      )
      _ <- me.raiseWhen(resetPasswordRequest.newPassword.trim.isEmpty)(
        PreservicaClientException("New password is empty")
      )
      token <- client.getAuthenticationToken
      _ <- client.sendJsonApiRequest[Option[String]](
        s"${clientConfig.apiBaseUrl}/api/user/password",
        token,
        Method.PUT,
        resetPasswordRequest.asJson.printWith(Printer.noSpaces).some
      )
    } yield ()
