package com.evolutiongaming.kafka.journal

import java.time.Instant
import java.util.UUID

import cats._
import cats.effect._
import cats.implicits._
import com.evolutiongaming.kafka.journal.EventsSerializer._
import com.evolutiongaming.kafka.journal.FoldWhileHelper._
import com.evolutiongaming.kafka.journal.stream.FoldWhile.FoldWhileOps
import com.evolutiongaming.kafka.journal.eventual.EventualJournal
import com.evolutiongaming.kafka.journal.stream.Stream
import com.evolutiongaming.kafka.journal.util.ClockHelper._
import com.evolutiongaming.kafka.journal.util.Par
import com.evolutiongaming.nel.Nel
import com.evolutiongaming.skafka.consumer.{ConsumerConfig, ConsumerRecords}
import com.evolutiongaming.skafka.producer.{Acks, ProducerConfig, ProducerRecord}
import com.evolutiongaming.skafka.{Bytes => _, _}

import scala.concurrent.duration._

trait Journal[F[_]] {

  def append(key: Key, events: Nel[Event], timestamp: Instant): F[PartitionOffset]

  def read(key: Key, from: SeqNr): Stream[F, Event]

  def pointer(key: Key): F[Option[SeqNr]]

  // TODO return Pointer and Test it
  def delete(key: Key, to: SeqNr, timestamp: Instant): F[Option[PartitionOffset]]
}

object Journal {

  def empty[F[_] : Applicative]: Journal[F] = new Journal[F] {

    def append(key: Key, events: Nel[Event], timestamp: Instant) = PartitionOffset.Empty.pure[F]

    def read(key: Key, from: SeqNr) = Stream.empty

    def pointer(key: Key) = none[SeqNr].pure[F]

    def delete(key: Key, to: SeqNr, timestamp: Instant) = none[PartitionOffset].pure[F]
  }

  
  def of[F[_] : Concurrent : ContextShift : Timer : Par : LogOf : KafkaConsumerOf : KafkaProducerOf](
    config: JournalConfig,
    origin: Option[Origin],
    eventualJournal: EventualJournal[F],
    metrics: Option[Metrics[F]]): Resource[F, Journal[F]] = {

    val consumer = Consumer.of[F](config.consumer)

    val headCache = {
      if (config.headCache) {
        HeadCache.of[F](config.consumer, eventualJournal)
      } else {
        Resource.pure[F, HeadCache[F]](HeadCache.empty[F])
      }
    }

    for {
      producer      <- Producer.of[F](config.producer)
      log           <- Resource.liftF(LogOf[F].apply(Journal.getClass))
      headCache     <- headCache
    } yield {
      implicit val log1 = log
      val journal = apply(
        origin,
        producer,
        consumer,
        eventualJournal,
        config.pollTimeout,
        headCache)
      val withLog = journal.withLog(log)
      metrics.fold(withLog) { metrics => withLog.withMetrics(metrics) }
    }
  }

  
  def apply[F[_] : Concurrent : ContextShift : Par : Clock : Log](
    origin: Option[Origin],
    producer: Producer[F],
    consumer: Resource[F, Consumer[F]],
    eventualJournal: EventualJournal[F],
    pollTimeout: FiniteDuration,
    headCache: HeadCache[F]): Journal[F] = {

    val withPollActions = WithPollActions[F](consumer, pollTimeout)
    val appendAction = AppendAction[F](producer)
    apply[F](origin, eventualJournal, withPollActions, appendAction, headCache)
  }


