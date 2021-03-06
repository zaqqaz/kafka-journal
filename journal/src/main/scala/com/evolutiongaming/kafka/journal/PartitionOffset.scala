package com.evolutiongaming.kafka.journal

import com.datastax.driver.core.{GettableByNameData, SettableData}
import com.evolutiongaming.scassandra.syntax._
import com.evolutiongaming.scassandra.{DecodeRow, EncodeRow}
import com.evolutiongaming.skafka.consumer.ConsumerRecord
import com.evolutiongaming.skafka.{Offset, Partition}

final case class PartitionOffset(
  partition: Partition = Partition.Min,
  offset: Offset = Offset.Min) {

  override def toString = s"$partition:$offset"
}

object PartitionOffset {

  val Empty: PartitionOffset = PartitionOffset()

  implicit val EncodeImpl: EncodeRow[PartitionOffset] = new EncodeRow[PartitionOffset] {

    def apply[B <: SettableData[B]](data: B, value: PartitionOffset) = {
      data
        .encode("partition", value.partition)
        .encode("offset", value.offset)
    }
  }

  implicit val DecodeImpl: DecodeRow[PartitionOffset] = new DecodeRow[PartitionOffset] {

    def apply(data: GettableByNameData) = {
      PartitionOffset(
        partition = data.decode[Partition]("partition"),
        offset = data.decode[Offset]("offset"))
    }
  }

  def apply(record: ConsumerRecord[_, _]): PartitionOffset = {
    PartitionOffset(
      partition = record.topicPartition.partition,
      offset = record.offset)
  }
}