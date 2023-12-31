package io.clickup.timelog

import java.time.{Instant, LocalDate}

import cats.effect.*
import fs2.Stream
import fs2.data.csv.lowlevel
import io.clickup.model.TaskId
import io.clickup.timelog.Timelog.Local
import weaver.*

import scala.concurrent.duration.*

object TimelogSuite extends SimpleIOSuite {

  test("Decode CSV line as Timelog.Local") {
    val input =
      """|2022-10-03,1:22:46,TEST-2327 - fix typo
        |2022-10-03,0:45:17,"TEST-401 - update basic image, fix security issues"
        |2022-10-04,1:39:56,TEST-2302 - add docker build""".stripMargin

    val expected = List(
      Timelog.Local(
        LocalDate.of(2022, 10, 3),
        1.hour + 22.minutes + 46.seconds,
        "TEST-2327 - fix typo",
        TaskId.fromString("TEST-2327").getOrElse(sys.error("invalid"))
      ),
      Timelog.Local(
        LocalDate.of(2022, 10, 3),
        45.minutes + 17.seconds,
        "TEST-401 - update basic image, fix security issues",
        TaskId.fromString("TEST-401").getOrElse(sys.error("invalid"))
      ),
      Timelog.Local(
        LocalDate.of(2022, 10, 4),
        1.hour + 39.minutes + 56.seconds,
        "TEST-2302 - add docker build",
        TaskId.fromString("TEST-2302").getOrElse(sys.error("invalid"))
      )
    )

    for {
      decoded <- Stream
        .emit(input)
        .covary[IO]
        .through(lowlevel.rows[IO, String]())
        .through(lowlevel.decode[IO, Timelog.Local])
        .compile
        .toList
    } yield expect.same(decoded, expected)
  }

  test("Decode CSV line as Timelog.LocalDetailed") {
    val input =
      """|2022-10-03,1:22:46,TEST-2327 - fix typo,2022-10-03T10:50:55Z,2022-10-03T12:13:41Z,Replace 'a' with 'an'
         |2022-10-03,0:45:17,"TEST-401 - update basic image, fix security issues",2022-10-03T12:19:00Z,2022-10-03T13:04:17Z,"Update, runtime image"
         |2022-10-04,1:39:56,TEST-2302 - add docker build,2022-10-04T21:50:30Z,2022-10-04T23:30:26Z""".stripMargin

    val expected = List(
      Timelog.LocalDetailed(
        LocalDate.of(2022, 10, 3),
        1.hour + 22.minutes + 46.seconds,
        Instant.parse("2022-10-03T10:50:55Z"),
        Instant.parse("2022-10-03T12:13:41Z"),
        "TEST-2327 - fix typo",
        TaskId.fromString("TEST-2327").getOrElse(sys.error("invalid")),
        "Replace 'a' with 'an'"
      ),
      Timelog.LocalDetailed(
        LocalDate.of(2022, 10, 3),
        45.minutes + 17.seconds,
        Instant.parse("2022-10-03T12:19:00Z"),
        Instant.parse("2022-10-03T13:04:17Z"),
        "TEST-401 - update basic image, fix security issues",
        TaskId.fromString("TEST-401").getOrElse(sys.error("invalid")),
        "Update, runtime image"
      ),
      Timelog.LocalDetailed(
        LocalDate.of(2022, 10, 4),
        1.hour + 39.minutes + 56.seconds,
        Instant.parse("2022-10-04T21:50:30Z"),
        Instant.parse("2022-10-04T23:30:26Z"),
        "TEST-2302 - add docker build",
        TaskId.fromString("TEST-2302").getOrElse(sys.error("invalid")),
        ""
      )
    )

    for {
      decoded <- Stream
        .emit(input)
        .covary[IO]
        .through(lowlevel.rows[IO, String]())
        .through(lowlevel.decode[IO, Timelog.LocalDetailed])
        .compile
        .toList
    } yield expect(decoded == expected)
  }

}
