package io.rhonix.rholang.interpreter

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable

import io.rhonix.models.rholang.implicits._
import io.rhonix.models.Par

class EnvSpec extends AnyFlatSpec with Matchers {

  val source0: Par = GPrivateBuilder()
  val source1: Par = GPrivateBuilder()
  val source2: Par = GPrivateBuilder()
  val source3: Par = GPrivateBuilder()
  val source4: Par = GPrivateBuilder()

  "Data" should "always be inserted at the next available level index" in {
    val result: Env[Par] = Env().put(source0).put(source1).put(source2)
    result should be(Env[Par](Map(0 -> source0, 1 -> source1, 2 -> source2), 3, 0))
  }
}
