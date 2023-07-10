package uk.gov.nationalarchives.dp.client.fs2

import cats.effect.IO
import com.amazonaws.xray.strategy.sampling.CentralizedSamplingStrategy
import com.amazonaws.xray.{AWSXRay, AWSXRayRecorder, AWSXRayRecorderBuilder}
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.propagation.{ContextPropagators, TextMapPropagator}
import io.opentelemetry.contrib.awsxray.AwsXrayIdGenerator
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.`export`.BatchSpanProcessor
import sttp.capabilities.fs2.Fs2Streams
import sttp.client3.httpclient.fs2.HttpClientFs2Backend
import uk.gov.nationalarchives.dp.client.AdminClient._
import uk.gov.nationalarchives.dp.client.Client.ClientConfig
import uk.gov.nationalarchives.dp.client.ContentClient.createContentClient
import uk.gov.nationalarchives.dp.client.EntityClient._
import uk.gov.nationalarchives.dp.client.{AdminClient, ContentClient, EntityClient, XRayWrapper}

import scala.concurrent.duration._
import scala.io.Source

object Fs2Client {
  private val defaultSecretsManagerEndpoint = "https://secretsmanager.eu-west-2.amazonaws.com"

  def entityClient(
      url: String,
      duration: FiniteDuration = 15.minutes,
      ssmEndpointUri: String = defaultSecretsManagerEndpoint
  ): IO[EntityClient[IO, Fs2Streams[IO]]] = {
    HttpClientFs2Backend.resource[IO]().use { backend =>
      IO {
        val rules = getClass.getResource("/sampling-rules.json")
        val builder = AWSXRayRecorderBuilder.standard.withSamplingStrategy(new CentralizedSamplingStrategy(rules))
        AWSXRay.setGlobalRecorder(builder.build())
        createEntityClient(ClientConfig(url, new XRayWrapper(backend), duration, ssmEndpointUri))
      }
    }
  }

  def adminClient(
      url: String,
      duration: FiniteDuration = 15.minutes,
      ssmEndpointUri: String = defaultSecretsManagerEndpoint
  ): IO[AdminClient[IO]] =
    HttpClientFs2Backend.resource[IO]().use { backend =>
      cats.effect.IO(createAdminClient(ClientConfig(url, backend, duration, ssmEndpointUri)))
    }

  def contentClient(
      url: String,
      duration: FiniteDuration = 15.minutes,
      ssmEndpointUri: String = defaultSecretsManagerEndpoint
  ): IO[ContentClient[IO]] =
    HttpClientFs2Backend.resource[IO]().use { backend =>
      cats.effect.IO(createContentClient(ClientConfig(url, backend, duration, ssmEndpointUri)))
    }
}
