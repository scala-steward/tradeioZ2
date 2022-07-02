package tradex.domain

import zio._
import zio.prelude._
import zio.test._
import zio.test.Assertion._
import zio.test.TestClock

import generators._
import repository._
import model.account._
import repository.inmemory._
import services.trading._
import java.time._

import io.circe._
import io.circe.generic.auto._
import io.circe.syntax._

object TradingServiceSpec extends ZIOSpecDefault {
  val localDateZERO = LocalDateTime.ofEpochSecond(0, 0, ZoneOffset.UTC)
  val testLayer     = TradingServiceTest.layer ++ TestRandom.deterministic ++ Sized.default ++ TestConfig.default

  val spec = suite("Trading Service")(
    test("successfully invoke getAccountsOpenedOn") {
      check(Gen.listOfN(5)(accountGen)) { accounts =>
        for {
          _   <- TestClock.adjust(1.day)
          now <- zio.Clock.instant
          dt = LocalDateTime.ofInstant(now, ZoneOffset.UTC)
          repo <- ZIO.service[AccountRepository]
          _ <- repo.store(
            accounts.map(_.copy(dateOfOpen = dt))
          )
          trading <- ZIO.service[TradingService]
          fetched <- trading.getAccountsOpenedOn(dt.toLocalDate())
        } yield assertTrue(
          fetched.forall(_.dateOfOpen.toLocalDate() == dt.toLocalDate())
        )
      }
    }.provide(testLayer),
    test("successfully invoke getTrades") {
      check(accountGen) { account =>
        for {
          repo   <- ZIO.service[AccountRepository]
          stored <- repo.store(account)
        } yield assert(stored.accountType)(
          isOneOf(AccountType.values)
        )
      } *>
        check(Gen.listOfN(5)(tradeGen)) { trades =>
          for {
            repo <- ZIO.service[AccountRepository]
            accs <- repo.all
            _    <- TestClock.adjust(1.day)
            now  <- zio.Clock.instant
            dt                    = LocalDateTime.ofInstant(now, ZoneOffset.UTC)
            tradesTodayForAccount = trades.map(_.copy(accountNo = accs.head.no, tradeDate = dt))
            _ <- TradeRepository.storeNTrades(
              NonEmptyList(tradesTodayForAccount.head, tradesTodayForAccount.tail: _*)
            )
            trading <- ZIO.service[TradingService]
            fetched <- trading.getTrades(accs.head.no, Some(dt.toLocalDate()))
          } yield assertTrue(
            fetched.forall(_.tradeDate.toLocalDate() == dt.toLocalDate())
          )
        }
    }.provide(testLayer),
    test("successfully invoke orders") {
      check(Gen.listOfN(5)(frontOfficeOrderGen)) { foOrders =>
        for {
          trading <- ZIO.service[TradingService]
          os      <- trading.orders(NonEmptyList(foOrders.head, foOrders.tail: _*))
        } yield assertTrue(
          os.size > 0
        )
      }
    }.provide(testLayer),
    test("successfully generate trades from front office input") {
      check(tradeGnerationInputGen) { case (account, isin, userId) =>
        check(generateTradeFrontOfficeInputGenWithAccountAndInstrument(List(account.no), List(isin))) { foInput =>
          ZIO.succeed(println(foInput.asJson.printWith(Printer.noSpaces))) *>
            (for {
              trading <- ZIO.service[TradingService]
              trades  <- trading.generateTrade(foInput, userId)
            } yield assertTrue(
              trades.size > 0 && trades.forall(trade => trade.accountNo == account.no && trade.isin == isin)
            ))
        }
      }
    }.provide(testLayer)
  )
}

object TradingServiceTest {
  val serviceLayer: URLayer[
    AccountRepository with OrderRepository with ExecutionRepository with TradeRepository,
    TradingService
  ] = {
    ZLayer.fromFunction(TradingServiceLive(_, _, _, _))
  }
  val layer =
    (AccountRepositoryInMemory.layer ++
      OrderRepositoryInMemory.layer ++
      ExecutionRepositoryInMemory.layer ++
      TradeRepositoryInMemory.layer) >+> serviceLayer
}
