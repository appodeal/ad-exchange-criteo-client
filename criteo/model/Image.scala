package services.bidding.pb.clients.criteo.model

import io.circe.derivation._


case class Image(url: String, height: Int, width: Int)

object Image {
  implicit val decoder = deriveDecoder[Image]
  implicit val encoder = deriveEncoder[Image]
}