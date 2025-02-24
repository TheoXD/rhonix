package io.rhonix.rholang.interpreter.compiler.normalizer

import io.rhonix.models.Connective.ConnectiveInstance._
import io.rhonix.models.Expr.ExprInstance._
import io.rhonix.models._
import io.rhonix.models.Var.VarInstance._
import io.rhonix.models.Var.WildcardMsg
import io.rhonix.models.rholang.implicits._
import io.rhonix.rholang.ast.rholang_mercury.Absyn.{
  Bundle => _,
  Ground => _,
  KeyValuePair => _,
  Send => _,
  _
}
import io.rhonix.rholang.interpreter.compiler._
import io.rhonix.rholang.interpreter.errors._
import monix.eval.Coeval
import org.scalatest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.immutable.BitSet

class ProcMatcherSpec extends AnyFlatSpec with Matchers {
  val inputs                                   = ProcVisitInputs(Par(), BoundMapChain.empty[VarSort], FreeMap.empty[VarSort])
  implicit val normalizerEnv: Map[String, Par] = Map.empty

  "PNil" should "Compile as no modification to the par object" in {
    val nil = new PNil()

    val result = ProcNormalizeMatcher.normalizeMatch[Coeval](nil, inputs).value
    result.par should be(inputs.par)
    result.freeMap should be(inputs.freeMap)
  }

  val pvar = new PVar(new ProcVarVar("x"))
  "PVar" should "Compile as BoundVar if it's in env" in {
    val boundInputs =
      inputs.copy(boundMapChain = inputs.boundMapChain.put(("x", ProcSort, SourcePosition(0, 0))))

    val result = ProcNormalizeMatcher.normalizeMatch[Coeval](pvar, boundInputs).value
    result.par should be(inputs.par.prepend(EVar(BoundVar(0)), 0))
    result.freeMap should be(inputs.freeMap)
    result.par.locallyFree.get should be(BitSet(0))
  }
  "PVar" should "Compile as FreeVar if it's not in env" in {
    val result = ProcNormalizeMatcher.normalizeMatch[Coeval](pvar, inputs).value
    result.par should be(inputs.par.prepend(EVar(FreeVar(0)), 0))
    result.freeMap shouldEqual
      (inputs.freeMap.put(("x", ProcSort, SourcePosition(0, 0))))
  }
  "PVar" should "Not compile if it's in env of the wrong sort" in {
    val boundInputs =
      inputs.copy(boundMapChain = inputs.boundMapChain.put(("x", NameSort, SourcePosition(0, 0))))

    an[UnexpectedProcContext] should be thrownBy {
      ProcNormalizeMatcher.normalizeMatch[Coeval](pvar, boundInputs).value
    }
  }
  "PVar" should "Not compile if it's used free somewhere else" in {
    val boundInputs =
      inputs.copy(freeMap = inputs.freeMap.put(("x", ProcSort, SourcePosition(0, 0))))

    an[UnexpectedReuseOfProcContextFree] should be thrownBy {
      ProcNormalizeMatcher.normalizeMatch[Coeval](pvar, boundInputs).value
    }
  }

  "PEval" should "Handle a bound name varible" in {
    val pEval = new PEval(new NameVar("x"))
    val boundInputs =
      inputs.copy(boundMapChain = inputs.boundMapChain.put(("x", NameSort, SourcePosition(0, 0))))

    val result = ProcNormalizeMatcher.normalizeMatch[Coeval](pEval, boundInputs).value
    result.par should be(inputs.par.prepend(EVar(BoundVar(0)), 0))
    result.freeMap should be(inputs.freeMap)
  }
  "PEval" should "Collapse a quote" in {
    val pEval = new PEval(
      new NameQuote(new PPar(new PVar(new ProcVarVar("x")), new PVar(new ProcVarVar("x"))))
    )
    val boundInputs =
      inputs.copy(boundMapChain = inputs.boundMapChain.put(("x", ProcSort, SourcePosition(0, 0))))

    val result = ProcNormalizeMatcher.normalizeMatch[Coeval](pEval, boundInputs).value
    result.par should be(inputs.par.prepend(EVar(BoundVar(0)), 0).prepend(EVar(BoundVar(0)), 0))
    result.freeMap should be(inputs.freeMap)
  }

  "PNot" should "Delegate" in {
    val pNot = new PNot(new PGround(new GroundBool(new BoolFalse())))

    val result = ProcNormalizeMatcher.normalizeMatch[Coeval](pNot, inputs).value
    result.par should be(inputs.par.prepend(ENot(GBool(false)), 0))
    result.freeMap should be(inputs.freeMap)
  }

  "PNeg" should "Delegate" in {
    val pNeg = new PNeg(new PVar(new ProcVarVar("x")))
    val boundInputs =
      inputs.copy(boundMapChain = inputs.boundMapChain.put(("x", ProcSort, SourcePosition(0, 0))))

    val result = ProcNormalizeMatcher.normalizeMatch[Coeval](pNeg, boundInputs).value
    result.par should be(inputs.par.prepend(ENeg(EVar(BoundVar(0))), 0))
    result.freeMap should be(inputs.freeMap)
  }

  "PMult" should "Delegate" in {
    val pMult = new PMult(new PVar(new ProcVarVar("x")), new PVar(new ProcVarVar("y")))
    val boundInputs =
      inputs.copy(boundMapChain = inputs.boundMapChain.put(("x", ProcSort, SourcePosition(0, 0))))

    val result = ProcNormalizeMatcher.normalizeMatch[Coeval](pMult, boundInputs).value
    result.par should be(inputs.par.prepend(EMult(EVar(BoundVar(0)), EVar(FreeVar(0))), 0))
    result.freeMap should be(inputs.freeMap.put(("y", ProcSort, SourcePosition(0, 0))))
  }

  "PDiv" should "Delegate" in {
    val pDiv = new PDiv(new PGround(new GroundInt("7")), new PGround(new GroundInt("2")))

    val result = ProcNormalizeMatcher.normalizeMatch[Coeval](pDiv, inputs).value
    result.par should be(inputs.par.prepend(EDiv(GInt(7), GInt(2)), 0))
    result.freeMap should be(inputs.freeMap)
  }

  "PPercentPercent" should "Delegate" in {
    val mapData = new ListKeyValuePair()
    mapData.add(
      new KeyValuePairImpl(
        new PGround(new GroundString("\"name\"")),
        new PGround(new GroundString("\"Alice\""))
      )
    )
    val pPercentPercent =
      new PPercentPercent(
        new PGround(new GroundString("\"Hi ${name}\"")),
        new PCollect(new CollectMap(mapData, new ProcRemainderEmpty()))
      )
    val result = ProcNormalizeMatcher.normalizeMatch[Coeval](pPercentPercent, inputs).value
    result.par should be(
      inputs.par.prepend(
        EPercentPercent(
          GString("Hi ${name}"),
          ParMap(seq = List[(Par, Par)]((GString("name"), GString("Alice"))))
        ),
        0
      )
    )
    result.freeMap should be(inputs.freeMap)
  }

  "PAdd" should "Delegate" in {
    val pAdd = new PAdd(new PVar(new ProcVarVar("x")), new PVar(new ProcVarVar("y")))
    val boundInputs =
      inputs.copy(
        boundMapChain = inputs.boundMapChain
          .put(List(("x", ProcSort, SourcePosition(0, 0)), ("y", ProcSort, SourcePosition(0, 0))))
      )

    val result = ProcNormalizeMatcher.normalizeMatch[Coeval](pAdd, boundInputs).value
    result.par should be(inputs.par.prepend(EPlus(EVar(BoundVar(1)), EVar(BoundVar(0))), 0))
    result.freeMap should be(inputs.freeMap)
  }

