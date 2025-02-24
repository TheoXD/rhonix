package io.rhonix.casper.protocol.client

import cats.effect.Sync
import io.rhonix.casper.protocol._
import io.rhonix.casper.protocol.propose.v1._
import io.rhonix.models.either.implicits._
import io.rhonix.monix.Monixable
import io.rhonix.shared.syntax._
import io.grpc.{ManagedChannel, ManagedChannelBuilder}

import java.io.Closeable
import java.util.concurrent.TimeUnit

trait ProposeService[F[_]] {
  def propose(isAsync: Boolean): F[Either[Seq[String], String]]
}

object ProposeService {
  def apply[F[_]](implicit ev: ProposeService[F]): ProposeService[F] = ev
}

class GrpcProposeService[F[_]: Monixable: Sync](host: String, port: Int, maxMessageSize: Int)
    extends ProposeService[F]
    with Closeable {

  private val channel: ManagedChannel =
    ManagedChannelBuilder
      .forAddress(host, port)
      .maxInboundMessageSize(maxMessageSize)
      .usePlaintext()
      .build

  private val stub = ProposeServiceV1GrpcMonix.stub(channel)

  def propose(isAsync: Boolean): F[Either[Seq[String], String]] =
    stub
      .propose(ProposeQuery(isAsync))
      .fromTask
      .toEitherF(
        _.message.error,
        _.message.result
      )

  def proposeResult: F[Either[Seq[String], String]] =
    stub
      .proposeResult(ProposeResultQuery())
      .fromTask
      .toEitherF(
        _.message.error,
        _.message.result
      )

  @SuppressWarnings(Array("org.wartremover.warts.NonUnitStatements"))
  override def close(): Unit = {
    val terminated = channel.shutdown().awaitTermination(10, TimeUnit.SECONDS)
    if (!terminated) {
      println(
        "warn: did not shutdown after 10 seconds, retrying with additional 10 seconds timeout"
      )
      channel.awaitTermination(10, TimeUnit.SECONDS)
    }
  }

}