  def apply[F[_] : Concurrent : Log : Clock : Par](
    origin: Option[Origin],
    eventual: EventualJournal[F],
    withPollActions: WithPollActions[F],
    appendAction: AppendAction[F],
    headCache: HeadCache[F]): Journal[F] = {

    def readActions(key: Key, from: SeqNr) = {
      val marker = {
        for {
          id              <- Sync[F].delay { UUID.randomUUID().toString }
          timestamp       <- Clock[F].instant
          action           = Action.Mark(key, timestamp, origin, id)
          partitionOffset <- appendAction(action)
          _               <- Log[F].debug(s"$key mark, id: $id, offset $partitionOffset")
        } yield {
          Marker(id, partitionOffset)
        }
      }
      val pointers = eventual.pointers(key.topic)

      for {
        pointers <- Concurrent[F].start(pointers)
        marker   <- marker
        result   <- if (marker.offset == Offset.Min) {
          (JournalInfo.empty.some, FoldActions.empty[F]).pure[F]
        } else {
          for {
            result   <- headCache(key, partition = marker.partition, offset = Offset.Min max marker.offset - 1)
            pointers <- pointers.join
            offset    = pointers.values.get(marker.partition)
          } yield {
            val info = for {
              result <- result
            } yield {
              def info = result.deleteTo.fold[JournalInfo](JournalInfo.Empty)(JournalInfo.Deleted(_))

              result.seqNr.fold(info)(JournalInfo.NonEmpty(_, result.deleteTo))
            }
            val foldActions = FoldActions(key, from, marker, offset, withPollActions)
            (info, foldActions)
          }
        }
      } yield result
    }

    new Journal[F] {

      def append(key: Key, events: Nel[Event], timestamp: Instant) = {
        val action = Action.Append(key, timestamp, origin, events) // TODO measure
        appendAction(action)
      }

      def read(key: Key, from: SeqNr) = new Stream[F, Event] {

        def foldWhileM[L, R](l: L)(f: (L, Event) => F[Either[L, R]]) = {

          // TODO why do we need this?
          def replicatedSeqNr(from: SeqNr) = {
            val ss = (l, from.some, none[Offset])
            eventual.read(key, from).foldWhileM(ss) { case ((l, _, _), replicated) =>
              val event = replicated.event
              val offset = replicated.partitionOffset.offset
              val from = event.seqNr.next
              f(l, event).map {
                case Left(l)  => (l, from, offset.some).asLeft[(R, Option[SeqNr], Option[Offset])]
                case Right(r) => (r, from, offset.some).asRight[(L, Option[SeqNr], Option[Offset])]
              }
            }
          }

          def replicated(from: SeqNr) = {
            val events = eventual.read(key, from).map(_.event)
            events.foldWhileM(l)(f)
          }

          def onNonEmpty(deleteTo: Option[SeqNr], readActions: FoldActions[F]) = {

            def events(from: SeqNr, offset: Option[Offset], l: L) = {
              readActions(offset).foldWhileM(l) { (l, a) =>
                a match {
                  case _: Action.Delete => l.asLeft[R].pure[F]
                  case a: Action.Append =>
                    if (a.range.to < from) l.asLeft[R].pure[F]
                    else {
                      val events = EventsFromPayload(a.payload, a.payloadType)
                      events.foldWhileM(l) { case (l, event) =>
                        if (event.seqNr >= from) f(l, event) else l.asLeft[R].pure[F]
                      }
                    }
                }
              }
            }

            val fromFixed = deleteTo.fold(from) { deleteTo => from max deleteTo.next }

            for {
              abc                    <- replicatedSeqNr(fromFixed)
              (result, from, offset)  = abc match {
                case Left((l, from, offset))  => (l.asLeft[R], from, offset)
                case Right((r, from, offset)) => (r.asRight[L], from, offset)
              }
              _                      <- Log[F].debug(s"$key read from: $from, offset: $offset")
              result                 <- from match {
                case None       => result.pure[F]
                case Some(from) => result match {
                  case Left(l)  => events(from, offset, l)
                  case Right(r) => r.asRight[L].pure[F]
                }
              }
            } yield result
          }

          for {
            ab           <- readActions(key, from)
            (info, read)  = ab
            // TODO use range after eventualRecords
            // TODO prevent from reading calling consume twice!
            info         <- info match {
              case Some(info) => info.pure[F]
              case None       => read(None).fold(JournalInfo.empty) { (info, action) => info(action) }
            }
            _            <- Log[F].debug(s"$key read info: $info")
            result       <- info match {
              case JournalInfo.Empty                 => replicated(from)
              case JournalInfo.NonEmpty(_, deleteTo) => onNonEmpty(deleteTo, read)
              // TODO test this case
              case JournalInfo.Deleted(deleteTo)     => deleteTo.next match {
                case None       => l.asLeft[R].pure[F]
                case Some(next) => replicated(from max next)
              }
            }
          } yield result
        }
      }

      def pointer(key: Key) = {
        // TODO reimplement, we don't need to call `eventual.pointer` without using it's offset

        val from = SeqNr.Min // TODO remove

        def seqNrEventual = eventual.pointer(key).map(_.map(_.seqNr))

        for {
          ab                  <- readActions(key, from)
          (info, readActions)  = ab
          result              <- info match {
            case Some(info) => info match {
              case JournalInfo.Empty          => seqNrEventual
              case info: JournalInfo.NonEmpty => info.seqNr.some.pure[F]
              case _   : JournalInfo.Deleted  => seqNrEventual
            }

            case None =>
              val seqNr = readActions(None /*TODO provide offset from eventual.lastSeqNr*/).fold(Option.empty[SeqNr]) { (seqNr, action) =>
                action match {
                  case action: Action.Append => Some(action.range.to)
                  case action: Action.Delete => Some(action.to max seqNr)
                }
              }

              val pointer = eventual.pointer(key)

              for {
                ab <- Par[F].tupleN(seqNr, pointer)
              } yield {
                val (seqNr, pointer) = ab
                pointer.map(_.seqNr) max seqNr
              }
          }
        } yield result
      }

      def delete(key: Key, to: SeqNr, timestamp: Instant) = {
        for {
          seqNr  <- pointer(key)
          result <- seqNr match {
            case None        => none[PartitionOffset].pure[F]
            case Some(seqNr) =>

              // TODO not delete already deleted, do not accept deleteTo=2 when already deleteTo=3
              val deleteTo = seqNr min to
              val action = Action.Delete(key, timestamp, origin, deleteTo)
              appendAction(action).map(_.some)
          }
        } yield result
      }
    }
  }


