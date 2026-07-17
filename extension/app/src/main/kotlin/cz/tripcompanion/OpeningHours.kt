package cz.tripcompanion

import java.time.LocalDateTime
import java.util.Locale

/**
 * Tiny OSM `opening_hours` evaluator — just enough for the specs our POIs carry
 * ("Mo-Th 07:00-20:00; Fr,Sa 07:00-22:00; Su 08:00-20:00", "24/7", "Mo off", …).
 * Anything it can't read degrades to [Status.Unknown]; callers then fall back to raw text.
 */
object OpeningHours {
    sealed class Status {
        /** Open right now; [untilHm] is "20:00" (null when open-ended, e.g. 24/7). */
        data class Open(val untilHm: String?, val closingSoon: Boolean) : Status()

        /** Closed right now; [opensHm] is today's next opening ("11:00"), null if none today. */
        data class Closed(val opensHm: String?) : Status()

        object Unknown : Status()
    }

    private val DAYS = listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")
    private val TIME = Regex("(\\d{1,2}):(\\d{2})\\s*-\\s*(\\d{1,2}):(\\d{2})")

    fun status(spec: String?, now: LocalDateTime = LocalDateTime.now()): Status = try {
        evaluate(spec, now)
    } catch (e: Exception) {
        Status.Unknown
    }

    private fun evaluate(spec: String?, now: LocalDateTime): Status {
        val s = spec?.trim() ?: return Status.Unknown
        if (s.isEmpty()) return Status.Unknown
        if (s == "24/7") return Status.Open(null, false)

        // dayIntervals[d] = minute-intervals for weekday d (0=Mo). Later rules override
        // earlier ones for the days they mention (OSM semantics).
        val dayIntervals = Array(7) { mutableListOf<IntRange>() }
        val daySet = Array(7) { false }
        var parsedAny = false
        for (ruleRaw in s.split(";")) {
            val rule = ruleRaw.trim()
            if (rule.isEmpty()) continue
            if (rule.contains("PH") || rule.contains("SH")) continue // public/school holidays: skip
            val days = parseDays(rule) ?: continue
            val off = rule.contains(Regex("\\b(off|closed)\\b"))
            val times = TIME.findAll(rule).toList()
            if (!off && times.isEmpty()) continue
            for (d in days) {
                if (!daySet[d]) { dayIntervals[d].clear(); daySet[d] = true }
            }
            for (t in times) {
                val (h1, m1, h2, m2) = t.destructured
                val start = h1.toInt() * 60 + m1.toInt()
                var end = h2.toInt() * 60 + m2.toInt()
                if (end == 0) end = 24 * 60
                for (d in days) {
                    if (off) continue
                    if (end > start) {
                        dayIntervals[d].add(start until end)
                    } else {
                        // Past midnight (18:00-02:00): tail lands on the next day.
                        dayIntervals[d].add(start until 24 * 60)
                        dayIntervals[(d + 1) % 7].add(0 until end)
                    }
                }
            }
            parsedAny = true
        }
        if (!parsedAny) return Status.Unknown

        val today = now.dayOfWeek.value - 1 // 0=Mo
        val nowMin = now.hour * 60 + now.minute
        val open = dayIntervals[today].firstOrNull { nowMin in it }
        if (open != null) {
            val endMin = open.last + 1
            return Status.Open(hm(endMin % (24 * 60)), closingSoon = endMin - nowMin <= 45)
        }
        val next = dayIntervals[today].map { it.first }.filter { it > nowMin }.minOrNull()
        return Status.Closed(next?.let { hm(it) })
    }

    /** Day tokens before the first time ("Mo-Th", "Fr,Sa", none = all week). Null = unparseable. */
    private fun parseDays(rule: String): List<Int>? {
        // Everything before the first digit is the day part.
        val dayPart = rule.takeWhile { !it.isDigit() }.trim().removeSuffix(",")
        if (dayPart.isEmpty() || dayPart.equals("off", true)) return (0..6).toList()
        val out = mutableListOf<Int>()
        for (tokRaw in dayPart.split(",")) {
            val tok = tokRaw.trim().removeSuffix("off").trim()
            if (tok.isEmpty()) continue
            if (tok.contains("-")) {
                val a = DAYS.indexOf(tok.substringBefore("-").trim())
                val b = DAYS.indexOf(tok.substringAfter("-").trim())
                if (a < 0 || b < 0) return null
                var d = a
                while (true) {
                    out.add(d)
                    if (d == b) break
                    d = (d + 1) % 7
                }
            } else {
                val a = DAYS.indexOf(tok)
                if (a < 0) return null
                out.add(a)
            }
        }
        return if (out.isEmpty()) (0..6).toList() else out
    }

    private fun hm(min: Int): String = String.format(Locale.US, "%d:%02d", min / 60, min % 60)
}
