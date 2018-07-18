package services.bidding.pb.clients.criteo.model

import io.circe.derivation._


case class Native(product: Product,
                  advertiser: Advertiser,
                  privacy: Privacy,
                  `impression_pixels`: ImpressionPixel)

object Native {
  implicit val decoder = deriveDecoder[Native]
  implicit val encoder = deriveEncoder[Native]
}