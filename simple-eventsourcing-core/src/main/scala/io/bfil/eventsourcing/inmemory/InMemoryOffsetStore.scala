package io.bfil.eventsourcing.inmemory

import scala.collection.mutable
import scala.concurrent.Future

import io.bfil.eventsourcing.OffsetStore

class InMemoryOffsetStore extends OffsetStore {
  private val offsets: mutable.Map[String, Long] = mutable.Map.empty
  def load(offsetId: String): Future[Long] = Future.successful {
    offsets.getOrElse(offsetId, 0L)
  }
  def save(offsetId: String, value: Long): Future[Unit] = Future.successful {
    offsets += offsetId -> value
  }
}
