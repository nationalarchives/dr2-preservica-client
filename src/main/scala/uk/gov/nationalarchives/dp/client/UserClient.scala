package uk.gov.nationalarchives.dp.client

import cats.MonadError
import cats.effect._
import cats.implicits._
import sttp.client3._
import sttp.model.Method
import uk.gov.nationalarchives.dp.client.Client.{AuthDetails, ClientConfig}

trait UserClient[F[_]] {
  def changePassword(oldPassword: String, newPassword: String): F[Unit]

  def testAuthDetails(authDetails: AuthDetails): F[Unit]
}
object UserClient {
  def createUserClient[F[_], S](clientConfig: ClientConfig[F, S])(implicit
                                                                  me: MonadError[F, Throwable],
                                                                  sync: Async[F]
  ): UserClient[F] = new UserClient[F] {

    private val client: Client[F, S] = Client(clientConfig)

    import client._

    override def testAuthDetails(authDetails: AuthDetails): F[Unit] = for {
      _ <- getToken(authDetails, uri"$apiBaseUrl/api/accesstoken/login")
    } yield ()

    override def changePassword(oldPassword: String, newPassword: String): F[Unit] = {
      val url = uri"$apiBaseUrl/api/user/password"
      for {
        token <- getAuthenticationToken
        res <- backend.send {
          basicRequest.put(url)
            .headers(Map("Preservica-Access-Token" -> token, "Content-Type"-> "application/json"))
            .body(s"""{"password": "$oldPassword", "newPassword": "$newPassword"}""")
        }
        _ <- me.fromEither(
          res.body.left.map(err => PreservicaClientException(Method.PUT, url, res.code, err))
        )
      } yield ()
    }
  }
}
