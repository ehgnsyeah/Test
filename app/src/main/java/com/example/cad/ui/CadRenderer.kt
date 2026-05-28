package com.example.cad.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.example.cad.math.MathUtils
import com.example.cad.math.Point2D
import com.example.cad.math.Point3D
import com.example.cad.model.CadEntity
import com.example.cad.model.EntityType
import com.example.cad.model.generateEntityGeometry
import kotlin.math.abs
import kotlin.math.max

// Structural camera parameters
data class ViewportState(
    val yaw: Float = 45f,      // Rotation around global Y/Up axis
    val pitch: Float = -30f,   // Rotation around screen X axis
    val zoom: Float = 1.0f,     // Zoom scale factor
    val panX: Float = 0f,      // Drag translation X
    val panY: Float = 0f       // Drag translation Y
)

enum class ViewportMode {
    PERSPECTIVE, ISOMETRIC, TOP, FRONT, RIGHT
}

object CadRenderer {

    // Simple directional lighting source (from light-front-right-top)
    private val LIGHT_DIR = Point3D(0.5f, 1.0f, 0.8f).normalize()

    /**
     * Converts a 3D point in world coordinates to 2D screen coordinates.
     */
    fun projectPoint(
        point: Point3D,
        camera: ViewportState,
        viewMode: ViewportMode,
        cx: Float,
        cy: Float
    ): Point2D {
        // 1. Shift by pan offset
        // 2. Rotate by Camera Angles
        var p = point

        val yawRad = Math.toRadians(camera.yaw.toDouble()).toFloat()
        val pitchRad = Math.toRadians(camera.pitch.toDouble()).toFloat()

        when (viewMode) {
            ViewportMode.TOP -> {
                // Look down from Z axis (XY plane projection)
                // x = point.x, y = point.y
                return Point2D(
                    cx + (p.x * 2f * camera.zoom) + camera.panX,
                    cy + (p.y * 2f * camera.zoom) + camera.panY
                )
            }
            ViewportMode.FRONT -> {
                // Look from Y axis (XZ plane projection)
                return Point2D(
                    cx + (p.x * 2f * camera.zoom) + camera.panX,
                    cy - (p.z * 2f * camera.zoom) + camera.panY
                )
            }
            ViewportMode.RIGHT -> {
                // Look from X axis (YZ plane projection)
                return Point2D(
                    cx + (p.z * 2f * camera.zoom) + camera.panX,
                    cy - (p.y * 2f * camera.zoom) + camera.panY
                )
            }
            ViewportMode.ISOMETRIC, ViewportMode.PERSPECTIVE -> {
                // Standard 3D Orbit Rotations
                // Rotate around vertical Y axis (yaw)
                p = MathUtils.rotateY(p, yawRad)
                // Rotate around screen horizontal X axis (pitch)
                p = MathUtils.rotateX(p, pitchRad)
            }
        }

        // Project
        if (viewMode == ViewportMode.PERSPECTIVE) {
            // Distance from camera to projection screen
            val d = 600f
            // Base offset so camera is placed back from coordinates center
            val distZ = p.z + 800f
            val factor = if (distZ > 50f) d / distZ else 12f

            return Point2D(
                cx + (p.x * factor * camera.zoom) + camera.panX,
                cy - (p.y * factor * camera.zoom) + camera.panY
            )
        } else {
            // Orthographic / Isometric Projection (isometric uses constant scale)
            val scale = 0.8f * camera.zoom
            return Point2D(
                cx + (p.x * scale) + camera.panX,
                cy - (p.y * scale) + camera.panY
            )
        }
    }

    /**
     * Compute face normal and depth in camera space for ordering & shading.
     */
    class RenderFace(
        val entityId: String,
        val entityName: String,
        val faceIndices: List<Int>,
        val worldVertices: List<Point3D>,
        val baseColor: Color,
        val avgZ: Float,         // Camera-space Z depth for sorting
        val lightingFactor: Float,
        val isSelected: Boolean
    )

