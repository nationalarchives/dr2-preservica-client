package examples

object Authentication {
  // An illustrative, standalone version of the "single-flight" pattern used internally by every client to make
  // sure that, however many fibers call it concurrently, only one of them ever fetches a new token (from Secrets
  // Manager and the Preservica login endpoint) once the cached token has expired.
  object SingleFlightTokenFetch {
    import cats.effect.{Deferred, IO, Ref}
    import cats.syntax.all.*

    case class TokenDetails(token: String, apiUrl: String)

    // #token_refresh_guard
    // None means no fetch is in progress. Some(deferred) means a fetch is in progress, and `deferred` will be
    // completed with its result (success or failure) once that fetch finishes.
    val tokenRefreshInFlight: IO[Ref[IO, Option[Deferred[IO, Either[Throwable, TokenDetails]]]]] =
      Ref.of[IO, Option[Deferred[IO, Either[Throwable, TokenDetails]]]](None)
    // #token_refresh_guard

    // #single_flight_fetch
    def singleFlightFetch(
        tokenRefreshInFlight: Ref[IO, Option[Deferred[IO, Either[Throwable, TokenDetails]]]],
        fetchGenerateAndCacheToken: IO[TokenDetails]
    ): IO[TokenDetails] =
      Deferred[IO, Either[Throwable, TokenDetails]].flatMap { newDeferred =>
        tokenRefreshInFlight.modify {
          // A fetch is already in progress: discard our own `newDeferred` and wait for the in-flight one instead.
          case existing @ Some(inFlight) => (existing, inFlight.get.rethrow)
          // No fetch is in progress: register ourselves as the fetcher, using our own `newDeferred`.
          case None =>
            val fetch = fetchGenerateAndCacheToken.attempt
              .flatTap(newDeferred.complete) // wake up any other fibers waiting on `newDeferred`
              .flatTap(_ => tokenRefreshInFlight.set(None)) // allow a future expiry to trigger a new fetch
              .rethrow
            (Some(newDeferred), fetch)
        }.flatten // run the atomic Ref update, then run whichever action (wait or fetch) was selected
      }
    // #single_flight_fetch
  }
}
