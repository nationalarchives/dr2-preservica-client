# Authentication

Every client (Entity, Content, Workflow, Process Monitor and User) authenticates with Preservica in exactly the
same way. The logic lives in one shared, internal `Client` class that each specific client delegates to, so there
is nothing extra you need to do to authenticate: simply create a client (see the "Creating a client" section on
each client's usage page) and every method call will transparently obtain a token before making its API request.

### The auth flow

Before any request is sent to Preservica, the client calls an internal `getAuthenticationToken` method. This
returns a `TokenDetails` (an access token plus the Preservica `apiUrl` to call), either from the cache or freshly
fetched:

1. Check the cache for a `TokenDetails` value.
2. If a value is found, return it immediately - no network calls are made.
3. If no value is found (e.g. this is the first call, or the previous token has expired), fetch a new one:
   1. Build a `SecretsManagerAsyncClient` pointing at the configured Secrets Manager endpoint (defaults to the
      regional AWS endpoint, but can be overridden, for example to reach a private VPC endpoint or a test server).
   2. Call `getSecretValue` to retrieve the secret named by `secretName`. This secret is expected to contain JSON
      with a `userName`, `password` and `apiUrl`.
   3. `POST` the username and password to `{apiUrl}/api/accesstoken/login`. Preservica responds with an access
      token.
   4. Wrap the token and `apiUrl` in a `TokenDetails` and write it into the cache, with a time-to-live equal to the
      configured cache `duration` (default 15 minutes).
4. Add the token to the outgoing request as a `Preservica-Access-Token` header, then send the request.

### Caching

The token is cached in memory using a [Caffeine](https://github.com/ben-manes/caffeine) cache (via
[scalacache](https://github.com/cb372/scalacache)), scoped to a single client instance. This avoids calling
Secrets Manager and the Preservica login endpoint on every single API request - instead, a token is fetched once
and reused for the configured `duration`, which should be set to (at most) the actual lifetime of tokens issued by
Preservica.

### Avoiding duplicate token fetches

Client methods are frequently called from many concurrent fibers/threads at once, for example when paginating
through results in parallel. If the cached token has expired, a naive implementation would have every one of those
concurrent callers independently notice the empty cache and independently call Secrets Manager and the Preservica
login endpoint at the same time. Besides being wasteful, a large burst of simultaneous Secrets Manager calls can
overwhelm any proxy or firewall sitting in front of it, causing failures.

To prevent this, the client internally tracks whether a token fetch is already in progress using a single,
effectful "single-flight" guard: a cats-effect `Ref` holding an optional `Deferred`. The snippets below are a
standalone, illustrative version of the same pattern used by the real client:

@@snip [Authentication.scala](../../../scala/examples/Authentication.scala) { #token_refresh_guard }

* `None` means no fetch is currently in progress.
* `Some(deferred)` means a fetch is in progress, and `deferred` will be completed with its result (success or
  failure) once it finishes.

When the cache is found to be empty, the client calls `singleFlightFetch`:

@@snip [Authentication.scala](../../../scala/examples/Authentication.scala) { #single_flight_fetch }

* Every caller speculatively creates its own `Deferred` up front (`newDeferred`), since a new one can't be
  allocated inside the atomic `Ref.modify` block below.
* `tokenRefreshInFlight.modify` atomically inspects and updates the guard in one step, which is what prevents two
  callers from both seeing "no fetch in progress" at the same time:
    * If a fetch is **already in progress** (`Some(inFlight)`), the guard is left unchanged and this caller is
      simply given `inFlight.get.rethrow` - an action that waits for the *other* fiber's `Deferred` to complete,
      then returns its result (or raises its error). Its own `newDeferred` is discarded, unused.
    * If **no fetch is in progress** (`None`), this caller "wins": the guard becomes `Some(newDeferred)`, and it is
      given the `fetch` action to run - which performs the real fetch, completes `newDeferred` with the outcome so
      any waiting callers are woken up, then clears the guard back to `None` so a future expiry can trigger a new
      fetch.

This guarantees that no matter how many callers are waiting on an expired/missing token, only one call is ever
made to Secrets Manager and to the Preservica login endpoint.

### Token invalidation

If any API request comes back `Unauthorized` or `Forbidden`, this is treated as a sign that the cached token is no
longer valid (for example, if it has been revoked server-side). The client responds by clearing the entire token
cache before retrying, so that the next request goes through the fetch process above and obtains a fresh token,
rather than repeatedly retrying with a token that will never succeed.

### Configuration

The following parameters, all available when creating any client, affect authentication:

| Name                | Description                                                                                                     |
|---------------------|-------------------------------------------------------------------------------------------------------------------|
| secretName          | The name of the Secrets Manager secret containing the Preservica `userName`, `password` and `apiUrl`             |
| duration            | The time-to-live for the cached token. Defaults to 15 minutes                                                    |
| ssmEndpointUri      | The endpoint used to call Secrets Manager. Useful for tests or private VPC endpoints                             |
| potentialProxyUrl   | An optional proxy used for both the login call and subsequent API calls                                          |
| retryCount          | The number of retries used both when fetching a token and when calling the Preservica API                        |

@@@ index

* [Fs2](fs2.md)

@@@
