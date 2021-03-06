package pavlomi.poloniex

import java.time.Instant

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.RawHeader
import akka.stream.Materializer
import enumeratum.values.{StringEnum, StringEnumEntry}
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import pavlomi.poloniex.domain.dto._
import pavlomi.poloniex.domain.dto.tradingapi._
import pavlomi.poloniex.domain._
import spray.json._

import scala.collection.immutable.Seq
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

class PoloniexTradingApi(APIKey: PoloniexAPIKey, secret: PoloniexSecret)(implicit actorSystem: ActorSystem, mac: Materializer, ec: ExecutionContext) {
  import JsonConversion._

  type PoloniexResponseFut[S <: PoloniexSuccessResponse] = Future[Either[PoloniexErrorResponse, S]]
  type PoloniexSeqResponseFut[T]                         = PoloniexResponseFut[PoloniexSuccessSeqResponse[T]]

  def returnBalances(): PoloniexResponseFut[ReturnBalanceResponse] = {
    val command  = PoloniexTradingApi.Command.ReturnBalances.value
    val formData = FormData(Map("nonce" -> getNonce.toString, "command" -> command))

    httpRequestRun(formData)(strict => ReturnBalanceResponse(strict.data.utf8String.parseJson.convertTo[Map[PoloniexCurrency, String]]))
  }

  /**
   * Returns all of your balances, including available balance, balance on orders, and the estimated BTC value of your balance.
   * By default, this call is limited to your exchange account; set the "account" POST parameter to "all" to include your margin and lending accounts.
   */
  def returnCompleteBalances(): PoloniexResponseFut[ReturnCompleteBalancesResponse] = {
    val command  = PoloniexTradingApi.Command.ReturnCompleteBalances.value
    val formData = FormData(Map("nonce" -> getNonce.toString, "command" -> command))

    httpRequestRun(formData) { strict =>
      ReturnCompleteBalancesResponse(strict.data.utf8String.parseJson.convertTo[Map[PoloniexCurrency, ReturnCompleteBalances]])
    }
  }

  /**
   * Returns all of your deposit addresses.
   */
  def returnDepositAddresses(): PoloniexResponseFut[ReturnDepositAddressesResponse] = {
    val command  = PoloniexTradingApi.Command.ReturnDepositAddresses.value
    val formData = FormData(Map("nonce" -> getNonce.toString, "command" -> command))

    httpRequestRun(formData) { strict =>
      ReturnDepositAddressesResponse(strict.data.utf8String.parseJson.convertTo[Map[PoloniexCurrency, String]])
    }
  }

  /**
   * Generates a new deposit address for the currency specified by the "currency" POST parameter.
   */
  def generateNewAddress(currency: PoloniexCurrency): PoloniexResponseFut[GenerateNewAddressResponse] = {
    val command  = PoloniexTradingApi.Command.GenerateNewAddress.value
    val formData = FormData(Map("nonce" -> getNonce.toString, "command" -> command, "currency" -> currency.value))

    httpRequestRun(formData)(_.data.utf8String.parseJson.convertTo[GenerateNewAddressResponse])
  }

  /**
   * Returns your deposit and withdrawal history within a range, specified by the "start" and "end" POST parameters, both of which should be given as UNIX timestamps.
   */
  def returnDepositsWithdrawals(): PoloniexResponseFut[ReturnDepositsWithdrawalsResponse] = {
    val command  = PoloniexTradingApi.Command.ReturnDepositsWithdrawals.value
    val formData = FormData(Map("nonce" -> getNonce.toString, "command" -> command))

    httpRequestRun(formData)(_.data.utf8String.parseJson.convertTo[ReturnDepositsWithdrawalsResponse])
  }

  /**
   * Returns your open orders for a given market, specified by the "currencyPair" POST parameter, e.g. "BTC_XCP".
   * Set "currencyPair" to "all" to return open orders for all markets.
   */
  def returnOpenOrders(currencyOpt: Option[PoloniexCurrencyPair]): PoloniexResponseFut[ReturnOpenOrdersResponse] = {
    val command = PoloniexTradingApi.Command.ReturnOpenOrders.value
    val formData = FormData(
      Map(
        "nonce"        -> getNonce.toString,
        "command"      -> command,
        "currencyPair" -> currencyOpt.map(_.toString).getOrElse("all")
      )
    )

    httpRequestRun(formData) { strict =>
      currencyOpt
        .map(_ => ReturnOpenOrdersSingle(strict.data.utf8String.parseJson.convertTo[Seq[ReturnOpenOrders]]))
        .getOrElse(ReturnOpenOrdersAll(strict.data.utf8String.parseJson.convertTo[Map[PoloniexCurrencyPair, Seq[ReturnOpenOrders]]]))
    }
  }

