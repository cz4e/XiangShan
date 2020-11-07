package xiangshan.backend

import chisel3._
import chisel3.util._
import chisel3.util.experimental.BoringUtils
import xiangshan._
import xiangshan.backend.decode.{DecodeBuffer, DecodeStage}
import xiangshan.backend.rename.Rename
import xiangshan.backend.brq.Brq
import xiangshan.backend.dispatch.Dispatch
import xiangshan.backend.exu._
import xiangshan.backend.fu.FunctionUnit
import xiangshan.backend.issue.{IssueQueue, ReservationStation}
import xiangshan.backend.regfile.{Regfile, RfWritePort}
import xiangshan.backend.roq.Roq
import xiangshan.mem._
import utils._

/** Backend Pipeline:
  * Decode -> Rename -> Dispatch-1 -> Dispatch-2 -> Issue -> Exe
  */
class Backend extends XSModule
  with NeedImpl {
  val io = IO(new Bundle {
    val frontend = Flipped(new FrontendToBackendIO)
    val mem = Flipped(new MemToBackendIO)
  })
  val timer = GTimer()

  val aluExeUnits =Array.tabulate(exuParameters.AluCnt)(_ => Module(new AluExeUnit))
  val jmpExeUnit = Module(new JmpExeUnit)
  val mulExeUnits = Array.tabulate(exuParameters.MulCnt)(_ => Module(new MulExeUnit))
  val mduExeUnits = Array.tabulate(exuParameters.MduCnt)(_ => Module(new MulDivExeUnit))
  // val fmacExeUnits = Array.tabulate(exuParameters.FmacCnt)(_ => Module(new Fmac))
  // val fmiscExeUnits = Array.tabulate(exuParameters.FmiscCnt)(_ => Module(new Fmisc))
  // val fmiscDivSqrtExeUnits = Array.tabulate(exuParameters.FmiscDivSqrtCnt)(_ => Module(new FmiscDivSqrt))
  val exeUnits = jmpExeUnit +: (aluExeUnits ++ mulExeUnits ++ mduExeUnits)
  exeUnits.foreach(exe => {
    exe.io.exception := DontCare
    exe.io.dmem := DontCare
    exe.io.mcommit := DontCare
  })

  val decode = Module(new DecodeStage)
  val brq = Module(new Brq)
  val decBuf = Module(new DecodeBuffer)
  val rename = Module(new Rename)
  val dispatch = Module(new Dispatch)
  val roq = Module(new Roq)
  val intRf = Module(new Regfile(
    numReadPorts = NRIntReadPorts,
    numWirtePorts = NRIntWritePorts,
    hasZero = true
  ))
  val fpRf = Module(new Regfile(
    numReadPorts = NRFpReadPorts,
    numWirtePorts = NRFpWritePorts,
    hasZero = false
  ))

  // backend redirect, flush pipeline
  val redirect = Mux(
    roq.io.redirect.valid,
    roq.io.redirect,
    Mux(
      brq.io.redirect.valid,
      brq.io.redirect,
      io.mem.replayAll
    )
  )

  io.frontend.redirect := redirect
  io.frontend.redirect.valid := redirect.valid && !redirect.bits.isReplay

  val memConfigs =
    Seq.fill(exuParameters.LduCnt)(Exu.ldExeUnitCfg) ++
    Seq.fill(exuParameters.StuCnt)(Exu.stExeUnitCfg)

  val exuConfigs = exeUnits.map(_.config) ++ memConfigs

  val exeWbReqs = exeUnits.map(_.io.out) ++ io.mem.ldout ++ io.mem.stout

  def needWakeup(cfg: ExuConfig): Boolean =
    (cfg.readIntRf && cfg.writeIntRf) || (cfg.readFpRf && cfg.writeFpRf)

  def needData(a: ExuConfig, b: ExuConfig): Boolean =
    (a.readIntRf && b.writeIntRf) || (a.readFpRf && b.writeFpRf)


  val reservedStations = exuConfigs.zipWithIndex.map({ case (cfg, i) =>
    val wakeUpDateVec = exuConfigs.zip(exeWbReqs).filter(x => needData(cfg, x._1)).map(_._2)
    val bypassCnt = exuConfigs.count(c => c.enableBypass && needData(cfg, c))

    println(s"exu:${cfg.name} wakeupCnt:${wakeUpDateVec.length} bypassCnt:$bypassCnt")

    val rs = Module(new ReservationStation(
      cfg, wakeUpDateVec.length, bypassCnt, cfg.enableBypass, fifo = false
    ))
    rs.io.redirect <> redirect
    rs.io.numExist <> dispatch.io.numExist(i)
    rs.io.enqCtrl <> dispatch.io.enqIQCtrl(i)
    rs.io.enqData <> dispatch.io.enqIQData(i)
    for(
      (wakeUpPort, exuOut) <-
        rs.io.wakeUpPorts.zip(wakeUpDateVec)
    ){
      wakeUpPort.bits := exuOut.bits
      wakeUpPort.valid := exuOut.valid
    }

    cfg match {
      case Exu.ldExeUnitCfg =>
      case Exu.stExeUnitCfg =>
      case otherCfg =>
        exeUnits(i).io.in <> rs.io.deq
        exeUnits(i).io.in.bits.uop.debugInfo.issueTime := timer
        exeUnits(i).io.redirect <> redirect
        rs.io.tlbFeedback := DontCare
    }

    rs
  })

  for(rs <- reservedStations){
    rs.io.bypassUops <> reservedStations.
      filter(x => x.enableBypass && needData(rs.exuCfg, x.exuCfg)).
      map(_.io.selectedUop)

    val bypassDataVec = exuConfigs.zip(exeWbReqs).
      filter(x => x._1.enableBypass && needData(rs.exuCfg, x._1)).map(_._2)

    for(i <- bypassDataVec.indices){
      rs.io.bypassData(i).valid := bypassDataVec(i).valid
      rs.io.bypassData(i).bits := bypassDataVec(i).bits
    }
  }

  io.mem.commits <> roq.io.commits
  io.mem.roqDeqPtr := roq.io.roqDeqPtr
  io.mem.ldin <> reservedStations.filter(_.exuCfg == Exu.ldExeUnitCfg).map(_.io.deq)
  io.mem.ldin.map(_.bits.uop.debugInfo.issueTime := timer)
  io.mem.stin <> reservedStations.filter(_.exuCfg == Exu.stExeUnitCfg).map(_.io.deq)
  io.mem.stin.map(_.bits.uop.debugInfo.issueTime := timer)
  io.mem.tlbFeedback <> reservedStations.filter(_.exuCfg == Exu.ldExeUnitCfg).map(_.io.tlbFeedback) ++ reservedStations.filter(_.exuCfg == Exu.stExeUnitCfg).map(_.io.tlbFeedback)
  jmpExeUnit.io.exception.valid := roq.io.redirect.valid && roq.io.redirect.bits.isException
  jmpExeUnit.io.exception.bits := roq.io.exception

  io.frontend.outOfOrderBrInfo <> brq.io.outOfOrderBrInfo
  io.frontend.inOrderBrInfo <> brq.io.inOrderBrInfo

  decode.io.in <> io.frontend.cfVec
  brq.io.roqRedirect <> roq.io.redirect
  brq.io.memRedirect <> io.mem.replayAll
  brq.io.bcommit := roq.io.bcommit
  brq.io.enqReqs <> decode.io.toBrq
  for ((x, y) <- brq.io.exuRedirect.zip(exeUnits.filter(_.config.hasRedirect))) {
    x.bits := y.io.out.bits
    x.valid := y.io.out.fire() && y.io.out.bits.redirectValid
  }
  decode.io.brTags <> brq.io.brTags
  decBuf.io.isWalking := Cat(roq.io.commits.map(c => c.valid && c.bits.isWalk)).orR // TODO: opt this
  decBuf.io.redirect <> redirect
  decBuf.io.in <> decode.io.out

  rename.io.redirect <> redirect
  rename.io.roqCommits <> roq.io.commits
  rename.io.in <> decBuf.io.out
  rename.io.intRfReadAddr <> dispatch.io.readIntRf.map(_.addr) ++ dispatch.io.memIntRf.map(_.addr)
  rename.io.intPregRdy <> dispatch.io.intPregRdy ++ dispatch.io.intMemRegRdy
  rename.io.fpRfReadAddr <> dispatch.io.readFpRf.map(_.addr) ++ dispatch.io.memFpRf.map(_.addr)
  rename.io.fpPregRdy <> dispatch.io.fpPregRdy ++ dispatch.io.fpMemRegRdy
  rename.io.replayPregReq <> dispatch.io.replayPregReq
  dispatch.io.redirect <> redirect
  dispatch.io.fromRename <> rename.io.out
  dispatch.io.fromRename.foreach(_.bits.debugInfo.renameTime := timer)

  roq.io.memRedirect <> io.mem.replayAll
  roq.io.brqRedirect <> brq.io.redirect
  roq.io.dp1Req <> dispatch.io.toRoq
  roq.io.dp1Req.foreach(_.bits.debugInfo.dispatchTime := timer)
  dispatch.io.roqIdxs <> roq.io.roqIdxs
  io.mem.dp1Req <> dispatch.io.toLsroq
  dispatch.io.lsIdxs <> io.mem.lsIdxs
  dispatch.io.dequeueRoqIndex.valid := roq.io.commitRoqIndex.valid || io.mem.oldestStore.valid
  // store writeback must be after commit roqIdx
  dispatch.io.dequeueRoqIndex.bits := Mux(io.mem.oldestStore.valid, io.mem.oldestStore.bits, roq.io.commitRoqIndex.bits)


  intRf.io.readPorts <> dispatch.io.readIntRf ++ dispatch.io.memIntRf
  fpRf.io.readPorts <> dispatch.io.readFpRf ++ dispatch.io.memFpRf

  io.mem.redirect <> redirect

  val wbu = Module(new Wbu(exuConfigs))
  wbu.io.in <> exeWbReqs

  val wbIntResults = wbu.io.toIntRf
  val wbFpResults = wbu.io.toFpRf

  def exuOutToRfWrite(x: Valid[ExuOutput]): RfWritePort = {
    val rfWrite = Wire(new RfWritePort)
    rfWrite.wen := x.valid
    rfWrite.addr := x.bits.uop.pdest
    rfWrite.data := x.bits.data
    rfWrite
  }
  intRf.io.writePorts <> wbIntResults.map(exuOutToRfWrite)
  fpRf.io.writePorts <> wbFpResults.map(exuOutToRfWrite)

  rename.io.wbIntResults <> wbIntResults
  rename.io.wbFpResults <> wbFpResults

  roq.io.exeWbResults.take(exeWbReqs.length).zip(wbu.io.toRoq).foreach(x => x._1 := x._2)
  roq.io.exeWbResults.last := brq.io.out
  roq.io.exeWbResults.foreach(_.bits.uop.debugInfo.writebackTime := timer)

  val commitTime = timer

  val renameToCommit = roq.io.commits.map(c => Mux(c.valid && !c.bits.isWalk, commitTime - c.bits.uop.debugInfo.renameTime, 0.U))//.reduce(_ + _)
  val dispatchToCommit = roq.io.commits.map(c => Mux(c.valid && !c.bits.isWalk, commitTime - c.bits.uop.debugInfo.dispatchTime, 0.U))//.reduce(_ + _)
  val issueToCommit = roq.io.commits.map(c => Mux(c.valid && !c.bits.isWalk, commitTime - c.bits.uop.debugInfo.issueTime, 0.U))//.reduce(_ + _)
  val writebackToCommit = roq.io.commits.map(c => Mux(c.valid && !c.bits.isWalk, commitTime - c.bits.uop.debugInfo.writebackTime, 0.U))//.reduce(_ + _)
  val loadIssueToCommit = roq.io.commits.map(c => Mux(c.valid && !c.bits.isWalk && c.bits.uop.ctrl.commitType === CommitType.LOAD, commitTime - c.bits.uop.debugInfo.issueTime, 0.U))//.reduce(_ + _)
  val loadIssueToWriteback = roq.io.commits.map(c => Mux(c.valid && !c.bits.isWalk && c.bits.uop.ctrl.commitType === CommitType.LOAD, c.bits.uop.debugInfo.writebackTime - c.bits.uop.debugInfo.issueTime, 0.U))//.reduce(_ + _)
  val storeIssueToCommit = roq.io.commits.map(c => Mux(c.valid && !c.bits.isWalk && c.bits.uop.ctrl.commitType === CommitType.STORE, commitTime - c.bits.uop.debugInfo.issueTime, 0.U))//.reduce(_ + _)
  val storeIssueToWriteback = roq.io.commits.map(c => Mux(c.valid && !c.bits.isWalk && c.bits.uop.ctrl.commitType === CommitType.STORE, c.bits.uop.debugInfo.writebackTime - c.bits.uop.debugInfo.issueTime, 0.U))//.reduce(_ + _)

  for (i <- 0 until CommitWidth) {
    XSPerf("renameToCommit"+i.toString, renameToCommit(i))
    XSPerf("dispatchToCommit"+i.toString, dispatchToCommit(i))
    XSPerf("issueToCommit"+i.toString, issueToCommit(i))
    XSPerf("writebackToCommit"+i.toString, writebackToCommit(i))
    XSPerf("loadIssueToCommit"+i.toString, loadIssueToCommit(i))
    XSPerf("loadIssueToWriteback"+i.toString, loadIssueToWriteback(i))
    XSPerf("storeIssueToCommit"+i.toString, storeIssueToCommit(i))
    XSPerf("storeIssueToWriteback"+i.toString, storeIssueToWriteback(i))
  }

  // TODO: Remove sink and source
  val tmp = WireInit(0.U)
  val sinks = Array[String](
    "DTLBFINISH",
    "DTLBPF",
    "DTLBENABLE",
    "perfCntCondMdcacheLoss",
    "perfCntCondMl2cacheLoss",
    "perfCntCondMdcacheHit",
    "lsuMMIO",
    "perfCntCondMl2cacheHit",
    "perfCntCondMl2cacheReq",
    "mtip",
    "perfCntCondMdcacheReq",
    "meip"
  )
  for (s <- sinks) {
    BoringUtils.addSink(tmp, s)
  }

  val debugIntReg, debugFpReg = WireInit(VecInit(Seq.fill(32)(0.U(XLEN.W))))
  BoringUtils.addSink(debugIntReg, "DEBUG_INT_ARCH_REG")
  BoringUtils.addSink(debugFpReg, "DEBUG_FP_ARCH_REG")
  val debugArchReg = WireInit(VecInit(debugIntReg ++ debugFpReg))
  if (!env.FPGAPlatform) {
    BoringUtils.addSource(debugArchReg, "difftestRegs")
  }

}
