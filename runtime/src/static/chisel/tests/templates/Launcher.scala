// See LICENSE.txt for license details.
package templates

import chisel3.iotesters.{PeekPokeTester, Driver, ChiselFlatSpec}
import utils.TemplateRunner
import scala.reflect.runtime._ 
import scala.reflect.runtime.universe

// Ripped from http://stackoverflow.com/questions/1469958/scala-how-do-i-dynamically-instantiate-an-object-and-invoke-a-method-using-refl
object Inst {

  def apply(className: String, arg: Any) = {
    val runtimeMirror: universe.Mirror = universe.runtimeMirror(getClass.getClassLoader)
    val classSymbol: universe.ClassSymbol = runtimeMirror.classSymbol(Class.forName(s"templates.${className}"))
    val classMirror: universe.ClassMirror = runtimeMirror.reflectClass(classSymbol)
    if (classSymbol.companion.toString() == "<none>") {
      println(s"Info: $className has no companion object")
      val constructors = classSymbol.typeSignature.members.filter(_.isConstructor).toList
      if (constructors.length > 1) { 
        println(s"Info: $className has several constructors")
      } else {
        val constructorMirror = classMirror.reflectConstructor(constructors.head.asMethod) // we can reuse it
        constructorMirror()
      }
    } else {
      val companionSymbol = classSymbol.companion
      println(s"Info: $className has companion object $companionSymbol")
      // TBD
    }
  }
}

// Start args
object Arguments {
  val FF = List(
    16,
    32,
    64
  )
  val FFNoInit = List(
    32
  )
  val FFNoInitNoReset = List(
    32
  )
  val FFNoReset = List(
    32
  )
  val TFF = List(
    "null"
  )
  val SRFF = List(
    "null"
  )
  val SingleCounter = List(
    1,3
  )
  val Counter = List(
    List(2,2,2),
    List(4,1,1)
  )
  val Sequential = List(
    1,
    10
  )
  val Metapipe = List(
    1,
    5
  )
  val Delay = List(
    10
  )
  val Mem1D = List(
    50,
    1024
  )
  val MemND = List(
    List(4,8),
    List(5,9)
  )
  val SRAM = List( // Contain each set of args in its own list
           ( List(16,16), 1, 32, 
             List(1,2), List(1,1), 1, 1,
             2, 2, "strided"),
           ( List(16,16), 1, 32, 
             List(1,1), List(1,1), 1, 1,
             1, 1, "strided")
        )
  val Pipe = List(
    2
  )
  val Parallel = List(
    3
  )
}
// End args


object Launcher {
  var templates:Map[String,String => Boolean] = Map() 

  // Start launcher
  templates = templates ++ Arguments.FF.zipWithIndex.map{ case(arg,i) => 
    (s"FF$i" -> { (backendName: String) =>
        Driver(() => new FF(arg), "verilator") {
          (c) => new FFTests(c)
        }
      }) 
  }.toMap

  templates = templates ++ Arguments.FFNoInit.zipWithIndex.map{ case(arg,i) => 
    (s"FFNoInit$i" -> { (backendName: String) =>
        Driver(() => new FFNoInit(arg), "verilator") {
          (c) => new FFNoInitTests(c)
        }
      }) 
  }.toMap

  templates = templates ++ Arguments.FFNoInitNoReset.zipWithIndex.map{ case(arg,i) => 
    (s"FFNoInitNoReset$i" -> { (backendName: String) =>
        Driver(() => new FFNoInitNoReset(arg), "verilator") {
          (c) => new FFNoInitNoResetTests(c)
        }
      }) 
  }.toMap

  templates = templates ++ Arguments.FFNoReset.zipWithIndex.map{ case(arg,i) => 
    (s"FFNoReset$i" -> { (backendName: String) =>
        Driver(() => new FFNoReset(arg), "verilator") {
          (c) => new FFNoResetTests(c)
        }
      }) 
  }.toMap

  templates = templates ++ Arguments.TFF.zipWithIndex.map{ case(arg,i) => 
    (s"TFF$i" -> { (backendName: String) =>
        Driver(() => new TFF(arg), "verilator") {
          (c) => new TFFTests(c)
        }
      }) 
  }.toMap

  templates = templates ++ Arguments.SRFF.zipWithIndex.map{ case(arg,i) => 
    (s"SRFF$i" -> { (backendName: String) =>
        Driver(() => new SRFF(arg), "verilator") {
          (c) => new SRFFTests(c)
        }
      }) 
  }.toMap

  templates = templates ++ Arguments.SingleCounter.zipWithIndex.map{ case(arg,i) => 
    (s"SingleCounter$i" -> { (backendName: String) =>
        Driver(() => new SingleCounter(arg), "verilator") {
          (c) => new SingleCounterTests(c)
        }
      }) 
  }.toMap

  templates = templates ++ Arguments.Counter.zipWithIndex.map{ case(arg,i) => 
    (s"Counter$i" -> { (backendName: String) =>
        Driver(() => new Counter(arg), "verilator") {
          (c) => new CounterTests(c)
        }
      }) 
  }.toMap

  templates = templates ++ Arguments.Sequential.zipWithIndex.map{ case(arg,i) => 
    (s"Sequential$i" -> { (backendName: String) =>
        Driver(() => new Sequential(arg), "verilator") {
          (c) => new SequentialTests(c)
        }
      }) 
  }.toMap

  templates = templates ++ Arguments.Metapipe.zipWithIndex.map{ case(arg,i) => 
    (s"Metapipe$i" -> { (backendName: String) =>
        Driver(() => new Metapipe(arg), "verilator") {
          (c) => new MetapipeTests(c)
        }
      }) 
  }.toMap

  templates = templates ++ Arguments.Delay.zipWithIndex.map{ case(arg,i) => 
    (s"Delay$i" -> { (backendName: String) =>
        Driver(() => new Delay(arg), "verilator") {
          (c) => new DelayTests(c)
        }
      }) 
  }.toMap

  templates = templates ++ Arguments.Mem1D.zipWithIndex.map{ case(arg,i) => 
    (s"Mem1D$i" -> { (backendName: String) =>
        Driver(() => new Mem1D(arg), "verilator") {
          (c) => new Mem1DTests(c)
        }
      }) 
  }.toMap

  templates = templates ++ Arguments.MemND.zipWithIndex.map{ case(arg,i) => 
    (s"MemND$i" -> { (backendName: String) =>
        Driver(() => new MemND(arg), "verilator") {
          (c) => new MemNDTests(c)
        }
      }) 
  }.toMap

  templates = templates ++ Arguments.SRAM.zipWithIndex.map{ case(arg,i) => 
    (s"SRAM$i" -> { (backendName: String) =>
        Driver(() => new SRAM(arg), "verilator") {
          (c) => new SRAMTests(c)
        }
      }) 
  }.toMap

  templates = templates ++ Arguments.Pipe.zipWithIndex.map{ case(arg,i) => 
    (s"Pipe$i" -> { (backendName: String) =>
        Driver(() => new Pipe(arg), "verilator") {
          (c) => new PipeTests(c)
        }
      }) 
  }.toMap

  templates = templates ++ Arguments.Parallel.zipWithIndex.map{ case(arg,i) => 
    (s"Parallel$i" -> { (backendName: String) =>
        Driver(() => new Parallel(arg), "verilator") {
          (c) => new ParallelTests(c)
        }
      }) 
  }.toMap

// End launcher             

  def main(args: Array[String]): Unit = {
    TemplateRunner(templates, args)
  }
}

//lastline