  /**
   * Returns your trade history for a given market, specified by the "currencyPair" POST parameter.
   * You may specify "all" as the currencyPair to receive your trade history for all markets.
   * You may optionally specify a range via "start" and/or "end" POST parameters, given in UNIX timestamp format; if you do not specify a range, it will be limited to one day.
   * You may optionally limit the number of entries returned using the "limit" parameter, up to a maximum of 10,000.
   * If the "limit" parameter is not specified, no more than 500 entries will be returned.
   */
  def returnTradeHistory(
    currencyOpt: Option[PoloniexCurrencyPair],
    startOpt: Option[Instant],
    endOpt: Option[Instant],
    limitOpt: Option[Int]
  ): PoloniexResponseFut[ReturnTradeHistoryResponse] = {
    require(limitOpt.map(_ <= 10000).getOrElse(true))
    val command = PoloniexTradingApi.Command.ReturnTradeHistory.value

    val startParam = startOpt.map(("start" -> convertInstantToTimestampString(_))).toMap

    val endParam = endOpt.map(("end" -> convertInstantToTimestampString(_))).toMap

    val limitParam = limitOpt.map(("limit" -> _.toString)).toMap

    val formData = FormData(
      Map(
        "nonce"        -> getNonce.toString,
        "command"      -> command,
        "currencyPair" -> currencyOpt.map(_.toString).getOrElse("all")
      ) ++ startParam ++ endParam ++ limitParam
    )

    httpRequestRun(formData) { strict =>
      currencyOpt
        .map(_ => ReturnTradeHistorySingle(strict.data.utf8String.parseJson.convertTo[Seq[ReturnTradeHistory]]))
        .getOrElse(ReturnTradeHistoryAll(strict.data.utf8String.parseJson.convertTo[Map[PoloniexCurrencyPair, Seq[ReturnTradeHistory]]]))
    }
  }

  /**
   * Returns all trades involving a given order, specified by the "orderNumber" POST parameter.
   * If no trades for the order have occurred or you specify an order that does not belong to you, you will receive an error.
   */
  def returnOrderTrades(orderNumber: OrderNumber): PoloniexResponseFut[ReturnOrderTradesResponse] = {
    val command  = PoloniexTradingApi.Command.ReturnOrderTrades.value
    val formData = FormData(Map("nonce" -> getNonce.toString, "command" -> command, "orderNumber" -> orderNumber.value.toString))

    httpRequestRun(formData)(strict => ReturnOrderTradesResponse(strict.data.utf8String.parseJson.convertTo[Seq[ReturnOrderTrades]]))
  }

  def returnOrderStatus(orderNumber: OrderNumber) = {
    val command = PoloniexTradingApi.Command.ReturnOrderStatus.value

    val formData = FormData(Map("nonce" -> getNonce.toString, "command" -> command, "orderNumber" -> orderNumber.value.toString))

    httpRequestRun(formData){strict =>
    println(strict.data.utf8String)
      strict.data.utf8String.parseJson.convertTo[ReturnOrderStatusResponse]
    }
  }

  /**
   * Places a limit buy order in a given market.
   * Required POST parameters are "currencyPair", "rate", and "amount".
   * If successful, the command will return the order number.
   * TODO: optionally set implement
   */
  def buy(currencyPair: PoloniexCurrencyPair, rate: BigDecimal, amount: BigDecimal): PoloniexResponseFut[BuyResponse] = {
    val command = PoloniexTradingApi.Command.Buy.value
    val formData = FormData(
      Map(
        "nonce"        -> getNonce.toString,
        "command"      -> command,
        "currencyPair" -> currencyPair.toString,
        "rate"         -> rate.toString(),
        "amount"       -> amount.toString()
      )
    )

    httpRequestRun(formData){ strict =>
      val parsedJson = strict.data.utf8String.parseJson
      parsedJson.asJsObject.fields.get("error") match {
        case Some(_) => parsedJson.convertTo[BuyErrorResponse]
        case None    => parsedJson.convertTo[BuySuccessResponse]
      }
    }
  }

