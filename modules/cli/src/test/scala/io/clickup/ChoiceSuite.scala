package io.clickup

import java.time.{Instant, LocalDate}

import cats.Show
import cats.effect.*
import com.monovore.decline.Command
import io.clickup.model.TaskId
import io.clickup.model.TimeRange
import weaver.*
import weaver.scalacheck.Checkers
import org.scalacheck.Arbitrary
import org.scalacheck.Gen

import scala.concurrent.duration.*

// Suites must be "objects" for them to be picked by the framework
object ChoiceSuite extends SimpleIOSuite with Checkers {

  private val command = Command("clickup-cli", "", false)(Choice.opts)

  pureTest("timelog add") {
    val taskId: TaskId = TaskId.fromString("DEV-123").getOrElse(sys.error("invalid id"))
    val now            = Instant.now
    val duration       = "0:00:05"
    val description    = "description with spaces"

    val expected = Choice.Timelog(Choice.TimelogOp.Add(taskId, now, 5.seconds, description))

    val result = command.parse(
      Seq(
        "timelog",
        "add",
        "--task-id",
        "DEV-123",
        "--date",
        now.toString,
        "--duration",
        duration,
        "--description",
        description
      )
    )

    expect.same(result, Right(expected))
  }

  test("timelog summary") {
    forall { (range: TimeRange, detailed: Boolean) =>
      val rangeArgs = range match {
        case TimeRange.Quick(shortcut)    => Seq("--range", shortcut.value)
        case TimeRange.Custom(start, end) => Seq("--start", start.toString, "--end", end.toString)
      }

      val detailedArg = if (detailed) Seq("--detailed") else Nil
      val expected    = Choice.Timelog(Choice.TimelogOp.Summary(range, detailed))
      val args        = Seq("timelog", "summary") ++ rangeArgs ++ detailedArg

      expect.same(command.parse(args), Right(expected))
    }
  }

  private given Arbitrary[TimeRange] = {
    val custom =
      for {
        year   <- Gen.chooseNum(2020, 2040)
        month  <- Gen.chooseNum(1, 12)
        day    <- Gen.chooseNum(1, 25)
        start  <- Gen.const(LocalDate.of(year, month, day))
        offset <- Gen.chooseNum(1, 40)
      } yield TimeRange.Custom(start, start.plusDays(offset))

    val quick =
      for {
        shortcut <- Gen.oneOf(TimeRange.Shortcut.values.toSeq)
      } yield TimeRange.Quick(shortcut)

    Arbitrary(Gen.oneOf(custom, quick))
  }

  private given Show[TimeRange] = Show.fromToString

}
