package templates

import chisel3._
import chisel3.util


class flatW(val w: Int) extends Bundle {
  val addr = UInt(w.W)
  val data = UInt(w.W)
  val en = Bool()

  override def cloneType = (new flatW(w)).asInstanceOf[this.type] // See chisel3 bug 358
}
class flatR(val w: Int) extends Bundle {
  val addr = UInt(w.W)
  val en = Bool()

  override def cloneType = (new flatR(w)).asInstanceOf[this.type] // See chisel3 bug 358
}
class multidimW(val N: Int, val w: Int) extends Bundle {
  val addr = Vec(N, UInt(w.W))
  val data = UInt(w.W)
  val en = Bool()

  override def cloneType = (new multidimW(N, w)).asInstanceOf[this.type] // See chisel3 bug 358
}
class multidimR(val N: Int, val w: Int) extends Bundle {
  val addr = Vec(N, UInt(w.W))
  val en = Bool()
  
  override def cloneType = (new multidimR(N, w)).asInstanceOf[this.type] // See chisel3 bug 358
}


class Mem1D(val size: Int) extends Module { // Unbanked, inner 1D mem
  val io = IO( new Bundle {
    val w = new flatW(32).asInput
    val r = new flatR(32).asInput
    val output = new Bundle {
      val data  = UInt(32.W).asOutput
    }
    val debug = new Bundle {
      val invalidRAddr = Bool().asOutput
      val invalidWAddr = Bool().asOutput
      val rwOn = Bool().asOutput
      val error = Bool().asOutput
    }
  })

  // We can do better than MaxJ by forcing mems to be single-ported since
  //   we know how to properly schedule reads and writes
  val m = Mem(UInt(width = 32), size /*, seqRead = true deprecated? */)
  val wInBound = io.w.addr < UInt(size)
  val rInBound = io.r.addr < UInt(size)

  val reg_rAddr = Reg(UInt())
  when (io.w.en & wInBound) {m(io.w.addr) := io.w.data}
  .elsewhen (io.r.en & rInBound) {reg_rAddr := io.r.addr}

  io.output.data := m(reg_rAddr)
  io.debug.invalidRAddr := ~rInBound
  io.debug.invalidWAddr := ~wInBound
  io.debug.rwOn := io.w.en & io.r.en
  io.debug.error := ~rInBound | ~wInBound | (io.w.en & io.r.en)

}


// Last dimension is the leading-dim
class MemND(val dims: List[Int]) extends Module { 
  val depth = dims.reduce{_*_} // Size of memory
  val N = dims.length // Number of dimensions

  val io = IO( new Bundle {
    val w = new multidimW(N, 32).asInput
    val r = new multidimR(N, 32).asInput
    val output = new Bundle {
      val data  = UInt(32.W).asOutput
    }
    val debug = new Bundle {
      val invalidRAddr = Bool().asOutput
      val invalidWAddr = Bool().asOutput
      val rwOn = Bool().asOutput
      val error = Bool().asOutput
    }
  })

  // Instantiate 1D mem
  val m = Module(new Mem1D(depth))

  // Address flattening
  m.io.w.addr := io.w.addr.zipWithIndex.map{ case (addr, i) =>
    addr * UInt(dims.drop(i).reduce{_*_}/dims(i))
  }.reduce{_+_}
  m.io.r.addr := io.r.addr.zipWithIndex.map{ case (addr, i) =>
    addr * UInt(dims.drop(i).reduce{_*_}/dims(i))
  }.reduce{_+_}

  // Check if read/write is in bounds
  val rInBound = io.r.addr.zip(dims).map { case (addr, bound) => addr < UInt(bound) }.reduce{_&_}
  val wInBound = io.w.addr.zip(dims).map { case (addr, bound) => addr < UInt(bound) }.reduce{_&_}

  // Connect the other ports
  m.io.w.data := io.w.data
  m.io.w.en := io.w.en
  m.io.r.en := io.r.en
  io.output.data := m.io.output.data
  io.debug.invalidWAddr := ~wInBound
  io.debug.invalidRAddr := ~rInBound
  io.debug.rwOn := io.w.en & io.r.en
  io.debug.error := ~wInBound | ~rInBound | (io.w.en & io.r.en)
}


