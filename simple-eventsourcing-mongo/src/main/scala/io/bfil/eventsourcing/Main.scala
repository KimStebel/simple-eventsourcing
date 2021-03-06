package io.bfil.eventsourcing

import java.util.logging.{Level, Logger}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

import io.circe.generic.auto._
import io.bfil.eventsourcing.util.JsonEncoding
import io.bfil.eventsourcing.mongodb._
import io.bfil.eventsourcing.inmemory.InMemoryCache
import io.bfil.eventsourcing.serialization._
import org.mongodb.scala._
import org.mongodb.scala.model._

object Main extends App {
  Logger.getLogger("org.mongodb.driver").setLevel(Level.OFF)

  val mongoClient = MongoClient("mongodb://localhost/?waitQueueMultiple=10")
  val database = mongoClient.getDatabase("simple-eventsourcing")
  val journalCollection = database.getCollection("journal")
  val offsetsCollection = database.getCollection("offsets")
  val bankAccountsCollection = database.getCollection("bankAccounts")

  Await.result(Future.sequence(Seq(
    journalCollection.drop().toFuture(),
    offsetsCollection.drop().toFuture(),
    bankAccountsCollection.drop().toFuture()
  )), 3 seconds)

  implicit val bankAccountEventSerializer = new BankAccountEventSerializer
  implicit val bankAccountEventUpcaster = new BankAccountEventUpcaster

  val journalWriter = new MongoJournalWriter(journalCollection)
  val journal = new MongoJournal[BankAccountEvent](journalCollection, journalWriter)
  val cache = new InMemoryCache[BankAccountState]

  1 to 1000 foreach { id =>
    val bankAccount = new BankAccountAggregate(id, journal, cache)
    for {
      _ <- bankAccount.open("Bruno", 1000)
      _ <- bankAccount.withdraw(100)
      _ <- bankAccount.withdraw(100)
    } yield ()
  }

  val offsetStore = new MongoOffsetStore(offsetsCollection)
  val journalEventStream = new MongoPollingEventStream[BankAccountEvent](journalCollection)
  val bankAccounts = new BankAccountsProjection(bankAccountsCollection, journalEventStream, offsetStore)

  val start = System.currentTimeMillis
  bankAccounts.run()
  while (Await.result(offsetStore.load("bank-accounts-projection"), 3 seconds) != 3000) {
    Thread.sleep(100)
  }
  println(s"Projection run in ${System.currentTimeMillis - start}ms")

  journalWriter.shutdown()
  journalEventStream.shutdown()
}

sealed trait BankAccountState extends AggregateState[BankAccountEvent, BankAccountState]
case object Empty extends BankAccountState {
  val eventHandler = EventHandler {
    case BankAccountOpened(id, name, balance) => BankAccount(id, name, balance)
  }
}
case class BankAccount(id: Int, name: String, balance: Int) extends BankAccountState {
  val eventHandler = EventHandler {
    case MoneyWithdrawn(id, amount) => copy(balance = balance - amount)
  }
}

sealed trait BankAccountEvent
case class BankAccountOpened(id: Int, name: String, balance: Int) extends BankAccountEvent
case class MoneyWithdrawn(id: Int, amount: Int) extends BankAccountEvent

case class MoneyWithdrawnV1(id: Int, amount: Int, balance: Int) extends BankAccountEvent

class BankAccountEventSerializer extends EventSerializer[BankAccountEvent] {
  import JsonEncoding._
  def serialize(event: BankAccountEvent) = event match {
    case event: BankAccountOpened => SerializedEvent("BankAccountOpened.V1", encode(event))
    case event: MoneyWithdrawnV1  => SerializedEvent("MoneyWithdrawn.V1", encode(event))
    case event: MoneyWithdrawn    => SerializedEvent("MoneyWithdrawn.V2", encode(event))
  }
  def deserialize(manifest: String, data: String) = manifest match {
    case "BankAccountOpened.V1" => decode[BankAccountOpened](data)
    case "MoneyWithdrawn.V1"    => decode[MoneyWithdrawnV1](data)
    case "MoneyWithdrawn.V2"    => decode[MoneyWithdrawn](data)
  }
}

class BankAccountEventUpcaster extends EventUpcaster[BankAccountEvent] {
  def upcastEvent(event: BankAccountEvent): Seq[BankAccountEvent] = event match {
    case MoneyWithdrawnV1(id, amount, _) => Seq(MoneyWithdrawn(id, amount))
    case _                               => Seq(event)
  }
}

class BankAccountAggregate(id: Int, journal: Journal[BankAccountEvent], cache: Cache[BankAccountState])
  extends CachedAggregate[BankAccountEvent, BankAccountState](journal, cache) {

  val aggregateId = s"bank-account-$id"
  val initialState = Empty

  private def recoverBankAccount(): Future[BankAccount] =
    recover map {
      case bankAccount: BankAccount => bankAccount
      case _                        => throw new Exception(s"Bank account with id '$id' not found")
    }

  def open(name: String, balance: Int): Future[BankAccount] =
    for {
      state <- recover
      bankAccount <- state match {
        case Empty => persist(state, BankAccountOpened(id, name, balance)).mapStateTo[BankAccount]
        case _     => Future.failed(new Exception(s"Bank account with id '$id' already exists"))
      }
    } yield bankAccount

  def withdraw(amount: Int): Future[Int] = retry(1) {
    for {
      bankAccount        <- recoverBankAccount
      updatedBankAccount <-
        if(bankAccount.balance >= amount) {
          persist(bankAccount, MoneyWithdrawn(id, amount)).mapStateTo[BankAccount]
        } else Future.failed(new Exception(s"Not enough funds in account with id '$id'"))
    } yield updatedBankAccount.balance
  }
}

class BankAccountsProjection(
  collection: MongoCollection[Document],
  eventStream: EventStream[BankAccountEvent],
  offsetStore: OffsetStore
  ) extends ResumableProjection[BankAccountEvent](eventStream, offsetStore) {
  val projectionId = "bank-accounts-projection"

  collection.createIndex(Indexes.ascending("id"), new IndexOptions().unique(true)).toFuture()

  def processEvent(event: BankAccountEvent): Future[Unit] = event match {
    case BankAccountOpened(id, name, balance) =>
      collection.insertOne(Document("id" -> id, "name" -> name, "balance" -> balance))
                .toFuture()
                .map(_ => ())
    case MoneyWithdrawn(id, amount) =>
      collection.updateOne(Filters.equal("id", id), Document("$inc" -> Document("balance" -> -amount)))
                .toFuture()
                .map(_ => ())
    case MoneyWithdrawnV1(id, amount, _) => processEvent(MoneyWithdrawn(id, amount))
  }
}
