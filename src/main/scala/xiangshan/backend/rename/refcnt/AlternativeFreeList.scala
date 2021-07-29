package xiangshan.backend.rename.refcnt

import chipsalliance.rocketchip.config.Parameters
import chisel3._
import chisel3.util._
import xiangshan._
import xiangshan.backend.rename._
import utils._

class IntFreeListPtr(implicit val p: Parameters) extends CircularQueuePtr[IntFreeListPtr](
  p => p(XSCoreParamsKey).NRPhyRegs // TODO depends on size of free list
)

object IntFreeListPtr {
  def apply(f: Bool, v:UInt)(implicit p: Parameters): IntFreeListPtr = {
    val ptr = Wire(new IntFreeListPtr)
    ptr.flag := f
    ptr.value := v
    ptr
  }
}

class AlternativeFreeList(implicit p: Parameters) extends XSModule with HasCircularQueuePtrHelper {
  val io = IO(new Bundle() {

    // flush
    val flush = Input(Bool())

    // redirect
    val redirect = Input(Bool())

    // increase physical registers reference count (rename)
    val inc = new Bundle {
      // need to increase reference count (not actually do the increment)
      val req = Vec(RenameWidth, Input(Bool()))
      
      // have enough free registers (>= RenameWidth)
      val canInc = Output(Bool())
      // prepared pdest according to req
      val pdests = Vec(RenameWidth, Output(UInt(PhyRegIdxWidth.W)))
      
      // actually do the increment
      val doInc = Input(Bool())
      // psrc of move instructions ready for elimination
      val psrcOfMove = Vec(RenameWidth, Flipped(ValidIO(UInt(PhyRegIdxWidth.W))))
    }

    // decrease physical registers reference count (commit or walk/redirect/recover)
    val dec = new Bundle {
      // instruction need commit/redirect
      val req = Vec(CommitWidth, Input(Bool()))
      // free old p_dest reg
      val old_pdests = Vec(CommitWidth, Input(UInt(PhyRegIdxWidth.W)))
      // instruction fits move elimination
      val eliminatedMove = Vec(CommitWidth, Input(Bool()))
      // for eliminated move instruction, increase arch ref count of (new) p_dest reg
      val pdests = Vec(CommitWidth, Input(UInt(PhyRegIdxWidth.W)))
    }

    // max vector from speculative reference counter
    val maxVec = Vec(NRPhyRegs, Output(Bool()))
  })

  val FL_SIZE = NRPhyRegs // TODO calculate max number of free list using NRPhyRegs and width of counter

  // recording referenced times of each physical registers
  val archRefCounter = RegInit(VecInit(Seq.fill(NRPhyRegs)(0.U(2.W))))
  val specRefCounter = RegInit(VecInit(Seq.fill(NRPhyRegs)(0.U(2.W))))
  val cmtCounter = RegInit(VecInit(Seq.fill(NRPhyRegs)(0.U(2.W))))

  val archRefCounterNext = Wire(Vec(NRPhyRegs, UInt(2.W)))
  val specRefCounterNext = Wire(Vec(NRPhyRegs, UInt(2.W)))
  val cmtCounterNext = Wire(Vec(NRPhyRegs, UInt(2.W)))
  // initialization for next counter val
  for (i <- 0 until NRPhyRegs) {
    archRefCounterNext(i) := archRefCounter(i)
    specRefCounterNext(i) := specRefCounter(i)
    cmtCounterNext(i) := cmtCounter(i)
  }
  // send max flag of spec ref counter to rename stage
  io.maxVec zip specRefCounter foreach { case (max, cnt) =>
    max := cnt.andR()
  }


  // number of free registers
  val freeRegCnt = Wire(UInt())
  
  // free list as circular buffer
  val freeList = RegInit(VecInit(Seq.tabulate(FL_SIZE){
    case n if (n >= 0 && n < NRPhyRegs - 32) => (n + 32).U
    case _ => DontCare
  }))
  
  // head and tail pointer
  val headPtr = RegInit(IntFreeListPtr(false.B, 0.U))

  val tailPtr = RegInit(IntFreeListPtr(false.B, (NRPhyRegs-32).U)) // TODO change 128 into parameters


