package io.rhonix.rholang.interpreter.compiler.normalizer

import io.rhonix.rholang.ast.rholang_mercury.Absyn._

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import io.rhonix.models.Expr.ExprInstance._

class BoolMatcherSpec extends AnyFlatSpec with Matchers {
  "BoolTrue" should "Compile as GBool(true)" in {
    val btrue = new BoolTrue()

    BoolNormalizeMatcher.normalizeMatch(btrue) should be(GBool(true))
  }
  "BoolFalse" should "Compile as GBool(false)" in {
    val bfalse = new BoolFalse()

    BoolNormalizeMatcher.normalizeMatch(bfalse) should be(GBool(false))
  }
}
