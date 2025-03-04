/***************************************************************************************
* Copyright (c) 2020-2021 Institute of Computing Technology, Chinese Academy of Sciences
* Copyright (c) 2020-2021 Peng Cheng Laboratory
*
* XiangShan is licensed under Mulan PSL v2.
* You can use this software according to the terms and conditions of the Mulan PSL v2.
* You may obtain a copy of Mulan PSL v2 at:
*          http://license.coscl.org.cn/MulanPSL2
*
* THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
* EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
* MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
*
* See the Mulan PSL v2 for more details.
***************************************************************************************/

package xiangshan.cache.mmu

import chipsalliance.rocketchip.config.Parameters
import chisel3._
import chisel3.util._
import xiangshan._
import xiangshan.cache.{HasDCacheParameters, MemoryOpConstants}
import utils._
import freechips.rocketchip.diplomacy.{LazyModule, LazyModuleImp}
import freechips.rocketchip.tilelink._

class PTWReapterIO(Width: Int)(implicit p: Parameters) extends MMUIOBaseBundle {
  val tlb = Flipped(new TlbPtwIO(Width))
  val ptw = new TlbPtwIO

  def apply(tlb: TlbPtwIO, ptw: TlbPtwIO, sfence: SfenceBundle, csr: TlbCsrBundle): Unit = {
    this.tlb <> tlb
    this.ptw <> ptw
    this.sfence <> sfence
    this.csr <> csr
  }

  def apply(tlb: TlbPtwIO, sfence: SfenceBundle, csr: TlbCsrBundle): Unit = {
    this.tlb <> tlb
    this.sfence <> sfence
    this.csr <> csr
  }

  override def cloneType: this.type = (new PTWReapterIO(Width)).asInstanceOf[this.type]
}

class PTWRepeater(Width: Int = 1)(implicit p: Parameters) extends XSModule with HasPtwConst {
  val io = IO(new PTWReapterIO(Width))

  val req_in = if (Width == 1) {
    io.tlb.req(0)
  } else {
    val arb = Module(new RRArbiter(io.tlb.req(0).bits.cloneType, Width))
    arb.io.in <> io.tlb.req
    arb.io.out
  }
  val (tlb, ptw, flush) = (io.tlb, io.ptw, RegNext(io.sfence.valid || io.csr.satp.changed))
  val req = RegEnable(req_in.bits, req_in.fire())
  val resp = RegEnable(ptw.resp.bits, ptw.resp.fire())
  val haveOne = BoolStopWatch(req_in.fire(), tlb.resp.fire() || flush)
  val sent = BoolStopWatch(ptw.req(0).fire(), req_in.fire() || flush)
  val recv = BoolStopWatch(ptw.resp.fire(), req_in.fire() || flush)

  req_in.ready := !haveOne
  ptw.req(0).valid := haveOne && !sent
  ptw.req(0).bits := req

  tlb.resp.bits := resp
  tlb.resp.valid := haveOne && recv
  ptw.resp.ready := !recv

  XSPerfAccumulate("req_count", ptw.req(0).fire())
  XSPerfAccumulate("tlb_req_cycle", BoolStopWatch(req_in.fire(), tlb.resp.fire() || flush))
  XSPerfAccumulate("ptw_req_cycle", BoolStopWatch(ptw.req(0).fire(), ptw.resp.fire() || flush))

  XSDebug(haveOne, p"haveOne:${haveOne} sent:${sent} recv:${recv} sfence:${flush} req:${req} resp:${resp}")
  XSDebug(req_in.valid || io.tlb.resp.valid, p"tlb: ${tlb}\n")
  XSDebug(io.ptw.req(0).valid || io.ptw.resp.valid, p"ptw: ${ptw}\n")
  assert(!RegNext(recv && io.ptw.resp.valid, init = false.B), "re-receive ptw.resp")
  TimeOutAssert(sent && !recv, timeOutThreshold, "Repeater doesn't recv resp in time")
}

/* dtlb
 *
 */

class PTWRepeaterNB(Width: Int = 1, passReady: Boolean = false)(implicit p: Parameters) extends XSModule with HasPtwConst {
  val io = IO(new PTWReapterIO(Width))

  val req_in = if (Width == 1) {
    io.tlb.req(0)
  } else {
    val arb = Module(new RRArbiter(io.tlb.req(0).bits.cloneType, Width))
    arb.io.in <> io.tlb.req
    arb.io.out
  }
  val (tlb, ptw, flush) = (io.tlb, io.ptw, RegNext(io.sfence.valid || io.csr.satp.changed))
  /* sent: tlb -> repeater -> ptw
   * recv: ptw -> repeater -> tlb
   * different from PTWRepeater
   */