  def apply[F[_] : FlatMap : Clock](journal: Journal[F], log: Log[F]): Journal[F] = new Journal[F] {

    def append(key: Key, events: Nel[Event], timestamp: Instant) = {
      for {
        rl     <- Latency { journal.append(key, events, timestamp) }
        (r, l)  = rl
        _      <- log.debug {
          val first = events.head.seqNr
          val last = events.last.seqNr
          val seqNr = if (first == last) s"seqNr: $first" else s"seqNrs: $first..$last"
          s"$key append in ${ l }ms, $seqNr, timestamp: $timestamp, result: $r"
        }
      } yield r
    }

    def read(key: Key, from: SeqNr) = new Stream[F, Event] {

      def foldWhileM[L, R](l: L)(f: (L, Event) => F[Either[L, R]]) = {
        for {
          rl     <- Latency { journal.read(key, from).foldWhileM(l)(f) }
          (r, l)  = rl
          _      <- log.debug(s"$key read in ${ l }ms, from: $from, result: $r")
        } yield r
      }
    }

    def pointer(key: Key) = {
      for {
        rl     <- Latency { journal.pointer(key) }
        (r, l)  = rl
        _      <- log.debug(s"$key lastSeqNr in ${ l }ms, result: $r")
      } yield r
    }

    def delete(key: Key, to: SeqNr, timestamp: Instant) = {
      for {
        rl     <- Latency { journal.delete(key, to, timestamp) }
        (r, l)  = rl
        _      <- log.debug(s"$key delete in ${ l }ms, to: $to, timestamp: $timestamp, r: $r")
      } yield r
    }
  }


  def apply[F[_] : Sync : Clock](journal: Journal[F], metrics: Metrics[F]): Journal[F] = {

    def latency[A](name: String, topic: Topic)(fa: F[A]): F[(A, Long)] = {
      Latency {
        fa.handleErrorWith { e =>
          for {
            _ <- metrics.failure(name, topic)
            a <- e.raiseError[F, A]
          } yield a
        }
      }
    }

    new Journal[F] {

      def append(key: Key, events: Nel[Event], timestamp: Instant) = {
        for {
          rl     <- latency("append", key.topic) { journal.append(key, events, timestamp) }
          (r, l)  = rl
          _      <- metrics.append(topic = key.topic, latency = l, events = events.size)
        } yield r
      }

      def read(key: Key, from: SeqNr) = {

        val stream = new Stream[F, Event] {
          def foldWhileM[L, R](l: L)(f: (L, Event) => F[Either[L, R]]) = {
            for {
              rl     <- Latency { journal.read(key, from).foldWhileM(l)(f) } // TODO around, capture stream as val?
              (r, l)  = rl
              _      <- metrics.read(topic = key.topic, latency = l)
            } yield r
          }
        }

        for {
          a <- stream
          _ <- Stream.lift(metrics.read(key.topic))
        } yield a
      }

      def pointer(key: Key) = {
        for {
          rl     <- latency("pointer", key.topic) { journal.pointer(key) }
          (r, l)  = rl
          _      <- metrics.pointer(key.topic, l)
        } yield r
      }

      def delete(key: Key, to: SeqNr, timestamp: Instant) = {
        for {
          rl     <- latency("delete", key.topic) { journal.delete(key, to, timestamp) }
          (r, l)  = rl
          _      <- metrics.delete(key.topic, l)
        } yield r
      }
    }
  }