  "PMinus" should "Delegate" in {
    val pMinus = new PMinus(
      new PVar(new ProcVarVar("x")),
      new PMult(new PVar(new ProcVarVar("y")), new PVar(new ProcVarVar("z")))
    )
    val boundInputs = inputs.copy(
      boundMapChain = inputs.boundMapChain
        .put(
          List(
            ("x", ProcSort, SourcePosition(0, 0)),
            ("y", ProcSort, SourcePosition(0, 0)),
            ("z", ProcSort, SourcePosition(0, 0))
          )
        )
    )

    val result = ProcNormalizeMatcher.normalizeMatch[Coeval](pMinus, boundInputs).value
    result.par should be(
      inputs.par.prepend(EMinus(EVar(BoundVar(2)), EMult(EVar(BoundVar(1)), EVar(BoundVar(0)))), 0)
    )
    result.freeMap should be(inputs.freeMap)
  }

  "PPlusPlus" should "Delegate" in {
    val pPlusPlus = new PPlusPlus(
      new PGround(new GroundString("\"abc\"")),
      new PGround(new GroundString("\"def\""))
    )
    val result = ProcNormalizeMatcher.normalizeMatch[Coeval](pPlusPlus, inputs).value
    result.par should be(inputs.par.prepend(EPlusPlus(GString("abc"), GString("def")), 0))
    result.freeMap should be(inputs.freeMap)
  }

  "PMinusMinus" should "Delegate" in {
    val pMinusMinus = new PMinusMinus(
      new PGround(new GroundString("\"abc\"")),
      new PGround(new GroundString("\"def\""))
    )
    val result = ProcNormalizeMatcher.normalizeMatch[Coeval](pMinusMinus, inputs).value
    result.par should be(inputs.par.prepend(EMinusMinus(GString("abc"), GString("def")), 0))
    result.freeMap should be(inputs.freeMap)
  }

  "PSend" should "handle a basic send" in {
    val sentData = new ListProc()
    sentData.add(new PGround(new GroundInt("7")))
    sentData.add(new PGround(new GroundInt("8")))
    val pSend = new PSend(new NameQuote(new PNil()), new SendSingle(), sentData)

    val result = ProcNormalizeMatcher.normalizeMatch[Coeval](pSend, inputs).value
    result.par should be(
      inputs.par.prepend(Send(Par(), List[Par](GInt(7), GInt(8)), false, BitSet()))
    )
    result.freeMap should be(inputs.freeMap)
  }

  "PSend" should "handle a name var" in {
    val sentData = new ListProc()
    sentData.add(new PGround(new GroundInt("7")))
    sentData.add(new PGround(new GroundInt("8")))
    val pSend = new PSend(new NameVar("x"), new SendSingle(), sentData)
    val boundInputs =
      inputs.copy(boundMapChain = inputs.boundMapChain.put(("x", NameSort, SourcePosition(0, 0))))

    val result = ProcNormalizeMatcher.normalizeMatch[Coeval](pSend, boundInputs).value
    result.par should be(
      inputs.par.prepend(Send(EVar(BoundVar(0)), List[Par](GInt(7), GInt(8)), false, BitSet(0)))
    )
    result.freeMap should be(inputs.freeMap)
  }

  "PSend" should "propagate knownFree" in {
    val sentData = new ListProc()
    sentData.add(new PGround(new GroundInt("7")))
    sentData.add(new PVar(new ProcVarVar("x")))
    val pSend = new PSend(new NameQuote(new PVar(new ProcVarVar("x"))), new SendSingle(), sentData)

    an[UnexpectedReuseOfProcContextFree] should be thrownBy {
      ProcNormalizeMatcher.normalizeMatch[Coeval](pSend, inputs).value
    }
  }

  "PSend" should "Not compile if data contains negation" in {
    an[TopLevelLogicalConnectivesNotAllowedError] should be thrownBy {
      Compiler[Coeval].sourceToADT("""new x in { x!(~1) }""").value()
    }
  }

  "PSend" should "Not compile if data contains conjunction" in {
    an[TopLevelLogicalConnectivesNotAllowedError] should be thrownBy {
      Compiler[Coeval].sourceToADT("""new x in { x!(1 /\ 2) }""").value()
    }
  }

  "PSend" should "Not compile if data contains disjunction" in {
    an[TopLevelLogicalConnectivesNotAllowedError] should be thrownBy {
      Compiler[Coeval].sourceToADT("""new x in { x!(1 \/ 2) }""").value()
    }
  }

  "PSend" should "Not compile if data contains wildcard" in {
    an[TopLevelWildcardsNotAllowedError] should be thrownBy {
      Compiler[Coeval].sourceToADT("""@"x"!(_)""").value()
    }
  }

  "PSend" should "Not compile if data contains free variable" in {
    an[TopLevelFreeVariablesNotAllowedError] should be thrownBy {
      Compiler[Coeval].sourceToADT("""@"x"!(y)""").value()
    }
  }

  "PSend" should "not compile if name contains connectives" in {
    an[TopLevelLogicalConnectivesNotAllowedError] should be thrownBy {
      Compiler[Coeval].sourceToADT("""@{Nil /\ Nil}!(1)""").value()
    }

    an[TopLevelLogicalConnectivesNotAllowedError] should be thrownBy {
      Compiler[Coeval].sourceToADT("""@{Nil \/ Nil}!(1)""").value()
    }

    an[TopLevelLogicalConnectivesNotAllowedError] should be thrownBy {
      Compiler[Coeval].sourceToADT("""@{~Nil}!(1)""").value()
    }
  }

  "PPar" should "Compile both branches into a par object" in {
    val parGround = new PPar(new PGround(new GroundInt("7")), new PGround(new GroundInt("8")))
    val result    = ProcNormalizeMatcher.normalizeMatch[Coeval](parGround, inputs).value
    result.par should be(inputs.par.copy(exprs = List(GInt(8), GInt(7))))
    result.freeMap should be(inputs.freeMap)
  }

  "PPar" should "Compile both branches with the same environment" in {
    val parDoubleBound = new PPar(new PVar(new ProcVarVar("x")), new PVar(new ProcVarVar("x")))
    val boundInputs =
      inputs.copy(boundMapChain = inputs.boundMapChain.put(("x", ProcSort, SourcePosition(0, 0))))

    val result = ProcNormalizeMatcher.normalizeMatch[Coeval](parDoubleBound, boundInputs).value
    result.par should be(
      inputs.par.copy(exprs = List(EVar(BoundVar(0)), EVar(BoundVar(0))), locallyFree = BitSet(0))
    )
    result.freeMap should be(inputs.freeMap)
  }
  "PPar" should "Not compile if both branches use the same free variable" in {
    val parDoubleFree = new PPar(new PVar(new ProcVarVar("x")), new PVar(new ProcVarVar("x")))
    an[UnexpectedReuseOfProcContextFree] should be thrownBy {
      ProcNormalizeMatcher.normalizeMatch[Coeval](parDoubleFree, inputs).value
    }
  }
  "PPar" should "Accumulate free counts from both branches" in {
    val parDoubleFree = new PPar(new PVar(new ProcVarVar("x")), new PVar(new ProcVarVar("y")))

    val result = ProcNormalizeMatcher.normalizeMatch[Coeval](parDoubleFree, inputs).value
    result.par should be(
      inputs.par.copy(exprs = List(EVar(FreeVar(1)), EVar(FreeVar(0))), connectiveUsed = true)
    )
    result.freeMap should be(
      inputs.freeMap.put(
        List(("x", ProcSort, SourcePosition(0, 0)), ("y", ProcSort, SourcePosition(0, 0)))
      )
    )
  }

