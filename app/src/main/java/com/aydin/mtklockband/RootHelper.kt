package com.aydin.mtklockband

import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.InputStream
import java.io.OutputStream

object RootHelper {

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.isEmpty()) {
            println("ERROR: No arguments. Try 'lock', 'reset', 'rat', 'lock_cell', 'unlock_cell'")
            System.exit(1)
        }

        val cmd = args[0]
        try {
            when (cmd) {
                "lock" -> {
                    // lock <bands_comma> <priority_band> <slot>
                    if (args.size < 4) {
                        println("ERROR: Usage: lock <bands_comma> <priority_band> <slot_index>")
                        System.exit(1)
                    }
                    val bandStr = args[1]
                    val priorityBand = args[2].toIntOrNull() ?: 0
                    val slotIndex = args[3].toIntOrNull() ?: 0
                    
                    val bands = bandStr.split(",")
                        .mapNotNull { it.trim().toIntOrNull() }
                        .toIntArray()
                        
                    val success = lockBandsAndPriority(bands, priorityBand, slotIndex)
                    println("RESULT:" + if (success) "SUCCESS" else "FAILED")
                }
                "reset" -> {
                    // reset <slot>
                    val slotIndex = if (args.size >= 2) args[1].toIntOrNull() ?: 0 else 0
                    val success = resetModem(slotIndex)
                    println("RESULT:" + if (success) "SUCCESS" else "FAILED")
                }
                "rat" -> {
                    // rat <rat_mode> <slot>
                    if (args.size < 3) {
                        println("ERROR: Usage: rat <rat_mode> <slot_index>")
                        System.exit(1)
                    }
                    val ratMode = args[1].toIntOrNull() ?: 33
                    val slotIndex = args[2].toIntOrNull() ?: 0
                    val success = setNetworkMode(ratMode, slotIndex)
                    println("RESULT:" + if (success) "SUCCESS" else "FAILED")
                }
                "lock_cell" -> {
                    // lock_cell <mcc> <mnc> <earfcn> <pci> <slot>
                    if (args.size < 6) {
                        println("ERROR: Usage: lock_cell <mcc> <mnc> <earfcn> <pci> <slot_index>")
                        System.exit(1)
                    }
                    val mcc = args[1]
                    val mnc = args[2]
                    val earfcn = args[3].toIntOrNull() ?: 0
                    val pci = args[4].toIntOrNull() ?: 0
                    val slotIndex = args[5].toIntOrNull() ?: 0
                    
                    val success = setCellLock(1, mcc, mnc, earfcn, pci, slotIndex)
                    println("RESULT:" + if (success) "SUCCESS" else "FAILED")
                }
                "unlock_cell" -> {
                    // unlock_cell <mcc> <mnc> <earfcn> <pci> <slot>
                    if (args.size < 6) {
                        println("ERROR: Usage: unlock_cell <mcc> <mnc> <earfcn> <pci> <slot_index>")
                        System.exit(1)
                    }
                    val mcc = args[1]
                    val mnc = args[2]
                    val earfcn = args[3].toIntOrNull() ?: 0
                    val pci = args[4].toIntOrNull() ?: 0
                    val slotIndex = args[5].toIntOrNull() ?: 0
                    
                    val success = setCellLock(0, mcc, mnc, earfcn, pci, slotIndex)
                    println("RESULT:" + if (success) "SUCCESS" else "FAILED")
                }
                else -> {
                    println("ERROR: Unknown command: $cmd")
                    System.exit(1)
                }
            }
        } catch (e: Throwable) {
            e.printStackTrace()
            System.exit(1)
        }
        System.exit(0)
    }

    // Typical Indonesian telecom EARFCN ranges mapping
    private fun getCommonEarfcnForBand(band: Int): IntArray {
        return when (band) {
            1 -> intArrayOf(100, 225, 300, 325, 375, 425)                 // 2100 MHz
            3 -> intArrayOf(1275, 1325, 1375, 1825, 1850)                // 1800 MHz
            5 -> intArrayOf(2450, 2550, 2600)                            // 850 MHz
            7 -> intArrayOf(2850, 3050, 3300)                            // 2600 MHz
            8 -> intArrayOf(3500, 3525, 3550, 3575, 3740)                // 900 MHz
            20 -> intArrayOf(6200, 6300, 6400)                           // 800 MHz
            28 -> intArrayOf(9260, 9360, 9460)                           // 700 MHz
            38 -> intArrayOf(37900, 38000, 38100)                        // 2600 TDD
            40 -> intArrayOf(38950, 39150, 39350, 40100, 40300)          // 2300 TDD
            41 -> intArrayOf(39750, 40300, 40620, 41240)                 // 2500 TDD
            else -> intArrayOf()
        }
    }

    // Communication bridge directly via Unix socket length-prefix protocol
    private fun sendAtCommand(cmd: String): String {
        val socket = LocalSocket()
        try {
            socket.connect(LocalSocketAddress("/dev/socket/rild-atci", LocalSocketAddress.Namespace.FILESYSTEM))
            
            val payload = (cmd + "\r\n").toByteArray(Charsets.UTF_8)
            val header = ByteArray(4)
            header[0] = ((payload.size shr 24) and 0xFF).toByte()
            header[1] = ((payload.size shr 16) and 0xFF).toByte()
            header[2] = ((payload.size shr 8) and 0xFF).toByte()
            header[3] = (payload.size and 0xFF).toByte()
            
            val out = socket.outputStream
            out.write(header)
            out.write(payload)
            out.flush()
            
            socket.soTimeout = 2000
            val input = socket.inputStream
            
            val resHeader = ByteArray(4)
            var readHeaderLen = 0
            while (readHeaderLen < 4) {
                val r = input.read(resHeader, readHeaderLen, 4 - readHeaderLen)
                if (r == -1) break
                readHeaderLen += r
            }
            
            if (readHeaderLen == 4) {
                val resLen = ((resHeader[0].toInt() and 0xFF) shl 24) or
                             ((resHeader[1].toInt() and 0xFF) shl 16) or
                             ((resHeader[2].toInt() and 0xFF) shl 8) or
                             (resHeader[3].toInt() and 0xFF)
                             
                val resPayload = ByteArray(resLen)
                var readPayloadLen = 0
                while (readPayloadLen < resLen) {
                    val r = input.read(resPayload, readPayloadLen, resLen - readPayloadLen)
                    if (r == -1) break
                    readPayloadLen += r
                }
                return String(resPayload, Charsets.UTF_8).trim()
            }
        } catch (e: Exception) {
            return "ERROR: ${e.message}"
        } finally {
            try { socket.close() } catch (ignored: Exception) {}
        }
        return "TIMEOUT"
    }

    private fun selectSimSlot(slotIndex: Int) {
        println("Switching ATCI context to SIM slot $slotIndex...")
        val res = sendAtCommand("AT+ESIMS=$slotIndex")
        println("  Slot switch response: $res")
        Thread.sleep(100)
    }

    private fun lockBandsAndPriority(bands: IntArray, priorityBand: Int, slotIndex: Int): Boolean {
        selectSimSlot(slotIndex)
        
        // Calculate LTE Bitmask
        var lteMaskLow = 0L
        var lteMaskHigh = 0L
        
        for (b in bands) {
            if (b in 1..32) {
                lteMaskLow = lteMaskLow or (1L shl (b - 1))
            } else if (b in 33..64) {
                lteMaskHigh = lteMaskHigh or (1L shl (b - 33))
            }
        }
        
        // Execute EPBSE band lock command
        // AT+EPBSE=<gsm>,<wcdma>,<lte_l>,<lte_h>,<nr_l>,<nr_h>,...
        val epbseCmd = "AT+EPBSE=154,155,$lteMaskLow,$lteMaskHigh,0,0,0,0,0,0"
        println("Sending Band Lock: $epbseCmd")
        val epbseRes = sendAtCommand(epbseCmd)
        println("  Response: $epbseRes")
        
        // Execute dynamic band priority command if specified
        if (priorityBand > 0 && bands.contains(priorityBand)) {
            println("Setting Primary cell priority to LTE Band $priorityBand...")
            val egmcCmd = "AT+EGMC=1,\"priority_band\",$priorityBand"
            val egmcRes = sendAtCommand(egmcCmd)
            println("  Priority Response: $egmcRes")
        }
        
        return epbseRes.contains("OK")
    }

    private fun resetModem(slotIndex: Int): Boolean {
        selectSimSlot(slotIndex)
        
        // Restore all bands
        println("Restoring all baseband bands...")
        val epbseRes = sendAtCommand("AT+EPBSE=154,155,168165599,928,0,0,0,0,0,0")
        println("  Band Restore Response: $epbseRes")
        
        // Disable priority band locking
        val egmcRes = sendAtCommand("AT+EGMC=0,\"priority_band\"")
        println("  Priority Reset Response: $egmcRes")
        
        // Restore RAT Mode to Auto
        val eratRes = sendAtCommand("AT+ERAT=33")
        println("  RAT Mode Auto Response: $eratRes")
        
        return epbseRes.contains("OK")
    }

    private fun setNetworkMode(ratMode: Int, slotIndex: Int): Boolean {
        selectSimSlot(slotIndex)
        println("Locking Network mode to RAT ID $ratMode...")
        val eratRes = sendAtCommand("AT+ERAT=$ratMode")
        println("  RAT Mode Response: $eratRes")
        return eratRes.contains("OK")
    }

    private fun setCellLock(op: Int, mcc: String, mnc: String, earfcn: Int, pci: Int, slotIndex: Int): Boolean {
        selectSimSlot(slotIndex)
        // AT+EPLMNFREQ=<op>,<mcc>,<mnc>,<earfcn>,<pci>
        val cmd = "AT+EPLMNFREQ=$op,$mcc,$mnc,$earfcn,$pci"
        println("${if(op == 1) "Locking" else "Unlocking"} Cell Sector: $cmd")
        val res = sendAtCommand(cmd)
        println("  Cell Lock Response: $res")
        return res.contains("OK")
    }
}
