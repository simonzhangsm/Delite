package templates

import chisel3._

/**
 * FF: Flip-flop with the ability to set enable and init
 * value as IO
 * @param w: Word width
 */

class FFIn(val w: Int) extends Bundle {
  val data   = UInt(w.W)
  val init = UInt(w.W)
  val enable = Bool()
  val reset = Bool() // Asynchronous reset

  override def cloneType = (new FFIn(w)).asInstanceOf[this.type] // See chisel3 bug 358
}
class FFOut(val w: Int) extends Bundle {
  val data  = UInt(w.W)

  override def cloneType = (new FFOut(w)).asInstanceOf[this.type] // See chisel3 bug 358
}

class FF(val w: Int) extends Module {
  val io = IO(new Bundle{
    val input = new FFIn(w).asInput
    val output = new FFOut(w).asOutput
  })
  
  val ff = Reg(init = io.input.init)
  ff := Mux(io.input.reset, io.input.init, Mux(io.input.enable, io.input.data, ff))
  io.output.data := Mux(io.input.reset, io.input.init, ff)
  
  def write(data: UInt, en: Bool, reset: Bool, port: Int) {
    io.input.data := data
    io.input.enable := en
    io.input.reset := reset
    // Ignore port
  }
}

class NBufFF(val numBufs: Int, val w: Int) extends Module {

  // Define overloaded
  def this(tuple: (Int, Int)) = this(tuple._1, tuple._2)

  val io = IO(new Bundle {
    val sEn = Vec(numBufs, Bool().asInput)
    val sDone = Vec(numBufs, Bool().asInput)
    val broadcast = new FFIn(w).asInput
    val input = Vec(numBufs, new FFIn(w).asInput)
    val output = Vec(numBufs, new FFOut(w).asOutput)
  })

  def bitsToAddress(k:Int) = {(scala.math.log(k)/scala.math.log(2)).toInt + 1}
  // def rotate[T](x: Vec[T], i:Int)={ // Chisel is so damn annoying with types, so this method doesn't work
  //   val temp = x.toList
  //   val result = x.drop(i)++x.take(i)
  //   Vec(result.toArray)
  // }

  val ff = (0 until numBufs).map{i => Module(new FF(w))}

  val sEn_latch = (0 until numBufs).map{i => Module(new SRFF())}
  val sDone_latch = (0 until numBufs).map{i => Module(new SRFF())}

  val swap = Wire(Bool())

  // Latch whether each buffer's stage is enabled and when they are done
  (0 until numBufs).foreach{ i => 
    sEn_latch(i).io.input.set := io.sEn(i)
    sEn_latch(i).io.input.reset := swap
    sDone_latch(i).io.input.set := io.sDone(i)
    sDone_latch(i).io.input.reset := swap
  }
  val anyEnabled = sEn_latch.map{ en => en.io.output.data }.reduce{_|_}
  swap := sEn_latch.zip(sDone_latch).map{ case (en, done) => en.io.output.data === done.io.output.data }.reduce{_&_} & anyEnabled

  val statesIn = (0 until numBufs).map{  i => 
    val c = Module(new NBufCtr())
    c.io.input.start := i.U // WAS DECIDING WHAT TO DO ABOUT START SIGNAL
    c.io.input.max := numBufs.U
    c.io.input.enable := swap
    c.io.input.countUp := false.B
    c
  }
  val statesOut = (0 until numBufs).map{  i => 
    val c = Module(new NBufCtr())
    c.io.input.start := i.U // WAS DECIDING WHAT TO DO ABOUT START SIGNAL
    c.io.input.max := numBufs.U
    c.io.input.enable := swap
    c.io.input.countUp := true.B
    c
  }

  ff.zip(statesIn).foreach{ case (f,s) => 
    val sel = (0 until numBufs).map{ i => s.io.output.count === i.U }
    val normal =  chisel3.util.Mux1H(sel, io.input)
    f.io.input := Mux(io.broadcast.enable, io.broadcast, normal)
  }

  io.output.zip(statesOut).foreach{ case (wire, s) => 
    val sel = (0 until numBufs).map{ i => s.io.output.count === i.U }
    wire.data := chisel3.util.Mux1H(sel, Vec(ff.map{f => f.io.output.data}))
  }

  def write(data: UInt, en: Bool, reset: Bool, port: Int) {
    io.input(port).data := data
    io.input(port).enable := en
    io.input(port).reset := reset
  }

}

class FFNoInit(val w: Int) extends Module {
  val io = IO(new Bundle{
    val input = new FFIn(w).asInput
    val output = new FFOut(w).asOutput
  })

  val ff = Module(new FF(w))
  ff.io.input.data := io.input.data
  ff.io.input.enable := io.input.enable
  ff.io.input.reset := io.input.reset
  ff.io.input.init := UInt(0, w.W)
  io.output.data := ff.io.output.data
}

class FFNoInitNoReset(val w: Int) extends Module {
  val io = IO(new Bundle{
    val input = new FFIn(w).asInput
    val output = new FFOut(w).asOutput
  })

  val ff = Module(new FF(w))
  ff.io.input.data := io.input.data
  ff.io.input.enable := io.input.enable
  ff.io.input.reset := Bool(false)
  ff.io.input.init := UInt(0, w.W)
  io.output.data := ff.io.output.data
}

class FFNoReset(val w: Int) extends Module {
  val io = IO(new Bundle{
    val input = new FFIn(w).asInput
    val output = new FFOut(w).asOutput
  })

  val ff = Module(new FF(w))
  ff.io.input.data := io.input.data
  ff.io.input.enable := io.input.enable
  ff.io.input.reset := Bool(false)
  ff.io.input.init := io.input.init
  io.output.data := ff.io.output.data
}

class TFF() extends Module {

  // Overload with null string input for testing
  def this(n: String) = this()

  val io = IO(new Bundle {
    val input = new Bundle {
      val enable = Bool().asInput
    }
    val output = new Bundle {
      val data = Bool().asOutput      
    }
  })

  val ff = Reg(init = Bool(false))
  ff := Mux(io.input.enable, ~ff, ff)
  io.output.data := ff
}

class SRFF() extends Module {

  // Overload with null string input for testing
  def this(n: String) = this()

  val io = IO(new Bundle {
    val input = new Bundle {
      val set = Bool().asInput // Set overrides reset.  Asyn_reset overrides both
      val reset = Bool().asInput
      val asyn_reset = Bool().asInput
    }
    val output = new Bundle {
      val data = Bool().asOutput      
    }
  })

  val ff = Reg(init = Bool(false))
  ff := Mux(io.input.asyn_reset, Bool(false), Mux(io.input.set, 
                                  Bool(true), Mux(io.input.reset, Bool(false), ff)))
  io.output.data := Mux(io.input.asyn_reset, Bool(false), ff)
}


