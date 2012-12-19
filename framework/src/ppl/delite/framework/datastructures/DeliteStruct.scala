package ppl.delite.framework.datastructures

import java.io.{File,FileWriter,PrintWriter}
import scala.virtualization.lms.common._
import ppl.delite.framework.ops.DeliteOpsExp
import scala.reflect.{RefinedManifest, SourceContext}

trait DeliteStructsExp extends StructExp { this: DeliteOpsExp with PrimitiveOpsExp with OrderingOpsExp => // FIXME: mix in prim somewhere else
	
  abstract class DeliteStruct[T:Manifest] extends AbstractStruct[T] with DeliteOp[T] {
    type OpType <: DeliteStruct[T]
    val tag = classTag[T]

    def copyTransformedElems(e: => Seq[(String, Rep[Any])]): Seq[(String, Rep[Any])] = 
      original.map(p=>(p._2.asInstanceOf[OpType]).elems.map(e=>(e._1,p._1(e._2)))).getOrElse(e)
  }

  //the following is a HACK to make struct inputs appear in delite op kernel input lists while keeping them out of the read effects list
  //the proper solution is to simply override readSyms as done in trait StructExp, but see def freeInScope in BlockTraversal.scala
  override def readSyms(e: Any): List[Sym[Any]] = e match {
    case s: AbstractStruct[_] => s.elems.flatMap(e => readSyms(e._2)).toList
    case _ => super.readSyms(e)
  }

  override def reflectEffect[A:Manifest](d: Def[A], u: Summary)(implicit pos: SourceContext): Exp[A] =  d match {
    case s: AbstractStruct[_] => reflectEffectInternal(d, u)
    case _ => super.reflectEffect(d,u)
  }

  case class NestedFieldUpdate[T:Manifest](struct: Exp[Any], fields: List[String], rhs: Exp[T]) extends Def[Unit]

  override def field_update[T:Manifest](struct: Exp[Any], index: String, rhs: Exp[T]) = recurseFields(struct, List(index), rhs)


  // TODO: clean up and check everything's safe
  override def field[T:Manifest](struct: Exp[Any], index: String)(implicit pos: SourceContext): Exp[T] = struct match {
    // is this confined to unsafe immutable or should we look at any mutable struct def?
    case Def(rhs@Reflect(ObjectUnsafeImmutable(orig), u, es)) => 
      println("**** trying to shortcut field access: " + struct.toString + "=" + rhs + "." + index)

      for (e@Def(r) <- es) {
        println("      dep: " + e.toString + "=" + r)
      }

      // find last assignment ... FIXME: should look at *all* mutations of orig
      val writes = es collect {
        case Def(Reflect(NestedFieldUpdate(`orig`,List(`index`),rhs), _, _)) => rhs
      }
      writes.reverse match {
        case rhs::_ => 
          println("      picking write " + rhs.toString)
          rhs.asInstanceOf[Exp[T]] // take last one
        case Nil => 
          orig match {
            case Def(Reflect(SimpleStruct(tag, fields), _, _)) =>
              val rhs = fields.find(_._1 == index).get._2
              println("      picking alloc " + rhs.toString)
              rhs.asInstanceOf[Exp[T]] // take field
            case _ =>
              println("      giving up...")
              super.field(struct, index)
          }
      }
    case Def(rhs@Reflect(SimpleStruct(tag, fields), _, _)) =>
      println("**** trying to shortcut field access: " + struct.toString + "=" + rhs + "." + index)

      // find last assignment ... FIXME: should look at *all* mutations of struct
      /*context foreach {
        case Def(Reflect(NestedFieldUpdate(`struct`,List(`index`),rhs), _, _)) =>  //ok
        case Def(e) => 
          println("      ignoring " + e)
      }*/
      val writes = context collect {
        case Def(Reflect(NestedFieldUpdate(`struct`,List(`index`),rhs), _, _)) => rhs
      }
      writes.reverse match {
        case rhs::_ => 
          println("      picking write " + rhs.toString)
          rhs.asInstanceOf[Exp[T]] // take last one
        case Nil =>
          val rhs = fields.find(_._1 == index).get._2
          println("      picking alloc " + rhs.toString)
          rhs.asInstanceOf[Exp[T]] // take field
      }

    case _ => super.field(struct, index)
  }



  private def recurseFields[T:Manifest](struct: Exp[Any], fields: List[String], rhs: Exp[T]): Exp[Unit] = struct match {
    case Def(Reflect(Field(s,name),_,_)) => recurseFields(s, name :: fields, rhs)
    case _ => reflectWrite(struct)(NestedFieldUpdate(struct, fields, rhs))
  }

  // TODO: get rid of entirely or just use mirrorDef
  def mirrorDD[A:Manifest](e: Def[A], f: Transformer)(implicit ctx: SourceContext): Def[A] = (e match {
    case IntTimes(a,b) => 
      println("warning: encountered effectful primitive def during mirror "+e)
      IntTimes(f(a),f(b))
    case IntPlus(a,b) => 
      println("warning: encountered effectful primitive def during mirror "+e)
      IntPlus(f(a),f(b))
    case IntMinus(a,b) => 
      println("warning: encountered effectful primitive def during mirror "+e)
      IntMinus(f(a),f(b))
    case IntMod(a,b) => 
      println("warning: encountered effectful primitive def during mirror "+e)
      IntMod(f(a),f(b))
    case IntDivide(a,b) => 
      println("warning: encountered effectful primitive def during mirror "+e)
      IntDivide(f(a),f(b)) //xx
    case e@OrderingLT(a,b) =>
      println("warning: encountered effectful primitive def during mirror "+e)
      OrderingLT(f(a),f(b))(null.asInstanceOf[Ordering[Any]],manifest[Any]) //HACK
    case e@Reflect(a,u,es) => Reflect(mirrorDD(a,f),mapOver(f,u),f(es))
    case _ => 
      println("FAIL: "+e)
      e
  }).asInstanceOf[Def[A]]



