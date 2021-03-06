package com.evolutiongaming.kafka.journal.eventual.cassandra

import com.evolutiongaming.kafka.journal.{PartitionOffset, SeqNr}


// TODO rename to topic metadata
// TODO add Origin
final case class Metadata(
  partitionOffset: PartitionOffset,
  segmentSize: Int,
  seqNr: SeqNr,
  deleteTo: Option[SeqNr])