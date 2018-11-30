package com.evolutiongaming.kafka.journal

import cats.Applicative
import cats.effect.Concurrent
import com.evolutiongaming.safeakka.actor.ActorLog

trait Log[F[_]] { self =>

  def debug(msg: => String): F[Unit]

  def info(msg: => String): F[Unit]

  def warn(msg: => String): F[Unit]

  def error(msg: => String, cause: Throwable): F[Unit]


  def prefixed(prefix: String): Log[F] = new Log[F] {

    def debug(msg: => String) = self.debug(s"$prefix $msg")

    def info(msg: => String) = self.info(s"$prefix $msg")

    def warn(msg: => String) = self.warn(s"$prefix $msg")

    def error(msg: => String, cause: Throwable) = self.error(s"$prefix $msg", cause)
  }
}

object Log {

  def apply[F[_]](implicit F: Log[F]): Log[F] = F

  def empty[F[_]](unit: F[Unit]): Log[F] = new Log[F] {

    def debug(msg: => String) = unit

    def info(msg: => String) = unit

    def warn(msg: => String) = unit

    def error(msg: => String, cause: Throwable) = unit
  }


  def empty[F[_] : Applicative]: Log[F] = empty(Applicative[F].unit)


  def apply[F[_] : IO](log: ActorLog): Log[F] = new Log[F] {

    def debug(msg: => String) = IO[F].effect {
      log.debug(msg)
    }

    def info(msg: => String) = IO[F].effect {
      log.info(msg)
    }

    def warn(msg: => String) = IO[F].effect {
      log.warn(msg)
    }

    def error(msg: => String, cause: Throwable) = IO[F].effect {
      log.error(msg, cause)
    }
  }

  // TODO rename
  def apply1[F[_] : Concurrent](log: ActorLog): Log[F] = new Log[F] {

    def debug(msg: => String) = {
      Concurrent[F].delay {
        log.debug(msg)
      }
    }

    def info(msg: => String) = {
      Concurrent[F].delay {
        log.info(msg)
      }
    }

    def warn(msg: => String) = {
      Concurrent[F].delay {
        log.warn(msg)
      }
    }

    def error(msg: => String, cause: Throwable) = {
      Concurrent[F].delay {
        log.error(msg)
      }
    }
  }
}