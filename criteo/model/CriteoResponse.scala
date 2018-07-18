package services.bidding.pb.clients.criteo.model

import io.circe.derivation.deriveDecoder


case class CriteoResponse(slots: Seq[Slot])

object CriteoResponse {
  implicit val decoder = deriveDecoder[CriteoResponse]
}