  "PPar" should "normalize without StackOverflowError-s even for huge programs" in {
    val hugePPar = (1 to 50000)
      .map(x => new PGround(new GroundInt(x.toString)))
      .reduce((l: Proc, r: Proc) => new PPar(l, r))
    noException should be thrownBy {
      ProcNormalizeMatcher.normalizeMatch[Coeval](hugePPar, inputs).value
    }
  }

  "PContr" should "Handle a basic contract" in {
    /*  new add in {
          contract add(ret, @x, @y) = {
            ret!(x + y)
          }
        }
        // new is simulated by bindings.
     */
    val listBindings = new ListName()
    listBindings.add(new NameVar("ret"))
    listBindings.add(new NameQuote(new PVar(new ProcVarVar("x"))))
    listBindings.add(new NameQuote(new PVar(new ProcVarVar("y"))))
    val bindCount = 3
    val listSend  = new ListProc()
    listSend.add(new PAdd(new PVar(new ProcVarVar("x")), new PVar(new ProcVarVar("y"))))
    val pBasicContr = new PContr(
      new NameVar("add"),
      listBindings,
      new NameRemainderEmpty(),
      new PSend(new NameVar("ret"), new SendSingle(), listSend)
    )
    val boundInputs =
      inputs.copy(boundMapChain = inputs.boundMapChain.put(("add", NameSort, SourcePosition(0, 0))))

    val result = ProcNormalizeMatcher.normalizeMatch[Coeval](pBasicContr, boundInputs).value
    result.par should be(
      inputs.par.prepend(
        Receive(
          List(
            ReceiveBind(
              List(EVar(FreeVar(0)), EVar(FreeVar(1)), EVar(FreeVar(2))),
              EVar(BoundVar(0)),
              freeCount = 3
            )
          ),
          Send(
            EVar(BoundVar(2)),
            List[Par](EPlus(EVar(BoundVar(1)), EVar(BoundVar(0)))),
            false,
            BitSet(0, 1, 2)
          ),
          true, // persistent
          peek = false,
          bindCount,
          BitSet(0)
        )
      )
    )
    result.freeMap should be(inputs.freeMap)
  }

  "PContr" should "Not count ground values in the formals towards the bind count" in {
    /*  new ret5 in {
          contract ret5(ret, @5) = {
            ret!(5)
          }
        }
        // new is simulated by bindings.
     */
    val listBindings = new ListName()
    listBindings.add(new NameVar("ret"))
    listBindings.add(new NameQuote(new PGround(new GroundInt("5"))))
    val bindCount = 1
    val listSend  = new ListProc()
    listSend.add(new PGround(new GroundInt("5")))
    val pBasicContr = new PContr(
      new NameVar("ret5"),
      listBindings,
      new NameRemainderEmpty(),
      new PSend(new NameVar("ret"), new SendSingle(), listSend)
    )
    val boundInputs = inputs.copy(
      boundMapChain = inputs.boundMapChain.put(("ret5", NameSort, SourcePosition(0, 0)))
    )

    val result = ProcNormalizeMatcher.normalizeMatch[Coeval](pBasicContr, boundInputs).value
    result.par should be(
      inputs.par.prepend(
        Receive(
          List(
            ReceiveBind(
              List(EVar(FreeVar(0)), Par().copy(exprs = List(GInt(5)))),
              EVar(BoundVar(0)),
              freeCount = 1
            )
          ),
          Send(EVar(BoundVar(0)), List(Par().copy(exprs = List(GInt(5)))), false, BitSet(0)),
          true, // persistent
          peek = false,
          bindCount,
          BitSet(0)
        )
      )
    )
    result.freeMap should be(inputs.freeMap)
  }

  "PInput" should "Handle a simple receive" in {
    // for ( x, y <- @Nil ) { x!(*y) }
    val listBindings = new ListName()
    listBindings.add(new NameVar("x"))
    listBindings.add(new NameVar("y"))
    val listLinearBinds = new ListLinearBind()
    listLinearBinds.add(
      new LinearBindImpl(
        listBindings,
        new NameRemainderEmpty(),
        new SimpleSource(new NameQuote(new PNil()))
      )
    )
    val linearSimple = new LinearSimple(listLinearBinds)
    val receipt      = new ReceiptLinear(linearSimple)
    val listReceipt  = new ListReceipt()
    listReceipt.add(receipt)

    val listSend = new ListProc()
    listSend.add(new PEval(new NameVar("y")))
    val body       = new PSend(new NameVar("x"), new SendSingle(), listSend)
    val basicInput = new PInput(listReceipt, body)
    val bindCount  = 2

    val result = ProcNormalizeMatcher.normalizeMatch[Coeval](basicInput, inputs).value
    result.par should be(
      inputs.par.prepend(
        Receive(
          List(
            ReceiveBind(List(EVar(FreeVar(0)), EVar(FreeVar(1))), Par(), freeCount = 2)
          ),
          Send(
            EVar(BoundVar(1)),
            List[Par](EVar(BoundVar(0))),
            false,
            BitSet(0, 1)
          ),
          persistent = false,
          peek = false,
          bindCount,
          BitSet(),
          connectiveUsed = false
        )
      )
    )
    result.freeMap should be(inputs.freeMap)
  }

  it should "handle peek" in {
    (for {
      basicInput <- Compiler[Coeval].sourceToAST("""for ( x, y <<- @Nil ) { x!(*y) }""")
      result     <- ProcNormalizeMatcher.normalizeMatch[Coeval](basicInput, inputs)
    } yield result.par.receives.head.peek shouldBe true).value()
  }

