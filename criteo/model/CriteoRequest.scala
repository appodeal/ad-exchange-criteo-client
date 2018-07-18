package services.bidding.pb.clients.criteo.model

import io.circe.derivation.deriveEncoder


case class CriteoRequest(publisher: CriteoPublisher,
                         user: CriteoUser,
                         slots: Seq[SlotRequest])

object CriteoRequest {
  implicit val encoder = deriveEncoder[CriteoRequest]
}