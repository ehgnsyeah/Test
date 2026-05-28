package com.example.cad.math

import kotlin.math.sqrt

data class Point3D(val x: Float, val y: Float, val z: Float) {
    operator fun plus(other: Point3D) = Point3D(x + other.x, y + other.y, z + other.z)
    operator fun minus(other: Point3D) = Point3D(x - other.x, y - other.y, z - other.z)
    operator fun times(scale: Float) = Point3D(x * scale, y * scale, z * scale)
    operator fun div(scale: Float) = Point3D(x / scale, y / scale, z / scale)

    fun dot(other: Point3D): Float {
        return x * other.x + y * other.y + z * other.z
    }

    fun cross(other: Point3D): Point3D {
        return Point3D(
            y * other.z - z * other.y,
            z * other.x - x * other.z,
            x * other.y - y * other.x
        )
    }

    fun length(): Float {
        return sqrt((x * x + y * y + z * z).toDouble()).toFloat()
    }

    fun normalize(): Point3D {
        val len = length()
        return if (len > 0.0001f) {
            this / len
        } else {
            Point3D(0f, 0f, 0f)
        }
    }

    fun distanceTo(other: Point3D): Float {
        return (this - other).length()
    }
}

data class Point2D(val x: Float, val y: Float)

object MathUtils {
    fun rotateX(p: Point3D, angleRad: Float): Point3D {
        val cos = kotlin.math.cos(angleRad.toDouble()).toFloat()
        val sin = kotlin.math.sin(angleRad.toDouble()).toFloat()
        return Point3D(p.x, p.y * cos - p.z * sin, p.y * sin + p.z * cos)
    }

    fun rotateY(p: Point3D, angleRad: Float): Point3D {
        val cos = kotlin.math.cos(angleRad.toDouble()).toFloat()
        val sin = kotlin.math.sin(angleRad.toDouble()).toFloat()
        return Point3D(p.x * cos + p.z * sin, p.y, -p.x * sin + p.z * cos)
    }

    fun rotateZ(p: Point3D, angleRad: Float): Point3D {
        val cos = kotlin.math.cos(angleRad.toDouble()).toFloat()
        val sin = kotlin.math.sin(angleRad.toDouble()).toFloat()
        return Point3D(p.x * cos - p.y * sin, p.x * sin + p.y * cos, p.z)
    }
}