  "PInput" should "Handle a more complicated receive" in {
    // for ( (x1, @y1) <- @Nil  & (x2, @y2) <- @1) { x1!(y2) | x2!(y1) }
    val listBindings1 = new ListName()
    listBindings1.add(new NameVar("x1"))
    listBindings1.add(new NameQuote(new PVar(new ProcVarVar("y1"))))
    val listBindings2 = new ListName()
    listBindings2.add(new NameVar("x2"))
    listBindings2.add(new NameQuote(new PVar(new ProcVarVar("y2"))))
    val listLinearBinds = new ListLinearBind()
    listLinearBinds.add(
      new LinearBindImpl(
        listBindings1,
        new NameRemainderEmpty(),
        new SimpleSource(new NameQuote(new PNil()))
      )
    )
    listLinearBinds.add(
      new LinearBindImpl(
        listBindings2,
        new NameRemainderEmpty(),
        new SimpleSource(new NameQuote(new PGround(new GroundInt("1"))))
      )
    )
    val linearSimple = new LinearSimple(listLinearBinds)
    val receipt      = new ReceiptLinear(linearSimple)
    val listReceipt  = new ListReceipt()
    listReceipt.add(receipt)

    val listSend1 = new ListProc()
    listSend1.add(new PVar(new ProcVarVar("y2")))
    val listSend2 = new ListProc()
    listSend2.add(new PVar(new ProcVarVar("y1")))
    val body = new PPar(
      new PSend(new NameVar("x1"), new SendSingle(), listSend1),
      new PSend(new NameVar("x2"), new SendSingle(), listSend2)
    )
    val pInput    = new PInput(listReceipt, body)
    val bindCount = 4

    val result = ProcNormalizeMatcher.normalizeMatch[Coeval](pInput, inputs).value
    result.par should be(
      inputs.par.prepend(
        Receive(
          List(
            ReceiveBind(
              List(EVar(FreeVar(0)), EVar(FreeVar(1))),
              Par(),
              freeCount = 2
            ),
            ReceiveBind(
              List(EVar(FreeVar(0)), EVar(FreeVar(1))),
              GInt(1),
              freeCount = 2
            )
          ),
          Par().copy(
            sends = List(
              Send(EVar(BoundVar(1)), List[Par](EVar(BoundVar(2))), false, BitSet(1, 2)),
              Send(EVar(BoundVar(3)), List[Par](EVar(BoundVar(0))), false, BitSet(0, 3))
            ),
            locallyFree = BitSet(0, 1, 2, 3)
          ),
          persistent = false,
          peek = false,
          bindCount,
          BitSet(),
          connectiveUsed = false
        )
      )
    )
    result.freeMap should be(inputs.freeMap)
  }

  "PInput" should "bind whole list to the list remainder" in {
    // for (@[...a] <- @0) { … }
    val listBindings = new ListName()
    listBindings.add(
      new NameQuote(
        new PCollect(new CollectList(new ListProc(), new ProcRemainderVar(new ProcVarVar("a"))))
      )
    )
    val listLinearBinds = new ListLinearBind()
    listLinearBinds.add(
      new LinearBindImpl(
        listBindings,
        new NameRemainderEmpty(),
        new SimpleSource(new NameQuote(new PNil()))
      )
    )
    val linearSimple = new LinearSimple(listLinearBinds)
    val receipt      = new ReceiptLinear(linearSimple)
    val listReceipt  = new ListReceipt()
    listReceipt.add(receipt)
    val bindCount = 1
    val pInput    = new PInput(listReceipt, new PNil())
    val result    = ProcNormalizeMatcher.normalizeMatch[Coeval](pInput, inputs).value
    val expected = inputs.par.prepend(
      Receive(
        List(
          ReceiveBind(
            List(
              Par(
                connectiveUsed = true,
                exprs = List(EList(connectiveUsed = true, remainder = Some(FreeVar(0))))
              )
            ),
            Par(),
            freeCount = 1
          )
        ),
        Par(),
        persistent = false,
        peek = false,
        bindCount,
        BitSet(),
        connectiveUsed = false
      )
    )

    result.par should be(expected)
  }

  "PInput" should "Fail if a free variable is used in 2 different receives" in {
    // for ( (x1, @y1) <- @Nil  & (x2, @y1) <- @1) { Nil }
    val listBindings1 = new ListName()
    listBindings1.add(new NameVar("x1"))
    listBindings1.add(new NameQuote(new PVar(new ProcVarVar("y1"))))
    val listBindings2 = new ListName()
    listBindings2.add(new NameVar("x2"))
    listBindings2.add(new NameQuote(new PVar(new ProcVarVar("y1"))))
    val listLinearBinds = new ListLinearBind()
    listLinearBinds.add(
      new LinearBindImpl(
        listBindings1,
        new NameRemainderEmpty(),
        new SimpleSource(new NameQuote(new PNil()))
      )
    )
    listLinearBinds.add(
      new LinearBindImpl(
        listBindings2,
        new NameRemainderEmpty(),
        new SimpleSource(new NameQuote(new PGround(new GroundInt("1"))))
      )
    )
    val linearSimple = new LinearSimple(listLinearBinds)
    val receipt      = new ReceiptLinear(linearSimple)
    val listReceipt  = new ListReceipt()
    listReceipt.add(receipt)

    val body   = new PNil()
    val pInput = new PInput(listReceipt, body)

    an[UnexpectedReuseOfNameContextFree] should be thrownBy {
      ProcNormalizeMatcher.normalizeMatch[Coeval](pInput, inputs).value
    }
  }

  "PInput" should "not compile when connectives are used in the channel" in {
    an[TopLevelLogicalConnectivesNotAllowedError] should be thrownBy {
      Compiler[Coeval]
        .sourceToADT("""for(x <- @{Nil \/ Nil}){ Nil }""")
        .value()
    }

    an[TopLevelLogicalConnectivesNotAllowedError] should be thrownBy {
      Compiler[Coeval]
        .sourceToADT("""for(x <- @{Nil /\ Nil}){ Nil }""")
        .value()
    }

    an[TopLevelLogicalConnectivesNotAllowedError] should be thrownBy {
      Compiler[Coeval]
        .sourceToADT("""for(x <- @{~Nil}){ Nil }""")
        .value()
    }
  }

  "PInput" should "not compile when connectives are the top level expression in the body" in {
    an[TopLevelLogicalConnectivesNotAllowedError] should be thrownBy {
      Compiler[Coeval]
        .sourceToADT("""for(x <- @Nil){ 1 /\ 2 }""")
        .value()
    }

    an[TopLevelLogicalConnectivesNotAllowedError] should be thrownBy {
      Compiler[Coeval]
        .sourceToADT("""for(x <- @Nil){ 1 \/ 2 }""")
        .value()
    }

    an[TopLevelLogicalConnectivesNotAllowedError] should be thrownBy {
      Compiler[Coeval]
        .sourceToADT("""for(x <- @Nil){ ~1 }""")
        .value()
    }
  }

  "PInput" should "not compile when logical OR or NOT is used in the pattern of the receive" in {
    an[PatternReceiveError] should be thrownBy {
      Compiler[Coeval]
        .sourceToADT("""new x in { for(@{Nil \/ Nil} <- x) { Nil } }""")
        .value()
    }

    an[PatternReceiveError] should be thrownBy {
      Compiler[Coeval]
        .sourceToADT("""new x in { for(@{~Nil} <- x) { Nil } }""")
        .value()
    }
  }

  "PInput" should "compile when logical AND is used in the pattern of the receive" in {
    noException should be thrownBy {
      Compiler[Coeval]
        .sourceToADT("""new x in { for(@{Nil /\ Nil} <- x) { Nil } }""")
        .value()
    }
  }

  "PContr" should "not compile when logical OR or NOT is used in the pattern of the receive" in {
    an[PatternReceiveError] should be thrownBy {
      Compiler[Coeval]
        .sourceToADT("""new x in { contract x(@{ y /\ {Nil \/ Nil}}) = { Nil } }""")
        .value()
    }

    an[PatternReceiveError] should be thrownBy {
      Compiler[Coeval]
        .sourceToADT("""new x in { contract x(@{ y /\ ~Nil}) = { Nil } }""")
        .value()
    }
  }

  "PContr" should "compile when logical AND is used in the pattern of the receive" in {
    noException should be thrownBy {
      Compiler[Coeval]
        .sourceToADT("""new x in { contract x(@{ y /\ {Nil /\ Nil}}) = { Nil } }""")
        .value()
    }
  }

