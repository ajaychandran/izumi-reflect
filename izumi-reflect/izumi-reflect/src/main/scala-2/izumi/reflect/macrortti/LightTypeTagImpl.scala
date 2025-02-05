/*
 * Copyright 2019-2020 Septimal Mind Ltd
 * Copyright 2020 John A. De Goes and the ZIO Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package izumi.reflect.macrortti

import izumi.reflect.internal.fundamentals.collections.IzCollections._
import izumi.reflect.internal.fundamentals.platform.assertions.IzAssert
import izumi.reflect.internal.fundamentals.platform.console.TrivialLogger
import izumi.reflect.internal.fundamentals.platform.console.TrivialLogger.Config
import izumi.reflect.internal.fundamentals.platform.strings.IzString._
import izumi.reflect.macrortti.LightTypeTagImpl.{Broken, globalCache}
import izumi.reflect.macrortti.LightTypeTagRef.RefinementDecl.TypeMember
import izumi.reflect.macrortti.LightTypeTagRef.SymName.{SymLiteral, SymTermName, SymTypeName}
import izumi.reflect.macrortti.LightTypeTagRef._
import izumi.reflect.{DebugProperties, ReflectionUtil}

import scala.annotation.tailrec
import scala.collection.mutable
import scala.language.reflectiveCalls
import scala.reflect.api.Universe

object LightTypeTagImpl {
  private lazy val globalCache = new java.util.WeakHashMap[Any, AbstractReference]

  /** caching is enabled by default for runtime light type tag creation */
  private[this] lazy val runtimeCacheEnabled: Boolean = {
    System
      .getProperty(DebugProperties.`izumi.reflect.rtti.cache.runtime`).asBoolean()
      .getOrElse(true)
  }

  /** Create a LightTypeTag at runtime for a reflected type */
  def makeLightTypeTag(u: Universe)(typeTag: u.Type): LightTypeTag = {
    ReflectionLock.synchronized {
      val logger = TrivialLogger.make[this.type](config = Config.console)
      new LightTypeTagImpl[u.type](u, withCache = runtimeCacheEnabled, logger).makeFullTagImpl(typeTag)
    }
  }

  private[this] object ReflectionLock

  private sealed trait Broken[T, S] {
    def intersectionComponents: Set[T]
    def decls: Set[S]
    def maybeUnbrokenType: Option[T]
  }

  private object Broken {

    final case class Single[T, S](t: T) extends Broken[T, S] {
      override def intersectionComponents: Set[T] = Set(t)
      override def decls: Set[S] = Set.empty
      override def maybeUnbrokenType: Option[T] = Some(t)
    }

    final case class Compound[T, S](intersectionComponents: Set[T], decls: Set[S]) extends Broken[T, S] {
      override def maybeUnbrokenType: Option[T] = None
    }

  }

}

final class LightTypeTagImpl[U <: Universe with Singleton](val u: U, withCache: Boolean, logger: TrivialLogger) {

  import u._

  @inline private[this] final val any = definitions.AnyTpe
  @inline private[this] final val obj = definitions.ObjectTpe
  @inline private[this] final val nothing = definitions.NothingTpe
  @inline private[this] final val ignored = Set(any, obj, nothing)

  def makeFullTagImpl(tpe0: Type): LightTypeTag = {
    val tpe = Dealias.fullNormDealias(tpe0)

    logger.log(s"Initial mainTpe=$tpe:${tpe.getClass} beforeDealias=$tpe0:${tpe0.getClass}")

    val lttRef = makeRef(tpe)
//    println(s"$tpe0 => $tpe => $lttRef")

    val allReferenceComponents = allTypeReferences(tpe)

    val fullDb = {
      val stableBases = makeAppliedBases(tpe, allReferenceComponents)
      val basesAsLambdas = makeLambdaOnlyBases(tpe, allReferenceComponents)
      val allBases = Seq(basesAsLambdas, stableBases)
      allBases.iterator.flatten.toMultimap.filterNot(_._2.isEmpty)
    }

    val unappliedDb = makeClassOnlyInheritanceDb(tpe, allReferenceComponents)

    LightTypeTag(lttRef, fullDb, unappliedDb)
  }

