package com.spenmer.tripguard.domain

object ContactPhone {
    fun normalize(input: String): String? {
        val value = input.trim()
        if (value.length > 40 || value.any { it !in '0'..'9' && it !in " +()-" }) return null
        val compact = value.filterNot { it in " ()-" }
        if (!Regex("^\\+?[0-9]{7,15}$").matches(compact)) return null
        return compact
    }
}