  "PNew" should "Bind new variables" in {
    val listNameDecl = new ListNameDecl()
    listNameDecl.add(new NameDeclSimpl("x"))
    listNameDecl.add(new NameDeclSimpl("y"))
    listNameDecl.add(new NameDeclSimpl("z"))
    val listData1 = new ListProc()
    listData1.add(new PGround(new GroundInt("7")))
    val listData2 = new ListProc()
    listData2.add(new PGround(new GroundInt("8")))
    val listData3 = new ListProc()
    listData3.add(new PGround(new GroundInt("9")))

    val pNew = new PNew(
      listNameDecl,
      new PPar(
        new PPar(
          new PSend(new NameVar("x"), new SendSingle(), listData1),
          new PSend(new NameVar("y"), new SendSingle(), listData2)
        ),
        new PSend(new NameVar("z"), new SendSingle(), listData3)
      )
    )

    val result = ProcNormalizeMatcher.normalizeMatch[Coeval](pNew, inputs).value
    result.par should be(
      inputs.par.prepend(
        New(
          bindCount = 3,
          p = Send(EVar(BoundVar(2)), List[Par](GInt(7)), false, BitSet(2))
            .prepend(Send(EVar(BoundVar(1)), List[Par](GInt(8)), false, BitSet(1)))
            .prepend(Send(EVar(BoundVar(0)), List[Par](GInt(9)), false, BitSet(0))),
          uri = Vector.empty,
          locallyFree = BitSet()
        )
      )
    )
    result.freeMap should be(inputs.freeMap)
  }

  "PNew" should "Sort URI's and place them at the end" in {
    val listNameDecl = new ListNameDecl()
    listNameDecl.add(new NameDeclSimpl("x"))
    listNameDecl.add(new NameDeclSimpl("y"))
    listNameDecl.add(new NameDeclUrn("r", "`rho:registry`"))
    listNameDecl.add(new NameDeclUrn("out", "`rho:stdout`"))
    listNameDecl.add(new NameDeclSimpl("z"))
    val listData1 = new ListProc()
    listData1.add(new PGround(new GroundInt("7")))
    val listData2 = new ListProc()
    listData2.add(new PGround(new GroundInt("8")))
    val listData3 = new ListProc()
    listData3.add(new PGround(new GroundInt("9")))
    val listData4 = new ListProc()
    listData4.add(new PGround(new GroundInt("10")))
    val listData5 = new ListProc()
    listData5.add(new PGround(new GroundInt("11")))

    val pNew = new PNew(
      listNameDecl,
      new PPar(
        new PPar(
          new PPar(
            new PPar(
              new PSend(new NameVar("x"), new SendSingle(), listData1),
              new PSend(new NameVar("y"), new SendSingle(), listData2)
            ),
            new PSend(new NameVar("r"), new SendSingle(), listData3)
          ),
          new PSend(new NameVar("out"), new SendSingle(), listData4)
        ),
        new PSend(new NameVar("z"), new SendSingle(), listData5)
      )
    )

    val result = ProcNormalizeMatcher.normalizeMatch[Coeval](pNew, inputs).value
    result.par should be(
      inputs.par.prepend(
        New(
          bindCount = 5,
          p = Send(EVar(BoundVar(4)), List[Par](GInt(7)), false, BitSet(4))
            .prepend(Send(EVar(BoundVar(3)), List[Par](GInt(8)), false, BitSet(3)))
            .prepend(Send(EVar(BoundVar(1)), List[Par](GInt(9)), false, BitSet(1)))
            .prepend(Send(EVar(BoundVar(0)), List[Par](GInt(10)), false, BitSet(0)))
            .prepend(Send(EVar(BoundVar(2)), List[Par](GInt(11)), false, BitSet(2))),
          uri = Vector("rho:registry", "rho:stdout"),
          locallyFree = BitSet()
        )
      )
    )
    result.par.news(0).p.sends.map(x => x.locallyFree.get) should be(
      List(BitSet(2), BitSet(0), BitSet(1), BitSet(3), BitSet(4))
    )
    result.par.news(0).p.locallyFree.get should be(BitSet(0, 1, 2, 3, 4))
  }

  "PMatch" should "Handle a match inside a for comprehension" in {
    // for (@x <- @Nil) { match x { case 42 => Nil ; case y => Nil } | @Nil!(47)
    val listBindings = new ListName()
    listBindings.add(new NameQuote(new PVar(new ProcVarVar("x"))))
    val listLinearBinds = new ListLinearBind()
    listLinearBinds.add(
      new LinearBindImpl(
        listBindings,
        new NameRemainderEmpty(),
        new SimpleSource(new NameQuote(new PNil()))
      )
    )
    val linearSimple = new LinearSimple(listLinearBinds)
    val receipt      = new ReceiptLinear(linearSimple)
    val listReceipt  = new ListReceipt()
    listReceipt.add(receipt)

    val listCases = new ListCase()
    listCases.add(new CaseImpl(new PGround(new GroundInt("42")), new PNil()))
    listCases.add(new CaseImpl(new PVar(new ProcVarVar("y")), new PNil()))
    val body = new PMatch(new PVar(new ProcVarVar("x")), listCases)

    val listData = new ListProc()
    listData.add(new PGround(new GroundInt("47")))
    val send47OnNil = new PSend(new NameQuote(new PNil()), new SendSingle(), listData)

    val pPar = new PPar(
      new PInput(listReceipt, body),
      send47OnNil
    )
    val result    = ProcNormalizeMatcher.normalizeMatch[Coeval](pPar, inputs).value
    val bindCount = 1

    val expectedResult =
      inputs.par
        .prepend(Send(Par(), List[Par](GInt(47)), false, BitSet()))
        .prepend(
          Receive(
            List(ReceiveBind(List(EVar(FreeVar(0))), Par(), freeCount = 1)),
            Match(
              EVar(BoundVar(0)),
              List(MatchCase(GInt(42), Par()), MatchCase(EVar(FreeVar(0)), Par(), freeCount = 1)),
              BitSet(0)
            ),
            persistent = false,
            peek = false,
            bindCount,
            BitSet(),
            connectiveUsed = false
          )
        )

    result.par should be(expectedResult)
    result.freeMap should be(inputs.freeMap)
  }

  "PMatch" should "have a freeCount of 1 if the case contains a wildcard and a free variable." in {
    val listCases = new ListCase()
    val listProc  = new ListProc()
    listProc.add(new PVar(new ProcVarVar("y")))
    listProc.add(new PVar(new ProcVarWildcard()))
    listCases.add(
      new CaseImpl(new PCollect(new CollectList(listProc, new ProcRemainderEmpty())), new PNil())
    )
    listCases.add(new CaseImpl(new PVar(new ProcVarWildcard()), new PNil()))
    val pMatch = new PMatch(new PVar(new ProcVarVar("x")), listCases)

    val boundInputs =
      inputs.copy(boundMapChain = inputs.boundMapChain.put(("x", ProcSort, SourcePosition(0, 0))))
    val result = ProcNormalizeMatcher.normalizeMatch[Coeval](pMatch, boundInputs).value

    val expectedResult =
      inputs.par.prepend(
        Match(
          EVar(BoundVar(0)),
          List(
            MatchCase(
              EList(Seq[Par](EVar(FreeVar(0)), EVar(Wildcard(Var.WildcardMsg()))), BitSet(), true),
              Par(),
              freeCount = 1
            ),
            MatchCase(EVar(Wildcard(Var.WildcardMsg())), Par())
          ),
          BitSet(0),
          false
        )
      )
    result.par should be(expectedResult)
    result.par.matches.head.cases.head.freeCount should be(1)
  }

