package com.aydin.mtklockband

import android.os.IBinder
import android.telephony.RadioAccessSpecifier
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object RootHelper {

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.isEmpty()) {
            println("ERROR: No arguments. Use 'lock <bands> <slot>' or 'reset <slot>'")
            System.exit(1)
        }

        val cmd = args[0]
        try {
            when (cmd) {
                "lock" -> {
                    if (args.size < 3) {
                        println("ERROR: Missing arguments. Usage: lock <bands> <slot_index>")
                        System.exit(1)
                    }
                    val bandStr = args[1]
                    val slotIndex = args[2].toIntOrNull() ?: 0
                    
                    val bands = bandStr.split(",")
                        .mapNotNull { it.trim().toIntOrNull() }
                        .toIntArray()
                    
                    if (bands.isEmpty()) {
                        println("ERROR: Valid bands list is empty")
                        System.exit(1)
                    }

                    val success = setBands(bands, slotIndex)
                    println("RESULT:" + if (success) "SUCCESS" else "FAILED")
                }
                "reset" -> {
                    val slotIndex = if (args.size >= 2) args[1].toIntOrNull() ?: 0 else 0
                    val success = setBands(intArrayOf(), slotIndex)
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
            1 -> intArrayOf(100, 225, 300, 325, 375, 425)                 // 2100 MHz (XL, Tsel, ISAT, Tri)
            3 -> intArrayOf(1275, 1325, 1375, 1825, 1850)                // 1800 MHz (XL, Tsel, ISAT, Tri)
            5 -> intArrayOf(2450, 2550, 2600)                            // 850 MHz (Smartfren, XL)
            7 -> intArrayOf(2850, 3050, 3300)                            // 2600 MHz
            8 -> intArrayOf(3500, 3525, 3550, 3575, 3740)                // 900 MHz (XL, ISAT, Tsel)
            20 -> intArrayOf(6200, 6300, 6400)                           // 800 MHz
            28 -> intArrayOf(9260, 9360, 9460)                           // 700 MHz (Tsel, ISAT)
            38 -> intArrayOf(37900, 38000, 38100)                        // 2600 MHz TDD
            40 -> intArrayOf(38950, 39150, 39350, 40100, 40300)          // 2300 MHz TDD (Smartfren, Tsel)
            41 -> intArrayOf(39750, 40300, 40620, 41240)                 // 2500 MHz TDD (ISAT)
            else -> intArrayOf()
        }
    }

    private fun setBands(bands: IntArray, slotIndex: Int): Boolean {
        println("Attempting to lock bands: ${if(bands.isEmpty()) "ALL/RESET" else bands.joinToString(", ")} on SIM slot $slotIndex")
        
        // 1. Get ServiceManager class
        val serviceManagerClass = Class.forName("android.os.ServiceManager")
        val getServiceMethod: Method = serviceManagerClass.getMethod("getService", String::class.java)
        
        val phoneBinder = getServiceMethod.invoke(null, "phone") as? IBinder
        if (phoneBinder == null) {
            println("ERROR: Phone binder service not found")
            return false
        }

        // 2. Get ITelephony interface
        val iTelephonyClass = Class.forName("com.android.internal.telephony.ITelephony")
        val stubClass = Class.forName("com.android.internal.telephony.ITelephony\$Stub")
        val asInterfaceMethod: Method = stubClass.getMethod("asInterface", IBinder::class.java)
        val telephonyService = asInterfaceMethod.invoke(null, phoneBinder)
        if (telephonyService == null) {
            println("ERROR: Could not bind ITelephony interface")
            return false
        }

        // 3. Find setSystemSelectionChannels method dynamically
        val methods = iTelephonyClass.methods
        val targetMethod = methods.find { it.name == "setSystemSelectionChannels" }
        if (targetMethod == null) {
            println("ERROR: Method setSystemSelectionChannels not found on ITelephony")
            return false
        }

        println("Found target method: $targetMethod")

        // 4. Create RadioAccessSpecifier with explicit frequencies (EARFCN)
        val specifiers = ArrayList<RadioAccessSpecifier>()
        if (bands.isNotEmpty()) {
            for (band in bands) {
                val channels = getCommonEarfcnForBand(band)
                // AccessNetworkConstants.AccessNetworkType.EUTRAN = 3 (LTE)
                val ras = RadioAccessSpecifier(3, intArrayOf(band), channels)
                specifiers.add(ras)
                println("Built specifier for LTE Band $band with EARFCNs: ${channels.joinToString(", ")}")
            }
        }

        // 5. Generate Dynamic Proxy for the callback
        val paramTypes = targetMethod.parameterTypes
        val callbackType = paramTypes.find { it.isInterface && it.name.contains("Consumer") }
        
        var callbackInstance: Any? = null
        val latch = CountDownLatch(1)
        var callbackSuccess = false

        if (callbackType != null) {
            println("Found callback type: ${callbackType.name}")
            callbackInstance = Proxy.newProxyInstance(
                callbackType.classLoader,
                arrayOf(callbackType)
            ) { _, method, argsList ->
                if (argsList != null && argsList.isNotEmpty()) {
                    val firstArg = argsList[0]
                    println("Callback invoked with arg: $firstArg (${firstArg?.javaClass?.name})")
                    if (firstArg is Boolean) {
                        callbackSuccess = firstArg
                    } else if (firstArg is Number) {
                        callbackSuccess = (firstArg.toInt() == 0)
                    }
                    latch.countDown()
                }
                null
            }
        }

        // 6. Resolve subId (Quadruple Redundant)
        var targetSubId = -1
        
        // Lapis 1: Query binder 'isub' Langsung
        try {
            val isubBinder = getServiceMethod.invoke(null, "isub") as? IBinder
            if (isubBinder != null) {
                val isubStubClass = Class.forName("com.android.internal.telephony.ISub\$Stub")
                val isubAsInterface = isubStubClass.getMethod("asInterface", IBinder::class.java)
                val isubService = isubAsInterface.invoke(null, isubBinder)
                
                if (isubService != null) {
                    val getSubIdMethod = isubService.javaClass.methods.find { it.name == "getSubId" }
                    if (getSubIdMethod != null) {
                        val result = getSubIdMethod.invoke(isubService, slotIndex)
                        if (result is IntArray && result.isNotEmpty()) {
                            targetSubId = result[0]
                            println("Lapis 1 (ISub Binder) sukses, subId = $targetSubId")
                        } else if (result is Int) {
                            targetSubId = result
                            println("Lapis 1 (ISub Binder) sukses, subId = $targetSubId")
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            println("Lapis 1 (ISub Binder) gagal: ${t.message}")
        }
        
        // Lapis 2: Refleksi Context System
        if (targetSubId == -1) {
            try {
                val activityThreadClass = Class.forName("android.app.ActivityThread")
                val systemMainMethod = activityThreadClass.getMethod("systemMain")
                val activityThread = systemMainMethod.invoke(null)
                val getSystemContextMethod = activityThreadClass.getMethod("getSystemContext")
                val systemContext = getSystemContextMethod.invoke(activityThread)
                
                val contextClass = Class.forName("android.content.Context")
                val getSystemServiceMethod = contextClass.getMethod("getSystemService", String::class.java)
                val subManager = getSystemServiceMethod.invoke(systemContext, "telephony_subscription_service")
                
                if (subManager != null) {
                    val subManagerClass = Class.forName("android.telephony.SubscriptionManager")
                    val getActiveSubMethod = subManagerClass.getMethod("getActiveSubscriptionInfoForSimSlotIndex", Int::class.java)
                    val subInfo = getActiveSubMethod.invoke(subManager, slotIndex)
                    
                    if (subInfo != null) {
                        val subInfoClass = Class.forName("android.telephony.SubscriptionInfo")
                        val getSubIdMethod = subInfoClass.getMethod("getSubscriptionId")
                        targetSubId = getSubIdMethod.invoke(subInfo) as Int
                        println("Lapis 2 (Context Refleksi) sukses, subId = $targetSubId")
                    }
                }
            } catch (t: Throwable) {
                println("Lapis 2 gagal: ${t.message}")
            }
        }
        
        // Lapis 3: Dumpsys Parsing
        if (targetSubId == -1) {
            try {
                val process = Runtime.getRuntime().exec("dumpsys subscription")
                val reader = process.inputStream.bufferedReader()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val l = line!!
                    if (l.contains("simSlotIndex=") || l.contains("simSlotIndex :") || l.contains("mSlotIndex=")) {
                        val idMatch = Regex("""\bid[ =:]+(\d+)""").find(l) ?: Regex("""\bmSubId[ =:]+(\d+)""").find(l)
                        val slotMatch = Regex("""\bsimSlotIndex[ =:]+(\d+)""").find(l) ?: Regex("""\bmSlotIndex[ =:]+(\d+)""").find(l)
                        if (idMatch != null && slotMatch != null) {
                            val id = idMatch.groupValues[1].toInt()
                            val slot = slotMatch.groupValues[1].toInt()
                            if (slot == slotIndex) {
                                targetSubId = id
                                println("Lapis 3 (Dumpsys Parsing) sukses, subId = $targetSubId")
                                break
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                println("Lapis 3 gagal: ${t.message}")
            }
        }
        
        // Lapis 4: Fallback Static Method
        if (targetSubId == -1) {
            try {
                val subManagerClass = Class.forName("android.telephony.SubscriptionManager")
                val getSubIdMethod = subManagerClass.getMethod("getSubscriptionId", Int::class.java)
                val res = getSubIdMethod.invoke(null, slotIndex) as? Int
                if (res != null && res != -1) {
                    targetSubId = res
                    println("Lapis 4 (Static Fallback) sukses, subId = $targetSubId")
                }
            } catch (t: Throwable) {
                println("Lapis 4 gagal: ${t.message}")
            }
        }
        
        // Final fallback
        if (targetSubId == -1) {
            targetSubId = slotIndex + 1
            println("Semua lapis gagal, gunakan fallback kasar = $targetSubId")
        }
        
        println("Resolved Subscription ID for slot $slotIndex: $targetSubId")

        // 7. Invoke method
        val invokeArgs = arrayOfNulls<Any>(paramTypes.size)
        for (i in paramTypes.indices) {
            val type = paramTypes[i]
            when {
                type.isAssignableFrom(List::class.java) -> invokeArgs[i] = specifiers
                type == String::class.java -> invokeArgs[i] = "android"
                type == Int::class.javaPrimitiveType || type == Int::class.javaObjectType -> {
                    invokeArgs[i] = targetSubId
                }
                callbackType != null && type.isAssignableFrom(callbackType) -> invokeArgs[i] = callbackInstance
                else -> invokeArgs[i] = null
            }
        }

        try {
            targetMethod.invoke(telephonyService, *invokeArgs)
            println("Method invoked successfully. Waiting for callback...")
            if (callbackType != null) {
                val callbackReceived = latch.await(4, TimeUnit.SECONDS)
                if (!callbackReceived) {
                    println("WARNING: Timeout waiting for callback response. Operation might still succeed.")
                    return true
                }
                return callbackSuccess
            }
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }
}
