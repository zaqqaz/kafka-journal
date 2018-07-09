package com.evolutiongaming.kafka.journal.eventual.cassandra

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import com.datastax.driver.core.policies.LoggingRetryPolicy
import com.datastax.driver.core.{Metadata => _, _}
import com.evolutiongaming.cassandra.NextHostRetryPolicy
import com.evolutiongaming.kafka.journal.Alias._
import com.evolutiongaming.kafka.journal.FutureHelper._
import com.evolutiongaming.kafka.journal.SeqRange
import com.evolutiongaming.kafka.journal.StreamHelper._
import com.evolutiongaming.kafka.journal.eventual._
import com.evolutiongaming.safeakka.actor.ActorLog
import com.evolutiongaming.skafka.Topic

import scala.collection.immutable.{Iterable, Seq}
import scala.concurrent.{ExecutionContext, Future}


// TODO create collection that is optimised for ordered sequence and seqNr
object EventualCassandra {

  def apply(
    session: Session,
    schemaConfig: SchemaConfig,
    config: EventualCassandraConfig,
    log: ActorLog)(implicit system: ActorSystem, ec: ExecutionContext): EventualJournal = {

    implicit val materializer = ActorMaterializer()

    val retries = 3

    val statementConfig = StatementConfig(
      idempotent = true, /*TODO remove from here*/
      consistencyLevel = ConsistencyLevel.ONE,
      retryPolicy = new LoggingRetryPolicy(NextHostRetryPolicy(retries)))


    val statements = for {
      tables <- CreateSchema(schemaConfig, session)
      prepareAndExecute = PrepareAndExecute(session, statementConfig)
      statements <- Statements(tables, prepareAndExecute)
    } yield {
      statements
    }

    def metadata(id: Id, statements: Statements) = {
      val selectMetadata = statements.selectMetadata
      for {
        metadata <- selectMetadata(id)
      } yield {
        // TODO what to do if it is empty?

        if (metadata.isEmpty) println(s"$id metadata is empty")

        metadata
      }
    }


    new EventualJournal {

      def topicPointers(topic: Topic): Future[TopicPointers] = {
        for {
          statements <- statements
          topicPointers <- statements.selectTopicPointer(topic)
        } yield {
          topicPointers
        }
      }

      // TODO test use case when cassandra is not up to last Action.Delete

      def read(id: Id, range: SeqRange): Future[Seq[EventualRecord]] = {

        def list(statement: JournalStatement.SelectRecords.Type, metadata: Metadata) = {

          val segmentSize = metadata.segmentSize

          def segmentOf(seqNr: SeqNr) = Segment(seqNr, segmentSize)

          def list(range: SeqRange) = {
            val segment = segmentOf(range.from)
            val source = Source.unfoldWhile(segment) { segment =>
              println(s"$id Source.unfoldWhile range: $range, segment: $segment")
              for {
                records <- statement(id, segment, range)
              } yield {
                if (records.isEmpty) {
                  (segment, false, Iterable.empty)
                } else {
                  val seqNrNext = records.last.seqNr.next
                  val segmentNext = segmentOf(seqNrNext)
                  val continue = (range contains seqNrNext) && segment != segmentNext
                  (segmentNext, continue, records)
                }
              }
            }

            source.runWith(Sink.seq)
          }

          val deletedTo = metadata.deletedTo

          println(s"$id EventualCassandra.list deletedTo: $deletedTo")

          if (deletedTo >= range.to) {
            Future.seq
          } else {
            val from = range.from max (deletedTo + 1)
            val range2 = range.copy(from = from)
            list(range2)
          }
        }

        for {
          statements <- statements
          metadata <- metadata(id, statements)
          result <- metadata match {
            case Some(metadata) => list(statements.selectRecords, metadata)
            case None           => Future.seq
          }
        } yield {
          result
        }
      }

      // TODO remove range argument
      def lastSeqNr(id: Id, from: SeqNr) = {
        // TODO use range.to
        /*def lastSeqNr(statement: JournalStatement.SelectLastRecord.Type, segmentSize: Int) = {

          // TODO create lastSeqNr statement
          // TODO remove duplication
          def recur(from: SeqNr, prev: Option[(Segment, SeqNr)]): Future[Option[SeqNr]] = {
            // println(s"EventualCassandra.last.recur id: $id, segment: $segment")

            def record = prev.map { case (_, record) => record }

            // TODO use deletedTo
            val segment = Segment(from, segmentSize)
            if (prev.exists { case (segmentPrev, _) => segmentPrev == segment }) {
              Future.successful(record)
            } else {
              for {
                result <- statement(id, segment, from)
                result <- result match {
                  case None         => Future.successful(record)
                  case Some(result) =>
                    val seqNr = (segment, result.seqNr)
                    recur(from.next, Some(seqNr))
                }
              } yield {
                result
              }
            }
          }

          recur(range.from, None)
        }*/


        def lastSeqNr(statement: JournalStatement.SelectLastRecord.Type, metadata: Metadata) = {
          LastSeqNr(id, from, statement, metadata)
        }

        for {
          statements <- statements
          metadata <- metadata(id, statements)
          seqNr <- metadata match {
            case Some(metadata) => lastSeqNr(statements.selectLastRecord, metadata)
            case None           => Future.successful(SeqNr.Min) // TODO cache value
          }
        } yield {
          Some(seqNr) // TODO simplify api
        }
      }
    }
  }


  final case class Statements(
    selectLastRecord: JournalStatement.SelectLastRecord.Type,
    selectRecords: JournalStatement.SelectRecords.Type,
    selectMetadata: MetadataStatement.Select.Type,
    selectSegmentSize: MetadataStatement.SelectSegmentSize.Type,
    updatePointer: PointerStatement.Update.Type,
    selectPointer: PointerStatement.Select.Type,
    selectTopicPointer: PointerStatement.SelectTopicPointers.Type)

  object Statements {

    def apply(tables: Tables, prepareAndExecute: PrepareAndExecute)(implicit ec: ExecutionContext): Future[Statements] = {

      val selectLastRecord = JournalStatement.SelectLastRecord(tables.journal, prepareAndExecute)
      val listRecords = JournalStatement.SelectRecords(tables.journal, prepareAndExecute)
      val selectMetadata = MetadataStatement.Select(tables.metadata, prepareAndExecute)
      val selectSegmentSize = MetadataStatement.SelectSegmentSize(tables.metadata, prepareAndExecute)
      val updatePointer = PointerStatement.Update(tables.pointer, prepareAndExecute)
      val selectPointer = PointerStatement.Select(tables.pointer, prepareAndExecute)
      val selectTopicPointers = PointerStatement.SelectTopicPointers(tables.pointer, prepareAndExecute)

      for {
        selectLastRecord <- selectLastRecord
        listRecords <- listRecords
        selectMetadata <- selectMetadata
        selectSegmentSize <- selectSegmentSize
        updatePointer <- updatePointer
        selectPointer <- selectPointer
        selectTopicPointers <- selectTopicPointers
      } yield {
        Statements(
          selectLastRecord,
          listRecords,
          selectMetadata,
          selectSegmentSize,
          updatePointer,
          selectPointer,
          selectTopicPointers)
      }
    }
  }
}

