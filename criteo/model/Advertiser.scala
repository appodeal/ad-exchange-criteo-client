package services.bidding.pb.clients.criteo.model

import io.circe.derivation._


case class Advertiser(description: String,
                      domain: String,
                      logo: Image,
                      `logo_click_url`: String,
                      `legal_text`: String)

object Advertiser {
  implicit val decoder = deriveDecoder[Advertiser]
  implicit val encoder = deriveEncoder[Advertiser]
}