  // FIXME `allTypeReferences` & `makeRef` should be merged together,
  //  since they both pass over all visible components of a type in a similar way
  private[this] def allTypeReferences(mainTpe: Type): Set[Type] = {

    def extractComponents(tpeRaw0: Type, inh: mutable.HashSet[Type]): Unit = {
      val breakResult = UniRefinement.breakRefinement(tpeRaw0, squashHKTRefToPolyTypeResultType = false)
      val current = breakResult.maybeUnbrokenType
      inh ++= current

      val intersectionWithPreservedLambdas = breakResult.intersectionComponents
      val refinementDeclMembers = breakResult.decls.iterator.flatMap {
        sym =>
          if (sym.isMethod) {
            val m = sym.asMethod
            m.returnType :: m.paramLists.iterator.flatten.map(UniRefinement.typeOfParam).toList
          } else if (sym.isType) {
            List(UniRefinement.typeOfTypeMember(sym))
          } else Nil
      }
      val intersectionExpansionsArgsBounds: Iterator[Type] = intersectionWithPreservedLambdas.iterator.flatMap(collectArgsAndBounds)

      val nextToInspect = mutable
        .HashSet.newBuilder[Type]
        .++=(intersectionExpansionsArgsBounds ++ intersectionWithPreservedLambdas.iterator ++ refinementDeclMembers)
        .result()
        .diff(inh)

      nextToInspect.foreach(t => if (!inh(t) && !ignored(t)) extractComponents(t, inh))
    }

    def collectArgsAndBounds(tpeUnexpanded0: Type): Iterator[Type] = {
      // unexpanded: Either
      val tpeUnexpanded = Dealias.fullNormDealias(tpeUnexpanded0)
      // polyType: [L,R]Either[L,R]
      // polyTypeResult: Either[L,R] where L,R are trash symbols

      // we need to use tpe.etaExpand but 2.13 has a bug: https://github.com/scala/bug/issues/11673#
      // tpe.etaExpand.resultType.dealias.typeArgs.flatMap(_.dealias.resultType.typeSymbol.typeSignature match {

      def doExtract(t: Type) = {
        val tpePolyTypeResultType = Dealias.fullNormDealiasSquashHKTToPolyTypeResultType(t)

        tpePolyTypeResultType.typeArgs.flatMap {
          targ0 =>
            val targ = Dealias.fullNormDealias(targ0)
            val targSym = targ.typeSymbol
            targSym.typeSignature match {
              case t: TypeBoundsApi =>
                Seq(t.hi, t.lo).filterNot(ignored)
              case _ =>
                if (!targSym.isParameter) {
                  Seq(targ0)
                } else {
                  Seq.empty
                }
            }
        }
      }
      val tparamTypeBoundsAndTypeArgs = tpeUnexpanded match {
        case e: ExistentialTypeApi =>
          doExtract(e.underlying)
        case o =>
          doExtract(o)
      }

//      logger.log(
//        s"""Got tpeUnexpanded=$tpeUnexpanded:${tpeUnexpanded.getClass} args=${tpeUnexpanded.typeArgs} params=${tpeUnexpanded.typeParams}
//           |tpePolyTypeResultType=$tpePolyTypeResultType:${tpePolyTypeResultType.getClass} args=${tpePolyTypeResultType.typeArgs} params=${tpePolyTypeResultType.typeParams}
//           |tparamTypeBoundsAndTypeArgs=$tparamTypeBoundsAndTypeArgs
//           |""".stripMargin
//      )

      /**
        * Don't do this:
        *  Iterator.single(tpePolyTypeResultType) -- produces trash sybmold out of skolems
        *  tpePolyTypeResultType.typeArgs.iterator -- just redundant, included in `tparamTypeBoundsAndTypeArgs`
        */
      val out = Iterator.single(tpeUnexpanded) ++
        tpeUnexpanded.typeArgs.iterator ++
        tparamTypeBoundsAndTypeArgs.iterator
      out
    }

    val inh = mutable.HashSet[Type]()
    extractComponents(mainTpe, inh)
    logger.log(s"Extracted all type references for mainTpe=$mainTpe inh=${inh.iterator.map(t => (t, t.getClass.asInstanceOf[Class[Any]])).toMap.niceList()}")

    inh.toSet
  }

