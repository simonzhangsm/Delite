package ppl.delite.runtime.codegen.sync

import ppl.delite.runtime.graph.ops._
import collection.mutable.ArrayBuffer
import ppl.delite.runtime.graph.targets.{OS, Targets}
import ppl.delite.runtime.codegen._
import ppl.delite.runtime.scheduler.OpHelper._
import ppl.delite.runtime.graph.targets.Targets._
import ppl.delite.runtime.Config
import ppl.delite.runtime.graph._

trait ChiselSyncGenerator extends SyncGenerator with ChiselExecutableGenerator {

  override protected def receiveData(s: ReceiveData) {
    (scheduledTarget(s.sender.from), scheduledTarget(s.to)) match {
      case (Targets.Cpp, Targets.Chisel) => writeGetter(s.sender.from, s.sender.sym, s.to, false)
      case (Targets.Scala, Targets.Chisel) => out.append(s"""// writeGetter(${s.sender.from} (${s.sender.from.opName}), ${s.sender.sym}, ${s.to} (${s.to.opName}), false): scala -> chisel\n""")
      case (Targets.Chisel, Targets.Cpp) => //
      case (a,b) =>
        println(s"""[ChiselSyncGenerator::receiveData] from: $a, to: $b""")
        super.receiveData(s)
    }
  }

  override protected def receiveView(s: ReceiveView) {
    (scheduledTarget(s.sender.from), scheduledTarget(s.to)) match {
      case (Targets.Cpp, Targets.Chisel) => //
      case (Targets.Chisel, Targets.Cpp) => //
      case _ => super.receiveView(s)
    }
  }

  override protected def awaitSignal(s: Await) {
    (scheduledTarget(s.sender.from), scheduledTarget(s.to)) match {
      case (Targets.Cpp, Targets.Chisel) => //
      case (Targets.Chisel, Targets.Cpp) => //
      case _ => super.awaitSignal(s)
    }
  }

  override protected def receiveUpdate(s: ReceiveUpdate) {
    (scheduledTarget(s.sender.from), scheduledTarget(s.to)) match {
      case (Targets.Cpp, Targets.Chisel) => //
        s.sender.from.mutableInputsCondition.get(s.sender.sym) match {
          case Some(lst) =>
            out.append("if(")
            out.append(lst.map(c => c._1.id.split('_').head + "_cond=="+c._2).mkString("&&"))
            out.append(") {\n")
            writeRecvUpdater(s.sender.from, s.sender.sym);
            out.append("}\n")
          case _ =>
            writeRecvUpdater(s.sender.from, s.sender.sym);
        }
      case (Targets.Chisel, Targets.Cpp) => //
      case _ => super.receiveUpdate(s)
    }
  }

  override protected def sendData(s: SendData) {
    if ((scheduledTarget(s.from) == Targets.Chisel) && (s.receivers.map(_.to).filter(r => getHostTarget(scheduledTarget(r)) == Targets.Cpp).nonEmpty)) {
      writeSetter(s.from, s.sym, false)
    }
    super.sendData(s)
  }

  override protected def sendView(s: SendView) {
    super.sendView(s)
  }

  override protected def sendSignal(s: Notify) {
    super.sendSignal(s)
  }

  override protected def sendUpdate(s: SendUpdate) {
    writeSendUpdater(s.from, s.sym)
    super.sendUpdate(s)
  }

  private def writeGetter(dep: DeliteOP, sym: String, to: DeliteOP, view: Boolean) {
    assert(view == false)
    val ref = if (isPrimitiveType(dep.outputType(sym))) "" else "*"
//    val devType = dep.outputType(Targets.Chisel, sym)
//    val hostType = dep.outputType(Targets.Cpp, sym)
    if(isPrimitiveType(dep.outputType(sym))) {
      out.append(s"""//writeGetter($dep, $sym, $to, $view) - primitive\n""")
    }
    else {
      out.append(s"""//writeGetter($dep, $sym, $to, $view) - copy\n""")
    }
  }

  private def writeRecvUpdater(dep: DeliteOP, sym:String) {
//    val hostType = dep.inputType(Targets.Cpp, sym)
//    val devType = dep.inputType(Targets.Chisel, sym)
    assert(!isPrimitiveType(dep.inputType(sym)))
    out.append(s"""// writeRecvUpdater($dep, $sym)\n""")
  }

  private def writeSetter(op: DeliteOP, sym: String, view: Boolean) {
    assert(view == false)
//    val hostType = op.outputType(Targets.Cpp, sym)
//    val devType = op.outputType(Targets.Chisel, sym)
    if(isPrimitiveType(op.outputType(sym)) && op.isInstanceOf[OP_Nested]) {
      out.append(s"""// writeSetter($op, $sym, $view), primitive, OP_NESTED\n""")
    }
    else if(isPrimitiveType(op.outputType(sym))) {
      out.append(s"""// writeSetter($op, $sym, $view), primitive\n""")
    }
    else {
      out.append(s"""// writeSetter($op, $sym, $view)\n""")
    }
  }

  private def writeSendUpdater(op: DeliteOP, sym: String) {
//    val devType = op.inputType(Targets.Chisel, sym)
//    val hostType = op.inputType(Targets.Cpp, sym)
    assert(!isPrimitiveType(op.inputType(sym)))
    out.append(s"""// writeSendUpdater($op, $sym)\n""")
  }

  private def addFree(m: Free) {
    out.append(s"""// addFree($m);\n""")
  }

  override protected def makeNestedFunction(op: DeliteOP) = op match {
    case s: Sync => addSync(s)
    case _ => super.makeNestedFunction(op)
  }

  override protected[codegen] def writeSyncObject() {
    super.writeSyncObject()
  }
}
