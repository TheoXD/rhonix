package io.rhonix.rspace.trace

import io.rhonix.rspace.hashing.StableHashProvider._
import io.rhonix.rspace.examples.StringExamples.implicits._
import io.rhonix.rspace.examples.StringExamples.{Pattern, StringsCaptor}
import io.rhonix.rspace.hashing.{Blake2b256Hash, StableHashProvider}
import io.rhonix.rspace.serializers.ScodecSerialize.{RichAttempt, _}
import io.rhonix.rspace.test.ArbitraryInstances._
import io.rhonix.rspace.util
import io.rhonix.shared.Serialize
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import scodec.codecs.{ignore => cignore, _}

class EventTests extends AnyFlatSpec with Matchers with ScalaCheckDrivenPropertyChecks {

  "A Produce" should "contain the expected hash" in {
    forAll { (channel: String, data: String, persist: Boolean) =>
      val actual = Produce(channel, data, persist)

      val channelEncoded = Serialize[String].encode(channel)
      val channelHash    = Blake2b256Hash.create(channelEncoded)

      val encodedSeq =
        List(
          channelHash.bytes,
          Serialize[String].encode(data),
          bool(8).encode(persist).map(_.bytes).getUnsafe
        )
      val encoded = codecSeqByteVector.encode(encodedSeq).getUnsafe.toByteVector

      val expectedHash = Blake2b256Hash.create(encoded)

      actual.channelsHash shouldBe hash(channel)
      actual.hash shouldBe expectedHash
    }
  }

  "A Consume" should "contain the expected hash" in {
    forAll { (channelPatterns: List[(String, Pattern)], persist: Boolean) =>
      val (channels, patterns) = channelPatterns.unzip

      val continuation = new StringsCaptor

      val actual = Consume(channels, patterns, continuation, persist)

      val hashedChannels =
        channels.map(StableHashProvider.hash(_)).sortBy(_.bytes)(util.ordByteVector)
      val encodedChannels = hashedChannels.map(_.bytes)
      val encodedPatterns = toOrderedByteVectors(patterns)

      val encodedSeq =
        encodedChannels ++ encodedPatterns ++ List(
          Serialize[StringsCaptor].encode(continuation),
          bool(8).encode(persist).map(_.bytes).getUnsafe
        )
      val encoded = codecSeqByteVector.encode(encodedSeq).getUnsafe.toByteVector

      val expectedHash = Blake2b256Hash.create(encoded)

      actual.channelsHashes shouldBe hashedChannels
      actual.hash shouldBe expectedHash
    }
  }

  "A Consume hash" should "be same for reordered (channel, pattern) pairs" in {
    forAll { (channelPatterns: List[(String, Pattern)], persist: Boolean) =>
      val (channels, patterns) = channelPatterns.unzip
      val continuation         = new StringsCaptor

      val actual   = Consume(channels, patterns, continuation, persist)
      val reversed = Consume(channels.reverse, patterns.reverse, continuation, persist)

      reversed.hash shouldBe actual.hash
    }
  }
}