  /**
   * Places a sell order in a given market.
   * Parameters and output are the same as for the buy command.
   */
  def sell(currencyPair: PoloniexCurrencyPair, rate: BigDecimal, amount: BigDecimal): PoloniexResponseFut[SellResponse] = {
    val command = PoloniexTradingApi.Command.Sell.value
    val formData = FormData(
      Map(
        "nonce"        -> getNonce.toString,
        "command"      -> command,
        "currencyPair" -> currencyPair.toString,
        "rate"         -> rate.toString(),
        "amount"       -> amount.toString()
      )
    )

    httpRequestRun(formData){ strict =>
      println(strict)
      val parsedJson = strict.data.utf8String.parseJson
      parsedJson.asJsObject.fields.get("error") match {
        case Some(_) => parsedJson.convertTo[SellErrorResponse]
        case None    => parsedJson.convertTo[SellSuccessResponse]
      }
    }
  }

  /**
   * Cancels an order you have placed in a given market.
   * Required POST parameter is "orderNumber".
   */
  def cancelOrder(orderNumber: OrderNumber): PoloniexResponseFut[CancelOrderResponse] = {
    val command  = PoloniexTradingApi.Command.CancelOrder.value
    val formData = FormData(Map("nonce" -> getNonce.toString, "command" -> command, "orderNumber" -> orderNumber.toString()))

    httpRequestRun(formData)(_.data.utf8String.parseJson.convertTo[CancelOrderResponse])
  }

  /**
   * Cancels an order and places a new one of the same type in a single atomic transaction, meaning either both operations will succeed or both will fail.
   */
  def moveOrder(orderNumber: OrderNumber, rate: String, amountOpt: Option[BigDecimal]): PoloniexResponseFut[MoveOrderResponse] = {
    val command = PoloniexTradingApi.Command.MoveOrder.value

    val amount = amountOpt.map("amount" -> _.toString).toMap
    val data = Map(
      "nonce"       -> getNonce.toString,
      "command"     -> command,
      "orderNumber" -> orderNumber.toString,
      "rate"        -> rate
    ) ++ amount

    val formData = FormData(data)

    httpRequestRun(formData)(_.data.utf8String.parseJson.convertTo[MoveOrderResponse])
  }

  /**
   * Immediately places a withdrawal for a given currency, with no email confirmation.
   * In order to use this command, the withdrawal privilege must be enabled for your API key
   */
  def withdraw(currency: PoloniexCurrency, amount: BigDecimal, address: String): PoloniexResponseFut[WithdrawResponse] = {
    val command = PoloniexTradingApi.Command.Withdraw.value
    val formData = FormData(
      Map(
        "nonce"    -> getNonce.toString,
        "command"  -> command,
        "currency" -> currency.value,
        "amount"   -> amount.toString,
        "address"  -> address
      )
    )

    httpRequestRun(formData)(_.data.utf8String.parseJson.convertTo[WithdrawResponse])
  }

  /**
   * If you are enrolled in the maker-taker fee schedule, returns your current trading fees and trailing 30-day volume in BTC.
   * This information is updated once every 24 hours.
   */
  def returnFeeInfo(): PoloniexResponseFut[ReturnFeeInfoResponse] = {
    val command  = PoloniexTradingApi.Command.ReturnFeeInfo.value
    val formData = FormData(Map("nonce" -> getNonce.toString, "command" -> command))

    httpRequestRun(formData)(_.data.utf8String.parseJson.convertTo[ReturnFeeInfoResponse])
  }

  /**
   * Returns your balances sorted by account.
   * You may optionally specify the "account" POST parameter if you wish to fetch only the balances of one account.
   */
  def returnAvailableAccountBalances(): PoloniexResponseFut[ReturnAvailableAccountBalances] = {
    val command  = PoloniexTradingApi.Command.ReturnAvailableAccountBalances.value
    val formData = FormData(Map("nonce" -> getNonce.toString, "command" -> command))

    httpRequestRun(formData)(_.data.utf8String.parseJson.convertTo[ReturnAvailableAccountBalances])
  }

  /**
   * Returns your current tradable balances for each currency in each market for which margin trading is enabled.
   * Please note that these balances may vary continually with market conditions.
   */
  def returnTradableBalances(): PoloniexResponseFut[ReturnTradableBalanceResponse] = {
    val command  = PoloniexTradingApi.Command.ReturnTradableBalances.value
    val formData = FormData(Map("nonce" -> getNonce.toString, "command" -> command))

    httpRequestRun(formData) { strict =>
      val tradableBalanceResponse = strict.data.utf8String.parseJson.convertTo[Map[PoloniexCurrencyPair, Map[PoloniexCurrency, String]]]
      ReturnTradableBalanceResponse(tradableBalanceResponse)
    }
  }