  trait Metrics[F[_]] {

    def append(topic: Topic, latency: Long, events: Int): F[Unit]

    def read(topic: Topic, latency: Long): F[Unit]

    def read(topic: Topic): F[Unit]

    def pointer(topic: Topic, latency: Long): F[Unit]

    def delete(topic: Topic, latency: Long): F[Unit]

    def failure(name: String, topic: Topic): F[Unit]
  }

  object Metrics {

    def empty[F[_]](unit: F[Unit]): Metrics[F] = new Metrics[F] {

      def append(topic: Topic, latency: Long, events: Int) = unit

      def read(topic: Topic, latency: Long) = unit

      def read(topic: Topic) = unit

      def pointer(topic: Topic, latency: Long) = unit

      def delete(topic: Topic, latency: Long) = unit

      def failure(name: String, topic: Topic) = unit
    }

    def empty[F[_] : Applicative]: Metrics[F] = empty(().pure[F])
  }


  trait Producer[F[_]] {
    def send(record: ProducerRecord[Id, Bytes]): F[PartitionOffset]
  }

  object Producer {

    def of[F[_] : Sync : KafkaProducerOf](config: ProducerConfig): Resource[F, Producer[F]] = {

      val acks = config.acks match {
        case Acks.None => Acks.One
        case acks      => acks
      }

      val config1 = config.copy(
        acks = acks,
        idempotence = true,
        retries = config.retries max 10,
        common = config.common.copy(
          clientId = Some(config.common.clientId getOrElse "journal"),
          sendBufferBytes = config.common.sendBufferBytes max 1000000))

      for {
        kafkaProducer <- KafkaProducerOf[F].apply(config1)
      } yield {
        apply(kafkaProducer)
      }
    }

    def apply[F[_] : Sync](producer: KafkaProducer[F]): Producer[F] = {
      new Producer[F] {
        def send(record: ProducerRecord[Id, Bytes]) = {
          for {
            metadata  <- producer.send(record)
            partition  = metadata.topicPartition.partition
            offset    <- metadata.offset.fold {
              val error = new IllegalArgumentException("metadata.offset is missing, make sure ProducerConfig.acks set to One or All")
              error.raiseError[F, Offset]
            } {
              _.pure[F]
            }
          } yield {
            PartitionOffset(partition, offset)
          }
        }
      }
    }
  }


  trait Consumer[F[_]] {

    def assign(partitions: Nel[TopicPartition]): F[Unit]

    def seek(partition: TopicPartition, offset: Offset): F[Unit]

    def poll(timeout: FiniteDuration): F[ConsumerRecords[Id, Bytes]]
  }

  object Consumer {

    def of[F[_] : Sync : KafkaConsumerOf](config: ConsumerConfig): Resource[F, Consumer[F]] = {

      val config1 = config.copy(
        groupId = None,
        autoCommit = false)

      for {
        kafkaConsumer <- KafkaConsumerOf[F].apply[Id, Bytes](config1)
      } yield {
        apply[F](kafkaConsumer)
      }
    }

    def apply[F[_]](consumer: KafkaConsumer[F, Id, Bytes]): Consumer[F] = {
      new Consumer[F] {

        def assign(partitions: Nel[TopicPartition]) = {
          consumer.assign(partitions)
        }

        def seek(partition: TopicPartition, offset: Offset) = {
          consumer.seek(partition, offset)
        }

        def poll(timeout: FiniteDuration) = {
          consumer.poll(timeout)
        }
      }
    }
  }


  implicit class JournalOps[F[_]](val self: Journal[F]) extends AnyVal {

    def withLog(log: Log[F])(implicit sync: Sync[F], clock: Clock[F]): Journal[F] =  {
      Journal(self, log)
    }

    
    def withMetrics(metrics: Metrics[F])(implicit sync: Sync[F], clock: Clock[F]): Journal[F] =  {
      Journal(self, metrics)
    }


    def mapK[G[_]](to: F ~> G, from: G ~> F): Journal[G] = new Journal[G] {

      def append(key: Key, events: Nel[Event], timestamp: Instant) = {
        to(self.append(key, events, timestamp))
      }

      def read(key: Key, from1: SeqNr) = {
        self.read(key, from1).mapK(to, from)
      }

      def pointer(key: Key) = {
        to(self.pointer(key))
      }

      def delete(key: Key, to1: SeqNr, timestamp: Instant) = {
        to(self.delete(key, to1, timestamp))
      }
    }
  }
}