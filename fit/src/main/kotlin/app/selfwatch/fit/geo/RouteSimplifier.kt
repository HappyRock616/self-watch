/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.fit.geo

import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Douglas-Peucker simplification to a point budget. Each point gets the distance at which the
 * Douglas-Peucker split would keep it (capped by its parent split, so the order matches growing
 * tolerances); the [simplify] result keeps the end points and the most important others. That is the
 * Douglas-Peucker result for the smallest tolerance that fits the budget.
 */
object RouteSimplifier {
    /** Tracks longer than this are thinned evenly first, to bound the work on very long recordings. */
    private const val MAX_INPUT = 20_000

    /** At most [maxPoints] of [points] (first and last always kept), in their original order. */
    fun simplify(points: List<LatLonE7>, maxPoints: Int): List<LatLonE7> {
        if (maxPoints <= 0 || points.isEmpty()) return emptyList()
        if (points.size <= maxPoints) return points
        if (maxPoints == 1) return listOf(points.first())
        val pts = thin(points)
        val n = pts.size
        if (n <= maxPoints) return pts

        var latSum = 0.0
        for (p in pts) latSum += p.latE7
        val k = cos(Math.toRadians(latSum / n / 1e7))
        val x = DoubleArray(n) { pts[it].lonE7 * k }
        val y = DoubleArray(n) { pts[it].latE7.toDouble() }

        val imp = DoubleArray(n)
        imp[0] = Double.POSITIVE_INFINITY
        imp[n - 1] = Double.POSITIVE_INFINITY
        val stackS = IntArray(2 * n + 2)
        val stackE = IntArray(2 * n + 2)
        val stackP = DoubleArray(2 * n + 2)

        stackS[0] = 0; stackE[0] = n - 1; stackP[0] = Double.POSITIVE_INFINITY
        var top = 1
        while (top > 0) {
            top--
            val s = stackS[top]
            val e = stackE[top]
            val parent = stackP[top]
            if (e - s < 2) continue
            var best = -1.0
            var bi = s + 1
            for (i in s + 1 until e) {
                val d = segDist(x[i], y[i], x[s], y[s], x[e], y[e])
                if (d > best) { best = d; bi = i }
            }
            val d = minOf(best, parent)
            imp[bi] = d
            stackS[top] = s; stackE[top] = bi; stackP[top] = d; top++
            stackS[top] = bi; stackE[top] = e; stackP[top] = d; top++
        }
        val order = (0 until n).sortedByDescending { imp[it] }
        return order.take(maxPoints).sorted().map { pts[it] }
    }

    private fun thin(points: List<LatLonE7>): List<LatLonE7> {
        if (points.size <= MAX_INPUT) return points
        val step = (points.size + MAX_INPUT - 1) / MAX_INPUT
        val out = ArrayList<LatLonE7>(MAX_INPUT + 1)
        var i = 0
        while (i < points.size) { out.add(points[i]); i += step }
        if (out.last() != points.last()) out.add(points.last())
        return out
    }

    private fun segDist(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        var t = if (len2 == 0.0) 0.0 else ((px - ax) * dx + (py - ay) * dy) / len2
        t = t.coerceIn(0.0, 1.0)
        val qx = ax + t * dx - px
        val qy = ay + t * dy - py
        return sqrt(qx * qx + qy * qy)
    }
}