  /**
   * Transfers funds from one account to another (e.g. from your exchange account to your margin account).
   * Required POST parameters are "currency", "amount", "fromAccount", and "toAccount"
   */
  def transferBalance(
    currency: PoloniexCurrency,
    amount: BigDecimal,
    fromAccount: String,
    toAccount: String
  ): PoloniexResponseFut[TransferBalanceResponse] = {
    val command = PoloniexTradingApi.Command.TransferBalance.value
    val formData = FormData(
      Map(
        "nonce"       -> getNonce.toString,
        "command"     -> command,
        "currency"    -> currency.value,
        "amount"      -> amount.toString,
        "fromAccount" -> fromAccount,
        "toAccount"   -> toAccount
      )
    )

    httpRequestRun(formData)(_.data.utf8String.parseJson.convertTo[TransferBalanceResponse])
  }

  /**
   * Returns a summary of your entire margin account.
   * This is the same information you will find in the Margin Account section of the Margin Trading page, under the Markets list.
   */
  def returnMarginAccountSummary(): PoloniexResponseFut[ReturnMarginAccountSummaryResponse] = {
    val command  = PoloniexTradingApi.Command.ReturnMarginAccountSummary.value
    val formData = FormData(Map("nonce" -> getNonce.toString, "command" -> command))

    httpRequestRun(formData)(_.data.utf8String.parseJson.convertTo[ReturnMarginAccountSummaryResponse])
  }

  /**
   * Places a margin buy order in a given market.
   * Required POST parameters are "currencyPair", "rate", and "amount".
   * You may optionally specify a maximum lending rate using the "lendingRate" parameter.
   * If successful, the command will return the order number and any trades immediately resulting from your order
   */
  def marginBuy(
    currencyPair: PoloniexCurrencyPair,
    rate: BigDecimal,
    amount: BigDecimal
  ): PoloniexResponseFut[MarginBuyResponse] = {
    val command = PoloniexTradingApi.Command.MarginBuy.value
    val formData = FormData(
      Map(
        "nonce"        -> getNonce.toString,
        "command"      -> command,
        "currencyPair" -> currencyPair.toString,
        "rate"         -> rate.toString,
        "amount"       -> amount.toString
      )
    )

    httpRequestRun(formData)(_.data.utf8String.parseJson.convertTo[MarginBuyResponse])
  }

  /**
   * Places a margin sell order in a given market. Parameters and output are the same as for the marginBuy command.
   */
  def marginSell(
    currencyPair: PoloniexCurrencyPair,
    rate: BigDecimal,
    amount: BigDecimal
  ): PoloniexResponseFut[MarginSellResponse] = {
    val command = PoloniexTradingApi.Command.MarginSell.value
    val formData = FormData(
      Map(
        "nonce"        -> getNonce.toString,
        "command"      -> command,
        "currencyPair" -> currencyPair.toString,
        "rate"         -> rate.toString,
        "amount"       -> amount.toString
      )
    )

    httpRequestRun(formData)(_.data.utf8String.parseJson.convertTo[MarginSellResponse])
  }

  /**
   * Returns information about your margin position in a given market, specified by the "currencyPair" POST parameter.
   * You may set "currencyPair" to "all" if you wish to fetch all of your margin positions at once.
   * If you have no margin position in the specified market, "type" will be set to "none". "liquidationPrice" is an estimate, and does not necessarily represent the price at which an actual forced liquidation will occur.
   * If you have no liquidation price, the value will be -1.
   */
  def getMarginPosition(currencyPair: Option[PoloniexCurrencyPair]): PoloniexResponseFut[GetMarginPositionResponse] = {
    val command = PoloniexTradingApi.Command.GetMarginPosition.value
    val formData = FormData(
      Map(
        "nonce"        -> getNonce.toString,
        "command"      -> command,
        "currencyPair" -> currencyPair.map(_.toString).getOrElse("all")
      )
    )

    httpRequestRun(formData)(_.data.utf8String.parseJson.convertTo[GetMarginPositionResponse])
  }

