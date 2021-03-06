package akka.persistence.kafka.journal

import akka.actor.ActorSystem
import akka.persistence.PersistentRepr
import com.evolutiongaming.kafka.journal.FromBytes.Implicits._
import com.evolutiongaming.kafka.journal.ToBytes.Implicits._
import com.evolutiongaming.kafka.journal._
import com.evolutiongaming.serialization.{SerializedMsgConverter, SerializedMsgExt}
import play.api.libs.json.{JsString, JsValue, Json}

trait EventSerializer {
  def toEvent(persistentRepr: PersistentRepr): Event
  def toPersistentRepr(persistenceId: PersistenceId, event: Event): PersistentRepr
}

object EventSerializer {

  def apply(system: ActorSystem): EventSerializer = {
    apply(SerializedMsgExt(system))
  }

  def apply(serialisation: SerializedMsgConverter): EventSerializer = new EventSerializer {

    def toEvent(persistentRepr: PersistentRepr) = {
      val (anyRef: AnyRef, tags) = PayloadAndTags(persistentRepr.payload)

      def binary(payload: AnyRef) = {
        val serialized = serialisation.toMsg(payload)
        val persistent = PersistentBinary(serialized, persistentRepr)
        val bytes = persistent.toBytes
        Payload.Binary(bytes)
      }

      def json(payload: JsValue, payloadType: Option[PayloadType.TextOrJson] = None) = {
        val persistent = PersistentJson(
          manifest = persistentRepr.manifest,
          writerUuid = persistentRepr.writerUuid,
          payloadType = payloadType,
          payload = payload)
        val json = Json.toJson(persistent)
        Payload.Json(json)
      }

      val payload = anyRef match {
        case payload: JsValue => json(payload)
        case payload: String  => json(JsString(payload), Some(PayloadType.Text))
        case payload          => binary(payload)
      }
      val seqNr = SeqNr(persistentRepr.sequenceNr)
      Event(seqNr, tags, Some(payload))
    }

    def toPersistentRepr(persistenceId: PersistenceId, event: Event) = {
      val payload = event.payload getOrElse sys.error(s"Event.payload is not defined, persistenceId: $persistenceId, event: $event")

      def binary(payload: Bytes) = {
        val persistent = payload.fromBytes[PersistentBinary]
        val anyRef = serialisation.fromMsg(persistent.payload).get
        PersistentRepr(
          payload = anyRef,
          sequenceNr = event.seqNr.value,
          persistenceId = persistenceId,
          manifest = persistent.manifest,
          writerUuid = persistent.writerUuid)
      }

      def json(payload: JsValue) = {
        val persistent = payload.as[PersistentJson]
        val payloadType = persistent.payloadType getOrElse PayloadType.Json
        val anyRef: AnyRef = payloadType match {
          case PayloadType.Text => persistent.payload.as[String]
          case PayloadType.Json => persistent.payload
        }
        PersistentRepr(
          payload = anyRef,
          sequenceNr = event.seqNr.value,
          persistenceId = persistenceId,
          manifest = persistent.manifest,
          writerUuid = persistent.writerUuid)
      }

      payload match {
        case p: Payload.Binary => binary(p.value)
        case _: Payload.Text   => sys.error(s"Payload.Text is not supported, persistenceId: $persistenceId, event: $event")
        case p: Payload.Json   => json(p.value)
      }
    }
  }
}
