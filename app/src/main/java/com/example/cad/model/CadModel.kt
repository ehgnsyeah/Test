package com.example.cad.model

import com.example.cad.math.Point2D
import com.example.cad.math.Point3D
import com.example.cad.math.MathUtils
import java.util.UUID

enum class EntityType {
    BOX, CYLINDER, SPHERE, CONE, POLYLINE, EXTRUSION, COMBINED
}

data class CadEntity(
    val id: String = UUID.randomUUID().toString(),
    val type: EntityType,
    val name: String,
    // Pivot/Center position in 3D
    val x: Float = 0f,
    val y: Float = 0f,
    val z: Float = 0f,
    // Local size/dimensions
    val width: Float = 100f,   // dx for BOX
    val height: Float = 100f,  // dy for BOX/CYLINDER/CONE/EXTRUSION
    val depth: Float = 100f,   // dz for BOX
    val radius: Float = 50f,   // for CYLINDER/SPHERE/CONE
    // Local rotation (Euler angles in degrees)
    val rx: Float = 0f,
    val ry: Float = 0f,
    val rz: Float = 0f,
    // Styling properties
    val colorHex: String = "#FF2196F3", // Blue
    val layerId: String = "0", // Reference to a layer
    val isLocked: Boolean = false,
    val isVisible: Boolean = true,
    // Polyline points in local space
    val polylinePoints: List<Point3D> = emptyList(),
    // Custom extrusion 2D profile base points (local scale coordinates)
    val extrusionProfile: List<Point2D> = emptyList(),
    // Custom vertex offset displacement coordinates
    val vertexOffsets: List<Point3D> = emptyList(),
    // Merged coordinate lists for COMBINED / Splitted shapes
    val mergedVertices: List<Point3D> = emptyList(),
    val mergedFaces: List<List<Int>> = emptyList()
)

data class CadLayer(
    val id: String,
    val name: String,
    val colorHex: String = "#FF9E9E9E",
    val isVisible: Boolean = true,
    val isLocked: Boolean = false
)

object CadDefaults {
    val DefaultLayers = listOf(
        CadLayer("0", "주요 도면 (Main)", "#FF2196F3"),
        CadLayer("1", "외곽선/벽면 (Walls)", "#FF4CAF50"),
        CadLayer("2", "참조선 / 치수 (Ref Lines)", "#FFFFC107"),
        CadLayer("3", "가구 / 설비 (Fixtures)", "#FF9C27B0")
    )

    // Predefined 2D profiles for Extrusion
    // Star shape
    val ProfileStar = listOf(
        Point2D(0f, 50f), Point2D(15f, 15f),
        Point2D(50f, 15f), Point2D(20f, -5f),
        Point2D(30f, -45f), Point2D(0f, -25f),
        Point2D(-30f, -45f), Point2D(-20f, -5f),
        Point2D(-50f, 15f), Point2D(-15f, 15f)
    )

    // L-Bracket shape
    val ProfileLBracket = listOf(
        Point2D(-50f, -50f), Point2D(50f, -50f),
        Point2D(50f, -20f), Point2D(-20f, -20f),
        Point2D(-20f, 50f), Point2D(-50f, 50f)
    )

    // Regular Hexagon
    val ProfileHexagon = listOf(
        Point2D(0f, 50f), Point2D(43.3f, 25f),
        Point2D(43.3f, -25f), Point2D(0f, -50f),
        Point2D(-43.3f, -25f), Point2D(-43.3f, 25f)
    )
}

/**
 * Helper to export the list of 3D entities as standard OBJ format text.
 */
fun exportToObj(entities: List<CadEntity>, layers: List<CadLayer>): String {
    val sb = java.lang.StringBuilder()
    sb.append("# 3D CAD Modeler Android Generated Export\n")
    sb.append("# Generated elements: ${entities.size}\n\n")

    var vertexOffset = 1 // OBJ is 1-indexed

    entities.filter { it.isVisible }.forEach { entity ->
        sb.append("o ${entity.name.replace(" ", "_")}\n")
        sb.append("# Type: ${entity.type}\n")

        // Generate geometry in world space
        val geom = generateEntityGeometry(entity)
        val vertices = geom.first
        val faces = geom.second

        vertices.forEach { v ->
            sb.append("v ${v.x} ${v.y} ${v.z}\n")
        }

        faces.forEach { face ->
            // OBJ index starts at 1
            sb.append("f")
            face.forEach { idx ->
                sb.append(" ").append(idx + vertexOffset)
            }
            sb.append("\n")
        }

        sb.append("\n")
        vertexOffset += vertices.size
    }

    return sb.toString()
}

/**
 * Common geometry model representing vertices of a computed face
 */
data class FaceIndices(val indices: List<Int>)

private val geometryCache = java.util.concurrent.ConcurrentHashMap<CadEntity, Pair<List<Point3D>, List<List<Int>>>>()

