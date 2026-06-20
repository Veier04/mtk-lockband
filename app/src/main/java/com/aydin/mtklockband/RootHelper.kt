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

        // 4. Create RadioAccessSpecifier
        val specifiers = ArrayList<RadioAccessSpecifier>()
        if (bands.isNotEmpty()) {
            val ras = RadioAccessSpecifier(3, bands, intArrayOf())
            specifiers.add(ras)
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
                // Support both accept(int) and accept(boolean) dynamically
                if (argsList != null && argsList.isNotEmpty()) {
                    val firstArg = argsList[0]
                    println("Callback invoked with arg: $firstArg (${firstArg?.javaClass?.name})")
                    if (firstArg is Boolean) {
                        callbackSuccess = firstArg
                    } else if (firstArg is Number) {
                        callbackSuccess = (firstArg.toInt() == 0) // 0 means SUCCESS in integer interfaces
                    }
                    latch.countDown()
                }
                null
            }
        }

        // 6. Invoke method mapping primitive types correctly
        val invokeArgs = arrayOfNulls<Any>(paramTypes.size)
        
        // Find subscription ID for the given slotIndex using static method getSubscriptionId
        var targetSubId = -1
        try {
            val subManagerClass = Class.forName("android.telephony.SubscriptionManager")
            // Calling static `getSubscriptionId` instead of non-static `getSubscriptionIds`
            val getSubIdMethod = subManagerClass.getMethod("getSubscriptionId", Int::class.java)
            val res = getSubIdMethod.invoke(null, slotIndex) as? Int
            if (res != null && res != -1) {
                targetSubId = res
            }
        } catch (t: Throwable) {
            println("Failed to read SubscriptionManager for slot. Error: ${t.message}")
        }
        
        // Fallback to default subscription ID if slot manager fails
        if (targetSubId == -1) {
            try {
                val subManagerClass = Class.forName("android.telephony.SubscriptionManager")
                val getDefaultSubId = subManagerClass.getMethod("getDefaultSubscriptionId")
                targetSubId = getDefaultSubId.invoke(null) as Int
            } catch (t: Throwable) {
                targetSubId = 1
            }
        }
        
        println("Resolved Subscription ID for slot $slotIndex: $targetSubId")

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
