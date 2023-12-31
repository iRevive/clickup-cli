package io.clickup.api

import java.time.Instant

import cats.effect.{Async, Resource}
import cats.syntax.either.*
import cats.syntax.functor.*
import fs2.io.net.Network
import io.circe.Json
import io.circe.syntax.*
import io.clickup.model.{TaskId, TeamId}
import org.http4s.{Header, Headers, Method, Request, Response, Uri}
import org.http4s.circe.CirceEntityCodec.circeEntityDecoder
import org.http4s.circe.jsonEncoder
import org.http4s.client.Client
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.syntax.literals.*
import org.typelevel.ci.*

import scala.concurrent.duration.FiniteDuration

class ApiClient[F[_]: Async](client: Client[F]) {

  private val root = uri"https://api.clickup.com/api/v2"

  def taskInfo(taskId: TaskId, teamId: TeamId, token: ApiToken): F[Task] = {
    val uri = (root / "task" / taskId.toString)
      .withQueryParam("custom_task_ids", true)
      .withQueryParam("team_id", teamId.asInt)

    val request = Request[F](Method.GET, uri, headers = Headers(Header.Raw(ci"Authorization", token.value)))

    for {
      response <- client.expectOr[Task](request)(onError)
    } yield response
  }

  def timeEntries(start: Instant, end: Instant, teamId: TeamId, token: ApiToken): F[List[TimeEntry]] = {
    val startMillis = start.toEpochMilli
    val endMillis   = end.toEpochMilli

    val uri = (root / "team" / teamId.asInt / "time_entries")
      .withQueryParam("start_date", startMillis)
      .withQueryParam("end_date", endMillis)

    val request = Request[F](Method.GET, uri, headers = Headers(Header.Raw(ci"Authorization", token.value)))

    for {
      response <- client.expectOr[TimeEntries](request)(onError)
    } yield response.data
  }

  def addTimeEntry(
      taskId: TaskId,
      start: Instant,
      duration: FiniteDuration,
      description: String,
      teamId: TeamId,
      token: ApiToken
  ): F[Unit] = {
    val uri = (root / "team" / teamId.asInt / "time_entries")
      .withQueryParam("custom_task_ids", true)
      .withQueryParam("team_id", teamId.asInt)

    val body = Json.obj(
      "tid"         -> taskId.asJson,
      "description" -> description.asJson,
      "start"       -> start.toEpochMilli.asJson,
      "at"          -> start.toEpochMilli.asJson,
      "billable"    -> false.asJson,
      "duration"    -> duration.toMillis.asJson
    )

    val request =
      Request[F](Method.POST, uri, headers = Headers(Header.Raw(ci"Authorization", token.value))).withEntity(body)

    for {
      response <- client.expectOr[Json](request)(onError)
      _ = println(response)
    } yield ()
  }

  private def onError(response: Response[F]): F[Throwable] =
    for {
      body <- response.attemptAs[String].value
      _ = println(body)
    } yield ApiException.Unknown(body.leftMap(_.getMessage).merge, response.status.code)

}

object ApiClient {

  def create[F[_]: Async: Network]: Resource[F, ApiClient[F]] =
    for {
      client <- EmberClientBuilder.default[F].build
    } yield new ApiClient(client)

}