/**
 * Generate vertices and faces for a CadEntity in World coordinates.
 * Returns Pair of computed word points and list of faces representing point indexes.
 * Cached to achieve high-performance real-time 3D and 2D rendering.
 */
fun generateEntityGeometry(entity: CadEntity): Pair<List<Point3D>, List<List<Int>>> {
    if (geometryCache.size > 1000) {
        geometryCache.clear()
    }
    return geometryCache.computeIfAbsent(entity) {
        generateEntityGeometryImpl(it)
    }
}

fun generateEntityGeometryImpl(entity: CadEntity): Pair<List<Point3D>, List<List<Int>>> {
    val localVertices = mutableListOf<Point3D>()
    val faces = mutableListOf<List<Int>>()

    when (entity.type) {
        EntityType.BOX -> {
            val w = entity.width
            val h = entity.height
            val d = entity.depth

            // 8 corners
            localVertices.add(Point3D(-w/2, -h/2, -d/2)) // 0
            localVertices.add(Point3D(w/2, -h/2, -d/2))  // 1
            localVertices.add(Point3D(w/2, h/2, -d/2))   // 2
            localVertices.add(Point3D(-w/2, h/2, -d/2))  // 3
            localVertices.add(Point3D(-w/2, -h/2, d/2))  // 4
            localVertices.add(Point3D(w/2, -h/2, d/2))   // 5
            localVertices.add(Point3D(w/2, h/2, d/2))    // 6
            localVertices.add(Point3D(-w/2, h/2, d/2))   // 7

            // 6 faces (quads)
            faces.add(listOf(0, 1, 2, 3)) // Back
            faces.add(listOf(5, 4, 7, 6)) // Front
            faces.add(listOf(4, 0, 3, 7)) // Left
            faces.add(listOf(1, 5, 6, 2)) // Right
            faces.add(listOf(3, 2, 6, 7)) // Top
            faces.add(listOf(4, 5, 1, 0)) // Bottom
        }
        EntityType.CYLINDER -> {
            val r = entity.radius
            val h = entity.height
            val segments = 16

            // Generate cylinder vertices
            // Bottom circle: vertices [0, segments-1]
            for (i in 0 until segments) {
                val angle = (2 * Math.PI * i / segments).toFloat()
                val xPoint = r * kotlin.math.cos(angle)
                val yPoint = r * kotlin.math.sin(angle)
                localVertices.add(Point3D(xPoint, yPoint, -h/2))
            }
            // Top circle: vertices [segments, 2*segments-1]
            for (i in 0 until segments) {
                val angle = (2 * Math.PI * i / segments).toFloat()
                val xPoint = r * kotlin.math.cos(angle)
                val yPoint = r * kotlin.math.sin(angle)
                localVertices.add(Point3D(xPoint, yPoint, h/2))
            }

            // Bottom base center (index: 2 * segments)
            localVertices.add(Point3D(0f, 0f, -h/2))
            // Top base center (index: 2 * segments + 1)
            localVertices.add(Point3D(0f, 0f, h/2))

            val botCenter = 2 * segments
            val topCenter = 2 * segments + 1

            // Connect sides (quads)
            for (i in 0 until segments) {
                val next = (i + 1) % segments
                faces.add(listOf(i, next, next + segments, i + segments))
            }

            // Top Cap (triangles pointing to top center)
            for (i in 0 until segments) {
                val next = (i + 1) % segments
                faces.add(listOf(i + segments, next + segments, topCenter))
            }

            // Bottom Cap (triangles pointing to bot center)
            for (i in 0 until segments) {
                val next = (i + 1) % segments
                faces.add(listOf(next, i, botCenter))
            }
        }
        EntityType.SPHERE -> {
            val r = entity.radius
            val rings = 8
            val segments = 12

            for (ring in 0..rings) {
                val phi = (Math.PI * ring / rings).toFloat()
                val sinPhi = kotlin.math.sin(phi)
                val cosPhi = kotlin.math.cos(phi)

                for (seg in 0 until segments) {
                    val theta = (2 * Math.PI * seg / segments).toFloat()
                    val cosTheta = kotlin.math.cos(theta)
                    val sinTheta = kotlin.math.sin(theta)

                    val xPoint = r * sinPhi * cosTheta
                    val yPoint = r * sinPhi * sinTheta
                    val zPoint = r * cosPhi

                    localVertices.add(Point3D(xPoint, yPoint, zPoint))
                }
            }

            // Generate faces
            for (ring in 0 until rings) {
                for (seg in 0 until segments) {
                    val nextSeg = (seg + 1) % segments
                    val currRow = ring * segments
                    val nextRow = (ring + 1) * segments

                    faces.add(listOf(
                        currRow + seg,
                        currRow + nextSeg,
                        nextRow + nextSeg,
                        nextRow + seg
                    ))
                }
            }
        }
        EntityType.CONE -> {
            val r = entity.radius
            val h = entity.height
            val segments = 16

            // Bottom circular face vertices
            for (i in 0 until segments) {
                val angle = (2 * Math.PI * i / segments).toFloat()
                val xPoint = r * kotlin.math.cos(angle)
                val yPoint = r * kotlin.math.sin(angle)
                localVertices.add(Point3D(xPoint, yPoint, -h/2))
            }

            // Tip of the cone at index segments
            localVertices.add(Point3D(0f, 0f, h/2))
            // Bottom center point at index segments + 1
            localVertices.add(Point3D(0f, 0f, -h/2))

            val tipIdx = segments
            val baseCenterIdx = segments + 1

            // Connect walls (triangles)
            for (i in 0 until segments) {
                val next = (i + 1) % segments
                faces.add(listOf(i, next, tipIdx))
            }

            // Connect base (triangles pointed inside)
            for (i in 0 until segments) {
                val next = (i + 1) % segments
                faces.add(listOf(next, i, baseCenterIdx))
            }
        }
        EntityType.POLYLINE -> {
            // A Polyline doesn't have standard solid faces, but for structural rendering
            // we can simulate small rectangular wire pipes, or simply model a line path.
            // For OBJ output we convert the wire vertices to OBJ lines. However since OBJ 'o'
            // expects face polygons, let's represent the vertices and add sequential lines.
            // To make sure faces list is valid, we can generate a simple vertex loop or box for each segment,
            // or return individual points. Let's return the points directly.
            val polyPts = entity.polylinePoints ?: emptyList()
            if (polyPts.isNotEmpty()) {
                localVertices.addAll(polyPts)
                // Add linear connections as face indexes
                for (i in 0 until polyPts.size - 1) {
                    faces.add(listOf(i, i + 1, i + 1, i)) // Duplicated to draw a line quad
                }
            } else {
                // Mock default point
                localVertices.add(Point3D(0f, 0f, 0f))
            }
        }
        EntityType.COMBINED -> {
            localVertices.addAll(entity.mergedVertices)
            faces.addAll(entity.mergedFaces)
        }
        EntityType.EXTRUSION -> {
            // Extrudes a 2D profile along Z by height
            val profile = if (entity.extrusionProfile != null && entity.extrusionProfile.isNotEmpty()) {
                entity.extrusionProfile
            } else {
                CadDefaults.ProfileHexagon
            }
            val numPoints = profile.size
            val h = entity.height

            // Bottom vertices (z = -h/2) [0, numPoints-1]
            profile.forEach { p ->
                localVertices.add(Point3D(p.x, p.y, -h/2))
            }

            // Top vertices (z = h/2) [numPoints, 2*numPoints-1]
            profile.forEach { p ->
                localVertices.add(Point3D(p.x, p.y, h/2))
            }

            // Bottom center (index: 2 * numPoints)
            var sumX = 0f
            var sumY = 0f
            profile.forEach { sumX += it.x; sumY += it.y }
            val avgX = sumX / numPoints
            val avgY = sumY / numPoints

            localVertices.add(Point3D(avgX, avgY, -h/2))
            // Top center (index: 2 * numPoints + 1)
            localVertices.add(Point3D(avgX, avgY, h/2))

            val botCent = 2 * numPoints
            val topCent = 2 * numPoints + 1

            // Side panels
            for (i in 0 until numPoints) {
                val next = (i + 1) % numPoints
                faces.add(listOf(i, next, next + numPoints, i + numPoints))
            }

            // Top Cap (triangles back-facing)
            for (i in 0 until numPoints) {
                val next = (i + 1) % numPoints
                faces.add(listOf(i + numPoints, next + numPoints, topCent))
            }

            // Bottom Cap (triangles pointing down)
            for (i in 0 until numPoints) {
                val next = (i + 1) % numPoints
                faces.add(listOf(next, i, botCent))
            }
        }
    }

    // Apply local vertex custom offsets if specified
    val offsets = entity.vertexOffsets ?: emptyList()
    if (offsets.isNotEmpty()) {
        for (idx in 0 until localVertices.size) {
            if (idx < offsets.size) {
                val offset = offsets[idx]
                val pt = localVertices[idx]
                localVertices[idx] = Point3D(pt.x + offset.x, pt.y + offset.y, pt.z + offset.z)
            }
        }
    }

    // Transform points to world coordinates: apply local rotation, then local translation
    val worldVertices = localVertices.map { localPt ->
        // 1. Local rotation Rx -> Ry -> Rz
        var pt = MathUtils.rotateX(localPt, Math.toRadians(entity.rx.toDouble()).toFloat())
        pt = MathUtils.rotateY(pt, Math.toRadians(entity.ry.toDouble()).toFloat())
        pt = MathUtils.rotateZ(pt, Math.toRadians(entity.rz.toDouble()).toFloat())

        // 2. World translation
        Point3D(pt.x + entity.x, pt.y + entity.y, pt.z + entity.z)
    }

    return Pair(worldVertices, faces)
}