  override def mirror[A:Manifest](e: Def[A], f: Transformer)(implicit ctx: SourceContext): Exp[A] = (e match {
    case Reflect(NestedFieldUpdate(struct, fields, rhs), u, es) => reflectMirrored(Reflect(NestedFieldUpdate(f(struct), fields, f(rhs)), mapOver(f,u), f(es)))(mtype(manifest[A]))
    case Reflect(x@IntTimes(a,b), u, es) => reflectMirrored(mirrorDD(e,f).asInstanceOf[Reflect[A]])
    case Reflect(x@IntPlus(a,b), u, es) => reflectMirrored(mirrorDD(e,f).asInstanceOf[Reflect[A]])
    case Reflect(x@IntMinus(a,b), u, es) => reflectMirrored(mirrorDD(e,f).asInstanceOf[Reflect[A]])
    case Reflect(x@IntMod(a,b), u, es) => reflectMirrored(mirrorDD(e,f).asInstanceOf[Reflect[A]])
    case Reflect(x@IntDivide(a,b), u, es) => reflectMirrored(mirrorDD(e,f).asInstanceOf[Reflect[A]])
    case Reflect(x@OrderingLT(a,b), u, es) => reflectMirrored(mirrorDD(e,f).asInstanceOf[Reflect[A]])
    case _ => 
      //println("mirror: "+e)
      super.mirror(e,f)
  }).asInstanceOf[Exp[A]]


  def unapplyStructType[T:Manifest]: Option[(StructTag[T], List[(String,Manifest[_])])] = manifest[T] match {
    case r: RefinedManifest[T] => Some(AnonTag(r), r.fields)
    case t if t.erasure == classOf[Tuple2[_,_]] => Some((classTag(t), List("_1","_2") zip t.typeArguments))
    case t if t.erasure == classOf[Tuple3[_,_,_]] => Some((classTag(t), List("_1","_2","_3") zip t.typeArguments))
    case t if t.erasure == classOf[Tuple4[_,_,_,_]] => Some((classTag(t), List("_1","_2","_3","_4") zip t.typeArguments))
    case _ => None
  }

}


trait ScalaGenDeliteStruct extends BaseGenStruct {
  val IR: DeliteStructsExp with DeliteOpsExp
  import IR._

  override def emitNode(sym: Sym[Any], rhs: Def[Any]) = rhs match {
    //TODO: generalize primitive struct packing
    case Struct(tag, elems) =>
      registerStruct(structName(sym.tp), elems)
      emitValDef(sym, "new " + structName(sym.tp) + "(" + elems.map{ e => 
        if (isVarType(e._2.tp) && deliteInputs.contains(e._2)) quote(e._2) + ".get"
        else quote(e._2)
      }.mkString(",") + ")")
      printlog("WARNING: emitting " + structName(sym.tp) + " struct " + quote(sym))    
    case FieldApply(struct, index) =>
      emitValDef(sym, quote(struct) + "." + index)
      val lhs = struct match { case Def(lhs) => lhs.toString case _ => "?" }
      printlog("WARNING: emitting field access: " + quote(struct) + "=" + lhs + "." + index)
    case FieldUpdate(struct, index, rhs) =>
      emitValDef(sym, quote(struct) + "." + index + " = " + quote(rhs))
      val lhs = struct match { case Def(lhs) => lhs.toString case _ => "?" }
      printlog("WARNING: emitting field update: " + quote(struct) + "=" + lhs + "." + index)
    case NestedFieldUpdate(struct, fields, rhs) =>
      emitValDef(sym, quote(struct) + "." + fields.reduceLeft(_ + "." + _) + " = " + quote(rhs))
    case _ => super.emitNode(sym, rhs)
  }

  override def remap[A](m: Manifest[A]) = m match {
    case s if s <:< manifest[Record] => structName(m)
    case _ => super.remap(m)
  }

  private def isVarType[T](m: Manifest[T]) = m.erasure.getSimpleName == "Variable"
  private def isArrayType[T](m: Manifest[T]) = m.erasure.getSimpleName == "DeliteArray"
  private def isStringType[T](m: Manifest[T]) = m.erasure.getSimpleName == "String"
  private def baseType[T](m: Manifest[T]) = if (isVarType(m)) mtype(m.typeArguments(0)) else m
  
  override def emitDataStructures(path: String) {
    val stream = new PrintWriter(path + "DeliteStructs.scala")
    stream.println("package generated.scala")
    for ((name, elems) <- encounteredStructs) {
      stream.println("")
      emitStructDeclaration(name, elems)(stream)
    }
    stream.close()
    super.emitDataStructures(path)
  }

  def emitStructDeclaration(name: String, elems: Seq[(String,Manifest[_])])(stream: PrintWriter) {
    stream.print("case class " + name + "(")
    stream.print(elems.map{ case (idx,tp) => "var " + idx + ": " + remap(tp) }.mkString(", "))
    stream.println(")")
  }
}