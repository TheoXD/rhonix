package io.rhonix.rholang.interpreter.compiler.normalizer.processes

import cats.syntax.all._
import cats.effect.Sync
import io.rhonix.models.Par
import io.rhonix.rholang.interpreter.compiler.ProcNormalizeMatcher.normalizeMatch
import io.rhonix.rholang.interpreter.compiler.{ProcVisitInputs, ProcVisitOutputs}
import io.rhonix.rholang.ast.rholang_mercury.Absyn.PPar

object PParNormalizer {
  def normalize[F[_]: Sync](p: PPar, input: ProcVisitInputs)(
      implicit env: Map[String, Par]
  ): F[ProcVisitOutputs] =
    Sync[F].defer {
      for {
        result       <- normalizeMatch[F](p.proc_1, input)
        chainedInput = input.copy(freeMap = result.freeMap, par = result.par)
        chainedRes   <- normalizeMatch[F](p.proc_2, chainedInput)
      } yield chainedRes
    }
}