  private[this] def makeAppliedBases(mainTpe: Type, allReferenceComponents: Set[Type]): Set[(AbstractReference, AbstractReference)] = {

    val appliedBases = allReferenceComponents
      .filterNot(isHKTOrPolyType) // remove PolyTypes, only process applied types in this inspection
      .flatMap {
        component =>
          val appliedParents = tpeBases(component).filterNot(isHKTOrPolyType)
          val tparams = component.etaExpand.typeParams
          val lambdaParams = makeLambdaParams(None, tparams).toMap

          val componentRef = makeRef(component)

          appliedParents.map {
            parentTpe =>
              val parentRef = makeRefTop(parentTpe, terminalNames = lambdaParams, isLambdaOutput = lambdaParams.nonEmpty) match {
                case unapplied: Lambda =>
                  if (unapplied.someArgumentsReferenced) {
                    unapplied
                  } else {
                    logger.log(
                      s"Not all arguments referenced in l=$unapplied, parentTpe=$parentTpe(etaExpand:${parentTpe.etaExpand}), tparams=$tparams, mainTpe=$mainTpe(etaExpand:${mainTpe.etaExpand})"
                    )
                    unapplied.output
                  }
                case applied: AppliedReference =>
                  applied
              }
              (componentRef, parentRef)
          }
      }
    logger.log(s"Computed applied bases for tpe=$mainTpe appliedBases=${appliedBases.toMultimap.niceList()}")
    appliedBases
  }

  private[this] def makeLambdaOnlyBases(mainTpe: Type, allReferenceComponents: Set[Type]): Set[(AbstractReference, AbstractReference)] = {

    def processLambdasReturningRefinements(tpeRaw0: Type): Seq[(AbstractReference, AbstractReference)] = {
      val componentsOfPolyTypeResultType = UniRefinement.breakRefinement(tpeRaw0, squashHKTRefToPolyTypeResultType = true)

      IzAssert(
        assertion = {
          if (componentsOfPolyTypeResultType.maybeUnbrokenType.nonEmpty) {
            componentsOfPolyTypeResultType.intersectionComponents.exists(_.etaExpand.typeParams.nonEmpty)
          } else true
        },
        clue = {
          s"""Unexpected intersection contains a PolyType:
             |tpeRaw0 = $tpeRaw0
             |components = ${componentsOfPolyTypeResultType.intersectionComponents.niceList(prefix = "*")}
             |etaExpand = ${componentsOfPolyTypeResultType.intersectionComponents.map(_.etaExpand).niceList(prefix = "+")}
             |tparams = ${componentsOfPolyTypeResultType.intersectionComponents.map(_.etaExpand.typeParams).niceList(prefix = "-")}
             |""".stripMargin
        }
      )

      componentsOfPolyTypeResultType.intersectionComponents.toSeq.flatMap {
        component =>
          val componentAsPolyType = component.etaExpand
          val tparams = component.etaExpand.typeParams

          if (tparams.isEmpty) {
            Seq.empty
          } else {

            val lambdaParams = makeLambdaParams(None, tparams)
            val maybeComponentLambdaRef = makeRef(componentAsPolyType)
            IzAssert(maybeComponentLambdaRef.isInstanceOf[LightTypeTagRef.Lambda])
            val parentLambdas = makeLambdaParents(componentAsPolyType, lambdaParams)
            parentLambdas.map(maybeComponentLambdaRef -> _)
          }
      }
    }

    def makeLambdaParents(componentPolyType: Type, lambdaParams: List[(String, LambdaParameter)]): Seq[AbstractReference] = {
      val allBaseTypes = tpeBases(componentPolyType)

      val paramMap = lambdaParams.toMap

      allBaseTypes.map {
        parentTpe =>
          val reference = makeRefTop(parentTpe, terminalNames = paramMap, isLambdaOutput = false)
          reference match {
            case l: Lambda =>
              l
            case applied: AppliedReference =>
              val l = Lambda(lambdaParams.map(_._2), applied)
//              Some(l).filter(_.allArgumentsReferenced) // do not include non-lambda parents such as Product into lambda's inheritance tree
              // include ALL bases for lambdas (this should be more correct since lambda is a template for a full parameterized db after combine)
              if (l.someArgumentsReferenced) l else applied
          }
      }
    }

    val unappliedBases = allReferenceComponents.flatMap(processLambdasReturningRefinements)
    logger.log(s"Computed unapplied lambda bases for tpe=$mainTpe unappliedBases=${unappliedBases.toMultimap.niceList()}")
    unappliedBases
  }