/*
                            
                                                           __________             ___SRAM__
         _        _           _______                     |          |--bundleND-|   MemND |               
        | |------| |---------|       |                    |          |           |_________|                        
   IO(Vec(bundleSRAM))-------| Mux1H |-----bundleSRAM-----|   VAT    |--bundleND-|   MemND |    
        |_|------|_|---------|_______|                    |          |           |_________|                        
                               | | |                      |__________|--bundleND-|   MemND |               
                             stageEnables                                        |_________|
                                                                        
                                                                    
*/
class SRAM(val logicalDims: List[Int], val numBufs: Int, val w: Int, 
           val banks: List[Int], val strides: List[Int], val numWriters: Int, val numReaders: Int,
           val wPar: Int, val rPar: Int, val bankingMode: String) extends Module { 

  // Overloaded construters
  def this(logicalDims: List[Int], numBufs: Int, w: Int, 
           banks: List[Int], strides: List[Int], numWriters: Int, numReaders: Int,
           wPar: Int, rPar: Int) = this(logicalDims, numBufs, w, banks, strides, numWriters, numReaders, wPar, rPar, "strided")

  val depth = logicalDims.reduce{_*_} // Size of memory
  val N = logicalDims.length // Number of dimensions

  val io = IO( new Bundle {
    val w = Vec(numWriters, Vec(wPar, new multidimW(N, 32).asInput))
    val r = Vec(rPar,new multidimR(N, 32).asInput) // TODO: Spatial allows only one reader per mem
    val sel = Vec(numWriters, Bool().asInput) // Selects between multiple write bundles
    val globalWEn = Bool().asInput
    val output = new Bundle {
      val data  = Vec(rPar, UInt(32.W).asOutput)
      val invalidRAddr = Bool().asOutput
      val invalidWAddr = Bool().asOutput
    }
    val dddd = Vec(wPar, UInt(32.W).asOutput)
  })


  // Get info on physical dims
  // TODO: Upcast dims to evenly bank
  val physicalDims = logicalDims.zip(banks).map { case (dim, b) => dim/b}
  val numMems = banks.reduce{_*_}

  // Create physical mems
  val m = (0 until numMems).map{ i => Module(new MemND(physicalDims))}

  // Mux wrideBundles and connect to internal
  // MATT: Choose one vec(multidimW) from the vec(vec(multidimW))
  val selectedWVec = chisel3.util.Mux1H(io.sel, io.w)

  // TODO: Should connect multidimW's directly to their banks rather than all-to-all connections
  // Convert selectedWVec to translated physical addresses
  // MATT: Convert each multidimW in the selected vec to a new multidimW with converted addresses
  val converted = selectedWVec.zip(io.r).map{ case (wbundle, rbundle) => 
    // Writer conversion
    val convertedW = Wire(new multidimW(N,w))
    val physicalAddrsW = wbundle.addr.zip(banks).map{ case (logical, b) => logical / UInt(b) }
    physicalAddrsW.zipWithIndex.foreach { case (calculatedAddr, i) => convertedW.addr(i) := calculatedAddr}
    convertedW.data := wbundle.data
    convertedW.en := wbundle.en
    val bankCoordsW = wbundle.addr.zip(banks).map{ case (logical, b) => logical % UInt(b) }
    val bankCoordW = bankCoordsW.zipWithIndex.map{ case (c, i) => c*UInt(banks.drop(i).reduce{_*_}/banks(i)) }.reduce{_+_}

    // Reader conversion
    // MATT: Ignore this section, where I do the same conversion for the single reader
    val convertedR = Wire(new multidimR(N,w))
    val physicalAddrs = rbundle.addr.zip(banks).map{ case (logical, b) => logical / UInt(b) }
    physicalAddrs.zipWithIndex.foreach { case (calculatedAddr, i) => convertedR.addr(i) := calculatedAddr}
    convertedR.en := rbundle.en
    val bankCoordsR = rbundle.addr.zip(banks).map{ case (logical, b) => logical % UInt(b) }
    val bankCoordR = bankCoordsR.zipWithIndex.map{ case (c, i) => c*UInt(banks.drop(i).reduce{_*_}/banks(i)) }

    (convertedW, bankCoordW, convertedR, bankCoordR)
  }
  val convertedWVec = converted.map{_._1}
  val bankIdW = converted.map{_._2}
  val convertedRVec = converted.map{_._3}
  val bankIdR = converted.map{_._4}

  // TODO: Doing inefficient thing here of all-to-all connection between bundlesNDs and MemNDs
  // Convert bankCoords for each bundle to a bit vector
  // MATT: Compute bit vector to decide which internal bank each convertedWVec should go to
  // TODO: Probably need to have a dummy multidimW port to default to for unused banks so we don't overwrite anything
  m.zipWithIndex.foreach{ case (mem, i) => 
    val bundleSelect = bankIdW.map{ b => (b == UInt(i)).B }
    // MATT: The interface of the mem's seems ok sometimes, but sometimes reads 
    //       straight 0's.  Invariably, by the time these mems pass their
    //       flattened addresses to their respective flattened mems,
    //       the actual memory interfaces read straight 0s.
    //       Also noticed that if I connect mem.io.en to some signals, a & b,
    //       mem.io.en stays 0 even when a & b = 1.  But if I create two 
    //       ports, mem.io.a and mem.io.b and feed them seperately and &
    //       inside the module, it works as expected.  Chisel3 is acting strange
    //       all around...
    mem.io.w := chisel3.util.Mux1H(bundleSelect, convertedWVec)
  }

  var wId = 0
  def connectWPort(wBundle: Vec[multidimW]) {
    io.w(wId).zip(wBundle).foreach { case (p, b) => p := wBundle }
    wId = wId + 1
  }

  // TODO: Doing inefficient thing here of all-to-all connection between bundlesNDs and MemNDs
  // Convert bankCoords for each bundle to a bit vector
  m.zipWithIndex.foreach{ case (mem, i) => 
    val bundleSelect = bankIdR.map{ b => (b == UInt(i)).B }
    mem.io.r := chisel3.util.PriorityMux(bundleSelect, convertedRVec)
  }

  // Connect read data to output
  io.output.data.zip(bankIdR).foreach { case (wire, id) => 
    val sel = (0 until numMems).map{ i => (id == UInt(i)).B}
    val datas = m.map{ _.io.output.data }
    val d = chisel3.util.Mux1H(sel, datas)
    wire := d
  }


}
