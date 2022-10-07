package io.clickup.util

import cats.Monad
import cats.effect.std.Console
import cats.syntax.flatMap.*
import cats.syntax.functor.*

object Prompt {

  trait Read[A] {
    def read(input: String): Either[String, A]
  }

  def readWithRetries[F[_]: Monad: Console, A](name: String)(using read: Read[A]): F[A] = {

    def loop(): F[A] =
      for {
        _     <- Console[F].print(name)
        input <- Console[F].readLine
        result <- read.read(Option(input).getOrElse("")) match {
          case Right(value) => Monad[F].pure(value)
          case Left(reason) => Console[F].println(reason) >> loop()
        }
      } yield result

    loop()
  }

}
