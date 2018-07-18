package services.bidding.pb.clients.criteo.model

import io.circe.derivation.deriveEncoder


case class CriteoUser(deviceid: String,
                      deviceidtype: String,
                      deviceos: String,
                      hashedemail: Option[String] = None,
                      lmt: Option[String] = None)

object CriteoUser {
  implicit val encoder = deriveEncoder[CriteoUser]
}