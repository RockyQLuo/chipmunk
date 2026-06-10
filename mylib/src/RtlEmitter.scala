package mylib

import chipmunk._
import chipmunk.amba._
import chipmunk.component.acorn._
import chipmunk.component.spi._
import chisel3._
import chisel3.experimental.dataview._
import circt.stage._

class MyIncrement extends Module {
  val io = IO(new Bundle {
    val source = Input(UInt(3.W))
    val sink   = Output(UInt(3.W))
  })
  io.sink := RegNext(io.source + 1.U, init = 0.U)
}

class MyChipTop extends RawModule {
  val coreClock  = IO(Input(Clock()))
  val coreReset  = IO(Input(AsyncReset()))
  val coreSource = IO(Input(SInt(3.W)))
  val coreSink   = IO(Output(UInt(3.W)))

  implicit val clockSys: Clock      = coreClock
  implicit val resetSys: AsyncReset = AsyncResetSyncDessert.withSpecificClockDomain(clockSys, coreReset)

  withClockAndReset(clockSys, resetSys) {
    val uIncrement = Module(new MyIncrement)
    uIncrement.io.source <> coreSource.asUInt
    uIncrement.io.sink <> coreSink
  }
}

object RtlEmitter extends App {
  val targetDir = "generate/hw"

  val chiselArgs  = Array(f"--target-dir=$targetDir", "--split-verilog")
  val firtoolOpts =
    Array("--disable-all-randomization", "-repl-seq-mem", f"-repl-seq-mem-file=seq-mem.conf")

  ChiselStage
    .emitSystemVerilogFile(new MyChipTop, chiselArgs, firtoolOpts)
  println(f">>> RTL emitted in \"$targetDir\" directory.")
}

class SpiToAxiLiteTop(
  hasMisoValid: Boolean = true,
  spiClockPriority: Boolean = false,
  spiClockPhase: Boolean = false,
  busAddrWidth: Int = 32
) extends Module {
  val io = IO(new Bundle {
    val sck       = Input(Bool())
    val ssn       = Input(Bool())
    val mosi      = Input(Bool())
    val miso      = Output(Bool())
    val misoValid = if (hasMisoValid) Some(Output(Bool())) else None

    val mAxil = Master(
      new Axi4LiteIO(dataWidth = 32, addrWidth = busAddrWidth)
        .createVerilogIO(Seq(PortNameTransform.stringPrefix("m_axil_")))
    )
  })

  val uSpiDebugger = Module(
    new SpiDebugger(
      hasMisoValid = hasMisoValid,
      spiClockPriority = spiClockPriority,
      spiClockPhase = spiClockPhase,
      busAddrWidth = busAddrWidth
    )
  )
  val uAcornToAxiLite = Module(new AcornToAxiLiteBridge(dataWidth = 32, addrWidth = busAddrWidth))

  uSpiDebugger.io.sSpi.sck  := io.sck
  uSpiDebugger.io.sSpi.ssn  := io.ssn
  uSpiDebugger.io.sSpi.mosi := io.mosi
  io.miso                   := uSpiDebugger.io.sSpi.miso
  if (hasMisoValid) {
    io.misoValid.get := uSpiDebugger.io.sSpi.misoValid.get
  }

  uAcornToAxiLite.io.sAcorn <> uSpiDebugger.io.mDbg
  io.mAxil.viewAs[Axi4LiteIO] <> uAcornToAxiLite.io.mAxiL
}

object SpiToAxiLiteEmitter extends App {
  val targetDir = "../../ucie_integration/rtl/third_party/spi2apb/wrap/src/generated"

  val chiselArgs = Array(s"--target-dir=$targetDir", "--split-verilog")
  val firtoolOpts = Array(
    "--disable-all-randomization",
    "--strip-debug-info",
    "-repl-seq-mem",
    "-repl-seq-mem-file=seq-mem.conf"
  )

  ChiselStage.emitSystemVerilogFile(new SpiToAxiLiteTop, chiselArgs, firtoolOpts)
  println(s""">>> SPI-to-AXI-Lite RTL emitted in "$targetDir" directory.""")
}