  private[this] def makeClassOnlyInheritanceDb(mainTpe: Type, allReferenceComponents: Set[Type]): Map[NameReference, Set[NameReference]] = {
    val baseclassReferences = allReferenceComponents
      .iterator
      .flatMap {
        // squash all type lambdas and get the intersection of their results
        // because we don't care about type parameters at all in this inspection
        UniRefinement.breakRefinement(_, squashHKTRefToPolyTypeResultType = true).intersectionComponents
      }
      .flatMap {
        component =>
          val prefix = makePrefixReference(component)
          val componentRef = makeNameReference(component, component.typeSymbol, Boundaries.Empty, prefix)
          val appliedBases = tpeBases(component).filterNot(isHKTOrPolyType)
          appliedBases.map(componentRef -> makeRef(_))
      }

    val unparameterizedInheritanceData = baseclassReferences
      .toMultimap
      .map {
        case (t, parents) =>
          t -> parents
            .collect {
              case r: AppliedNamedReference =>
                r.asName
            }
            .filterNot {
              parent =>
                IzAssert(parent != t, parent -> t) // 2.11/2.12 fail this
                parent == t
            }
      }
      .filterNot(_._2.isEmpty)

    logger.log(s"Computed unparameterized inheritance data for tpe=$mainTpe unappliedBases=${unparameterizedInheritanceData.toMultimap.niceList()}")

    unparameterizedInheritanceData
  }

  private[this] def tpeBases(t0: Type): Seq[Type] = {
    // val tpef = Dealias.fullNormDealiasResultType(t0, squashHKTRefToPolyTypeResultType = false)
    // no PolyTypes passed to here [but actually we should preserve polyTypes]
    val tpe = Dealias.fullNormDealias(t0)
    tpe
      .baseClasses
      .iterator
      .map(tpe.baseType)
      .filterNot(ignored)
      .filterNot(if (isSingletonType(tpe)) _ => false else _.typeSymbol.fullName == tpe.typeSymbol.fullName)
      .filterNot(_ =:= tpe) // 2.11/2.12 fail this
      .toList
  }

  private[this] def makeRef(tpe: Type): AbstractReference = {
    if (withCache) {
      globalCache.synchronized(globalCache.get(tpe)) match {
        case null =>
          val ref = makeRefTop(tpe, terminalNames = Map.empty, isLambdaOutput = false)
          globalCache.synchronized(globalCache.put(tpe, ref))
          ref
        case ref =>
          ref
      }
    } else {
      makeRefTop(tpe, terminalNames = Map.empty, isLambdaOutput = false)
    }
  }

