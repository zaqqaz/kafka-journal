package com.evolutiongaming.kafka.journal.eventual.cassandra


import java.time.Instant

import cats.FlatMap
import cats.implicits._
import com.evolutiongaming.kafka.journal._
import com.evolutiongaming.kafka.journal.eventual.cassandra.CassandraHelper._
import com.evolutiongaming.scassandra.TableName
import com.evolutiongaming.scassandra.syntax._


object MetadataStatement {

  // TODO make use of partition and offset
  def createTable(name: TableName): String = {
    s"""
       |CREATE TABLE IF NOT EXISTS ${ name.toCql } (
       |id text,
       |topic text,
       |partition int,
       |offset bigint,
       |segment_size int,
       |seq_nr bigint,
       |delete_to bigint,
       |created timestamp,
       |updated timestamp,
       |origin text,
       |properties map<text,text>,
       |metadata text,
       |PRIMARY KEY ((topic), id))
       |""".stripMargin
  }


  type Insert[F[_]] = (Key, Instant, Metadata, Option[Origin]) => F[Unit]

  object Insert {

    def of[F[_]: FlatMap : CassandraSession](name: TableName): F[Insert[F]] = {

      val query =
        s"""
           |INSERT INTO ${ name.toCql } (id, topic, partition, offset, segment_size, seq_nr, delete_to, created, updated, origin, properties)
           |VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
           |""".stripMargin

      for {
        prepared <- query.prepare
      } yield {
        (key: Key, timestamp: Instant, metadata: Metadata, origin: Option[Origin]) =>
          val bound = prepared
            .bind()
            .encode(key)
            .encode(metadata.partitionOffset)
            .encode("segment_size", metadata.segmentSize)
            .encode(metadata.seqNr)
            .encodeSome("delete_to", metadata.deleteTo)
            .encode("created", timestamp)
            .encode("updated", timestamp)
            .encodeSome(origin)
          bound.execute.void
      }
    }
  }


  type Select[F[_]] = Key => F[Option[Metadata]]

  object Select {

    def of[F[_]: FlatMap : CassandraSession](name: TableName): F[Select[F]] = {
      val query =
        s"""
           |SELECT partition, offset, segment_size, seq_nr, delete_to FROM ${ name.toCql }
           |WHERE id = ?
           |AND topic = ?
           |""".stripMargin

      for {
        prepared <- query.prepare
      } yield {
        key: Key =>
          val bound = prepared
            .bind()
            .encode(key)
          for {
            result <- bound.execute
          } yield for {
            row <- result.head
          } yield {
            Metadata(
              partitionOffset = row.decode[PartitionOffset],
              segmentSize = row.decode[Int]("segment_size"),
              seqNr = row.decode[SeqNr],
              deleteTo = row.decode[Option[SeqNr]]("delete_to"))
          }
      }
    }
  }


  type Update[F[_]] = (Key, PartitionOffset, Instant, SeqNr, SeqNr) => F[Unit]

  // TODO add classes for common operations
  object Update {

    def of[F[_] : FlatMap : CassandraSession](name: TableName): F[Update[F]] = {
      val query =
        s"""
           |UPDATE ${ name.toCql }
           |SET partition = ?, offset = ?, seq_nr = ?, delete_to = ?, updated = ?
           |WHERE id = ?
           |AND topic = ?
           |""".stripMargin

      for {
        prepared <- query.prepare
      } yield {
        (key: Key, partitionOffset: PartitionOffset, timestamp: Instant, seqNr: SeqNr, deleteTo: SeqNr) =>
          val bound = prepared
            .bind()
            .encode(key)
            .encode(partitionOffset)
            .encode(seqNr)
            .encode("delete_to", deleteTo)
            .encode("updated", timestamp)

          bound.execute.void
      }
    }
  }


  type UpdateSeqNr[F[_]] = (Key, PartitionOffset, Instant, SeqNr) => F[Unit]

  // TODO TEST statement
  // TODO add classes for common operations
  object UpdateSeqNr {

    def of[F[_] : FlatMap : CassandraSession](name: TableName): F[UpdateSeqNr[F]] = {
      val query =
        s"""
           |UPDATE ${ name.toCql }
           |SET partition = ?, offset = ?, seq_nr = ?, updated = ?
           |WHERE id = ?
           |AND topic = ?
           |""".stripMargin

      for {
        prepared <- query.prepare
      } yield {
        (key: Key, partitionOffset: PartitionOffset, timestamp: Instant, seqNr: SeqNr) =>
          val bound = prepared
            .bind()
            .encode(key)
            .encode(partitionOffset)
            .encode(seqNr)
            .encode("updated", timestamp)

          bound.execute.void
      }
    }
  }


  type UpdateDeleteTo[F[_]] = (Key, PartitionOffset, Instant, SeqNr) => F[Unit]

  object UpdateDeleteTo {

    def of[F[_] : FlatMap : CassandraSession](name: TableName): F[UpdateDeleteTo[F]] = {
      val query =
        s"""
           |UPDATE ${ name.toCql }
           |SET partition = ?, offset = ?, delete_to = ?, updated = ?
           |WHERE id = ?
           |AND topic = ?
           |""".stripMargin

      for {
        prepared <- query.prepare
      } yield {
        (key: Key, partitionOffset: PartitionOffset, timestamp: Instant, deleteTo: SeqNr) =>
          val bound = prepared
            .bind()
            .encode(key)
            .encode(partitionOffset)
            .encode("delete_to", deleteTo)
            .encode("updated", timestamp)

          bound.execute.void
      }
    }
  }
}