  // tlb -> repeater -> ptw
  val req = RegEnable(req_in.bits, req_in.fire())
  val sent = BoolStopWatch(req_in.fire(), ptw.req(0).fire() || flush)
  req_in.ready := !sent || { if (passReady) ptw.req(0).ready else false.B }
  ptw.req(0).valid := sent
  ptw.req(0).bits := req

  // ptw -> repeater -> tlb
  val resp = RegEnable(ptw.resp.bits, ptw.resp.fire())
  val recv = BoolStopWatch(ptw.resp.fire(), tlb.resp.fire() || flush)
  ptw.resp.ready := !recv || { if (passReady) tlb.resp.ready else false.B }
  tlb.resp.valid := recv
  tlb.resp.bits := resp

  XSPerfAccumulate("req", req_in.fire())
  XSPerfAccumulate("resp", tlb.resp.fire())
  if (!passReady) {
    XSPerfAccumulate("req_blank", req_in.valid && sent && ptw.req(0).ready)
    XSPerfAccumulate("resp_blank", ptw.resp.valid && recv && tlb.resp.ready)
    XSPerfAccumulate("req_blank_ignore_ready", req_in.valid && sent)
    XSPerfAccumulate("resp_blank_ignore_ready", ptw.resp.valid && recv)
  }
  XSDebug(req_in.valid || io.tlb.resp.valid, p"tlb: ${tlb}\n")
  XSDebug(io.ptw.req(0).valid || io.ptw.resp.valid, p"ptw: ${ptw}\n")
}

class PTWFilterIO(Width: Int)(implicit p: Parameters) extends MMUIOBaseBundle {
  val tlb = Flipped(new BTlbPtwIO(Width))
  val ptw = new TlbPtwIO()

  def apply(tlb: BTlbPtwIO, ptw: TlbPtwIO, sfence: SfenceBundle, csr: TlbCsrBundle): Unit = {
    this.tlb <> tlb
    this.ptw <> ptw
    this.sfence <> sfence
    this.csr <> csr
  }

  def apply(tlb: BTlbPtwIO, sfence: SfenceBundle, csr: TlbCsrBundle): Unit = {
    this.tlb <> tlb
    this.sfence <> sfence
    this.csr <> csr
  }

  override def cloneType: this.type = (new PTWFilterIO(Width)).asInstanceOf[this.type]
}

class PTWFilter(Width: Int, Size: Int)(implicit p: Parameters) extends XSModule with HasPtwConst {
  require(Size >= Width)

  val io = IO(new PTWFilterIO(Width))

  val v = RegInit(VecInit(Seq.fill(Size)(false.B)))
  val ports = Reg(Vec(Size, Vec(Width, Bool()))) // record which port(s) the entry come from, may not able to cover all the ports
  val vpn = Reg(Vec(Size, UInt(vpnLen.W)))
  val enqPtr = RegInit(0.U(log2Up(Size).W)) // Enq
  val issPtr = RegInit(0.U(log2Up(Size).W)) // Iss to Ptw
  val deqPtr = RegInit(0.U(log2Up(Size).W)) // Deq
  val mayFullDeq = RegInit(false.B)
  val mayFullIss = RegInit(false.B)
  val counter = RegInit(0.U(log2Up(Size+1).W))

  val flush = RegNext(io.sfence.valid || io.csr.satp.changed)
  val tlb_req = WireInit(io.tlb.req)
  tlb_req.suggestName("tlb_req")

  val ptwResp = RegEnable(io.ptw.resp.bits, io.ptw.resp.fire())
  val ptwResp_OldMatchVec = vpn.zip(v).map{ case (pi, vi) =>
    vi && io.ptw.resp.bits.entry.hit(pi, io.csr.satp.asid, true, true)}
  val ptwResp_valid = RegNext(io.ptw.resp.fire() && Cat(ptwResp_OldMatchVec).orR, init = false.B)
  val oldMatchVec_early = io.tlb.req.map(a => vpn.zip(v).map{ case (pi, vi) => vi && pi === a.bits.vpn})
  val lastReqMatchVec_early = io.tlb.req.map(a => tlb_req.map{ b => b.valid && b.bits.vpn === a.bits.vpn})
  val newMatchVec_early = io.tlb.req.map(a => io.tlb.req.map(b => a.bits.vpn === b.bits.vpn))