  private[this] def makeRefTop(tpe: Type, terminalNames: Map[String, LambdaParameter], isLambdaOutput: Boolean): AbstractReference = {
    this.makeRefImpl(0, nestedIn = Set(tpe), terminalNames, Set.empty)(tpe, isLambdaOutput)
  }

  private[this] def makeRefImpl(
    level: Int,
    nestedIn: Set[Type],
    terminalNames: Map[String, LambdaParameter],
    knownWildcards: Set[Symbol]
  )(tpe0: Type,
    isLambdaOutput: Boolean
  ): AbstractReference = {
    def makeBoundaries(t: Type): Boundaries = {
      t.typeSymbol.typeSignature match {
        case b: TypeBoundsApi =>
          if ((b.lo =:= nothing && b.hi =:= any) || (nestedIn.contains(b.lo) || nestedIn.contains(b.hi))) {
            Boundaries.Empty
          } else {
            Boundaries.Defined(makeRefSub(b.lo, Map.empty, Set.empty), makeRefSub(b.hi, Map.empty, Set.empty))
          }
        case _ =>
          Boundaries.Empty
      }
    }

    def makeRefSub(tpe: Type, stop: Map[String, LambdaParameter], knownWildcardsSub: Set[Symbol]): AbstractReference = {
      val allWildcards = knownWildcards ++ knownWildcardsSub
      if (allWildcards.contains(tpe.typeSymbol)) {
        WildcardReference(makeBoundaries(tpe0))
      } else {
        this.makeRefImpl(level + 1, nestedIn + tpe, terminalNames ++ stop, allWildcards)(tpe, isLambdaOutput = false)
      }
    }

    val thisLevel = logger.sub(level)

    def unpackLambda(t: TypeApi): AbstractReference = {
      val polyType = t.etaExpand
      val polyTypeResult = Dealias.fullNormDealiasSquashHKTToPolyTypeResultType(polyType)

      val tparams = polyType.typeParams
      val nestingLevel = if (level > 0) Some(level) else None
      val lambdaParams = makeLambdaParams(nestingLevel, tparams)

      thisLevel.log(s"✴️ λ type $t has parameters $lambdaParams and result $polyTypeResult terminal names = $terminalNames")
      val reference = makeRefSub(polyTypeResult, lambdaParams.toMap, Set.empty)
      val out = Lambda(lambdaParams.map(_._2), reference)
      if (!out.allArgumentsReferenced) {
        val kvParams = lambdaParams.map { case (k, v) => s"$v = $k" }
        thisLevel.log(
          s"⚠️ unused 𝝺 args! type $t => $out, someReferenced: ${out.someArgumentsReferenced} context: $terminalNames, 𝝺 params: $kvParams, 𝝺 result: $polyTypeResult => $reference, referenced: ${out.referenced} "
        )
      }

      thisLevel.log(s"✳️ Restored λ $t => ${out.longName}")
      out
    }

    def unpackProperTypeRefinement(t0: Type, rules: Map[String, LambdaParameter]): AppliedReference = {
      IzAssert(!isHKTOrPolyType(Dealias.fullNormDealias(t0)))

      UniRefinement.breakRefinement(t0, squashHKTRefToPolyTypeResultType = false) match {
        case Broken.Compound(components, decls) =>
          val parts = components.map(unpackAsProperType(_, rules): AppliedReference)
          val intersection = LightTypeTagRef.maybeIntersection(parts)
          if (decls.nonEmpty) {
            Refinement(intersection, decls.flatMap(convertDecl(_, rules)))
          } else {
            intersection
          }

        case Broken.Single(t) =>
          unpackAsProperType(t, rules)
      }
    }

    def unpackAsProperType(tpeRaw: Type, rules: Map[String, LambdaParameter]): AppliedNamedReference = {
      val tpe = Dealias.fullNormDealias(tpeRaw)
      val prefix = makePrefixReference(tpe)
      val typeSymbol = tpe.typeSymbol

      val boundaries = makeBoundaries(tpe)
      val nameRef = rules.get(typeSymbol.fullName) match {
        case Some(lambdaParameter) =>
          // this is a previously encountered type variable
          NameReference(SymTypeName(lambdaParameter.name), boundaries, prefix)

        case None =>
          makeNameReference(tpe, typeSymbol, boundaries, prefix)
      }

      tpe.typeArgs match {
        case Nil =>
          nameRef

        case args =>
          val tparams = Dealias.fullNormDealias(tpeRaw).typeConstructor.typeParams

          val refParams = tpeRaw match {
            case t: ExistentialTypeApi =>
              val quantifiedParams = t.quantified.toSet
              t.underlying.typeArgs.zip(tparams).map {
                case (arg, param) =>
                  val paramRef =
                    if (quantifiedParams.contains(arg.typeSymbol) && !rules.contains(arg.typeSymbol.fullName)) {
                      WildcardReference(makeBoundaries(arg))
                    } else {
                      makeRefSub(arg, Map.empty, quantifiedParams)
                    }
                  TypeParam(paramRef, makeVariance(param.asType))
              }
            case _ =>
              args.zip(tparams).map {
                case (arg, param) =>
                  val paramRef = makeRefSub(arg, Map.empty, Set.empty)
                  TypeParam(paramRef, makeVariance(param.asType))
              }
          }

          val res = FullReference(nameRef.ref.name, refParams, prefix)
          thisLevel.log(s"Assembled FullReference=$res from args=$args and tparams=$tparams")
          res
      }
    }

    def convertDecl(decl: SymbolApi, rules: Map[String, LambdaParameter]): scala.collection.compat.IterableOnce[RefinementDecl] = {
      if (decl.isMethod) {
        val declMethod = decl.asMethod
        val returnTpe = declMethod.returnType

        val paramLists0 = declMethod
          .paramLists.map(_.map {
            param =>
              val paramTpe = UniRefinement.typeOfParam(param)
              makeRefSub(paramTpe, rules, Set.empty).asInstanceOf[AppliedReference]
          })
        val paramLists = if (paramLists0.nonEmpty) paramLists0 else List(Nil)

        paramLists.map {
          parameterList =>
            RefinementDecl.Signature(declMethod.name.decodedName.toString, parameterList, makeRefSub(returnTpe, rules, Set.empty).asInstanceOf[AppliedReference])
        }
      } else if (decl.isType) {
        val tpe = UniRefinement.typeOfTypeMember(decl)
        val ref = makeRefSub(tpe, rules, Set.empty)
        Some(TypeMember(decl.name.decodedName.toString, ref))
      } else {
        None
      }
    }

    val out = tpe0 match {
      case l if isLambdaOutput => // this is required for handling SwapF2, etc.
        IzAssert(!isHKTOrPolyType(l), l -> l.getClass)
        val out = Lambda(terminalNames.values.toList, unpackAsProperType(l, terminalNames))
        out

      case l: PolyTypeApi =>
        val out = unpackLambda(l)
        out

      case l if l.takesTypeArgs =>
        if (terminalNames.contains(l.typeSymbol.fullName)) {
          val out = unpackAsProperType(l, terminalNames)
          out
        } else {
          val out = unpackLambda(l)
          out
        }

      case c =>
        unpackProperTypeRefinement(c, terminalNames)
    }
    out
  }

