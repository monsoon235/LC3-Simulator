package Simulator

/**
 * 初始化
 * @param startPC 程序起始点
 * @param insts obj文件数据流去掉PC头
 */
fun init(startPC: Int, insts: IntArray) {
    PC = startPC
    System.arraycopy(insts, 0, M, startPC, insts.size)
    inputIndex = 0
    output.clear()
}

/**
 * 开始执行
 * @return 执行到HALT是的总cost
 */
fun start(startPC: Int): Long {
    var cost = 0L
    output.clear()
    PC = startPC
    while (true) {
        if (PC in breakPoints) {
            break
        }
        if (M[PC] == 0xFFFFF025.toInt()) {  //HALT
            break
        }
//        if (M[PC] and 0b1_111_111_111_111_111 == 0xC1C0) {  //RET
//            break
//        }
//        println("PC=x${PC.toString(16).toUpperCase()} " +
//                "exec x${M[PC].toString(16).toUpperCase()}")
        try {
            cost += exec(M[PC])
        } catch (e: RTIException) {
            e.printStackTrace()
            break
        }
    }
    return cost
}

//用于调试的 break point
val breakPoints = hashSetOf<Int>()

val R = IntArray(8)
val M = IntArray(65536)

var PSR: Int = 0
var PC: Int = 0x3000

/**
 * 执行指令
 * TRAP视为原子指令
 * @return 执行的cost
 */
fun exec(inst: Int): Int {

    PC++

    val op = (inst shr 12) and 0b1111
    val R1 = (inst shr 9) and 0b111
    val R2 = (inst shr 6) and 0b111
    val R3 = inst and 0b111

    when (op) {
        //ADD
        0b0001 -> {
            R[R1] = R[R2] +
                    when ((inst shr 5) and 1) {
                        0 -> R[R3]
                        else -> getImm(inst, 5)
                    }
            updateNZP(R[R1])
        }

        //AND
        0b0101 -> {
            R[R1] = R[R2] and
                    when ((inst shr 5) and 1) {
                        0 -> R[R3]
                        else -> getImm(inst, 5)
                    }
            updateNZP(R[R1])
        }

        //NOT
        0b1001 -> {
            R[R1] = R[R2].inv()
            updateNZP(R[R1])
        }
        //BR
        0b0000 -> {
            if (R1 and (PSR and 0b111) != 0) {
                PC += getImm(inst, 9)
            }
        }
        //JMP
        0b1100 -> PC = getMemAddr(R[R2])
        //JSR & JSRR
        0b0100 -> {
            val tmp= PC
            PC = when ((inst ushr 11) and 1) {
                0 -> getMemAddr(R[R2])
                else -> PC + getImm(inst, 11)
            }
            R[7]=tmp
        }
        //RTI
        0b1000 -> when ((PSR ushr 15) and 1) {
            //todo RTI指令暂定
            0 -> {
                PC = M[R[6]]
                R[6]++
                PSR = M[R[6]]
                R[6]++
            }
            else -> throw RTIException()
        }
        //LD
        0b0010 -> {
            R[R1] = M[PC + getImm(inst, 9)]
            updateNZP(R[R1])
        }
        //LDI
        0b1010 -> {
            R[R1] = M[M[PC + getImm(inst, 9)]]
            updateNZP(R[R1])
        }
        //ST
        0b0011 -> M[PC + getImm(inst, 9)] = R[R1]
        //STI
        0b1011 -> M[M[PC + getImm(inst, 9)]] = R[R1]
        //LDR
        0b0110 -> {
            R[R1] = M[getMemAddr(R[R2]) + getImm(inst, 6)]
            updateNZP(R[R1])
        }
        //STR
        0b0111 -> M[getMemAddr(R[R2]) + getImm(inst, 6)] = R[R1]
        //LEA
        0b1110 -> {
            R[R1] = PC + getImm(inst, 9)
            updateNZP(R[R1])
        }
        //TRAP
        0b1111 -> {
            //TRAP的cost单独计算
            //TRAP调用原子化
            val trapVector = inst and 0b11_111_111
            R[7] = PC
//            PC = M[trapVector]
            return trap(trapVector) + costTable[0b1111] as Int
        }

    }

    return costTable[op] as Int

}

fun getMemAddr(regData: Int): Int {
    return ((-1) ushr 16) and regData
}

/**
 * 从指令中提取有符号立即数
 * @param inst 指令
 * @param length 立即数位数
 * @return 立即数
 */
fun getImm(inst: Int, length: Int): Int {
    var imm = when ((inst ushr (length - 1)) and 1) {
        0 -> 0
        else -> (-1) shl length
    }
    imm += inst and ((-1) ushr (Int.SIZE_BITS - length))
//    println("imm = $imm")
    return imm
}

/**
 * 更新nzp
 */
fun updateNZP(num: Int) {
    PSR = (PSR and ((-1) shl 3)) +
            if (num > 0) {
                0b001
            } else if (num == 0) {
                0b010
            } else {
                0b100
            }
}

/**
 * 原子化TRAP调用
 */
fun trap(trapVector: Int): Int {
    when (trapVector) {
        0x20 -> {   //GETC
            R[0] = input[inputIndex++].toInt()
            return 28
        }
        0x21 -> {   //OUT
            output.add(R[0].toChar())
            return 36
        }
        0x22 -> {   //PUTS
            var addr = R[0]
            var c = M[addr]
            while (c != 0) {
                output.add(c.toChar())
                addr++
                c = M[addr]
            }
            return 4 * 4 + 27 * (addr - R[0]) + 6 + 4 * 4 + 2
        }
        else -> {
            //todo 待完成
            throw Exception("Unfinished")
        }
//        0x23 -> {   //IN
//
//        }
//        0x24 -> {   //PUTSP
//
//        }
//        0x25 -> {   //HALT
//
//        }
    }
}

lateinit var input: ArrayList<Char>
var inputIndex = 0

fun setInputChars(inputChars: ArrayList<Char>) {
    input = inputChars
    inputIndex = 0
}

val output = ArrayList<Char>()

fun getOutputChars(): String {
    return String(output.toCharArray())
}

fun addBreakPoint(addr: Int) {
    breakPoints.add(addr)
}

//指令cost列表
val costTable = hashMapOf(
        Pair(0b0001, 1),    //ADD
        Pair(0b0101, 1),    //AND
        Pair(0b1001, 1),    //NOT
        Pair(0b1110, 1),    //LEA

        Pair(0b0000, 2),    //BR
        Pair(0b1100, 2),    //JMP
        Pair(0b0100, 2),    //JSR & JSRR
        Pair(0b1111, 2),    //TRAP

        Pair(0b0010, 4),    //LD
        Pair(0b0011, 4),    //ST
        Pair(0b0110, 4),    //LDR
        Pair(0b0111, 4),    //STR

        Pair(0b1010, 8),    //LDI
        Pair(0b1011, 8),    //STI

        Pair(0b1000, 8)     //RTI
)

class RTIException : Exception()