package dev.leandro.quicklydrop.lab.spike

/** Uppercase hex without separators, for logging AIDs and APDUs. */
fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }
