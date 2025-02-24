package io.rhonix.models

import io.rhonix.models.Expr.ExprInstance.{EMapBody, GInt, GString}
import io.rhonix.models.Var.VarInstance.BoundVar
import io.rhonix.models.rholang.implicits._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ParMapSpec extends AnyFlatSpec with Matchers {

  "ParMap" should "serialize like EMap" in {
    val map = ParMap(
      Seq[(Par, Par)](
        (GInt(7), GString("Seven")),
        (GInt(7), GString("SeVen")),
        (EVar(BoundVar(1)), EVar(BoundVar(0))),
        (GInt(2), ParSet(Seq[Par](GInt(2), GInt(1)))),
        (GInt(2), ParSet(Seq[Par](GInt(2))))
      )
    )

    val sortedMap = ParMap(
      Seq[(Par, Par)](
        (GInt(7), GString("Seven")),
        (GInt(7), GString("SeVen")),
        (GInt(2), ParSet(Seq[Par](GInt(2), GInt(1)))),
        (GInt(2), ParSet(Seq[Par](GInt(2)))),
        (EVar(BoundVar(1)), EVar(BoundVar(0)))
      )
    )

    val expr = Expr(EMapBody(sortedMap))

    java.util.Arrays.equals(map.toByteArray, expr.toByteArray) should be(true)
  }

}
