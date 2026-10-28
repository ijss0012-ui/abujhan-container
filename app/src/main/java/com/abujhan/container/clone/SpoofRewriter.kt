package com.abujhan.container.clone

import org.jf.dexlib2.Opcode
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.dexbacked.DexBackedDexFile
import org.jf.dexlib2.iface.instruction.Instruction
import org.jf.dexlib2.iface.instruction.formats.Instruction35c
import org.jf.dexlib2.iface.instruction.formats.Instruction3rc
import org.jf.dexlib2.iface.reference.MethodReference
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction35c
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction3rc
import org.jf.dexlib2.immutable.reference.ImmutableMethodReference
import org.jf.dexlib2.rewriter.DexRewriter
import org.jf.dexlib2.rewriter.InstructionRewriter
import org.jf.dexlib2.rewriter.RewriterModule
import org.jf.dexlib2.rewriter.Rewriter
import org.jf.dexlib2.rewriter.Rewriters
import org.jf.dexlib2.writer.io.MemoryDataStore
import org.jf.dexlib2.writer.pool.DexPool

/**
 * يعيد كتابة ملف dex للنسخة: يستبدل استدعاءات واجهات الموقع بنقاط الاعتراض في
 * com.container.inject.LocationHook، فيحصل التطبيق على الموقع المزيّف.
 *
 * الاستدعاء الأصلي (virtual/interface) وبعده المعامِلات نفسها، والنقطة الجديدة (static)
 * تأخذ المستقبِل أولًا كمعامِل عادي، لذا تبقى قائمة السجلات كما هي.
 */
object SpoofRewriter {

    private const val HOOK = "Lcom/container/inject/LocationHook;"
    private const val LOCATION = "Landroid/location/Location;"
    private const val LOCATION_MANAGER = "Landroid/location/LocationManager;"
    private const val LOCATION_LISTENER = "Landroid/location/LocationListener;"
    private const val FUSED = "Lcom/google/android/gms/location/FusedLocationProviderClient;"
    private const val LOCATION_REQUEST = "Lcom/google/android/gms/location/LocationRequest;"
    private const val LOCATION_CALLBACK = "Lcom/google/android/gms/location/LocationCallback;"
    private const val TASK = "Lcom/google/android/gms/tasks/Task;"
    private const val LOOPER = "Landroid/os/Looper;"
    private const val STRING = "Ljava/lang/String;"
    private const val ANDROID_LOCATION_REQUEST = "Landroid/location/LocationRequest;"
    private const val EXECUTOR = "Ljava/util/concurrent/Executor;"
    private const val PLAY_SERVICES_LM = "Lcom/reactnativecommunity/geolocation/PlayServicesLocationManager;"

    private val hookSignatures = ArrayList<String>()

    private val targets: Map<String, ImmutableMethodReference> = mapOf(
        // LocationManager
        "$LOCATION_MANAGER->getLastKnownLocation($STRING)$LOCATION" to
            hook("getLastKnownLocation", listOf(LOCATION_MANAGER, STRING), LOCATION),
        "$LOCATION_MANAGER->requestLocationUpdates($STRING" + "JF$LOCATION_LISTENER)V" to
            hook("requestLocationUpdates", listOf(LOCATION_MANAGER, STRING, "J", "F", LOCATION_LISTENER), "V"),
        "$LOCATION_MANAGER->removeUpdates($LOCATION_LISTENER)V" to
            hook("removeUpdates", listOf(LOCATION_MANAGER, LOCATION_LISTENER), "V"),
        "$LOCATION_MANAGER->requestLocationUpdates($STRING" + "JF$LOCATION_LISTENER$LOOPER)V" to
            hook("requestLocationUpdates", listOf(LOCATION_MANAGER, STRING, "J", "F", LOCATION_LISTENER, LOOPER), "V"),
        "$LOCATION_MANAGER->requestLocationUpdates($STRING$ANDROID_LOCATION_REQUEST$EXECUTOR$LOCATION_LISTENER)V" to
            hook(
                "requestLocationUpdates",
                listOf(LOCATION_MANAGER, STRING, ANDROID_LOCATION_REQUEST, EXECUTOR, LOCATION_LISTENER),
                "V",
            ),

        // Fused (Google Play Services)
        "$FUSED->getLastLocation()$TASK" to
            hook("getLastLocation", listOf(FUSED), TASK),
        "$FUSED->requestLocationUpdates($LOCATION_REQUEST$LOCATION_CALLBACK$LOOPER)$TASK" to
            hook("requestLocationUpdates", listOf(FUSED, LOCATION_REQUEST, LOCATION_CALLBACK, LOOPER), TASK),
        "$FUSED->removeLocationUpdates($LOCATION_CALLBACK)$TASK" to
            hook("removeLocationUpdates", listOf(FUSED, LOCATION_CALLBACK), TASK),

        // React Native geolocation (يستدعيه التطبيق بـ invoke-direct)
        "$PLAY_SERVICES_LM->requestLocationUpdates($LOCATION_REQUEST$LOCATION_CALLBACK)V" to
            hook("requestPlayServicesUpdates", listOf("Ljava/lang/Object;", LOCATION_REQUEST, LOCATION_CALLBACK), "V"),
    )