  "PIf" should "Desugar to match with true/false cases" in {
    // if (true) { @Nil!(47) }
    val condition = new PGround(new GroundBool(new BoolTrue()))
    val listSend  = new ListProc()
    listSend.add(new PGround(new GroundInt("47")))
    val body       = new PSend(new NameQuote(new PNil()), new SendSingle(), listSend)
    val basicInput = new PIf(condition, body)

    val result = ProcNormalizeMatcher.normalizeMatch[Coeval](basicInput, inputs).value
    result.par should be(
      inputs.par.prepend(
        Match(
          GBool(true),
          List(
            MatchCase(GBool(true), Send(Par(), List[Par](GInt(47)), false, BitSet())),
            MatchCase(GBool(false), Par())
            // TODO: Fill in type error case
          ),
          BitSet()
        )
      )
    )
    result.freeMap should be(inputs.freeMap)
  }

  it should "not mix Par from the input with normalized one (RHOL-444)" in {
    val rightProc =
      new PIf(new PGround(new GroundBool(new BoolTrue())), new PGround(new GroundInt("10")))

    val input  = inputs.copy(par = Par(exprs = Seq(GInt(7))))
    val result = ProcNormalizeMatcher.normalizeMatch[Coeval](rightProc, input).value

    result.freeMap should be(inputs.freeMap)
    result.par should be(
      inputs.par.copy(
        matches = Seq(
          Match(GBool(true), Seq(MatchCase(GBool(true), GInt(10)), MatchCase(GBool(false), Par())))
        ),
        exprs = Seq(GInt(7))
      )
    )
  }

  "PIfElse" should "Handle a more complicated if statement with an else clause" in {
    // if (47 == 47) { new x in { x!(47) } } else { new y in { y!(47) } }
    val condition = new PEq(new PGround(new GroundInt("47")), new PGround(new GroundInt("47")))
    val xNameDecl = new ListNameDecl()
    xNameDecl.add(new NameDeclSimpl("x"))
    val xSendData = new ListProc()
    xSendData.add(new PGround(new GroundInt("47")))
    val pNewIf = new PNew(
      xNameDecl,
      new PSend(new NameVar("x"), new SendSingle(), xSendData)
    )
    val yNameDecl = new ListNameDecl()
    yNameDecl.add(new NameDeclSimpl("y"))
    val ySendData = new ListProc()
    ySendData.add(new PGround(new GroundInt("47")))
    val pNewElse = new PNew(
      yNameDecl,
      new PSend(new NameVar("y"), new SendSingle(), ySendData)
    )
    val basicInput = new PIfElse(condition, pNewIf, pNewElse)

    val result = ProcNormalizeMatcher.normalizeMatch[Coeval](basicInput, inputs).value
    result.par should be(
      inputs.par.prepend(
        Match(
          EEq(GInt(47), GInt(47)),
          List(
            MatchCase(
              GBool(true),
              New(
                bindCount = 1,
                p = Send(EVar(BoundVar(0)), List[Par](GInt(47)), false, BitSet(0)),
                uri = Vector.empty,
                locallyFree = BitSet()
              )
            ),
            MatchCase(
              GBool(false),
              New(
                bindCount = 1,
                p = Send(EVar(BoundVar(0)), List[Par](GInt(47)), false, BitSet(0)),
                uri = Vector.empty,
                locallyFree = BitSet()
              )
            )
            // TODO: Fill in type error case
          ),
          BitSet()
        )
      )
    )
    result.freeMap should be(inputs.freeMap)
  }
  "PMatch" should "Fail if a free variable is used twice in the target" in {
    // match 47 { case (y | y) => Nil }
    val listCases = new ListCase()
    listCases.add(
      new CaseImpl(
        new PPar(new PVar(new ProcVarVar("y")), new PVar(new ProcVarVar("y"))),
        new PNil()
      )
    )
    val pMatch = new PMatch(new PGround(new GroundInt("47")), listCases)

    an[UnexpectedReuseOfProcContextFree] should be thrownBy {
      ProcNormalizeMatcher.normalizeMatch[Coeval](pMatch, inputs).value
    }
  }
  "PMatch" should "Handle a match inside a for pattern" in {
    // for (@{match {x | y} { 47 => Nil }} <- @Nil) { Nil }

    val listCases = new ListCase()
    listCases.add(new CaseImpl(new PGround(new GroundInt("47")), new PNil()))
    val pMatch =
      new PMatch(new PPar(new PVar(new ProcVarVar("x")), new PVar(new ProcVarVar("y"))), listCases)
    val listBindings = new ListName()
    listBindings.add(new NameQuote(pMatch))
    val listLinearBinds = new ListLinearBind()
    listLinearBinds.add(
      new LinearBindImpl(
        listBindings,
        new NameRemainderEmpty(),
        new SimpleSource(new NameQuote(new PNil()))
      )
    )
    val linearSimple = new LinearSimple(listLinearBinds)
    val receipt      = new ReceiptLinear(linearSimple)
    val listReceipt  = new ListReceipt()
    listReceipt.add(receipt)
    val input = new PInput(listReceipt, new PNil())

    val result    = ProcNormalizeMatcher.normalizeMatch[Coeval](input, inputs).value
    val bindCount = 2

    val matchTarget = EVar(FreeVar(1)).prepend(EVar(FreeVar(0)), 0)
    val expectedResult =
      inputs.par.prepend(
        Receive(
          List(
            ReceiveBind(
              List(
                Match(matchTarget, List(MatchCase(GInt(47), Par())), connectiveUsed = true)
              ),
              Par(),
              freeCount = 2
            )
          ),
          Par(),
          persistent = false,
          peek = false,
          bindCount,
          connectiveUsed = false
        )
      )

    result.par should be(expectedResult)
    result.freeMap should be(inputs.freeMap)
  }

  "PMethod" should "produce proper method call" in {
    val methods = List("nth", "toByteArray")
    def test(methodName: String): Boolean = {
      val listProc = new ListProc()
      listProc.add(new PGround(new GroundInt("0")))
      val target  = new PVar(new ProcVarVar("x"))
      val pMethod = new PMethod(target, methodName, listProc)
      val boundInputs =
        inputs.copy(boundMapChain = inputs.boundMapChain.put(("x", ProcSort, SourcePosition(0, 0))))
      val result = ProcNormalizeMatcher.normalizeMatch[Coeval](pMethod, boundInputs).value
      val expectedResult =
        inputs.par
          .prepend(EMethod(methodName, EVar(BoundVar(0)), List(GInt(0)), BitSet(0), false), 0)
      result.par === expectedResult && result.freeMap === inputs.freeMap
    }
    methods.forall(m => test(m))

  }

  "PBundle" should "normalize terms inside a bundle" in {
    val pbundle = new PBundle(new BundleReadWrite(), new PVar(new ProcVarVar("x")))
    val boundInputs =
      inputs.copy(boundMapChain = inputs.boundMapChain.put(("x", ProcSort, SourcePosition(0, 0))))

    val result = ProcNormalizeMatcher.normalizeMatch[Coeval](pbundle, boundInputs).value

    val expectedResult =
      inputs.par
        .withBundles(List(Bundle(EVar(BoundVar(0)), writeFlag = true, readFlag = true)))
        .withLocallyFree(BitSet(0))

    result.par should be(expectedResult)
    result.freeMap should be(inputs.freeMap)
  }