  (0 until Width) foreach { i =>
    tlb_req(i).valid := RegNext(io.tlb.req(i).valid &&
      !(ptwResp_valid && ptwResp.entry.hit(io.tlb.req(i).bits.vpn, 0.U, true, true)) &&
      !Cat(lastReqMatchVec_early(i)).orR,
      init = false.B)
    tlb_req(i).bits := RegEnable(io.tlb.req(i).bits, io.tlb.req(i).valid)
  }

  val oldMatchVec = oldMatchVec_early.map(a => RegNext(Cat(a).orR))
  val newMatchVec = (0 until Width).map(i => (0 until Width).map(j =>
    RegNext(newMatchVec_early(i)(j)) && tlb_req(j).valid
  ))
  val ptwResp_newMatchVec = tlb_req.map(a =>
    ptwResp_valid && ptwResp.entry.hit(a.bits.vpn, 0.U, allType = true, true))

  val oldMatchVec2 = (0 until Width).map(i => oldMatchVec_early(i).map(RegNext(_)).map(_ & tlb_req(i).valid))
  val update_ports = v.indices.map(i => oldMatchVec2.map(j => j(i)))
  val ports_init = (0 until Width).map(i => (1 << i).U(Width.W))
  val filter_ports = (0 until Width).map(i => ParallelMux(newMatchVec(i).zip(ports_init).drop(i)))
  val resp_vector = RegEnable(ParallelMux(ptwResp_OldMatchVec zip ports), io.ptw.resp.fire())

  def canMerge(index: Int) : Bool = {
    ptwResp_newMatchVec(index) || oldMatchVec(index) ||
    Cat(newMatchVec(index).take(index)).orR
  }

  def filter_req() = {
    val reqs =  tlb_req.indices.map{ i =>
      val req = Wire(ValidIO(new PtwReq()))
      val merge = canMerge(i)
      req.bits := tlb_req(i).bits
      req.valid := !merge && tlb_req(i).valid
      req
    }
    reqs
  }

  val reqs = filter_req()
  val req_ports = filter_ports
  val isFull = enqPtr === deqPtr && mayFullDeq
  val isEmptyDeq = enqPtr === deqPtr && !mayFullDeq
  val isEmptyIss = enqPtr === issPtr && !mayFullIss
  val accumEnqNum = (0 until Width).map(i => PopCount(reqs.take(i).map(_.valid)))
  val enqPtrVecInit = VecInit((0 until Width).map(i => enqPtr + i.U))
  val enqPtrVec = VecInit((0 until Width).map(i => enqPtrVecInit(accumEnqNum(i))))
  val enqNum = PopCount(reqs.map(_.valid))
  val canEnqueue = counter +& enqNum <= Size.U

  io.tlb.req.map(_.ready := true.B) // NOTE: just drop un-fire reqs
  io.tlb.resp.valid := ptwResp_valid
  io.tlb.resp.bits.data := ptwResp
  io.tlb.resp.bits.vector := resp_vector
  io.ptw.req(0).valid := v(issPtr) && !isEmptyIss && !(ptwResp_valid && ptwResp.entry.hit(io.ptw.req(0).bits.vpn, io.csr.satp.asid, ignoreAsid = true))
  io.ptw.req(0).bits.vpn := vpn(issPtr)
  io.ptw.resp.ready := true.B

  reqs.zipWithIndex.map{
    case (req, i) =>
      when (req.valid && canEnqueue) {
        v(enqPtrVec(i)) := true.B
        vpn(enqPtrVec(i)) := req.bits.vpn
        ports(enqPtrVec(i)) := req_ports(i).asBools
      }
  }
  for (i <- ports.indices) {
    when (v(i)) {
      ports(i) := ports(i).zip(update_ports(i)).map(a => a._1 || a._2)
    }
  }

  val do_enq = canEnqueue && Cat(reqs.map(_.valid)).orR
  val do_deq = (!v(deqPtr) && !isEmptyDeq)
  val do_iss = Mux(v(issPtr), io.ptw.req(0).fire(), !isEmptyIss)
  when (do_enq) {
    enqPtr := enqPtr + enqNum
  }
  when (do_deq) {
    deqPtr := deqPtr + 1.U
  }
  when (do_iss) {
    issPtr := issPtr + 1.U
  }
  when (do_enq =/= do_deq) {
    mayFullDeq := do_enq
  }
  when (do_enq =/= do_iss) {
    mayFullIss := do_enq
  }