  /*
  Decrements: from roq commits
   */
  val freeVec = WireInit(VecInit(Seq.fill(CommitWidth)(false.B))) // if dec(i).bits is freed and ready for writing back to free list
  val freeRegCandidates = (0 until CommitWidth).map(io.dec.old_pdests(_))

  // handle duplicate INC requirements on cmtCounter and archRefCounter
  val old_pdests_cmp = Wire(MixedVec(List.tabulate(CommitWidth-1)(i => UInt((i+1).W))))
  val pdests_cmp = Wire(MixedVec(List.tabulate(CommitWidth-1)(i => UInt((i+1).W))))

  for (i <- 1 until CommitWidth) {
    // compare pdest and old_pdest with former inputs
    old_pdests_cmp(i - 1) := Cat((0 until i).map(j => {
      io.dec.req(i) && io.dec.req(j) && io.dec.old_pdests(i) === io.dec.old_pdests(j)
    }))
    pdests_cmp(i - 1) := Cat((0 until i).map(j => {
      io.dec.req(i) && io.dec.req(j) && io.dec.eliminatedMove(i) && io.dec.eliminatedMove(j) && io.dec.pdests(i) === io.dec.pdests(j)
    }))
  }

  def getCompareResult(m: MixedVec[UInt]): (Vec[Bool], Vec[Bool], Vec[UInt]) = {
    val is_last = WireInit(VecInit(Seq.tabulate(CommitWidth){
      case last if (last == CommitWidth - 1) => true.B
      case i => !(Cat((i until (CommitWidth - 1)).map(j => m(j)(i))).orR)
    }))
    val has_same_before = WireInit(VecInit(Seq.tabulate(CommitWidth){
      case 0 => false.B
      case i => m(i - 1).orR()
    }))
    val times = WireInit(VecInit(Seq.tabulate(CommitWidth){
      case 0 => 0.U
      case i => PopCount(m(i - 1))
    }))
    (is_last, has_same_before, times)
  }

  val (old_pdests_is_last, old_pdests_has_same_before, old_pdests_times) = getCompareResult(old_pdests_cmp)
  val (pdests_is_last, pdests_has_same_before, pdests_times) = getCompareResult(pdests_cmp)

  for (i <- 0 until CommitWidth) {
    val preg = freeRegCandidates(i) // physical register waiting for freeing

    val oldPdestIsUnique = old_pdests_is_last(i) && !old_pdests_has_same_before(i)
    val oldPdestNotUniqueButLast = old_pdests_is_last(i) && old_pdests_has_same_before(i)

    val pdestIsUnique = pdests_is_last(i) && !pdests_has_same_before(i)
    val pdestNotUniqueButLast = pdests_is_last(i) && pdests_has_same_before(i)

    freeVec(i) := (oldPdestIsUnique && cmtCounter(preg) === specRefCounter(preg) || oldPdestNotUniqueButLast && cmtCounter(preg) + old_pdests_times(i) === specRefCounter(preg)) && io.dec.req(i)
    
    // cmt counter after incrementing/ stay not change
    // free vec has higher priority than cmtCounterNext, so normal free wouldn't cause cmtCounter increasing
    cmtCounterNext(preg) := Mux(io.dec.req(i) && oldPdestIsUnique, cmtCounter(preg) + 1.U,
                    Mux(io.dec.req(i) && oldPdestNotUniqueButLast, cmtCounter(preg) + 1.U + old_pdests_times(i), 
                                             /* stay not change */ cmtCounter(preg)))
    
    // arch ref counter of pdest
    archRefCounterNext(io.dec.pdests(i)) := Mux(/* if this is me inst */io.dec.req(i) && io.dec.eliminatedMove(i) && pdestIsUnique, archRefCounter(io.dec.pdests(i)) + 1.U, 
      Mux(io.dec.req(i) && io.dec.eliminatedMove(i) && pdestNotUniqueButLast, archRefCounter(io.dec.pdests(i)) + 1.U + pdests_times(i), archRefCounter(io.dec.pdests(i))))

    XSDebug((specRefCounter(preg) === 0.U) && freeVec(i), p"normal preg free, preg:${preg}\n")
    XSDebug((cmtCounter(preg) === specRefCounter(preg) && (specRefCounter(preg) =/= 0.U)) && freeVec(i), p"multi referenced preg free, preg:${preg}\n")

    // write freed preg into free list at tail ptr
    val offset = i match {
      case 0 => 0.U
      case n => PopCount(freeVec.take(n))
    }
    val ptr = tailPtr + offset
    val idx = ptr.value
    when (freeVec(i)) {
      freeList(idx) := freeRegCandidates(i)
      XSDebug(p"Free List enqueue: [ preg ${freeRegCandidates(i)} ]\n")
    }
  }