  /**
   * Closes your margin position in a given market (specified by the "currencyPair" POST parameter) using a market order.
   * This call will also return success if you do not have an open position in the specified market.
   */
  def closeMarginPosition(currencyPair: PoloniexCurrencyPair): PoloniexResponseFut[CloseMarginPositionResponse] = {
    val command  = PoloniexTradingApi.Command.CloseMarginPosition.value
    val formData = FormData(Map("nonce" -> getNonce.toString, "command" -> command, "currencyPair" -> currencyPair.toString))

    httpRequestRun(formData)(_.data.utf8String.parseJson.convertTo[CloseMarginPositionResponse])
  }

  /**
   * Creates a loan offer for a given currency.
   * Required POST parameters are "currency", "amount", "duration", "autoRenew" (0 or 1), and "lendingRate".
   */
  def createLoanOffer(
    currency: PoloniexCurrency,
    amount: String,
    duration: String,
    autoRenew: Int,
    lendingRate: String
  ): PoloniexResponseFut[CreateLoanOfferResponse] = {
    val command = PoloniexTradingApi.Command.CreateLoanOffer.value
    val formData = FormData(
      Map(
        "nonce"       -> getNonce.toString,
        "command"     -> command,
        "currency"    -> currency.value,
        "amount"      -> amount,
        "duration"    -> duration,
        "autoRenew"   -> autoRenew.toString,
        "lendingRate" -> lendingRate
      )
    )

    httpRequestRun(formData)(_.data.utf8String.parseJson.convertTo[CreateLoanOfferResponse])
  }

  /**
   * Cancels a loan offer specified by the "orderNumber" POST parameter.
   */
  def cancelLoanOffer(orderNumber: String): PoloniexResponseFut[CancelLoanOfferResponse] = {
    val command  = PoloniexTradingApi.Command.CancelLoanOffer.value
    val formData = FormData(Map("nonce" -> getNonce.toString, "command" -> command, "orderNumber" -> orderNumber))

    httpRequestRun(formData)(_.data.utf8String.parseJson.convertTo[CancelLoanOfferResponse])
  }

  /**
   * Returns your open loan offers for each currency.
   */
  def returnOpenLoanOffers(): PoloniexResponseFut[ReturnOpenLoanOffersResponse] = {
    val command  = PoloniexTradingApi.Command.ReturnOpenLoanOffers.value
    val formData = FormData(Map("nonce" -> getNonce.toString, "command" -> command))

    httpRequestRun(formData)(_.data.utf8String.parseJson.convertTo[ReturnOpenLoanOffersResponse])
  }

  /**
   * Returns your active loans for each currency.
   */
  def returnActiveLoans(): PoloniexResponseFut[ReturnActiveLoansResponse] = {
    val command  = PoloniexTradingApi.Command.ReturnActiveLoans.value
    val formData = FormData(Map("nonce" -> getNonce.toString, "command" -> command))

    httpRequestRun(formData)(_.data.utf8String.parseJson.convertTo[ReturnActiveLoansResponse])
  }

  /**
   * Returns your lending history within a time range specified by the "start" and "end" POST parameters as UNIX timestamps.
   * "limit" may also be specified to limit the number of rows returned.
   */
  def returnLendingHistory(start: Instant, end: Instant, limit: Int): PoloniexSeqResponseFut[ReturnLendingHistoryResponse] = {
    val command = PoloniexTradingApi.Command.ReturnLendingHistory.value
    val formData = FormData(
      Map(
        "nonce"   -> getNonce.toString,
        "command" -> command,
        "start"   -> convertInstantToTimestampString(start),
        "end"     -> convertInstantToTimestampString(end),
        "limit"   -> limit.toString
      )
    )

    httpRequestRun(formData) { strict =>
      val lendingHistoryResponse = strict.data.utf8String.parseJson.convertTo[Seq[ReturnLendingHistoryResponse]]
      PoloniexSuccessSeqResponse(lendingHistoryResponse)
    }
  }

  /**
   * Toggles the autoRenew setting on an active loan, specified by the "orderNumber" POST parameter.
   * If successful, "message" will indicate the new autoRenew setting.
   */
  def toggleAutoRenew(orderNumber: String): PoloniexResponseFut[ToggleAutoRenewResponse] = {
    val command  = PoloniexTradingApi.Command.ToggleAutoRenew.value
    val formData = FormData(Map("nonce" -> getNonce.toString, "command" -> command, "orderNumber" -> orderNumber))

    httpRequestRun(formData)(_.data.utf8String.parseJson.convertTo[ToggleAutoRenewResponse])
  }

