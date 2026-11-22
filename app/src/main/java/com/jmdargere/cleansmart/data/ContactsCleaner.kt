package com.jmdargere.cleansmart.data

import android.content.Context
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.Contacts

data class ContactDup(val name: String, val number: String, val keep: Long, val remove: List<Long>)
data class ContactsResult(val total: Int, val groups: List<ContactDup>) {
    val duplicates get() = groups.sumOf { it.remove.size }
}

/** Doublons = meme nom ET meme numero (9 derniers chiffres). Le premier est garde. */
class ContactsCleaner(ctx: Context) {

    private val cr = ctx.contentResolver

    fun scan(): ContactsResult {
        val byKey = LinkedHashMap<String, LinkedHashSet<Long>>()
        val info = LinkedHashMap<Long, Pair<String, String>>()
        cr.query(Phone.CONTENT_URI, arrayOf(Phone.CONTACT_ID, Phone.DISPLAY_NAME, Phone.NUMBER),
            null, null, Phone.CONTACT_ID + " ASC")?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val name = (c.getString(1) ?: "").trim()
                val num = c.getString(2) ?: ""
                var digits = num.filter { it.isDigit() }
                if (digits.length > 9) digits = digits.takeLast(9)
                info.putIfAbsent(id, name to num)
                if (digits.isEmpty()) continue
                byKey.getOrPut(name.lowercase() + "|" + digits) { LinkedHashSet() } += id
            }
        }
        val seen = HashSet<Long>()
        val groups = byKey.values.filter { it.size > 1 }.mapNotNull { ids ->
            val list = ids.toList()
            if (list[0] in seen) return@mapNotNull null
            seen += list
            val (name, num) = info.getValue(list[0])
            ContactDup(name, num, list[0], list.drop(1))
        }
        return ContactsResult(info.size, groups)
    }

    /** Cles "lookup" pour exporter ces contacts en .vcf avant suppression. */
    fun lookupKeys(ids: List<Long>): List<String> {
        if (ids.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        ids.chunked(200).forEach { part ->
            cr.query(Contacts.CONTENT_URI, arrayOf(Contacts.LOOKUP_KEY),
                Contacts._ID + " IN (" + part.joinToString(",") + ")", null, null)?.use { c ->
                while (c.moveToNext()) c.getString(0)?.let { out += it }
            }
        }
        return out
    }

    fun delete(ids: List<Long>): Int =
        ids.sumOf { cr.delete(Contacts.CONTENT_URI, Contacts._ID + "=?", arrayOf(it.toString())) }
}