  /*
  Increments: from rename stage
   */
  val needAllocatingVec = WireInit(VecInit(Seq.fill(RenameWidth)(false.B)))
  XSDebug(needAllocatingVec.asUInt().orR(), p"needAllocatingVec:${Binary(needAllocatingVec.asUInt)}\n")
  for (i <- 0 until RenameWidth) {
    io.inc.pdests(i) := DontCare
    needAllocatingVec(i) := io.inc.req(i) && io.inc.canInc && io.inc.doInc && !io.flush && !io.inc.psrcOfMove(i).valid && !io.redirect
    
    when (io.inc.psrcOfMove(i).valid && io.inc.req(i) && io.inc.canInc && io.inc.doInc && !io.flush) {
      specRefCounterNext(io.inc.psrcOfMove(i).bits) := specRefCounter(io.inc.psrcOfMove(i).bits) + 1.U
    }
    
    val offset = i match {
      case 0 => 0.U
      case n => PopCount(needAllocatingVec.take(n))
    }
    val ptr = headPtr + offset
    when (needAllocatingVec(i)) {
      val pdest = freeList(ptr.value)
      XSDebug(p"[$i] Allocate phy reg $pdest\n")
      io.inc.pdests(i) := pdest
    }
  }
    

  /*
  Flush: directly flush reference counter according to arch-rat
   */
  val s_idle :: s_flush_1 :: s_flush_2 :: Nil = Enum(3)
  val state = RegInit(s_idle)

  val flushFreeVec = WireInit(VecInit(Seq.fill(NRPhyRegs)(false.B)))
  switch (state) {
    is (s_idle) {
      when (io.flush) {
        specRefCounter := archRefCounter
        state := s_flush_1
        XSDebug("Start Flush Process Next Cycle")
      }
    }

    is (s_flush_1) {
      for (i <- 0 until NRPhyRegs) {
        when (archRefCounter(i) < cmtCounter(i)) {
          // free reg i
          flushFreeVec(i) := true.B
        }

        val offset = i match {
          case 0 => 0.U
          case n => PopCount(flushFreeVec.take(n))
        }
        val ptr = tailPtr + offset
        val idx = ptr.value
        when (flushFreeVec(i)) {
          freeList(idx) := i.U
        }
      }
      state := s_flush_2
    }

    is (s_flush_2) {
      state := s_idle
    }
  }

  // update tail pointer
  tailPtr := Mux(state === s_flush_1, tailPtr + PopCount(flushFreeVec), tailPtr + PopCount(freeVec))
  // update head pointer
  val headPtrNext = Mux(state === s_idle && io.flush, tailPtr - (NRPhyRegs-32).U, 
                      Mux(state === s_flush_2, headPtr - PopCount(archRefCounter.map(_.orR())), 
                                               headPtr + PopCount(needAllocatingVec))) // when io.redirect is valid, needAllocatingVec is all-zero
                                               
  freeRegCnt := distanceBetween(tailPtr, headPtrNext)
  io.inc.canInc := RegNext(freeRegCnt >= RenameWidth.U)

  headPtr := headPtrNext

  // update reg counter
  for (i <- 0 until NRPhyRegs) {
    specRefCounter(i) := Mux(flushFreeVec(i), 0.U, specRefCounterNext(i))
    archRefCounter(i) := Mux(flushFreeVec(i), 0.U, archRefCounterNext(i))
    cmtCounter(i) := Mux(flushFreeVec(i), 0.U, cmtCounterNext(i))
  }
  for (i <- 0 until CommitWidth) {
    when (freeVec(i)) {
      specRefCounter(freeRegCandidates(i)) := 0.U
      archRefCounter(freeRegCandidates(i)) := 0.U
      cmtCounter(freeRegCandidates(i)) := 0.U
    }
  }