  private def getNonce = Instant.now.toEpochMilli

  private def getHeaders(formData: FormData): Seq[HttpHeader] = Seq(RawHeader("Sign", sign(formData.fields.toString)), RawHeader("Key", APIKey.value))

  private def convertInstantToTimestampString(instant: Instant) = instant.toEpochMilli.toString.substring(0, 10)

  private def http() = Http()

  private def sign(postData: String): String = calculateHMAC(postData)

  private def parseHttpResponse[S <: PoloniexSuccessResponse](httpResponse: HttpResponse, f: HttpEntity.Strict => S): PoloniexResponseFut[S] =
    httpResponse match {
      case HttpResponse(status, _, entity, _) if status.isSuccess() =>
        println("Status code", status.intValue)
        entity.toStrict(timeout).map(s => Right(f(s)))
      case HttpResponse(_, _, entity, _) =>
        entity.toStrict(timeout).map(s => Left(s.data.utf8String.parseJson.convertTo[PoloniexErrorResponse]))
    }

  private def httpRequestRun[S <: PoloniexSuccessResponse](formData: FormData)(f: HttpEntity.Strict => S): PoloniexResponseFut[S] = {
    val httpRequest = HttpRequest(
      HttpMethods.POST,
      POLONIEX_TRADING_API_URL,
      getHeaders(formData),
      formData.toEntity
    )

    http().singleRequest(httpRequest).flatMap(parseHttpResponse[S](_, f))
  }

  private def calculateHMAC(data: String): String = {
    val secretKeySpec = new SecretKeySpec(secret.value.getBytes, HMAC_SHA512)
    val mac           = Mac.getInstance(HMAC_SHA512)
    mac.init(secretKeySpec)
    valueOf(mac.doFinal(data.getBytes()))
  }

  private def valueOf(bytes: Array[Byte]): String = bytes.map("%02x" format _).mkString

  private val timeout                  = 3000.millis
  private val HMAC_SHA512              = "HmacSHA512"
  private val POLONIEX_TRADING_API_URL = "https://poloniex.com" + "/tradingApi"
}

object PoloniexTradingApi {

  sealed abstract class Command(val value: String) extends StringEnumEntry
  object Command extends StringEnum[Command] {
    case object ReturnBalances                 extends Command("returnBalances")
    case object ReturnCompleteBalances         extends Command("returnCompleteBalances")
    case object ReturnDepositAddresses         extends Command("returnDepositAddresses")
    case object GenerateNewAddress             extends Command("generateNewAddress")
    case object ReturnDepositsWithdrawals      extends Command("returnDepositsWithdrawals")
    case object ReturnOpenOrders               extends Command("returnOpenOrders")
    case object ReturnTradeHistory             extends Command("returnTradeHistory")
    case object ReturnOrderTrades              extends Command("returnOrderTrades")
    case object ReturnOrderStatus              extends Command("returnOrderStatus")
    case object Buy                            extends Command("buy")
    case object Sell                           extends Command("sell")
    case object CancelOrder                    extends Command("cancelOrder")
    case object MoveOrder                      extends Command("moveOrder")
    case object Withdraw                       extends Command("withdraw")
    case object ReturnFeeInfo                  extends Command("returnFeeInfo")
    case object ReturnAvailableAccountBalances extends Command("returnAvailableAccountBalances")
    case object ReturnTradableBalances         extends Command("returnTradableBalances")
    case object TransferBalance                extends Command("transferBalance")
    case object ReturnMarginAccountSummary     extends Command("returnMarginAccountSummary")
    case object MarginBuy                      extends Command("marginBuy")
    case object MarginSell                     extends Command("marginSell")
    case object GetMarginPosition              extends Command("getMarginPosition")
    case object CloseMarginPosition            extends Command("closeMarginPosition")
    case object CreateLoanOffer                extends Command("createLoanOffer")
    case object CancelLoanOffer                extends Command("cancelLoanOffer")
    case object ReturnOpenLoanOffers           extends Command("returnOpenLoanOffers")
    case object ReturnActiveLoans              extends Command("returnActiveLoans")
    case object ReturnLendingHistory           extends Command("returnLendingHistory")
    case object ToggleAutoRenew                extends Command("toggleAutoRenew")

    val values = findValues
  }

}