    private fun hook(name: String, params: List<String>, ret: String): ImmutableMethodReference {
        hookSignatures.add("$name(${params.joinToString("")})$ret")
        return ImmutableMethodReference(HOOK, name, params, ret)
    }

    /**
     * بصمة قائمة الأهداف ونقاط الاعتراض. تتغيّر تلقائيًا عند تعديل أي هدف،
     * فلا يُستخدم ناتج قديم من التخزين المؤقت.
     */
    val FINGERPRINT: String by lazy {
        val description = targets.keys.sorted().joinToString("\n") + "\n" +
            hookSignatures.sorted().joinToString("\n")
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(description.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(16)
    }

    /** يعيد dex جديدًا، ومعه عدد نقاط الاعتراض التي استُبدلت. */
    fun rewrite(dex: ByteArray): Pair<ByteArray, Int> {
        val opcodes = Opcodes.getDefault()
        val input = DexBackedDexFile(opcodes, dex)
        if (!containsTarget(input)) {
            return dex to 0
        }

        var replaced = 0
        val module = object : RewriterModule() {
            override fun getInstructionRewriter(rewriters: Rewriters): Rewriter<Instruction> =
                object : InstructionRewriter(rewriters) {
                    override fun rewrite(insn: Instruction): Instruction {
                        if (insn is Instruction35c && isVirtual(insn.opcode)) {
                            val target = targetOf(insn.reference)
                            if (target != null) {
                                replaced++
                                return ImmutableInstruction35c(
                                    Opcode.INVOKE_STATIC,
                                    insn.registerCount,
                                    insn.registerC,
                                    insn.registerD,
                                    insn.registerE,
                                    insn.registerF,
                                    insn.registerG,
                                    target,
                                )
                            }
                        }
                        if (insn is Instruction3rc && isVirtualRange(insn.opcode)) {
                            val target = targetOf(insn.reference)
                            if (target != null) {
                                replaced++
                                return ImmutableInstruction3rc(
                                    Opcode.INVOKE_STATIC_RANGE,
                                    insn.startRegister,
                                    insn.registerCount,
                                    target,
                                )
                            }
                        }
                        return super.rewrite(insn)
                    }
                }
        }

        val rewritten = DexRewriter(module).dexFileRewriter.rewrite(input)
        val store = MemoryDataStore()
        DexPool.writeTo(store, rewritten)
        return store.data to replaced
    }

    private fun isVirtual(op: Opcode) =
        op == Opcode.INVOKE_VIRTUAL || op == Opcode.INVOKE_INTERFACE || op == Opcode.INVOKE_DIRECT

    private fun isVirtualRange(op: Opcode) =
        op == Opcode.INVOKE_VIRTUAL_RANGE || op == Opcode.INVOKE_INTERFACE_RANGE || op == Opcode.INVOKE_DIRECT_RANGE

    private fun targetOf(ref: Any?): ImmutableMethodReference? {
        if (ref !is MethodReference) return null
        return targets[signature(ref)]
    }

    private fun signature(ref: MethodReference): String =
        "${ref.definingClass}->${ref.name}(${ref.parameterTypes.joinToString("")})${ref.returnType}"

    private fun containsTarget(dex: DexBackedDexFile): Boolean =
        dex.classes.any { cls ->
            cls.methods.any { method ->
                method.implementation?.instructions?.any { insn ->
                    (insn is Instruction35c || insn is Instruction3rc) &&
                        targetOf((insn as org.jf.dexlib2.iface.instruction.ReferenceInstruction).reference) != null
                } == true
            }
        }
}
