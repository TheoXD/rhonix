package io.rhonix.rholang

import io.rhonix.models.PrettyPrinted
import io.rhonix.rholang.interpreter.compiler.Compiler
import io.rhonix.rholang.ast.rholang_mercury.Absyn._
import io.rhonix.rholang.ast.rholang_mercury.PrettyPrinter
import monix.eval.Coeval
import org.scalacheck.Arbitrary
import org.scalacheck.Test.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class ProcGenTest extends AnyFlatSpec with ScalaCheckPropertyChecks with Matchers {
  implicit val params: Parameters = Parameters.defaultVerbose.withMinSuccessfulTests(1000)
  implicit val procArbitrary = Arbitrary(
    ProcGen.topLevelGen(5).map(PrettyPrinted[Proc](_, PrettyPrinter.print))
  )
  behavior of "Proc generator"

  it should "generate correct procs that are normalized successfully" in {

    forAll { p: PrettyPrinted[Proc] =>
      Compiler[Coeval].astToADT(p.value).apply
    }
  }

  behavior of "Proc shrinker"

  it should "produce a correct Proc" in {
    forAll { original: PrettyPrinted[Proc] =>
      ProcGen.procShrinker
        .shrink(original.value)
        .headOption
        .map(shrinked => Compiler[Coeval].astToADT(shrinked).apply)
        .getOrElse(true)

    }
  }

  it should "produce a shorter Proc when pretty printed" in {
    def length(p: Proc) = PrettyPrinter.print(p).length

    forAll { original: PrettyPrinted[Proc] =>
      ProcGen.procShrinker
        .shrink(original.value)
        .headOption
        .forall(shrinked => length(original.value) >= length(shrinked))
    }
  }
}
