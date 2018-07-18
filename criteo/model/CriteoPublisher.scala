package services.bidding.pb.clients.criteo.model

import io.circe.derivation.deriveEncoder


case class CriteoPublisher(bundleid: String, publisherid: Option[String])

object CriteoPublisher {
  implicit val encoder = deriveEncoder[CriteoPublisher]
}