  /*
  Re-direct: restore by walking, handled by rename using `dec` port
   */



  /*
  Debug Info
   */
  for (i <- 0 until NRPhyRegs) {
    XSDebug(specRefCounter(i) =/= 0.U || archRefCounter(i) =/= 0.U || cmtCounter(i) =/= 0.U, 
      p"preg[$i] specRefCounter:${specRefCounter(i)} archRefCounter:${archRefCounter(i)} cmtCounter:${cmtCounter(i)}\n")
    
    // specRefCounter(i) must >= cmtCounter(i)
    XSError(specRefCounter(i) < cmtCounter(i), p"Commits Overflow of preg${i}")
  }

  XSDebug(Array.range(0, FL_SIZE).map(x => x.toString()).mkString("Free List (idx): ", "\t", "\n"))
  XSDebug(p"Free List (val): " + Array.range(0, FL_SIZE).map(x => p"${freeList(x)}\t").reduceLeft(_ + _) + "\n")
  
  XSDebug(p"head:$headPtr tail:$tailPtr headPtrNext:$headPtrNext freeRegCnt:$freeRegCnt\n")
  
  XSDebug(p"io.flush ${io.flush} Flush State [ ${state} ]\n")

  
  /*
  Assertions
   */
  val enableFreeListCheck = false

  if (enableFreeListCheck) {

    for (i <- 0 until RenameWidth) {
      for (j <- (i + 1) until RenameWidth) {
        XSError(needAllocatingVec(i) && needAllocatingVec(j) && io.inc.pdests(i) === io.inc.pdests(j),
          p"Duplicate INC requirements detected!" + io.inc.pdests.zipWithIndex.map{case (p, idx) => p" ($idx):$p"}.reduceLeft(_ + _) + "\n")
        XSError(io.inc.req(i) && io.inc.req(j) && io.inc.canInc && io.inc.doInc && io.inc.psrcOfMove(i).valid && io.inc.psrcOfMove(j).valid && io.inc.psrcOfMove(i).bits === io.inc.psrcOfMove(j).bits,
          p"Duplicate ME requirements detected! Cannot inc same specRefCount in 1 cycle!\n")
      }
      // also, we cannot count ref numbers more than 3 (which is very rare)
      XSError(io.inc.req(i) && io.inc.canInc && io.inc.doInc && !io.inc.psrcOfMove(i).valid && specRefCounter(io.inc.pdests(i)).andR(), p"(norm) Exceeding specRefCounter Max Value: preg[${io.inc.pdests(i)}]\n")
      XSError(io.inc.req(i) && io.inc.canInc && io.inc.doInc && io.inc.psrcOfMove(i).valid && specRefCounter(io.inc.psrcOfMove(i).bits).andR(), p"(move) Exceeding specRefCounter Max Value: preg[${io.inc.psrcOfMove(i).bits}]\n")
    }

    for (i <- 0 until CommitWidth) {
      // we cannot handle duplicate inc/dec requirements on a preg in 1 cycle for now
      for (j <- (i + 1) until CommitWidth) {
        XSInfo(io.dec.req(i) && io.dec.req(j) && io.dec.old_pdests(i) === io.dec.old_pdests(j), 
          p"Duplicate DEC requirements detected!" + io.dec.old_pdests.zipWithIndex.map{case (p, idx) => p" ($idx):$p"}.reduceLeft(_ + _) + "\n")
        XSInfo(io.dec.req(i) && io.dec.req(j) && io.dec.eliminatedMove(i) && io.dec.eliminatedMove(j) && io.dec.pdests(i) === io.dec.pdests(j), 
          p"Duplicate INC requirements on archRefCount detected!" + io.dec.pdests.zipWithIndex.map{case (p, idx) => p" ($idx):$p"}.reduceLeft(_ + _) + "\n")
      }
    }
  }
}