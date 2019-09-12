/*
 * Copyright (c) 2019 Mathias Doenitz
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package io.bullet.borer.derivation

import io.bullet.borer._
import io.bullet.borer.derivation.internal._

import scala.reflect.macros.blackbox

object ArrayBasedCodecs {

  /**
    * Macro that creates an [[Encoder]] for [[T]] provided that
    * - [[T]] is a `case class`, `sealed abstract class` or `sealed trait`
    * - [[Encoder]] instances for all members of [[T]] (if [[T]] is a `case class`)
    *   or all sub-types of [[T]] (if [[T]] is an ADT) are implicitly available
    *
    * Case classes are converted into an array of values, one value for each member.
    *
    * NOTE: If `T` is unary (i.e. only has a single member) then the member value is written in an unwrapped form,
    * i.e. without the array container.
    */
  def deriveEncoder[T]: Encoder[T] = macro Macros.encoder[T]

  /**
    * Same as `deriveEncoder[T]` but doesn't compile if [[T]] is not a unary case class,
    * i.e. a case class with exactly one member.
    */
  def deriveUnaryEncoder[T]: Encoder[T] = macro Macros.unaryEncoder[T]

  /**
    * Macro that creates a [[Decoder]] for [[T]] provided that
    * - [[T]] is a `case class`, `sealed abstract class` or `sealed trait`
    * - [[Decoder]] instances for all members of [[T]] (if [[T]] is a `case class`)
    *   or all sub-types of [[T]] (if [[T]] is an ADT) are implicitly available
    *
    * Case classes are created from an array of deserialized values, one value for each member.
    *
    * NOTE: If `T` is unary (i.e. only has a single member) then the member value is expected in an unwrapped form,
    * i.e. without the array container.
    */
  def deriveDecoder[T]: Decoder[T] = macro Macros.decoder[T]

  /**
    * Same as `deriveDecoder[T]` but doesn't compile if [[T]] is not a unary case class,
    * i.e. a case class with exactly one member.
    */
  def deriveUnaryDecoder[T]: Decoder[T] = macro Macros.unaryDecoder[T]

  /**
    * Macro that creates an [[Encoder]] and [[Decoder]] pair for [[T]].
    * Convenience shortcut for `Codec(deriveEncoder[T], deriveDecoder[T])"`.
    */
  def deriveCodec[T]: Codec[T] = macro Macros.codec[T]

  /**
    * Same as `deriveCodec[T]` but doesn't compile if [[T]] is not a unary case class,
    * i.e. a case class with exactly one member.
    */
  def deriveUnaryCodec[T]: Codec[T] = macro Macros.unaryCodec[T]

  private object Macros {
    import MacroSupport._

    def unaryEncoder[T: c.WeakTypeTag](c: blackbox.Context): c.Tree = {
      import c.universe._
      val tpe    = weakTypeOf[T].dealias
      val fields = tpe.decls.collect { case m: MethodSymbol if m.isCaseAccessor => m.asMethod }.toList
      if (fields.lengthCompare(1) == 0) encoder(c)
      else c.abort(c.enclosingPosition, s"`$tpe` is not a unary case class")
    }

    def encoder[T: ctx.WeakTypeTag](ctx: blackbox.Context): ctx.Tree = DeriveWith[T](ctx) {
      new CodecDeriver[ctx.type](ctx) {
        import c.universe._

        def deriveForCaseObject(tpe: Type, module: ModuleSymbol) =
          q"$encoderCompanion[${module.typeSignature}]((w, _) => w.writeEmptyArray())"

        def deriveForCaseClass(
            tpe: Type,
            companion: ModuleSymbol,
            params: List[CaseParam],
            annotationTrees: List[Tree],
            constructorIsPrivate: Boolean) = {

          val arity     = params.size
          val writeOpen = if (arity == 1) q"w" else q"w.writeArrayOpen($arity)"
          val writeOpenAndFields = params.foldLeft(writeOpen) { (acc, field) =>
            val suffix =
              if (field.isBasicType && isDefinedOn(field.getImplicit(encoderType).get, encoderType)) {
                field.paramType.tpe.toString
              } else ""
            q"$acc.${TermName(s"write$suffix")}(x.${field.name})"
          }
          val writeOpenFieldsAndClose = if (arity == 1) writeOpenAndFields else q"$writeOpenAndFields.writeArrayClose()"
          q"""$encoderCompanion((w, x) => $writeOpenFieldsAndClose)"""
        }

        def deriveForSealedTrait(tpe: Type, subTypes: List[SubType]) = {
          val cases = adtSubtypeWritingCases(tpe, subTypes)
          q"""$encoderCompanion { (w, value) =>
                w.writeArrayOpen(2)
                value match { case ..$cases }
                w.writeArrayClose()
              }"""
        }
      }
    }

    def unaryDecoder[T: c.WeakTypeTag](c: blackbox.Context): c.Tree = {
      import c.universe._
      val tpe    = weakTypeOf[T].dealias
      val fields = tpe.decls.collect { case m: MethodSymbol if m.isCaseAccessor => m.asMethod }.toList
      if (fields.lengthCompare(1) == 0) decoder(c)
      else c.abort(c.enclosingPosition, s"`$tpe` is not a unary case class")
    }

    def decoder[T: ctx.WeakTypeTag](ctx: blackbox.Context): ctx.Tree = DeriveWith[T](ctx) {
      new CodecDeriver[ctx.type](ctx) {
        import c.universe._

        def deriveForCaseObject(tpe: Type, module: ModuleSymbol) =
          q"$decoderCompanion(r => r.readArrayClose(r.readArrayOpen(0), $module))"

        def deriveForCaseClass(
            tpe: Type,
            companion: ModuleSymbol,
            params: List[CaseParam],
            annotationTrees: List[Tree],
            constructorIsPrivate: Boolean) = {

          if (constructorIsPrivate)
            error(s"Cannot derive Decoder[$tpe] because the primary constructor of `$tpe` is private")

          val readFields = params.map { p =>
            val paramType = p.paramType.tpe
            if (isBasicType(paramType) && isDefinedOn(p.getImplicit(decoderType).get, decoderCompanion)) {
              q"r.${TermName(s"read$paramType")}()"
            } else q"r.read[$paramType]()"
          }
          val arity               = params.size
          val readObject          = q"$companion.apply(..$readFields)"
          def expected(s: String) = s"$s for decoding an instance of type `$tpe`"
          val readObjectWithWrapping =
            arity match {
              case 0 =>
                q"""if (r.tryReadArrayHeader(0) || r.tryReadArrayStart() && r.tryReadBreak()) $readObject
                    else r.unexpectedDataItem(${expected(s"Empty array")})"""
              case 1 => readObject
              case x =>
                q"""def readObject() = $readObject
                    if (r.tryReadArrayStart()) {
                      val result = readObject()
                      if (r.tryReadBreak()) result
                      else r.unexpectedDataItem(${expected(s"Array with $x elements")}, "at least one extra element")
                    } else if (r.tryReadArrayHeader($x)) readObject()
                    else r.unexpectedDataItem(${expected(s"Array Start or Array Header ($x)")})"""
            }
          q"$decoderCompanion(r => $readObjectWithWrapping)"
        }

        def deriveForSealedTrait(tpe: Type, subTypes: List[SubType]) = {
          val typeIdsAndSubTypes: Array[(Key, SubType)] = getTypeIds(tpe, subTypes).zip(subTypes)
          java.util.Arrays.sort(typeIdsAndSubTypes.asInstanceOf[Array[Object]], KeyPairOrdering)

          def rec(start: Int, end: Int): Tree =
            if (start < end) {
              val mid           = (start + end) >> 1
              val (typeId, sub) = typeIdsAndSubTypes(mid)
              val cmp           = r("tryRead", typeId, "Compare")
              if (start < mid) {
                q"""val cmp = $cmp
                  if (cmp < 0) ${rec(start, mid)}
                  else if (cmp > 0) ${rec(mid + 1, end)}
                  else r.read[${sub.tpe}]()"""
              } else q"if ($cmp == 0) r.read[${sub.tpe}]() else fail()"
            } else q"fail()"

          val readTypeIdAndValue = rec(0, typeIdsAndSubTypes.length)

          q"""$decoderCompanion { r =>
                def fail() = r.unexpectedDataItem(${s"type id key for subtype of `$tpe`"})
                r.readArrayClose(r.readArrayOpen(2), $readTypeIdAndValue)
              }"""
        }
      }
    }

    def unaryCodec[T: c.WeakTypeTag](c: blackbox.Context): c.Tree = {
      import c.universe._
      val tpe      = weakTypeOf[T]
      val borerPkg = c.mirror.staticPackage("io.bullet.borer")
      val prefix   = q"$borerPkg.derivation.ArrayBasedCodecs"
      q"$borerPkg.Codec($prefix.deriveUnaryEncoder[$tpe], $prefix.deriveUnaryDecoder[$tpe])"
    }

    def codec[T: c.WeakTypeTag](c: blackbox.Context): c.Tree = codecMacro(c)("ArrayBasedCodecs")
  }
}
