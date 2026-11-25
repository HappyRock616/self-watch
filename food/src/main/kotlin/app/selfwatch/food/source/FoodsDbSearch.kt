/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food.source

import java.text.Normalizer

/**
 * The pure rules of foods.db search (a port of `tools/fooddb/search_test.py`), kept apart from SQLite
 * so they can be tested directly. [FoodsDbSource] runs the queries.
 */
object FoodsDbSearch {

    /** Popularity of the pinned everyday foods (`canonical.csv`); their `keywords` start with ", term, term". */
    const val CANONICAL_POPULARITY: Int = 1000

    /** What ranking needs to know about one fetched row. */
    data class Candidate(val name: String, val brand: String?, val keywords: String?, val popularity: Int)

    /** Brand mode: the chain the query starts with and the words after its name. */
    data class BrandMode(val chainId: Long, val chainName: String, val rest: List<String>)

    /**
     * The words of [text]: lowercase, apostrophes (' and ’) dropped so "McDonald's" is "mcdonalds",
     * then runs of letters and digits. Everything else only separates words, so FTS operators can
     * never sneak in (`AND`/`OR`/`NOT` are lowercased into plain terms).
     */
    fun words(text: String?): List<String> {
        if (text.isNullOrEmpty()) return emptyList()
        val s = text.lowercase().replace("'", "").replace("’", "")
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            if (Character.isLetterOrDigit(cp) || Character.getType(cp) == Character.NON_SPACING_MARK.toInt()) {
                sb.appendCodePoint(cp)
            } else if (sb.isNotEmpty()) {
                out.add(sb.toString())
                sb.setLength(0)
            }
            i += Character.charCount(cp)
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    /** [words] with accents removed, for comparing names the way the FTS tokenizer does (crème = creme). */
    fun plainWords(text: String?): List<String> = words(text).map(::fold)

    /** The FTS4 MATCH text: every word whole except the last, which is a prefix (`big mac*`). */
    fun ftsMatch(words: List<String>): String {
        require(words.isNotEmpty()) { "no words" }
        return (words.dropLast(1) + (words.last() + "*")).joinToString(" ")
    }

    /**
     * Brand mode for [typed] words: the longest leading run of words that names one of [chains]
     * (`id` to name), compared with spaces or without ("chick fil a" and "chickfila" both find
     * "Chick-fil-A"). Null when the query does not start with a chain.
     */
    fun brandMode(typed: List<String>, chains: List<Pair<Long, String>>): BrandMode? {
        if (typed.isEmpty() || chains.isEmpty()) return null
        val byKey = HashMap<String, Pair<Long, String>>()
        for (ch in chains) {
            val w = plainWords(ch.second)
            if (w.isEmpty()) continue
            byKey.putIfAbsent(w.joinToString(" "), ch)
            byKey.putIfAbsent(w.joinToString(""), ch)
        }
        val plain = typed.map(::fold)
        // Longest first, so "taco bell bean burrito" picks Taco Bell, not a chain called "Taco".
        for (k in plain.size downTo 1) {
            val key = plain.subList(0, k).joinToString(" ")
            val hit = byKey[key] ?: byKey[key.replace(" ", "")] ?: continue
            return BrandMode(hit.first, hit.second, typed.subList(k, typed.size))
        }
        return null
    }

    /**
     * The rank tier of [c] for the query [words] (lower is better):
     * 0 a pinned everyday food whose term (keywords after the first comma) is exactly the query;
     * 1 a pinned food whose term contains the query as a phrase;
     * 2 the name equals the query or starts with it;
     * 3 every word whole in the name (the last word may be a prefix when shorter than 3 letters);
     * 4 every word found in the name, brand or keywords;
     * 5 the rest (prefix matches such as "salmonberries" for "salmon").
     */
    fun tier(words: List<String>, c: Candidate): Int {
        val q = words.map(::fold)
        val qs = q.joinToString(" ")
        val kw = c.keywords
        // Pinned foods carry the searches they answer after the first comma (", egg, eggs"), so
        // a plain search lands on the everyday food before brand items whose names merely contain the word.
        if (c.popularity == CANONICAL_POPULARITY && kw != null && kw.contains(',')) {
            val terms = kw.split(',').drop(1).map { plainWords(it).joinToString(" ") }
            if (qs in terms) return 0
            // Padded with spaces so only whole words match ("egg" in "egg white", not in "eggplant").
            if (terms.any { " $it ".contains(" $qs ") }) return 1
        }
        val nameWords = plainWords(c.name)
        val nn = nameWords.joinToString(" ")
        if (nn == qs || nn.startsWith("$qs ")) return 2
        val set = nameWords.toHashSet()
        val last = q.last()
        // The user may still be typing the last word: a short one ("ch") counts as a prefix, but from 3
        // letters it must be whole, or "rice" would rank "ricotta" with the exact matches.
        val lastOk = if (last.length < 3) last in set || set.any { it.startsWith(last) } else last in set
        if (q.dropLast(1).all { it in set } && lastOk) return 3
        val all = (nameWords + plainWords(c.brand) + plainWords(kw)).toHashSet()
        if (q.all { it in all }) return 4
        return 5
    }

    /**
     * [rows] sorted by [tier], then popularity (higher first), then shorter name. Stable. The shorter name
     * breaks ties because it is usually the plain food ("Banana" before "Banana chips, sweetened").
     */
    fun <T> rank(words: List<String>, rows: List<T>, candidate: (T) -> Candidate): List<T> =
        rows.map { it to candidate(it) }
            .sortedWith(compareBy<Pair<T, Candidate>>({ tier(words, it.second) }, { -it.second.popularity }, { it.second.name.length }))
            .map { it.first }

    private val MARKS = Regex("\\p{Mn}+")

    // Most words are plain ASCII: skip the Unicode normalization (the slow part) for them.
    private fun fold(w: String): String =
        if (w.all { it.code < 128 }) w else MARKS.replace(Normalizer.normalize(w, Normalizer.Form.NFD), "")
}
