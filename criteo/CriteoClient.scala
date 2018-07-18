package services.bidding.pb.clients.criteo

import java.util.UUID

import cats.syntax.either._
import cats.syntax.option._
import com.appodealx.exchange.common.models.auction.{AdDesc, CanBeImpSubordinate, DefaultAdDescInstances}
import com.appodealx.exchange.common.models.{BannerT, ImpT, Platform}
import com.typesafe.config.ConfigFactory
import io.circe.syntax._
import io.circe.{Printer, parser}
import models.Ad
import models.auction.NoBidReason.{NoFill, ParsingError, RequestException}
import models.auction.{AdCall, AdRequest, DisplayManagerInfo, NoBidReason, ReprBuilder, SoftBid}
import monix.eval.Task
import play.api.http.Status
import play.api.libs.ws.{WSClient, WSResponse}
import play.api.{Configuration, Logger}
import play.mvc.Http.HeaderNames
import play.twirl.api.Html
import scalacache.redis.RedisCache
import services.bidding.pb.ParallelBiddingClient
import services.bidding.pb.clients.criteo.model._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration
import scala.reflect._


object CriteoClient {
  val SupportedOs = Set(Platform.iOS.prettyValue, Platform.Android.prettyValue)
}

class CriteoClient(val ws: WSClient,
                   val configuration: Configuration,
                   val cache: RedisCache[Either[NoBidReason, CriteoResponse]],
                   val impIdGen: CriteoImpIdGenerator)
                  (implicit executionContext: ExecutionContext)

  extends ParallelBiddingClient
    with DefaultAdDescInstances {

  import scalacache.Monix.modes._


  private val criteoProfileId = configuration.get[String]("settings.pb.criteo.profileid")
  private val criteoEndpoint = configuration.get[String]("settings.pb.criteo.endpoint")
  private val ttl = Duration(configuration.get[String]("redis.criteo.ttl"))
  private val customPrinter = Printer.noSpaces.copy(dropNullValues = true)
  private val zonesConfig = Configuration(ConfigFactory.load("criteo_zones.conf").withOnlyPath("banner"))
  private val zones: Map[String, Int] = zonesConfig.entrySet.map {
    case (key, value) => key -> value.render().toInt
  }.toMap

  Logger.info(s"Criteo adNetwork client initialized.")

  override def announce[A <: ImpT](info: Vector[DisplayManagerInfo], request: AdRequest[A])
                                  (implicit buildRtbRepr: ReprBuilder[A],
                                   sub: CanBeImpSubordinate[A],
                                   desc: AdDesc[A]): Task[Vector[AdCall]] = {

    val infoByUuid = info.map(impIdGen.generate -> _).toMap

    import CriteoClient._

    def parseRes(response: WSResponse) = {
      parser.parse(response.body).leftMap(_.message)
        .flatMap(_.as[CriteoResponse].leftMap(_.message))
        .leftMap[NoBidReason](ParsingError)
    }

    def doRequest(): Task[Either[NoBidReason, CriteoResponse]] = {
      import HeaderNames._

      lazy val app = request.rtbApp.getOrElse(throw new NoSuchElementException("app not found"))
      val device = request.rtbDevice
      val os = request.rtbDevice.os.filter(SupportedOs)
      val isNative = desc.name == NativeAdDesc.name

      lazy val zoneId: Int = {
        val size = desc.size(request.ad)

        Logger.debug(s"AdDesc: $desc")
        Logger.debug(s"isInterstitial: ${request.interstitial}")
        Logger.debug(s"Size: $size")

        desc.name match {
          //          case NativeAdDesc.name => 1230606 //TODO: not implimented yet

          case BannerAdDesc.name if request.interstitial && size == (Some(320), Some(480)) => zones("banner.interstitial.portrait.phone")
          case BannerAdDesc.name if request.interstitial && size == (Some(1024), Some(768)) => zones("banner.interstitial.portrait.tablet")
          case BannerAdDesc.name if request.interstitial && size == (Some(480), Some(320)) => zones("banner.interstitial.landscape.phone")
          case BannerAdDesc.name if request.interstitial && size == (Some(768), Some(1028)) => zones("banner.interstitial.landscape.tablet")

          case BannerAdDesc.name if size == (Some(320), Some(50)) => zones("banner.phone")
          case BannerAdDesc.name if size == (Some(728), Some(90)) => zones("banner.tablet")

          case _ => throw new NoSuchElementException("zoneid for this ad type not found")
        }
      }

      lazy val criteoRequest = {

        val bundleId1 = app.bundle
        val bundleId2 = app.ext.flatMap(_.hcursor.get[String]("packagename").toOption)

        val publisher = CriteoPublisher(
          bundleid = bundleId1.getOrElse(bundleId2.getOrElse(throw new NoSuchElementException("bundleId not found"))),
          publisherid = app.publisher.flatMap(_.id)
        )

        val user = CriteoUser(
          deviceid = device.ifa.getOrElse(throw new NoSuchElementException("ifa not found")),
          deviceidtype = if (os.contains(Platform.iOS.prettyValue)) "IDFA" else "GAID",
          deviceos = os.getOrElse(throw new NoSuchElementException("os not found")),
          lmt = device.lmt.map(if (_) "1" else "0")
        )

        val slots = infoByUuid.map { case (uuid, i) =>
          SlotRequest(
            impid = uuid.toString,
            zoneid = zoneId,
            native = isNative.some
          )
        }.toSeq

        CriteoRequest(publisher, user, slots)
      }

      val headers = Nil ++
        device.ua.map(USER_AGENT -> _) ++
        device.ip.map("X-Client-IP" -> _)

      lazy val requestBody = criteoRequest.asJson.pretty(customPrinter)

      Task.deferFuture {
        val req = ws.url(criteoEndpoint)
          .withMethod("POST")
          .withQueryStringParameters("profileId" -> criteoProfileId)
          .withHttpHeaders(headers: _*)
          .withBody(requestBody)

        Logger.debug(s"Request: $request")
        Logger.debug(s"Request uri: $criteoEndpoint")
        Logger.debug(s"Criteo request body: $requestBody")
        Logger.debug(s"ProfileId = $criteoProfileId")
        Logger.debug(s"Headers = $headers")

        req.execute()
      }.map {
        case r if r.status == Status.OK && r.body.nonEmpty =>
          Logger.debug(s"Fill with body: ${r.body}")
          parseRes(r)
        case r =>
          Logger.debug(s"NoFill with status: ${r.status} and body: ${r.body}")
          NoFill.asLeft
      }.onErrorRecover {
        case e: Exception =>
          Logger.debug(s"Request exception Ad : $e")
          RequestException(e.getClass.getName).asLeft
      }
    }

    def fetch(id: String)(implicit desc: AdDesc[A]): Task[(Either[NoBidReason, CriteoResponse], Boolean)] = {
      val key = s"criteo#${desc.name}#$id"

      cache
        .get(key)
        .flatMap {
          case Some(r) =>
            Logger.debug(s"CriteoClient: Getting bid from cache by key: $key.")
            Task.pure((r, true))
          case None =>
            Logger.debug(s"CriteoClient: Cache is empty for key: $key.")
            for {
              e <- doRequest()
              _ <- cache.put(key)(e, ttl.some)
            } yield (e, false)
        }
    }

    val response = request.mediationId match {
      case Some(id) => fetch(id)
      case None => doRequest().map(_ -> false)
    }

    response.map {
      case (Left(reason), cached) =>
        info.map(AdCall(_, reason.asLeft, cached))

      case (Right(CriteoResponse(slots)), cached) =>
        slots.map { slot =>
          val softBid = SoftBid(
            price = slot.cpm.some,
            customResponse = slot.asJson.some
          )

          val info = infoByUuid(UUID.fromString(slot.impid))

          AdCall(info, softBid.asRight, cached, slot.cpm < request.bidfloor)
        }.toVector
    }
  }

  override def prepareAd[A <: ImpT : ClassTag](call: AdCall): Task[Ad[A]] = {
    val ct = classTag[A]
    val banner = classTag[BannerT]

    if (ct == banner) {
      val slot = call.bid.right.get.customResponse.get.as[Slot].right.get

      // TODO: Put size to metadata
      Task.deferFuture(ws.url(slot.displayurl.get).get())
        .map(res => "<script type=\"application/javascript\">\n" + res.body + "\n</script>")
        .map(markup => Ad(Html(markup).asInstanceOf[A#Markup#Out].asRight, Map.empty))

    } else {
      Task.raiseError(new RuntimeException(s"${ct.runtimeClass.getSimpleName} not supported by Criteo"))
    }
  }

}
