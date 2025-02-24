package io.rhonix.rspace.history

import io.rhonix.rspace.examples.StringExamples.implicits._
import io.rhonix.rspace.examples.StringExamples.{Pattern, StringsCaptor}
import io.rhonix.rspace.hashing.Blake2b256Hash
import io.rhonix.rspace.internal.{Datum, _}
import io.rhonix.rspace.serializers.ScodecSerialize._
import io.rhonix.rspace.test.ArbitraryInstances._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks

class EncodingSpec extends AnyFlatSpec with Matchers with ScalaCheckDrivenPropertyChecks {

  type Continuation = WaitingContinuation[Pattern, StringsCaptor]
  type Join         = Seq[String]

  "Datum list encode" should "return same hash for different orderings of each datum" in forAll {
    (datum1: Datum[String], datum2: Datum[String]) =>
      val bytes1 = encodeDatums(datum1 :: datum2 :: Nil)
      val bytes2 = encodeDatums(datum2 :: datum1 :: Nil)
      Blake2b256Hash.create(bytes1) shouldBe Blake2b256Hash.create(bytes2)
  }

  "WaitingContinuation list encode" should "return same hash for different orderings of each continuation" in forAll {
    (c1: Continuation, c2: Continuation, c3: Continuation) =>
      val bytes1 = encodeContinuations(c1 :: c2 :: c3 :: Nil)
      val bytes2 = encodeContinuations(c3 :: c2 :: c1 :: Nil)
      val bytes3 = encodeContinuations(c2 :: c3 :: c1 :: Nil)
      Blake2b256Hash.create(bytes1) shouldBe Blake2b256Hash.create(bytes2)
      Blake2b256Hash.create(bytes1) shouldBe Blake2b256Hash.create(bytes3)
  }

  "Joins list encode" should "return same hash for different orderings of each channel list" in forAll {
    (j1: Join, j2: Join, j3: Join) =>
      val bytes1 = encodeJoins(j1 :: j2 :: j3 :: Nil)
      val bytes2 = encodeJoins(j3 :: j2 :: j1 :: Nil)
      val bytes3 = encodeJoins(j2 :: j3 :: j1 :: Nil)
      val bytes4 = encodeJoins(j1 :: j3 :: j2 :: Nil)
      Blake2b256Hash.create(bytes1) shouldBe Blake2b256Hash.create(bytes2)
      Blake2b256Hash.create(bytes1) shouldBe Blake2b256Hash.create(bytes3)
      Blake2b256Hash.create(bytes1) shouldBe Blake2b256Hash.create(bytes4)
  }
}