  private[this] def makeLambdaParams(ctxIdx: Option[Int], tparams: List[Symbol]): List[(String, LambdaParameter)] = {
    tparams.zipWithIndex.map {
      case (tparamSym, idx) =>
        val fullName = tparamSym.fullName
        val idxStr = ctxIdx match {
          case Some(ctx) =>
            s"$ctx:$idx"
          case None =>
            idx.toString
        }
        fullName -> LambdaParameter(idxStr)
    }
  }

  private[this] def makeNameReference(originalType: Type, typeSymbol: Symbol, boundaries: Boundaries, prefix: Option[AppliedReference]): NameReference = {
    originalType match {
      case c: ConstantTypeApi =>
        NameReference(SymLiteral(c.value.value), boundaries, prefix)

      case s: SingleTypeApi if s.sym != NoSymbol =>
        val sym = Dealias.dealiasSingletons(s.termSymbol)
        val resultType = Dealias.fullNormDealias(sym.typeSignatureIn(s.pre).finalResultType)
        val newPrefix = if (hasSingletonType(resultType.typeSymbol)) makePrefixReference(resultType) else prefix
        NameReference(makeSymName(sym), boundaries, newPrefix)

      case _ =>
        NameReference(makeSymName(typeSymbol), boundaries, prefix)
    }
  }

