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

package utility

import chisel3._
import chisel3.util._

class DFTResetSignals extends Bundle {
  val lgc_rst_n = AsyncReset()
  val mode = Bool()
  val scan_mode = Bool()
}

class ResetGen(SYNC_NUM: Int = 3) extends Module {
  val o_reset = IO(Output(AsyncReset()))
  val dft = IO(Input(new DFTResetSignals()))
  private val lgc_rst = !dft.lgc_rst_n.asBool
  private val real_reset = Mux(dft.mode, lgc_rst, reset.asBool).asAsyncReset
  private val raw_reset = Wire(AsyncReset())
  withClockAndReset(clock, real_reset){
    val pipe_reset = RegInit(((1L << SYNC_NUM) - 1).U(SYNC_NUM.W))
    pipe_reset := Cat(pipe_reset(SYNC_NUM - 2, 0), 0.U(1.W))
    raw_reset := pipe_reset(SYNC_NUM - 1).asAsyncReset
  }

  // deassertion of the reset needs to be synchronized.
  o_reset := Mux(dft.scan_mode, lgc_rst, raw_reset.asBool).asAsyncReset
}

trait ResetNode

case class ModuleNode(mod: Module) extends ResetNode
case class CellNode(reset: Reset) extends ResetNode

case class ResetGenNode(children: Seq[ResetNode]) extends ResetNode

object ResetGen {
  def apply(SYNC_NUM: Int = 3, dft: Option[DFTResetSignals] = None): AsyncReset = {
    val resetSync = Module(new ResetGen(SYNC_NUM))
    resetSync.dft := dft.getOrElse(0.U.asTypeOf(new DFTResetSignals))
    resetSync.o_reset
  }
  def apply(dft: Option[DFTResetSignals]): AsyncReset = apply(3, dft)

  def apply(resetTree: ResetNode, reset: Reset, sim: Boolean, dft:Option[DFTResetSignals]): Unit = {
    if(!sim) {
      resetTree match {
        case ModuleNode(mod) =>
          mod.reset := reset
        case CellNode(r) =>
          r := reset
        case ResetGenNode(children) =>
          val next_rst = Wire(Reset())
          withReset(reset){
            val resetGen = Module(new ResetGen)
            next_rst := resetGen.o_reset
            resetGen.dft := dft.getOrElse(0.U.asTypeOf(new DFTResetSignals))
          }
          children.foreach(child => apply(child, next_rst, sim, dft))
      }
    }
  }
  def apply(resetTree: ResetNode, reset: Reset, sim: Boolean): Unit = apply(resetTree, reset, sim, None)

  def apply(resetChain: Seq[Seq[Module]], reset: Reset, sim: Boolean, dft:Option[DFTResetSignals]): Seq[Reset] = {
    val resetReg = Wire(Vec(resetChain.length + 1, Reset()))
    resetReg.foreach(_ := reset)
    for ((resetLevel, i) <- resetChain.zipWithIndex) {
      if (!sim) {
        withReset(resetReg(i)) {
          val resetGen = Module(new ResetGen)
          resetReg(i + 1) := resetGen.o_reset
          resetGen.dft := dft.getOrElse(0.U.asTypeOf(new DFTResetSignals))
        }
      }
      resetLevel.foreach(_.reset := resetReg(i + 1))
    }
    resetReg.tail
  }
  def apply(resetChain: Seq[Seq[Module]], reset: Reset, sim: Boolean): Seq[Reset] = apply(resetChain, reset, sim, None)
}
