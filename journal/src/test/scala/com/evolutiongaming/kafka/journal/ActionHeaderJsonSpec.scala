package com.evolutiongaming.kafka.journal

import com.evolutiongaming.kafka.journal.SeqNr.ops._
import org.scalatest.{FunSuite, Matchers}
import play.api.libs.json._

class ActionHeaderJsonSpec extends FunSuite with Matchers {

  for {
    origin <- List(Some(Origin("origin")), None)
  } {
    val originStr = origin.fold("None")(_.toString)
    for {
      payloadType <- List(PayloadType.Binary, PayloadType.Json)
    } {
      test(s"Append format, origin: $origin, payloadType: $payloadType") {
        val range = SeqRange(1, 5)
        val header = ActionHeader.Append(range, origin, payloadType)
        verify(header, s"Append-$originStr-$payloadType")
      }
    }

    test(s"Delete format, origin: $origin") {
      val seqNr = 3.toSeqNr
      val header = ActionHeader.Delete(seqNr, origin)
      verify(header, s"Delete-$originStr")
    }

    test(s"Mark format, origin: $origin, ") {
      val header = ActionHeader.Mark("id", origin)
      verify(header, s"Mark-$originStr")
    }
  }

  private def verify(value: ActionHeader, name: String) = {

    def verify(json: JsValue) = {
      val actual = json.as[ActionHeader]
      actual shouldEqual value
    }

    verify(Json.toJson(value))
    verify(Json.parse(BytesOf(getClass, s"$name.json")))
  }
}
