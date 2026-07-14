package cz.tripcompanion

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object RouteMath {
    fun haversine(la1: Double, lo1: Double, la2: Double, lo2: Double): Double {
        val r = 6371.0
        val p1 = Math.toRadians(la1)
        val p2 = Math.toRadians(la2)
        val dp = Math.toRadians(la2 - la1)
        val dl = Math.toRadians(lo2 - lo1)
        val a = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
        return 2 * r * asin(sqrt(a))
    }

    /** Your progress along the combined route = cumulative km of the nearest track point. */
    fun myRouteKm(lat: Double, lon: Double, track: List<DoubleArray>): Double {
        var best = Double.MAX_VALUE
        var km = 0.0
        for (t in track) {
            val d = haversine(lat, lon, t[0], t[1])
            if (d < best) {
                best = d
                km = t[2]
            }
        }
        return km
    }

    data class Ahead(val poi: Poi, val remainingKm: Double)

    /** POIs still ahead of you, nearest first. Keeps a small margin so a just-passed POI lingers briefly. */
    fun sortedAhead(pois: List<Poi>, myKm: Double): List<Ahead> =
        pois.map { Ahead(it, it.routeKm - myKm) }
            .filter { it.remainingKm > -0.25 }
            .sortedBy { it.remainingKm }
}
