package io.rhonix.rholang.interpreter.compiler.normalizer

import io.rhonix.rholang.ast.rholang_mercury.Absyn._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import io.rhonix.models.Expr.ExprInstance._
import io.rhonix.models.Var.VarInstance._
import io.rhonix.models._
import io.rhonix.rholang.interpreter.errors._
import io.rhonix.models.rholang.implicits._
import io.rhonix.rholang.interpreter.compiler.{
  BoundMapChain,
  FreeMap,
  NameSort,
  NameVisitInputs,
  ProcSort,
  SourcePosition,
  VarSort
}
import monix.eval.Coeval

class NameMatcherSpec extends AnyFlatSpec with Matchers {
  val inputs                                   = NameVisitInputs(BoundMapChain.empty[VarSort], FreeMap.empty[VarSort])
  implicit val normalizerEnv: Map[String, Par] = Map.empty

  "NameWildcard" should "add a wildcard count to knownFree" in {
    val nw                  = new NameWildcard()
    val result              = NameNormalizeMatcher.normalizeMatch[Coeval](nw, inputs).value
    val expectedResult: Par = EVar(Wildcard(Var.WildcardMsg()))
    result.par should be(expectedResult)
    result.freeMap.count shouldEqual 1
  }

  val nvar = new NameVar("x")

  "NameVar" should "Compile as BoundVar if it's in env" in {
    val boundInputs =
      inputs.copy(boundMapChain = inputs.boundMapChain.put(("x", NameSort, SourcePosition(0, 0))))

    val result              = NameNormalizeMatcher.normalizeMatch[Coeval](nvar, boundInputs).value
    val expectedResult: Par = EVar(BoundVar(0))
    result.par should be(expectedResult)
    result.freeMap should be(inputs.freeMap)
  }
  "NameVar" should "Compile as FreeVar if it's not in env" in {
    val result              = NameNormalizeMatcher.normalizeMatch[Coeval](nvar, inputs).value
    val expectedResult: Par = EVar(FreeVar(0))
    result.par should be(expectedResult)
    result.freeMap shouldEqual
      (inputs.freeMap.put(("x", NameSort, SourcePosition(0, 0))))
  }
  "NameVar" should "Not compile if it's in env of the wrong sort" in {
    val boundInputs =
      inputs.copy(boundMapChain = inputs.boundMapChain.put(("x", ProcSort, SourcePosition(0, 0))))

    an[UnexpectedNameContext] should be thrownBy {
      NameNormalizeMatcher.normalizeMatch[Coeval](nvar, boundInputs).value
    }
  }
  "NameVar" should "Not compile if it's used free somewhere else" in {
    val boundInputs =
      inputs.copy(freeMap = inputs.freeMap.put(("x", NameSort, SourcePosition(0, 0))))

    an[UnexpectedReuseOfNameContextFree] should be thrownBy {
      NameNormalizeMatcher.normalizeMatch[Coeval](nvar, boundInputs).value
    }
  }

  val nqvar = new NameQuote(new PVar(new ProcVarVar("x")))

  "NameQuote" should "compile to a var if the var is bound" in {
    val boundInputs =
      inputs.copy(boundMapChain = inputs.boundMapChain.put(("x", ProcSort, SourcePosition(0, 0))))
    val nqvar               = new NameQuote(new PVar(new ProcVarVar("x")))
    val result              = NameNormalizeMatcher.normalizeMatch[Coeval](nqvar, boundInputs).value
    val expectedResult: Par = EVar(BoundVar(0))
    result.par should be(expectedResult)
    result.freeMap should be(inputs.freeMap)
  }

  "NameQuote" should "return a free use if the quoted proc has a free var" in {
    val result              = NameNormalizeMatcher.normalizeMatch[Coeval](nqvar, inputs).value
    val expectedResult: Par = EVar(FreeVar(0))
    result.par should be(expectedResult)
    result.freeMap should be(inputs.freeMap.put(("x", ProcSort, SourcePosition(0, 0))))
  }

  "NameQuote" should "compile to a ground" in {
    val nqground            = new NameQuote(new PGround(new GroundInt("7")))
    val result              = NameNormalizeMatcher.normalizeMatch[Coeval](nqground, inputs).value
    val expectedResult: Par = GInt(7)
    result.par should be(expectedResult)
    result.freeMap should be(inputs.freeMap)
  }

  "NameQuote" should "collapse an eval" in {
    val nqeval = new NameQuote(new PEval(new NameVar("x")))
    val boundInputs =
      inputs.copy(boundMapChain = inputs.boundMapChain.put(("x", NameSort, SourcePosition(0, 0))))
    val result              = NameNormalizeMatcher.normalizeMatch[Coeval](nqeval, boundInputs).value
    val expectedResult: Par = EVar(BoundVar(0))
    result.par should be(expectedResult)
    result.freeMap should be(inputs.freeMap)
  }

  "NameQuote" should "not collapse an eval | eval" in {
    val nqeval = new NameQuote(new PPar(new PEval(new NameVar("x")), new PEval(new NameVar("x"))))
    val boundInputs =
      inputs.copy(boundMapChain = inputs.boundMapChain.put(("x", NameSort, SourcePosition(0, 0))))
    val result              = NameNormalizeMatcher.normalizeMatch[Coeval](nqeval, boundInputs).value
    val expectedResult: Par = EVar(BoundVar(0)).prepend(EVar(BoundVar(0)), 0)
    result.par should be(expectedResult)
    result.freeMap should be(inputs.freeMap)
  }

}
