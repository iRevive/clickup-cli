package io.clickup

trait Runner[F[_]] {
  def run(choice: Choice): F[Unit]
}

object Runner {

  def create[F[_]](cli: Cli[F]): Runner[F] = {
    case Choice.Task(Choice.TaskOp.Summary(taskIds, detailed)) =>
      cli.taskSummary(taskIds, detailed)

    case Choice.Timelog(Choice.TimelogOp.List(range)) =>
      cli.listTimeEntries(range)

    case Choice.Timelog(Choice.TimelogOp.Compare(range, delta, local, skip, detailed)) =>
      cli.compareTimelog(range, delta, local, skip, detailed)

    case Choice.Timelog(Choice.TimelogOp.Add(taskId, date, duration, description)) =>
      cli.addTimeEntry(taskId, date, duration, description)

    case Choice.Timelog(Choice.TimelogOp.Summary(range, detailed)) =>
      cli.summary(range, detailed)

    case Choice.Configure =>
      cli.configure
  }

}
