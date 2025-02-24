package io.rhonix.casper.protocol

import com.google.protobuf.ByteString
import io.rhonix.comm.protocol.routing.Packet
import enumeratum._
import scalapb.{GeneratedMessage, GeneratedMessageCompanion}

import scala.util.Try

sealed abstract class PacketTypeTag extends EnumEntry

object PacketTypeTag extends Enum[PacketTypeTag] {
  // Blocks messages
  case object BlockHashMessage extends PacketTypeTag
  case object BlockMessage     extends PacketTypeTag
  case object HasBlockRequest  extends PacketTypeTag
  case object HasBlock         extends PacketTypeTag
  case object BlockRequest     extends PacketTypeTag
  // Tips messages
  case object ForkChoiceTipRequest extends PacketTypeTag
  // Finalized fringe
  case object FinalizedFringeRequest extends PacketTypeTag
  case object FinalizedFringe        extends PacketTypeTag
  // Last finalized state messages
  case object StoreItemsMessageRequest extends PacketTypeTag
  case object StoreItemsMessage        extends PacketTypeTag

  override val values = findValues

  sealed abstract class ValueOf[Of <: PacketTypeTag] {
    def tag: String
  }
  object ValueOf {
    private def summon[A <: PacketTypeTag](a: A): ValueOf[A] = new ValueOf[A] {
      override val tag: String = a.entryName
    }
    def apply[A <: PacketTypeTag](implicit ev: ValueOf[A]) = ev

    // Block messages
    implicit val valueOfBlockHashMessage: ValueOf[BlockHashMessage.type] = summon(BlockHashMessage)
    implicit val valueOfBlockMessage: ValueOf[BlockMessage.type]         = summon(BlockMessage)
    implicit val valueOfHasBlockRequest: ValueOf[HasBlockRequest.type]   = summon(HasBlockRequest)
    implicit val valueOfHasBlock: ValueOf[HasBlock.type]                 = summon(HasBlock)
    implicit val valueOfBlockRequest: ValueOf[BlockRequest.type]         = summon(BlockRequest)
    // Tips
    implicit val valueOfForkChoiceTipRequest: ValueOf[ForkChoiceTipRequest.type] =
      summon(ForkChoiceTipRequest)
    // Finalized fringe
    implicit val valueOfFinalizedFringeRequest: ValueOf[FinalizedFringeRequest.type] =
      summon(FinalizedFringeRequest)
    implicit val valueOfFinalizedFringe: ValueOf[FinalizedFringe.type] = summon(FinalizedFringe)
    // Last finalized state messages
    implicit val valueOfStoreItemsMessageRequest: ValueOf[StoreItemsMessageRequest.type] =
      summon(StoreItemsMessageRequest)
    implicit val valueOfStoreItemsMessage: ValueOf[StoreItemsMessage.type] =
      summon(StoreItemsMessage)
  }

}

import io.rhonix.casper.protocol.PacketTypeTag.ValueOf

sealed abstract class PacketParseResult[+A](val isSuccess: Boolean) {
  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  final def get: A   = fold(identity)(err => throw new NoSuchElementException(err))
  final def toEither = fold(Right(_): Either[String, A])(Left(_))

  def fold[B](onSuccess: A => B)(onFailure: String => B): B
}
object PacketParseResult {
  final case class Success[A](parsed: A) extends PacketParseResult[A](true) {
    override def fold[B](onSuccess: A => B)(onFailure: String => B): B = onSuccess(parsed)
  }
  final case class Failure(throwable: Throwable) extends PacketParseResult[Nothing](false) {
    override def fold[B](onSuccess: Nothing => B)(onFailure: String => B): B =
      onFailure(throwable.getMessage)
  }
  final case class IllegalPacket(message: String) extends PacketParseResult[Nothing](false) {
    override def fold[B](onSuccess: Nothing => B)(onFailure: String => B): B = onFailure(message)
  }

  @inline def fromTry[A](a: Try[A]): PacketParseResult[A] = a.fold(Failure, Success(_))
}

import io.rhonix.casper.protocol.PacketParseResult._

trait FromPacket[Tag <: PacketTypeTag] {
  type To
  def witness: ValueOf[Tag]
  final def parseFrom(packet: Packet): PacketParseResult[To] =
    if (packet.typeId == witness.tag) parse(packet.content.toByteArray)
    else IllegalPacket(s"Got ${packet.typeId} packet - need ${witness.tag} packet")
  protected def parse(content: Array[Byte]): PacketParseResult[To]
}

object FromPacket {
  def protoImpl[Tag <: PacketTypeTag, A <: GeneratedMessage](
      implicit companion: GeneratedMessageCompanion[A],
      witness0: ValueOf[Tag]
  ): FromPacket[Tag] { type To = A } = new FromPacket[Tag] {
    override type To = A
    override val witness                               = witness0
    protected override def parse(content: Array[Byte]) = fromTry(Try(companion.parseFrom(content)))
  }
}

trait ToPacket[A] {
  type Tag <: PacketTypeTag
  def witness: ValueOf[Tag]
  final def mkPacket(model: A): Packet = Packet(witness.tag, content(model))
  protected def content(a: A): ByteString
}
object ToPacket {
  def apply[A](msg: A)(implicit ev: ToPacket[A]) = ev.mkPacket(msg)

  def protoMessageImpl[A <: GeneratedMessage, Tag0 <: PacketTypeTag](
      implicit witness0: ValueOf[Tag0]
  ): ToPacket[A] { type Tag = Tag0 } = new ToPacket[A] {
    override type Tag = Tag0
    override val witness                             = witness0
    protected override def content(a: A): ByteString = a.toByteString
  }
  implicit def protoSerde[Tag0 <: PacketTypeTag, A0 <: GeneratedMessage](
      implicit de: FromPacket[Tag0] { type To = A0 }
  ): ToPacket[A0] { type Tag = Tag0 } = protoMessageImpl[A0, Tag0](de.witness)
}