  private[this] def makePrefixReference(originalType: Type): Option[AppliedReference] = {

    @tailrec def extractPrefix(t0: Type): Option[Type] = {
      t0 match {
        case t: TypeRefApi => Some(t.pre).filterNot(_ == NoPrefix)
        case t: SingleTypeApi => Some(t.pre).filterNot(_ == NoPrefix)
        case t: ExistentialTypeApi => extractPrefix(t.underlying)
        case _ => None
      }
    }

    def unpackPrefix(pre: Type): Option[AppliedReference] = {
      pre match {
        case i if i.typeSymbol.isPackage =>
          None
        case k if k == NoPrefix =>
          None
        case k: ThisTypeApi =>
          k.sym.asType.toType match {
            // This case matches UniRefinement.unapply#it.RefinementTypeRef case
            case UniRefinement(_, _) =>
              None
            case _ =>
              if (originalType.termSymbol != NoSymbol) {
                fromRef(originalType.termSymbol.owner.asType.toType)
              } else {
                fromRef(originalType.typeSymbol.owner.asType.toType)
              }
          }
        case k if k.termSymbol != NoSymbol =>
          val finalSymbol = Dealias.dealiasSingletons(k.termSymbol)
          val finalSymbolTpe = Dealias.fullNormDealias(finalSymbol.typeSignature.finalResultType)
          val name = makeSymName(finalSymbol)
          val prePrefix = makePrefixReference(finalSymbolTpe)
          Some(NameReference(name, Boundaries.Empty, prePrefix))
        case o =>
          fromRef(o)
      }
    }

    def fromRef(o: Type): Option[AppliedReference] = {
      makeRef(o) match {
        case a: AppliedReference =>
          Some(a)
        case o =>
          throw new IllegalStateException(s"Cannot extract prefix from $originalType: expected applied reference, but got $o")
      }
    }

    val prefix = extractPrefix(originalType)
    val unpacked = prefix.flatMap(unpackPrefix)
    unpacked
  }

  private[this] def makeSymName(sym: Symbol): SymName = {
    val o = sym.owner
    val base = if (o.asInstanceOf[{ def hasMeaninglessName: Boolean }].hasMeaninglessName) {
      sym.name.decodedName.toString
    } else {
      sym.fullName
    }

    if (hasSingletonType(sym)) {
      SymTermName(base)
    } else {
      SymTypeName(base)
    }
  }

  private[this] def makeVariance(tpes: TypeSymbol): Variance = {
    if (tpes.isCovariant) {
      Variance.Covariant
    } else if (tpes.isContravariant) {
      Variance.Contravariant
    } else {
      Variance.Invariant
    }
  }

