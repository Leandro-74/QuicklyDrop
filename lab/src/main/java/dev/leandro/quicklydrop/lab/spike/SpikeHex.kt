package dev.leandro.quicklydrop.lab.spike

/** Hex maiúsculo sem separador, para logar AIDs e APDUs. */
fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }
