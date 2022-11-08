package io.rhonix.casper.util

import cats.effect.Sync
import cats.syntax.all._
import com.google.protobuf.ByteString

import io.rhonix.casper.protocol.{DeployData}
import io.rhonix.crypto.hash.Blake2b512Random
import io.rhonix.crypto.signatures.{Signed}
import io.rhonix.models._
import io.rhonix.rholang.interpreter.accounting.Cost
import io.rhonix.rholang.interpreter.{RhoRuntime}
import io.rhonix.shared.{Base16, Log}

import scala.util.Random
import scala.collection.immutable.BitSet
import io.rhonix.rholang.interpreter.SystemProcesses.FixedChannels
import io.rhonix.models.GUnforgeable.UnfInstance.GPrivateBody
import io.rhonix.models.Expr.ExprInstance.{GString}
import io.rhonix.models.rholang.RhoType

object SponsorUtil {
  val prepaidLookupChannel = FixedChannels.PREPAID_LOOKUP

  val ackChannel: Par = Par(
    unforgeables = Seq(
      GUnforgeable(
        GPrivateBody(
          new GPrivate(ByteString.copyFromUtf8(Random.alphanumeric.take(10).foldLeft("")(_ + _)))
        )
      )
    )
  )

  def getSponsorPhlo[F[_]: Sync, Env](
      runtime: RhoRuntime[F]
  )(implicit log: Log[F], deploy: Signed[DeployData], rand: Blake2b512Random): F[Long] = {
    import io.rhonix.models.rholang.{implicits => toPar}
    val data: Seq[Par] = Seq(
      Par(exprs = Seq(Expr(GString(deploy.data.sponsorPubKey)))),
      Par(exprs = Seq(Expr(GString(Base16.encode(deploy.pk.bytes))))),
      ackChannel
    )

    val send = Send(
      prepaidLookupChannel,
      data,
      persistent = false,
      BitSet()
    )

    for {
      _    <- Log[F].info(s"Looking up sponsor ...")
      cost <- runtime.cost.get
      _    <- runtime.cost.set(Cost.UNSAFE_MAX)

      checkpoint <- runtime.createSoftCheckpoint
      _          <- runtime.inj(toPar(send))(rand)
      chValues   <- runtime.getData(ackChannel)

      _ <- runtime.revertToSoftCheckpoint(checkpoint)
      _ <- runtime.cost.set(cost)

      ret = chValues.flatMap(
        d => d.a.pars
      )

      result = ret match {
        case Seq(RhoType.RhoNumber(x)) => x
        case _                         => 0
      }

      _ <- Log[F].info(s"Sponsored phlo: ${result}")
    } yield result
  }

}
