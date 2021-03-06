package ch.epfl.bluebrain.nexus.service.indexer.persistence

import akka.actor.{ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider}
import akka.persistence.cassandra.session.scaladsl.CassandraSession
import akka.persistence.query.{NoOffset, Offset}
import cats.MonadError
import cats.effect.LiftIO
import cats.implicits._
import monix.eval.Task

/**
  * Contract defining interface for the projection storage that allows storing an offset against a projection identifier
  * and querying the last known offset for an identifier.
  */
trait ProjectionStorage[F[_]] {

  /**
    * Records the specified offset against a projection identifier.
    *
    * @param identifier an unique identifier for a projection
    * @param offset     the offset to record
    * @return a future () value
    */
  def storeOffset(identifier: String, offset: Offset): F[Unit]

  /**
    * Retrieves the last known offset for the specified projection identifier.  If there is no record of an offset
    * the [[akka.persistence.query.NoOffset]] is returned.
    *
    * @param identifier an unique identifier for a projection
    * @return a future offset value for the specified projection identifier
    */
  def fetchLatestOffset(identifier: String): F[Offset]
}

/**
  * Cassandra backed [[ch.epfl.bluebrain.nexus.service.indexer.persistence.ProjectionStorage]] implementation as an
  * Akka extension that piggybacks on Akka Persistence Cassandra for configuration and session management.
  *
  * @param session  a cassandra session
  * @param keyspace the keyspace under which the projection storage operates
  * @param table    the table where projection offsets are stored
  */
final class CassandraProjectionStorage[F[_]: LiftIO](session: CassandraSession, keyspace: String, table: String)(
    implicit F: MonadError[F, Throwable])
    extends ProjectionStorage[F]
    with Extension
    with OffsetCodec {
  import io.circe.parser._

  override def storeOffset(identifier: String, offset: Offset): F[Unit] = {
    val stmt = s"update $keyspace.$table set offset = ? where identifier = ?"
    liftIO(session.executeWrite(stmt, offsetEncoder(offset).noSpaces, identifier)).map(_ => ())
  }

  override def fetchLatestOffset(identifier: String): F[Offset] = {
    val stmt = s"select offset from $keyspace.$table where identifier = ?"
    liftIO(session.selectOne(stmt, identifier)).flatMap {
      case Some(row) => F.fromTry(decode[Offset](row.getString("offset")).toTry)
      case None      => F.pure(NoOffset)
    }
  }
}

object ProjectionStorage
    extends ExtensionId[CassandraProjectionStorage[Task]]
    with ExtensionIdProvider
    with CassandraStorage {
  override def lookup(): ExtensionId[_ <: Extension] = ProjectionStorage

  override def createExtension(system: ExtendedActorSystem): CassandraProjectionStorage[Task] = {
    val (session, keyspace, table) =
      createSession("projection", "identifier varchar primary key, offset text", system)

    new CassandraProjectionStorage[Task](session, keyspace, table)
  }
}
