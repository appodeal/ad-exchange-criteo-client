package services.bidding.pb.clients.criteo.model

import io.circe.derivation._


case class Slot(impid: String,
                cpm: Double,
                displayurl: Option[String],
                zoneid: Int,
                width: Option[Int],
                height: Option[Int],
                native: Option[Native])

object Slot {
  implicit val decoder = deriveDecoder[Slot]
  implicit val encoder = deriveEncoder[Slot]
}