  private[this] object UniRefinement {

    def unapply(tpe: Type): Option[(List[Type], List[SymbolApi])] = {
      (tpe: AnyRef) match {
        case x: scala.reflect.internal.Types#RefinementTypeRef =>
          Some((x.parents.asInstanceOf[List[Type]], x.decls.toList.asInstanceOf[List[SymbolApi]]))
        case r: RefinedTypeApi @unchecked =>
          Some((r.parents, r.decls.toList))
        case _ =>
          None
      }
    }

    def breakRefinement(t0: Type, squashHKTRefToPolyTypeResultType: Boolean): Broken[Type, SymbolApi] = {
      breakRefinement0(t0, squashHKTRefToPolyTypeResultType) match {
        case (t, d) if d.isEmpty && t.size == 1 =>
          Broken.Single(t.head)
        case (t, d) =>
          logger.log(s"Found compound type parents=$t decls=$d")
          Broken.Compound(t, d)
      }
    }

    private[this] def breakRefinement0(t0: Type, squashHKTRefToPolyTypeResultType: Boolean): (Set[Type], Set[SymbolApi]) = {
      val normalized = if (squashHKTRefToPolyTypeResultType) {
        Dealias.fullNormDealiasSquashHKTToPolyTypeResultType(t0)
      } else {
        Dealias.fullNormDealias(t0)
      }
      normalized match {
        case UniRefinement(parents, decls) =>
          val parts = parents.map(breakRefinement0(_, squashHKTRefToPolyTypeResultType))
          val types = parts.flatMap(_._1)
          val partsDecls = parts.flatMap(_._2)
          (types.toSet, (decls ++ partsDecls).toSet)
        case t =>
          (Set(t), Set.empty)
      }
    }

    def typeOfParam(p: u.Symbol): Type = {
      p.typeSignature
    }

    def typeOfTypeMember(decl: u.SymbolApi): Type = {
      if (decl.isAbstract) {
        decl.asType.toType
      } else {
        decl.typeSignature
      }
    }

  }

  private[this] object Dealias {

    def fullNormDealiasSquashHKTToPolyTypeResultType(t0: Type): Type = {
      var prev = null: Type
      val t1 = fullNormDealias(t0)
      var cur = if (t1.takesTypeArgs) t1.etaExpand else t1

      while (cur ne prev) {
        prev = cur
        cur = norm(prev).dealias.resultType
      }
      cur
    }

    def fullNormDealias(t0: Type): Type = {
      scala211ExistentialDealiasWorkaround(t0)
    }

    // On Scala 2.12+ .dealias automatically destroys wildcards by using TypeMaps#ExistentialExtrapolation on dealiased existential output
    // This is kinda bad. But whatever, we're stuck with this behavior for this moment, so we should emulate it on 2.11 to make it work too.
    private[this] def scala211ExistentialDealiasWorkaround(t0: Type): Type = {
      t0 match {
        case existential: ExistentialTypeApi =>
          internal.existentialType(existential.quantified, scala211ExistentialDealiasWorkaround(existential.underlying.dealias))
        // internal.existentialAbstraction(existential.quantified, existential.underlying.dealias)
        case t =>
          val next = norm(t).dealias
          if (next eq t) {
            t
          } else {
            scala211ExistentialDealiasWorkaround(next)
          }
      }
    }

    @tailrec
    def dealiasSingletons(termSymbol: Symbol): Symbol = {
      val resultTerm = termSymbol.typeSignature.finalResultType.termSymbol
      if (hasSingletonType(resultTerm)) {
        dealiasSingletons(resultTerm)
      } else {
        termSymbol
      }
    }

    @inline def norm(x: Type): Type = {
      ReflectionUtil.norm(u: u.type, logger)(x)
    }

  }

  private[this] def isHKTOrPolyType(tpe: Type): Boolean = {
    tpe.takesTypeArgs || tpe.isInstanceOf[PolyTypeApi]
  }

  private[this] def hasSingletonType(sym: Symbol): Boolean = {
    sym.isTerm || sym.isModuleClass || isSingletonType(sym.typeSignature)
  }

  @inline private[this] def isSingletonType(tpe: Type): Boolean = {
    tpe.isInstanceOf[SingletonTypeApi] && !tpe.isInstanceOf[ThisTypeApi]
  }

}
