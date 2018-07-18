package services.bidding.pb.clients.criteo.model

import io.circe.derivation._


case class Product(title: String,
                   description: String,
                   price: String,
                   `click_url`: String,
                   `call_to_action`: String,
                   image: Image)

object Product {
  implicit val decoder = deriveDecoder[Product]
  implicit val encoder = deriveEncoder[Product]
}