  when (io.ptw.resp.fire()) {
    v.zip(ptwResp_OldMatchVec).map{ case (vi, mi) => when (mi) { vi := false.B }}
  }

  counter := counter - do_deq + Mux(do_enq, enqNum, 0.U)
  assert(counter <= Size.U, "counter should be less than Size")
  when (counter === 0.U) {
    assert(!io.ptw.req(0).fire(), "when counter is 0, should not req")
    assert(isEmptyDeq && isEmptyIss, "when counter is 0, should be empty")
  }
  when (counter === Size.U) {
    assert(mayFullDeq, "when counter is Size, should be full")
  }

  when (flush) {
    v.map(_ := false.B)
    deqPtr := 0.U
    enqPtr := 0.U
    issPtr := 0.U
    ptwResp_valid := false.B
    mayFullDeq := false.B
    mayFullIss := false.B
    counter := 0.U
  }

  // perf
  val inflight_counter = RegInit(0.U(log2Up(Size + 1).W))
  when (io.ptw.req(0).fire() =/= io.ptw.resp.fire()) {
    inflight_counter := Mux(io.ptw.req(0).fire(), inflight_counter + 1.U, inflight_counter - 1.U)
  }
  when (flush) {
    inflight_counter := 0.U
  }
  XSPerfAccumulate("tlb_req_count", PopCount(Cat(io.tlb.req.map(_.valid))))
  XSPerfAccumulate("tlb_req_count_filtered", Mux(do_enq, accumEnqNum(Width - 1), 0.U))
  XSPerfAccumulate("ptw_req_count", io.ptw.req(0).fire())
  XSPerfAccumulate("ptw_req_cycle", inflight_counter)
  XSPerfAccumulate("tlb_resp_count", io.tlb.resp.fire())
  XSPerfAccumulate("ptw_resp_count", io.ptw.resp.fire())
  XSPerfAccumulate("inflight_cycle", !isEmptyDeq)
  for (i <- 0 until Size + 1) {
    XSPerfAccumulate(s"counter${i}", counter === i.U)
  }

  for (i <- 0 until Size) {
    TimeOutAssert(v(i), timeOutThreshold, s"Filter ${i} doesn't recv resp in time")
  }
}

object PTWRepeater {
  def apply(
    tlb: TlbPtwIO,
    sfence: SfenceBundle,
    csr: TlbCsrBundle
  )(implicit p: Parameters) = {
    val width = tlb.req.size
    val repeater = Module(new PTWRepeater(width))
    repeater.io.apply(tlb, sfence, csr)
    repeater
  }

  def apply(
    tlb: TlbPtwIO,
    ptw: TlbPtwIO,
    sfence: SfenceBundle,
    csr: TlbCsrBundle
  )(implicit p: Parameters) = {
    val width = tlb.req.size
    val repeater = Module(new PTWRepeater(width))
    repeater.io.apply(tlb, ptw, sfence, csr)
    repeater
  }
}

object PTWRepeaterNB {
  def apply(passReady: Boolean,
    tlb: TlbPtwIO,
    sfence: SfenceBundle,
    csr: TlbCsrBundle
  )(implicit p: Parameters) = {
    val width = tlb.req.size
    val repeater = Module(new PTWRepeaterNB(width, passReady))
    repeater.io.apply(tlb, sfence, csr)
    repeater
  }

  def apply(passReady: Boolean,
    tlb: TlbPtwIO,
    ptw: TlbPtwIO,
    sfence: SfenceBundle,
    csr: TlbCsrBundle
  )(implicit p: Parameters) = {
    val width = tlb.req.size
    val repeater = Module(new PTWRepeaterNB(width, passReady))
    repeater.io.apply(tlb, ptw, sfence, csr)
    repeater
  }
}

object PTWFilter {
  def apply(
    tlb: BTlbPtwIO,
    ptw: TlbPtwIO,
    sfence: SfenceBundle,
    csr: TlbCsrBundle,
    size: Int
  )(implicit p: Parameters) = {
    val width = tlb.req.size
    val filter = Module(new PTWFilter(width, size))
    filter.io.apply(tlb, ptw, sfence, csr)
    filter
  }

  def apply(
    tlb: BTlbPtwIO,
    sfence: SfenceBundle,
    csr: TlbCsrBundle,
    size: Int
  )(implicit p: Parameters) = {
    val width = tlb.req.size
    val filter = Module(new PTWFilter(width, size))
    filter.io.apply(tlb, sfence, csr)
    filter
  }

}