  /** Example:
    * bundle { _ | x }
    */
  it should "throw an error when wildcard or free variable is found inside body of bundle" in {
    val pbundle =
      new PBundle(
        new BundleReadWrite(),
        new PPar(new PVar(new ProcVarWildcard()), new PVar(new ProcVarVar("x")))
      )

    an[UnexpectedBundleContent] should be thrownBy
      ProcNormalizeMatcher.normalizeMatch[Coeval](pbundle, inputs).value
  }

  /** Example:
    * bundle { Uri }
    */
  it should "throw an error when connective is used at top level of body of bundle" in {
    val pbundle =
      new PBundle(
        new BundleReadWrite(),
        new PSimpleType(new SimpleTypeUri())
      )

    an[UnexpectedBundleContent] should be thrownBy
      ProcNormalizeMatcher.normalizeMatch[Coeval](pbundle, inputs).value
  }

  /** Example:
    * bundle { @Nil!(Uri) }
    */
  it should "not throw an error when connective is used outside of top level of body of bundle" in {
    val listProc = new ListProc()
    listProc.add(new PSimpleType(new SimpleTypeUri()))

    val pbundle =
      new PBundle(
        new BundleReadWrite(),
        new PSend(new NameQuote(new PNil()), new SendSingle(), listProc)
      )

    noException should be thrownBy
      ProcNormalizeMatcher.normalizeMatch[Coeval](pbundle, inputs).value
  }

  it should "interpret bundle polarization" in {
    def newBundle(proc: Proc)(readOnly: Boolean, writeOnly: Boolean): PBundle =
      (readOnly, writeOnly) match {
        case (true, true)   => new PBundle(new BundleReadWrite(), proc)
        case (true, false)  => new PBundle(new BundleRead(), proc)
        case (false, true)  => new PBundle(new BundleWrite(), proc)
        case (false, false) => new PBundle(new BundleEquiv(), proc)
      }

    val proc = new PVar(new ProcVarVar("x"))
    val boundInputs =
      inputs.copy(boundMapChain = inputs.boundMapChain.put(("x", ProcSort, SourcePosition(0, 0))))
    def expectedResults(writeFlag: Boolean, readFlag: Boolean) =
      inputs.par
        .withBundles(List(Bundle(EVar(BoundVar(0)), writeFlag = writeFlag, readFlag = readFlag)))
        .withLocallyFree(BitSet(0))

    def test(readOnly: Boolean, writeOnly: Boolean) =
      withClue(s"for bundle with flags readOnly=$readOnly writeOnly=$writeOnly") {
        val result = ProcNormalizeMatcher
          .normalizeMatch[Coeval](p = newBundle(proc)(readOnly, writeOnly), input = boundInputs)
          .value

        assert(result.par === expectedResults(writeOnly, readOnly))
        assert(result.freeMap === inputs.freeMap)
      }

    test(readOnly = true, writeOnly = true)
    test(readOnly = true, writeOnly = false)
    test(readOnly = false, writeOnly = true)
    test(readOnly = false, writeOnly = false)
  }

  it should "collapse nested bundles merging their polarizations" in {
    val proc         = new PVar(new ProcVarVar("x"))
    val nestedBundle = new PBundle(new BundleReadWrite(), new PBundle(new BundleRead(), proc))
    val boundInputs =
      inputs.copy(boundMapChain = inputs.boundMapChain.put(("x", ProcSort, SourcePosition(0, 0))))

    val expectedResults = inputs.par
      .withBundles(List(Bundle(EVar(BoundVar(0)), writeFlag = false, readFlag = true)))
      .withLocallyFree(BitSet(0))

    val result =
      ProcNormalizeMatcher.normalizeMatch[Coeval](nestedBundle, input = boundInputs).value

    assert(result.par === expectedResults)
    assert(result.freeMap === boundInputs.freeMap)
  }

  "PNegation" should "delegate, but not count any free variables inside" in {
    val proc = new PNegation(new PVar(new ProcVarVar("x")))

    val result = ProcNormalizeMatcher.normalizeMatch[Coeval](proc, inputs).value
    val expectedResult = inputs.par
      .addConnectives(Connective(ConnNotBody(EVar(FreeVar(0)))))
      .withConnectiveUsed(true)

    result.par should be(expectedResult)
    result.freeMap.levelBindings should be(inputs.freeMap.levelBindings)
    result.freeMap.nextLevel should be(inputs.freeMap.nextLevel)
  }

  "PConjunction" should "delegate, and count any free variables inside" in {
    val proc = new PConjunction(new PVar(new ProcVarVar("x")), new PVar(new ProcVarVar("y")))

    val result = ProcNormalizeMatcher.normalizeMatch[Coeval](proc, inputs).value
    val expectedResult = inputs.par
      .addConnectives(
        Connective(ConnAndBody(ConnectiveBody(Vector(EVar(FreeVar(0)), EVar(FreeVar(1))))))
      )
      .withConnectiveUsed(true)

    result.par should be(expectedResult)

    val expectedFree = inputs.freeMap.put(
      List(("x", ProcSort, SourcePosition(0, 0)), ("y", ProcSort, SourcePosition(0, 0)))
    )

    result.freeMap.levelBindings should be(expectedFree.levelBindings)
    result.freeMap.nextLevel should be(expectedFree.nextLevel)
  }

  "PDisjunction" should "delegate, but not count any free variables inside" in {
    val proc = new PDisjunction(new PVar(new ProcVarVar("x")), new PVar(new ProcVarVar("x")))

    val result = ProcNormalizeMatcher.normalizeMatch[Coeval](proc, inputs).value
    val expectedResult = inputs.par
      .addConnectives(
        Connective(ConnOrBody(ConnectiveBody(Vector(EVar(FreeVar(0)), EVar(FreeVar(0))))))
      )
      .withConnectiveUsed(true)

    result.par should be(expectedResult)
    result.freeMap.levelBindings should be(inputs.freeMap.levelBindings)
    result.freeMap.nextLevel should be(inputs.freeMap.nextLevel)
  }

  "PVarRef" should "do a deep lookup in a match case" in {
    // assuming `x` is bound
    // example: @7!(10) | for (@x <- @7) { … }
    // match 7 { =x => Nil }
    val boundInputs =
      inputs.copy(boundMapChain = inputs.boundMapChain.put(("x", ProcSort, SourcePosition(0, 0))))
    val listCases = new ListCase()
    listCases.add(new CaseImpl(new PVarRef(new VarRefKindProc(), "x"), new PNil()))
    val proc = new PMatch(new PGround(new GroundInt("7")), listCases)

    val result = ProcNormalizeMatcher.normalizeMatch[Coeval](proc, boundInputs).value
    val expectedResult = inputs.par
      .addMatches(
        Match(
          target = GInt(7),
          cases = List(
            MatchCase(
              pattern = Connective(VarRefBody(VarRef(0, 1))).withLocallyFree(BitSet(0)),
              source = Par()
            )
          ),
          locallyFree = BitSet(0)
        )
      )
      .withLocallyFree(BitSet(0))
    result.par should be(expectedResult)
    result.freeMap should be(inputs.freeMap)
    // Make sure that variable references in patterns are reflected
    result.par.locallyFree.get should be(BitSet(0))
  }

