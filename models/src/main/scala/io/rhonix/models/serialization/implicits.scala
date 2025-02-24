package io.rhonix.models.serialization

import com.google.protobuf.CodedInputStream
import io.rhonix.models._
import io.rhonix.shared.Serialize
import monix.eval.Coeval
import scalapb.GeneratedMessageCompanion
import scodec.bits.ByteVector

object implicits {

  implicit def mkProtobufInstance[T <: StacksafeMessage[T]: GeneratedMessageCompanion] =
    new Serialize[T] {

      override def encode(a: T): ByteVector =
        ByteVector.view(ProtoM.toByteArray(a).value())

      override def decode(bytes: ByteVector): Either[Throwable, T] = {
        val companion = implicitly[GeneratedMessageCompanion[T]]
        val buffer    = CodedInputStream.newInstance(bytes.toArray)
        companion.defaultInstance.mergeFromM[Coeval](buffer).runAttempt()
      }
    }

}