    /**
     * Renders the base grid and 3D coordinate axes.
     */
    fun drawGridAndAxes(
        drawScope: DrawScope,
        camera: ViewportState,
        viewMode: ViewportMode,
        gridSize: Float,
        showGrid: Boolean
    ) {
        val cx = drawScope.size.width / 2f
        val cy = drawScope.size.height / 2f

        if (showGrid) {
            val gridColor = Color(0x33888888)
            val step = gridSize
            val range = 400f

            // Draw XY Ground Grid
            var xVal = -range
            while (xVal <= range) {
                val p1 = projectPoint(Point3D(xVal, -range, 0f), camera, viewMode, cx, cy)
                val p2 = projectPoint(Point3D(xVal, range, 0f), camera, viewMode, cx, cy)
                drawScope.drawLine(
                    color = gridColor,
                    start = androidx.compose.ui.geometry.Offset(p1.x, p1.y),
                    end = androidx.compose.ui.geometry.Offset(p2.x, p2.y),
                    strokeWidth = 1f
                )

                val p3 = projectPoint(Point3D(-range, xVal, 0f), camera, viewMode, cx, cy)
                val p4 = projectPoint(Point3D(range, xVal, 0f), camera, viewMode, cx, cy)
                drawScope.drawLine(
                    color = gridColor,
                    start = androidx.compose.ui.geometry.Offset(p3.x, p3.y),
                    end = androidx.compose.ui.geometry.Offset(p4.x, p4.y),
                    strokeWidth = 1f
                )
                xVal += step
            }
        }

        // Draw 3D Axes: Red (X), Green (Y), Blue (Z)
        val ax = projectPoint(Point3D(0f, 0f, 0f), camera, viewMode, cx, cy)
        val axX = projectPoint(Point3D(250f, 0f, 0f), camera, viewMode, cx, cy)
        val axY = projectPoint(Point3D(0f, 250f, 0f), camera, viewMode, cx, cy)
        val axZ = projectPoint(Point3D(0f, 0f, 250f), camera, viewMode, cx, cy)

        val o = androidx.compose.ui.geometry.Offset(ax.x, ax.y)
        // Red - X axis
        drawScope.drawLine(
            color = Color(0xFFFF5252),
            start = o,
            end = androidx.compose.ui.geometry.Offset(axX.x, axX.y),
            strokeWidth = 3f
        )
        // Green - Y axis
        drawScope.drawLine(
            color = Color(0xFF66BB6A),
            start = o,
            end = androidx.compose.ui.geometry.Offset(axY.x, axY.y),
            strokeWidth = 3f
        )
        // Blue - Z axis
        drawScope.drawLine(
            color = Color(0xFF42A5F5),
            start = o,
            end = androidx.compose.ui.geometry.Offset(axZ.x, axZ.y),
            strokeWidth = 3f
        )
    }