  it should "do a deep lookup in a receive case" in {
    // assuming `x` is bound:
    // example : new x in { … }
    // for(@{=*x} <- @Nil) { Nil }
    val boundInputs =
      inputs.copy(boundMapChain = inputs.boundMapChain.put(("x", NameSort, SourcePosition(0, 0))))
    val listBindings = new ListName()
    listBindings.add(new NameQuote(new PVarRef(new VarRefKindName(), "x")))
    val listLinearBinds = new ListLinearBind()
    listLinearBinds.add(
      new LinearBindImpl(
        listBindings,
        new NameRemainderEmpty(),
        new SimpleSource(new NameQuote(new PNil()))
      )
    )
    val linearSimple = new LinearSimple(listLinearBinds)
    val receipt      = new ReceiptLinear(linearSimple)
    val listReceipt  = new ListReceipt()
    listReceipt.add(receipt)

    val proc = new PInput(listReceipt, new PNil())

    // format: off
    val result = ProcNormalizeMatcher.normalizeMatch[Coeval](proc, boundInputs).value
    val expectedResult = inputs.par
      .addReceives(
        Receive(
          binds = List(
            ReceiveBind(
              patterns = List(
                Connective(VarRefBody(VarRef(0, 1))).withLocallyFree(BitSet(0))),
              source = Par())),
          body = Par(),
          persistent = false,
          bindCount = 0,
          locallyFree = BitSet(0)))
      .withLocallyFree(BitSet(0))
    result.par should be(expectedResult)
    result.freeMap should be(inputs.freeMap)
    result.par.locallyFree.get should be(BitSet(0))
    // format: on
  }

  "PSimpleType" should "result in a connective of the correct type" in {
    val procBool      = new PSimpleType(new SimpleTypeBool())
    val procInt       = new PSimpleType(new SimpleTypeInt())
    val procBigInt    = new PSimpleType(new SimpleTypeBigInt())
    val procString    = new PSimpleType(new SimpleTypeString())
    val procUri       = new PSimpleType(new SimpleTypeUri())
    val procByteArray = new PSimpleType(new SimpleTypeByteArray())

    val resultBool      = ProcNormalizeMatcher.normalizeMatch[Coeval](procBool, inputs).value
    val resultInt       = ProcNormalizeMatcher.normalizeMatch[Coeval](procInt, inputs).value
    val resultBigInt    = ProcNormalizeMatcher.normalizeMatch[Coeval](procBigInt, inputs).value
    val resultString    = ProcNormalizeMatcher.normalizeMatch[Coeval](procString, inputs).value
    val resultUri       = ProcNormalizeMatcher.normalizeMatch[Coeval](procUri, inputs).value
    val resultByteArray = ProcNormalizeMatcher.normalizeMatch[Coeval](procByteArray, inputs).value

    resultBool.par should be(
      Par(connectives = Seq(Connective(ConnBool(true))), connectiveUsed = true)
    )
    resultInt.par should be(
      Par(connectives = Seq(Connective(ConnInt(true))), connectiveUsed = true)
    )
    resultBigInt.par should be(
      Par(connectives = Seq(Connective(ConnBigInt(true))), connectiveUsed = true)
    )
    resultString.par should be(
      Par(connectives = Seq(Connective(ConnString(true))), connectiveUsed = true)
    )
    resultUri.par should be(
      Par(connectives = Seq(Connective(ConnUri(true))), connectiveUsed = true)
    )
    resultByteArray.par should be(
      Par(connectives = Seq(Connective(ConnByteArray(true))), connectiveUsed = true)
    )
  }

  "1 matches _" should "normalize correctly" in {
    val pMatches = new PMatches(new PGround(new GroundInt("1")), new PVar(new ProcVarWildcard()))

    val result = ProcNormalizeMatcher.normalizeMatch[Coeval](pMatches, inputs).value

    val expectedPar = inputs.par.prepend(EMatches(GInt(1), EVar(Wildcard(WildcardMsg()))), 0)

    result.par shouldBe expectedPar
    result.par.connectiveUsed should be(false)
  }

  "1 matches 2" should "normalize correctly" in {
    val pMatches = new PMatches(new PGround(new GroundInt("1")), new PGround(new GroundInt("2")))

    val result = ProcNormalizeMatcher.normalizeMatch[Coeval](pMatches, inputs).value

    val expectedPar = inputs.par.prepend(EMatches(GInt(1), GInt(2)), 0)

    result.par shouldBe expectedPar
    result.par.connectiveUsed should be(false)
  }

  "1 matches ~1" should "normalize with connectiveUsed=false" in {
    val pMatches =
      new PMatches(new PGround(new GroundInt("1")), new PNegation(new PGround(new GroundInt("1"))))
    val result = ProcNormalizeMatcher.normalizeMatch[Coeval](pMatches, inputs).value

    val expectedPar = inputs.par.prepend(EMatches(GInt(1), Connective(ConnNotBody(GInt(1)))), 0)

    result.par shouldBe expectedPar
    result.par.connectiveUsed should be(false)
  }

  "~1 matches 1" should "normalize with connectiveUsed=true" in {
    val pMatches =
      new PMatches(new PNegation(new PGround(new GroundInt("1"))), new PGround(new GroundInt("1")))
    val result = ProcNormalizeMatcher.normalizeMatch[Coeval](pMatches, inputs).value

    val expectedPar = inputs.par.prepend(EMatches(Connective(ConnNotBody(GInt(1))), GInt(1)), 0)

    result.par shouldBe expectedPar
    result.par.connectiveUsed should be(true)
  }

  "Patterns" should "compile when used not in the top level" in {
    def check(typ: String, position: String, pattern: String): Assertion =
      try {
        val rho = s"""
         new x in {
           for(@y <- x) {
             match y {
              $pattern => Nil
             }
           }
         }
       """
        Compiler[Coeval].sourceToADT(rho).value()
        assert(true)
      } catch {
        case e: Throwable =>
          fail(s"$typ in the $position $pattern should not throw errors: ${e.getMessage}")
      }

    check("wildcard", "send channel", "{_!(1)}")
    check("wildcard", "send data", "{@=*x!(_)}")
    check("wildcard", "send data", "{@Nil!(_)}")
    check("logical AND", "send data", "{@Nil!(1 /\\ 2)}")
    check("logical OR", "send data", "{@Nil!(1 \\/ 2)}")
    check("logical NOT", "send data", "{@Nil!(~1)}")
    check("logical AND", "send channel", "{@{Nil /\\ Nil}!(Nil)}")
    check("logical OR", "send channel", "{@{Nil \\/ Nil}!(Nil)}")
    check("logical NOT", "send channel", "{@{~Nil}!(Nil)}")
    check("wildcard", "receive pattern of the consume", "{for (_ <- x) { 1 }} ")
    check("wildcard", "body of the continuation", "{for (@1 <- x) { _ }} ")
    check("logical OR", "body of the continuation", "{for (@1 <- x) { 10 \\/ 20 }} ")
    check("logical AND", "body of the continuation", "{for(@1 <- x) { 10 /\\ 20 }} ")
    check("logical NOT", "body of the continuation", "{for(@1 <- x) { ~10 }} ")
    check("logical OR", "channel of the consume", "{for (@1 <- @{Nil /\\ Nil}) { Nil }} ")
    check("logical AND", "channel of the consume", "{for(@1 <- @{Nil \\/ Nil}) { Nil }} ")
    check("logical NOT", "channel of the consume", "{for(@1 <- @{~Nil}) { Nil }} ")
    check("wildcard", "channel of the consume", "{for(@1 <- _) { Nil }} ")
  }
}