    /**
     * Process list of entities, sort their polygon faces via Painter's Algorithm,
     * and render them on a canvas with solid flat lighting.
     */
    fun renderEntities(
        drawScope: DrawScope,
        entities: List<CadEntity>,
        selectedEntityId: String?,
        camera: ViewportState,
        viewMode: ViewportMode,
        isBlueprint: Boolean = false
    ) {
        val cx = drawScope.size.width / 2f
        val cy = drawScope.size.height / 2f

        val renderFaces = mutableListOf<RenderFace>()

        // 1. Generate geometry for all active entities
        entities.filter { it.isVisible }.forEach { entity ->
            val (vertices, faces) = generateEntityGeometry(entity)
            val isSelected = entity.id == selectedEntityId

            val baseColorComp = try {
                Color(android.graphics.Color.parseColor(entity.colorHex))
            } catch (e: Exception) {
                Color(0xFF2196F3)
            }
            val baseColor = if (isBlueprint) baseColorComp.copy(alpha = 0.15f) else baseColorComp

            // Convert world vertices into Camera space coordinates to calculate core depth sorting Z
            val yawRad = Math.toRadians(camera.yaw.toDouble()).toFloat()
            val pitchRad = Math.toRadians(camera.pitch.toDouble()).toFloat()

            val cameraSpaceVerts = vertices.map { wt ->
                var cPt = wt
                if (viewMode == ViewportMode.PERSPECTIVE || viewMode == ViewportMode.ISOMETRIC) {
                    cPt = MathUtils.rotateY(cPt, yawRad)
                    cPt = MathUtils.rotateX(cPt, pitchRad)
                }
                cPt
            }

            faces.forEach { faceIndices ->
                if (faceIndices.size >= 2) {
                    // Compute average camera-space space depth Z
                    var sumZ = 0f
                    faceIndices.forEach { sumZ += cameraSpaceVerts[it].z }
                    val avgZ = sumZ / faceIndices.size

                    // Compute normal of face in world space for correct lighting shading.
                    // (Ensure at least 3 vertices exist)
                    var lightingFactor = 1.0f
                    if (!isBlueprint && faceIndices.size >= 3) {
                        val v0 = vertices[faceIndices[0]]
                        val v1 = vertices[faceIndices[1]]
                        val v2 = vertices[faceIndices[2]]

                        val edge1 = v1 - v0
                        val edge2 = v2 - v0
                        val normal = edge1.cross(edge2).normalize()

                        // Simple diffuse dot shader
                        val dot = normal.dot(LIGHT_DIR)
                        // Take absolute value of back-side or front-side normal to give a bright visible shader either way
                        lightingFactor = 0.4f + 0.6f * abs(dot)
                    }

                    renderFaces.add(
                        RenderFace(
                            entityId = entity.id,
                            entityName = entity.name,
                            faceIndices = faceIndices,
                            worldVertices = vertices,
                            baseColor = baseColor,
                            avgZ = avgZ,
                            lightingFactor = lightingFactor,
                            isSelected = isSelected
                        )
                    )
                }
            }
        }

        // 2. Sort all faces by Z depth key (Painter's Algorithm)
        val sortedFaces = renderFaces.sortedByDescending { it.avgZ }

        // 3. Render each face onto screen
        sortedFaces.forEach { rf ->
            // Construct Compose Path
            val path = Path()
            var isFirst = true

            rf.faceIndices.forEach { idx ->
                val wp = rf.worldVertices[idx]
                val sp = projectPoint(wp, camera, viewMode, cx, cy)

                if (isFirst) {
                    path.moveTo(sp.x, sp.y)
                    isFirst = false
                } else {
                    path.lineTo(sp.x, sp.y)
                }
            }
            path.close()

            // Draw solid face coloring with light shading
            val lightedColor = rf.baseColor.copy(
                red = rf.baseColor.red * rf.lightingFactor,
                green = rf.baseColor.green * rf.lightingFactor,
                blue = rf.baseColor.blue * rf.lightingFactor
            )

            // Draw filled shape flat polygon
            drawScope.drawPath(
                path = path,
                color = lightedColor
            )

            // Draw line wireframe on top
            val edgeColor = if (rf.isSelected) {
                Color(0xFFFFEB3B) // Yellow highlights for active selection
            } else if (isBlueprint) {
                rf.baseColor.copy(alpha = 1.0f) // Sharp glowing border in 2D
            } else {
                lightedColor.copy(
                    red = max(0f, lightedColor.red - 0.25f),
                    green = max(0f, lightedColor.green - 0.25f),
                    blue = max(0f, lightedColor.blue - 0.25f)
                )
            }

            drawScope.drawPath(
                path = path,
                color = edgeColor,
                style = Stroke(width = if (rf.isSelected) 4f else (if (isBlueprint) 2.5f else 1.5f))
            )
        }
    }

    /**
     * Finds the index of the entity closest to the tapped screen coordinates in the given viewport.
     */
    fun hitTestEntity(
        tapX: Float,
        tapY: Float,
        entities: List<CadEntity>,
        camera: ViewportState,
        viewMode: ViewportMode,
        screenWidth: Float,
        screenHeight: Float
    ): CadEntity? {
        val cx = screenWidth / 2f
        val cy = screenHeight / 2f

        var bestMatch: CadEntity? = null
        var minDistance = 60f // Max tap sensitivity radius in dp/pixels

        entities.filter { it.isVisible && !it.isLocked }.forEach { entity ->
            // Project the object's core center coordinate
            val center = Point3D(entity.x, entity.y, entity.z)
            val sp = projectPoint(center, camera, viewMode, cx, cy)

            val dx = sp.x - tapX
            val dy = sp.y - tapY
            val dist = kotlin.math.sqrt(dx * dx + dy * dy)

            if (dist < minDistance) {
                minDistance = dist
                bestMatch = entity
            }
        }

        return bestMatch
    }
}
