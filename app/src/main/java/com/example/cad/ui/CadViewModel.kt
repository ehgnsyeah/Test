package com.example.cad.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.cad.data.CadDatabase
import com.example.cad.data.ProjectEntity
import com.example.cad.data.ProjectRepository
import com.example.cad.math.Point2D
import com.example.cad.math.Point3D
import com.example.cad.math.MathUtils
import com.example.cad.model.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class CadViewModel(
    application: Application,
    private val repository: ProjectRepository
) : AndroidViewModel(application) {

    // Primary project scene state
    private val _entities = MutableStateFlow<List<CadEntity>>(emptyList())
    val entities: StateFlow<List<CadEntity>> = _entities.asStateFlow()

    private val _selectedEntityId = MutableStateFlow<String?>(null)
    val selectedEntityId: StateFlow<String?> = _selectedEntityId.asStateFlow()

    // Viewport camera parameters
    private val _cameraState = MutableStateFlow(ViewportState())
    val cameraState: StateFlow<ViewportState> = _cameraState.asStateFlow()

    private val _viewMode = MutableStateFlow(ViewportMode.PERSPECTIVE)
    val viewMode: StateFlow<ViewportMode> = _viewMode.asStateFlow()

    // Layers configuration
    private val _layers = MutableStateFlow(CadDefaults.DefaultLayers)
    val layers: StateFlow<List<CadLayer>> = _layers.asStateFlow()

    // Grid and snap details
    private val _gridSize = MutableStateFlow(100f)
    val gridSize: StateFlow<Float> = _gridSize.asStateFlow()

    private val _snapToGrid = MutableStateFlow(true)
    val snapToGrid: StateFlow<Boolean> = _snapToGrid.asStateFlow()

    private val _showGrid = MutableStateFlow(true)
    val showGrid: StateFlow<Boolean> = _showGrid.asStateFlow()

    private val _gridPlane = MutableStateFlow("XY")
    val gridPlane: StateFlow<String> = _gridPlane.asStateFlow()

    // Slicing and splitting state variables
    private val _slicePlanePos = MutableStateFlow(0f)
    val slicePlanePos: StateFlow<Float> = _slicePlanePos.asStateFlow()

    private val _slicePlaneNormal = MutableStateFlow("Z") // Default normal along Z-axis
    val slicePlaneNormal: StateFlow<String> = _slicePlaneNormal.asStateFlow()

    private val _showSplittingPreview = MutableStateFlow(false)
    val showSplittingPreview: StateFlow<Boolean> = _showSplittingPreview.asStateFlow()

    // Undo/Redo stack history lists
    private val undoStack = mutableListOf<List<CadEntity>>()
    private val redoStack = mutableListOf<List<CadEntity>>()

    // Database Projects List
    val savedProjects: StateFlow<List<ProjectEntity>> = repository.allProjects
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Currently active project details
    private val _activeProjectId = MutableStateFlow(0) // 0 implies unsaved new draft
    val activeProjectId: StateFlow<Int> = _activeProjectId.asStateFlow()

    private val _activeProjectName = MutableStateFlow("새 도면 (Unsaved Project)")
    val activeProjectName: StateFlow<String> = _activeProjectName.asStateFlow()

    // Interactive operations alerts
    private val _operationStatus = MutableStateFlow("")
    val operationStatus: StateFlow<String> = _operationStatus.asStateFlow()

    // Measurement tool points (up to 2 selection objects for calculating 3D distance)
    private val _measuredDistance = MutableStateFlow<Float?>(null)
    val measuredDistance: StateFlow<Float?> = _measuredDistance.asStateFlow()

    init {
        // Load default starting shapes (a Box and Cylinder to demonstrate 3D modeling on first use)
        loadDefaultSampleScene()
    }

    private fun loadDefaultSampleScene() {
        val sampleEntities = listOf(
            CadEntity(
                name = "주초 기둥 (Base Pillar)",
                type = EntityType.BOX,
                x = -150f, y = 0f, z = 0f,
                width = 80f, height = 150f, depth = 80f,
                colorHex = "#FF4CAF50",
                layerId = "1"
            ),
            CadEntity(
                name = "상부 축 (Shaft Mechanism)",
                type = EntityType.CYLINDER,
                x = 150f, y = 0f, z = 0f,
                radius = 60f, height = 180f,
                colorHex = "#FF2196F3",
                layerId = "0"
            ),
            CadEntity(
                name = "조인트 너트 (Joint Cap)",
                type = EntityType.SPHERE,
                x = 150f, y = 90f, z = 0f,
                radius = 70f,
                colorHex = "#FF9C27B0",
                layerId = "3"
            )
        )
        _entities.value = sampleEntities
        updateDistanceMeasure()
    }

    /**
     * Record current state to undo history before making modifications.
     */
    private fun saveHistory() {
        undoStack.add(_entities.value.toList())
        redoStack.clear() // Clear redo history whenever a new action occurs
    }

    fun undo() {
        if (undoStack.isNotEmpty()) {
            redoStack.add(_entities.value.toList())
            _entities.value = undoStack.removeAt(undoStack.size - 1)
            _selectedEntityId.value = null
            updateDistanceMeasure()
            showStatus("되돌리기(Undo) 완료")
        } else {
            showStatus("되돌릴 작업이 없습니다.")
        }
    }

    fun redo() {
        if (redoStack.isNotEmpty()) {
            undoStack.add(_entities.value.toList())
            _entities.value = redoStack.removeAt(redoStack.size - 1)
            _selectedEntityId.value = null
            updateDistanceMeasure()
            showStatus("다시 실행(Redo) 완료")
        } else {
            showStatus("다시 실행할 작업이 없습니다.")
        }
    }

    /**
     * Add a default primitive shape to the CAD scene.
     */
    fun addEntity(type: EntityType, extrusionProfilePreset: List<Point2D> = emptyList()) {
        saveHistory()

        val layerId = "0" // Default Layer
        val size = _entities.value.size + 1

        val newEntity = when (type) {
            EntityType.BOX -> CadEntity(
                name = "상자 블록 (Box) #$size",
                type = type,
                x = 0f, y = 0f, z = 0f,
                width = 100f, height = 100f, depth = 100f,
                colorHex = "#FFFFC107",
                layerId = layerId
            )
            EntityType.CYLINDER -> CadEntity(
                name = "원기둥 기둥 (Cylinder) #$size",
                type = type,
                x = 0f, y = 0f, z = 0f,
                radius = 50f, height = 120f,
                colorHex = "#FF00BCD4",
                layerId = layerId
            )
            EntityType.SPHERE -> CadEntity(
                name = "구체 결합부 (Sphere) #$size",
                type = type,
                x = 0f, y = 0f, z = 0f,
                radius = 60f,
                colorHex = "#FF9C27B0",
                layerId = layerId
            )
            EntityType.CONE -> CadEntity(
                name = "원뿔 조인트 (Cone) #$size",
                type = type,
                x = 0f, y = 0f, z = 0f,
                radius = 55f, height = 110f,
                colorHex = "#FF3F51B5",
                layerId = layerId
            )
            EntityType.POLYLINE -> CadEntity(
                name = "파이프 배선 (Polyline) #$size",
                type = type,
                x = 0f, y = 0f, z = 0f,
                polylinePoints = listOf(
                    Point3D(-100f, -100f, 0f),
                    Point3D(0f, -100f, 50f),
                    Point3D(0f, 100f, 50f),
                    Point3D(100f, 100f, 100f)
                ),
                colorHex = "#FF009688",
                layerId = layerId
            )
            EntityType.EXTRUSION -> CadEntity(
                name = "압출 프로파일 (Extrusion) #$size",
                type = type,
                x = 0f, y = 0f, z = 0f,
                height = 100f,
                extrusionProfile = if (extrusionProfilePreset.isNotEmpty()) extrusionProfilePreset else CadDefaults.ProfileHexagon,
                colorHex = "#FFE91E63",
                layerId = layerId
            )
            EntityType.COMBINED -> CadEntity(
                name = "결합된 모델 (Combined) #$size",
                type = type,
                x = 0f, y = 0f, z = 0f,
                colorHex = "#FF9E9E9E",
                layerId = layerId
            )
        }

        _entities.value = _entities.value + newEntity
        _selectedEntityId.value = newEntity.id
        updateDistanceMeasure()
        showStatus("${newEntity.name} 추가됨")
    }

    /**
     * Deletes the currently selected geometry.
     */
    fun deleteSelected() {
        val id = _selectedEntityId.value ?: return
        val item = _entities.value.find { it.id == id } ?: return
        saveHistory()

        _entities.value = _entities.value.filter { it.id != id }
        _selectedEntityId.value = null
        updateDistanceMeasure()
        showStatus("${item.name} 삭제됨")
    }

    /**
     * Duplicate selected element.
     */
    fun duplicateSelected() {
        val id = _selectedEntityId.value ?: return
        val current = _entities.value.find { it.id == id } ?: return
        saveHistory()

        val offset = if (_snapToGrid.value) _gridSize.value else 50f
        val duplicate = current.copy(
            id = java.util.UUID.randomUUID().toString(),
            name = "${current.name} (복사본)",
            x = current.x + offset,
            y = current.y + offset,
            z = current.z
        )

        _entities.value = _entities.value + duplicate
        _selectedEntityId.value = duplicate.id
        updateDistanceMeasure()
        showStatus("요소 복제됨: ${duplicate.name}")
    }

    /**
     * Triggered by tactile touch coordinates selection.
     */
    fun selectEntity(id: String?) {
        _selectedEntityId.value = id
        updateDistanceMeasure()
    }

    fun recordHistoryState() {
        saveHistory()
    }

    /**
     * Perform precision translations, rotations, size changes, colors.
     */
    fun updateSelectedProperties(
        x: Float? = null,
        y: Float? = null,
        z: Float? = null,
        rx: Float? = null,
        ry: Float? = null,
        rz: Float? = null,
        w: Float? = null,
        h: Float? = null,
        d: Float? = null,
        r: Float? = null,
        color: String? = null,
        layerId: String? = null,
        name: String? = null,
        saveToHistory: Boolean = true
    ) {
        val selectedId = _selectedEntityId.value ?: return
        if (saveToHistory) saveHistory()

        _entities.value = _entities.value.map { entity ->
            if (entity.id == selectedId) {
                // Apply grid snap to coordinate values if active
                val targetX = if (x != null) {
                    if (_snapToGrid.value) snapValue(x, _gridSize.value) else x
                } else entity.x

                val targetY = if (y != null) {
                    if (_snapToGrid.value) snapValue(y, _gridSize.value) else y
                } else entity.y

                val targetZ = if (z != null) {
                    if (_snapToGrid.value) snapValue(z, _gridSize.value) else z
                } else entity.z

                entity.copy(
                    x = targetX,
                    y = targetY,
                    z = targetZ,
                    rx = rx ?: entity.rx,
                    ry = ry ?: entity.ry,
                    rz = rz ?: entity.rz,
                    width = w ?: entity.width,
                    height = h ?: entity.height,
                    depth = d ?: entity.depth,
                    radius = r ?: entity.radius,
                    colorHex = color ?: entity.colorHex,
                    layerId = layerId ?: entity.layerId,
                    name = name ?: entity.name
                )
            } else {
                entity
            }
        }
        updateDistanceMeasure()
    }

    private fun snapValue(value: Float, size: Float): Float {
        return kotlin.math.round(value / size) * size
    }

    fun setGridSize(size: Float) {
        _gridSize.value = size
    }

    fun toggleSnapToGrid() {
        _snapToGrid.value = !_snapToGrid.value
    }

    fun toggleShowGrid() {
        _showGrid.value = !_showGrid.value
    }

    fun setGridPlane(plane: String) {
        if (plane in listOf("XY", "XZ", "YZ")) {
            _gridPlane.value = plane
        }
    }

    /**
     * Zoom In viewport camera.
     */
    fun zoomIn() {
        _cameraState.value = _cameraState.value.copy(zoom = _cameraState.value.zoom * 1.2f)
    }

    /**
     * Zoom Out viewport camera.
     */
    fun zoomOut() {
        _cameraState.value = _cameraState.value.copy(zoom = _cameraState.value.zoom / 1.2f)
    }

    /**
     * Fast Orbit controls.
     */
    fun rotateYaw(delta: Float) {
        _cameraState.value = _cameraState.value.copy(
            yaw = (_cameraState.value.yaw + delta) % 360f
        )
    }

    fun rotatePitch(delta: Float) {
        val newPitch = _cameraState.value.pitch + delta
        _cameraState.value = _cameraState.value.copy(
            pitch = newPitch.coerceIn(-89f, 89f) // Avoid gimbal flip lock
        )
    }

    fun panViewport(dx: Float, dy: Float) {
        _cameraState.value = _cameraState.value.copy(
            panX = _cameraState.value.panX + dx,
            panY = _cameraState.value.panY + dy
        )
    }

    fun resetCamera() {
        _cameraState.value = ViewportState()
        showStatus("뷰포트 시점 초기화됨")
    }

    fun changeViewMode(mode: ViewportMode) {
        _viewMode.value = mode
        when (mode) {
            ViewportMode.TOP -> _cameraState.value = ViewportState(yaw = 0f, pitch = 0f, zoom = _cameraState.value.zoom)
            ViewportMode.FRONT -> _cameraState.value = ViewportState(yaw = 0f, pitch = -90f, zoom = _cameraState.value.zoom)
            ViewportMode.RIGHT -> _cameraState.value = ViewportState(yaw = 90f, pitch = -90f, zoom = _cameraState.value.zoom)
            ViewportMode.ISOMETRIC -> _cameraState.value = ViewportState(yaw = 45f, pitch = -30f, zoom = _cameraState.value.zoom)
            ViewportMode.PERSPECTIVE -> _cameraState.value = ViewportState(yaw = 45f, pitch = -30f, zoom = _cameraState.value.zoom)
        }
        showStatus("뷰 변경: ${mode.name}")
    }

    /**
     * Layers functions: toggle visibility or locks.
     */
    fun toggleLayerVisibility(id: String) {
        _layers.value = _layers.value.map { layer ->
            if (layer.id == id) {
                val nextVis = !layer.isVisible
                // Apply update to entities
                _entities.value = _entities.value.map { ent ->
                    if (ent.layerId == id) ent.copy(isVisible = nextVis) else ent
                }
                layer.copy(isVisible = nextVis)
            } else layer
        }
        showStatus("레이어 가시성 변경")
    }

    fun toggleLayerLock(id: String) {
        _layers.value = _layers.value.map { layer ->
            if (layer.id == id) {
                val nextLock = !layer.isLocked
                _entities.value = _entities.value.map { ent ->
                    if (ent.layerId == id) ent.copy(isLocked = nextLock) else ent
                }
                layer.copy(isLocked = nextLock)
            } else layer
        }
        showStatus("레이어 정밀 잠금 상태 변경")
    }

    /**
     * Compute strict 3D distance measurements between selection items.
     * If there are at least two items, displays the 3D distance between their pivots.
     */
    private fun updateDistanceMeasure() {
        val selected = _entities.value.find { it.id == _selectedEntityId.value }
        if (selected == null) {
            _measuredDistance.value = null
            return
        }

        // Find the second closest item in terms of index, or closest item to selected
        val otherItem = _entities.value.firstOrNull { it.id != selected.id }
        if (otherItem != null) {
            val dX = selected.x - otherItem.x
            val dY = selected.y - otherItem.y
            val dZ = selected.z - otherItem.z
            _measuredDistance.value = kotlin.math.sqrt(dX * dX + dY * dY + dZ * dZ)
        } else {
            _measuredDistance.value = null
        }
    }

    /**
     * Saves CAD drawing session to Database.
     */
    fun saveProjectDraft(name: String) {
        viewModelScope.launch {
            try {
                val currentId = _activeProjectId.value
                val newId = repository.saveProject(currentId, name, _entities.value)
                _activeProjectId.value = newId.toInt()
                _activeProjectName.value = name
                showStatus("도면이 로컬 저장소에 정상 저장되었습니다.")
            } catch (e: Exception) {
                showStatus("저장 실패: ${e.message}")
            }
        }
    }

    /**
     * Loads saved project from Database.
     */
    fun loadProjectDraft(id: Int) {
        viewModelScope.launch {
            try {
                val project = repository.getProjectById(id)
                if (project != null) {
                    _activeProjectId.value = project.id
                    _activeProjectName.value = project.name
                    // Parse json using Moshi converter
                    val entitiesList = com.example.cad.data.Converters().fromJson(project.sceneDataJson) ?: emptyList()
                    _entities.value = entitiesList
                    _selectedEntityId.value = null
                    updateDistanceMeasure()
                    showStatus("도면 로드됨: ${project.name}")
                }
            } catch (e: Exception) {
                showStatus("도면 로드에 실패하였습니다.")
            }
        }
    }

    fun startNewDraft() {
        _activeProjectId.value = 0
        _activeProjectName.value = "새 도면 (Unsaved Project)"
        _entities.value = emptyList()
        _selectedEntityId.value = null
        _measuredDistance.value = null
        undoStack.clear()
        redoStack.clear()
        showStatus("새로운 도면 작성이 시작되었습니다.")
    }

    fun deleteProjectFromDb(id: Int) {
        viewModelScope.launch {
            try {
                repository.deleteProject(id)
                if (_activeProjectId.value == id) {
                    startNewDraft()
                }
                showStatus("도면이 삭제되었습니다.")
            } catch (e: Exception) {
                showStatus("도면 삭제 실패")
            }
        }
    }

    fun updateVertexOffset(vertexIndex: Int, xOffset: Float, yOffset: Float, zOffset: Float, saveToHistory: Boolean = true) {
        val selectedId = _selectedEntityId.value ?: return
        if (saveToHistory) saveHistory()

        _entities.value = _entities.value.map { entity ->
            if (entity.id == selectedId) {
                val (vertices, _) = generateEntityGeometry(entity.copy(vertexOffsets = emptyList()))
                val vertexCount = vertices.size
                val currentOffsets = (entity.vertexOffsets ?: emptyList()).toMutableList()
                while (currentOffsets.size < vertexCount) {
                    currentOffsets.add(Point3D(0f, 0f, 0f))
                }
                if (vertexIndex in 0 until vertexCount) {
                    currentOffsets[vertexIndex] = Point3D(xOffset, yOffset, zOffset)
                }
                entity.copy(vertexOffsets = currentOffsets)
            } else {
                entity
            }
        }
        updateDistanceMeasure()
    }

    fun updatePolylinePoint(pointIndex: Int, x: Float, y: Float, z: Float, saveToHistory: Boolean = true) {
        val selectedId = _selectedEntityId.value ?: return
        if (saveToHistory) saveHistory()

        _entities.value = _entities.value.map { entity ->
            if (entity.id == selectedId && entity.type == EntityType.POLYLINE) {
                val currentPoints = (entity.polylinePoints ?: emptyList()).toMutableList()
                if (pointIndex in 0 until currentPoints.size) {
                    currentPoints[pointIndex] = Point3D(x, y, z)
                }
                entity.copy(polylinePoints = currentPoints)
            } else {
                entity
            }
        }
        updateDistanceMeasure()
    }

    fun updateExtrusionProfilePoint(pointIndex: Int, x: Float, y: Float, saveToHistory: Boolean = true) {
        val selectedId = _selectedEntityId.value ?: return
        if (saveToHistory) saveHistory()

        _entities.value = _entities.value.map { entity ->
            if (entity.id == selectedId && entity.type == EntityType.EXTRUSION) {
                val currentProfile = (entity.extrusionProfile ?: emptyList()).toMutableList()
                if (pointIndex in 0 until currentProfile.size) {
                    currentProfile[pointIndex] = Point2D(x, y)
                }
                entity.copy(extrusionProfile = currentProfile)
            } else {
                entity
            }
        }
        updateDistanceMeasure()
    }

    fun setSlicePlanePos(pos: Float) {
        _slicePlanePos.value = pos
    }

    fun setSlicePlaneNormal(normal: String) {
        if (normal in listOf("X", "Y", "Z")) {
            _slicePlaneNormal.value = normal
        }
    }

    fun toggleSplittingPreview() {
        _showSplittingPreview.value = !_showSplittingPreview.value
    }

    /**
     * Merge/Connect two entities (either Solid or Polylines).
     */
    fun mergeEntities(idA: String, idB: String) {
        saveHistory()
        val list = _entities.value
        val entityA = list.find { it.id == idA } ?: return
        val entityB = list.find { it.id == idB } ?: return

        // 1. If both are Polylines, merge end-to-end (connecting lines)
        val mergedEntity = if (entityA.type == EntityType.POLYLINE && entityB.type == EntityType.POLYLINE) {
            val ptsA = entityA.polylinePoints ?: emptyList()
            val ptsB = entityB.polylinePoints ?: emptyList()
            
            // Transform A points to world space
            val worldA = ptsA.map { localPt ->
                var pt = MathUtils.rotateX(localPt, Math.toRadians(entityA.rx.toDouble()).toFloat())
                pt = MathUtils.rotateY(pt, Math.toRadians(entityA.ry.toDouble()).toFloat())
                pt = MathUtils.rotateZ(pt, Math.toRadians(entityA.rz.toDouble()).toFloat())
                Point3D(pt.x + entityA.x, pt.y + entityA.y, pt.z + entityA.z)
            }
            // Transform B points to world space
            val worldB = ptsB.map { localPt ->
                var pt = MathUtils.rotateX(localPt, Math.toRadians(entityB.rx.toDouble()).toFloat())
                pt = MathUtils.rotateY(pt, Math.toRadians(entityB.ry.toDouble()).toFloat())
                pt = MathUtils.rotateZ(pt, Math.toRadians(entityB.rz.toDouble()).toFloat())
                Point3D(pt.x + entityB.x, pt.y + entityB.y, pt.z + entityB.z)
            }
            
            val combinedWorld = worldA + worldB
            // Centroid
            var sx = 0f; var sy = 0f; var sz = 0f
            combinedWorld.forEach { sx += it.x; sy += it.y; sz += it.z }
            val cx = sx / combinedWorld.size
            val cy = sy / combinedWorld.size
            val cz = sz / combinedWorld.size
            
            val localPts = combinedWorld.map { Point3D(it.x - cx, it.y - cy, it.z - cz) }
            
            CadEntity(
                name = "${entityA.name} + ${entityB.name} (연결됨)",
                type = EntityType.POLYLINE,
                x = cx, y = cy, z = cz,
                polylinePoints = localPts,
                colorHex = entityA.colorHex,
                layerId = entityA.layerId
            )
        } else {
            // 2. Solid mesh merge
            val (vA, fA) = generateEntityGeometry(entityA)
            val (vB, fB) = generateEntityGeometry(entityB)
            
            val combinedV = vA + vB
            val offset = vA.size
            val combinedF = fA.toMutableList()
            fB.forEach { face ->
                combinedF.add(face.map { it + offset })
            }
            
            // Find centroid
            var sx = 0f; var sy = 0f; var sz = 0f
            combinedV.forEach { sx += it.x; sy += it.y; sz += it.z }
            val cx = sx / combinedV.size
            val cy = sy / combinedV.size
            val cz = sz / combinedV.size
            
            // Express vertices relative to combined centroid
            val localV = combinedV.map { Point3D(it.x - cx, it.y - cy, it.z - cz) }
            
            CadEntity(
                name = "${entityA.name} + ${entityB.name} (결합됨)",
                type = EntityType.COMBINED,
                x = cx, y = cy, z = cz,
                mergedVertices = localV,
                mergedFaces = combinedF,
                colorHex = entityA.colorHex,
                layerId = entityA.layerId
            )
        }

        // Replace both in entity list
        _entities.value = list.filter { it.id != idA && it.id != idB } + mergedEntity
        _selectedEntityId.value = mergedEntity.id
        updateDistanceMeasure()
        showStatus("도형 결합 완료: ${mergedEntity.name}")
    }

    /**
     * Slice/Split a solid CAD entity with a mathematical plane.
     */
    fun splitEntityWithPlane(id: String) {
        val list = _entities.value
        val entity = list.find { it.id == id } ?: return
        
        saveHistory()
        
        // Slicing plane relative to parent pivot
        val nType = _slicePlaneNormal.value
        val offsetVal = _slicePlanePos.value
        
        val nx: Float
        val ny: Float
        val nz: Float
        
        when (nType) {
            "X" -> { nx = 1f; ny = 0f; nz = 0f }
            "Y" -> { nx = 0f; ny = 1f; nz = 0f }
            else -> { nx = 0f; ny = 0f; nz = 1f }
        }
        
        // Plane passing point in world coordinates
        val px = entity.x + nx * offsetVal
        val py = entity.y + ny * offsetVal
        val pz = entity.z + nz * offsetVal
        
        val d = -(nx * px + ny * py + nz * pz)
        
        fun signedDist(v: Point3D): Float {
            return nx * v.x + ny * v.y + nz * v.z + d
        }
        
        val (worldVertices, faces) = generateEntityGeometry(entity)
        if (worldVertices.isEmpty()) {
            showStatus("분할 실패: 기하 정보가 없습니다.")
            return
        }
        
        val posVertices = mutableListOf<Point3D>()
        val posFaces = mutableListOf<List<Int>>()
        val negVertices = mutableListOf<Point3D>()
        val negFaces = mutableListOf<List<Int>>()
        
        val intersectionPoints = mutableSetOf<Point3D>()
        
        faces.forEach { face ->
            val polyPos = mutableListOf<Point3D>()
            val polyNeg = mutableListOf<Point3D>()
            val n = face.size
            
            for (i in 0 until n) {
                val idxCurr = face[i]
                val idxNext = face[(i + 1) % n]
                val vCurr = worldVertices[idxCurr]
                val vNext = worldVertices[idxNext]
                
                val dCurr = signedDist(vCurr)
                val dNext = signedDist(vNext)
                
                if (dCurr >= -0.05f) {
                    polyPos.add(vCurr)
                }
                if (dCurr <= 0.05f) {
                    polyNeg.add(vCurr)
                }
                
                if (dCurr * dNext < -0.001f) {
                    val t = -dCurr / (dNext - dCurr)
                    val pInter = Point3D(
                        vCurr.x + t * (vNext.x - vCurr.x),
                        vCurr.y + t * (vNext.y - vCurr.y),
                        vCurr.z + t * (vNext.z - vCurr.z)
                    )
                    polyPos.add(pInter)
                    polyNeg.add(pInter)
                    intersectionPoints.add(pInter)
                }
            }
            
            if (polyPos.size >= 3) {
                val faceIndices = mutableListOf<Int>()
                polyPos.forEach { pt ->
                    var idx = posVertices.indexOfFirst { kotlin.math.abs(it.x - pt.x) < 0.05f && kotlin.math.abs(it.y - pt.y) < 0.05f && kotlin.math.abs(it.z - pt.z) < 0.05f }
                    if (idx == -1) {
                        posVertices.add(pt)
                        idx = posVertices.size - 1
                    }
                    faceIndices.add(idx)
                }
                posFaces.add(faceIndices)
            }
            if (polyNeg.size >= 3) {
                val faceIndices = mutableListOf<Int>()
                polyNeg.forEach { pt ->
                    var idx = negVertices.indexOfFirst { kotlin.math.abs(it.x - pt.x) < 0.05f && kotlin.math.abs(it.y - pt.y) < 0.05f && kotlin.math.abs(it.z - pt.z) < 0.05f }
                    if (idx == -1) {
                        negVertices.add(pt)
                        idx = negVertices.size - 1
                    }
                    faceIndices.add(idx)
                }
                negFaces.add(faceIndices)
            }
        }
        
        // Add CAP to split surfaces to keep splitting watertight/solid
        if (intersectionPoints.size >= 3) {
            var sumX = 0f; var sumY = 0f; var sumZ = 0f
            intersectionPoints.forEach { sumX += it.x; sumY += it.y; sumZ += it.z }
            val centroid = Point3D(sumX / intersectionPoints.size, sumY / intersectionPoints.size, sumZ / intersectionPoints.size)
            
            val ux: Float; val uy: Float; val uz: Float
            if (kotlin.math.abs(nz) < 0.9f) {
                val len = kotlin.math.sqrt((ny*ny + nx*nx).toDouble()).toFloat()
                ux = ny / len; uy = -nx / len; uz = 0f
            } else {
                ux = 1f; uy = 0f; uz = 0f
            }
            
            val vx = ny * uz - nz * uy
            val vy = nz * ux - nx * uz
            val vz = nx * uy - ny * ux
            
            val sortedList = intersectionPoints.toList().sortedBy { pt ->
                val dx = pt.x - centroid.x
                val dy = pt.y - centroid.y
                val dz = pt.z - centroid.z
                val u = dx * ux + dy * uy + dz * uz
                val v = dx * vx + dy * vy + dz * vz
                kotlin.math.atan2(v.toDouble(), u.toDouble()).toFloat()
            }
            
            val capFacePos = mutableListOf<Int>()
            sortedList.forEach { pt ->
                var idx = posVertices.indexOfFirst { kotlin.math.abs(it.x - pt.x) < 0.05f && kotlin.math.abs(it.y - pt.y) < 0.05f && kotlin.math.abs(it.z - pt.z) < 0.05f }
                if (idx == -1) {
                    posVertices.add(pt)
                    idx = posVertices.size - 1
                }
                capFacePos.add(idx)
            }
            if (capFacePos.size >= 3) {
                posFaces.add(capFacePos)
            }
            
            val capFaceNeg = mutableListOf<Int>()
            sortedList.reversed().forEach { pt ->
                var idx = negVertices.indexOfFirst { kotlin.math.abs(it.x - pt.x) < 0.05f && kotlin.math.abs(it.y - pt.y) < 0.05f && kotlin.math.abs(it.z - pt.z) < 0.05f }
                if (idx == -1) {
                    negVertices.add(pt)
                    idx = negVertices.size - 1
                }
                capFaceNeg.add(idx)
            }
            if (capFaceNeg.size >= 3) {
                negFaces.add(capFaceNeg)
            }
        }
        
        val parts = mutableListOf<CadEntity>()
        
        if (posVertices.isNotEmpty() && posFaces.isNotEmpty()) {
            var sx = 0f; var sy = 0f; var sz = 0f
            posVertices.forEach { sx += it.x; sy += it.y; sz += it.z }
            val cx = sx / posVertices.size
            val cy = sy / posVertices.size
            val cz = sz / posVertices.size
            val localPos = posVertices.map { Point3D(it.x - cx, it.y - cy, it.z - cz) }
            
            parts.add(
                CadEntity(
                    name = "${entity.name} (분할_A)",
                    type = EntityType.COMBINED,
                    x = cx, y = cy, z = cz,
                    mergedVertices = localPos,
                    mergedFaces = posFaces,
                    colorHex = entity.colorHex,
                    layerId = entity.layerId
                )
            )
        }
        
        if (negVertices.isNotEmpty() && negFaces.isNotEmpty()) {
            var sx = 0f; var sy = 0f; var sz = 0f
            negVertices.forEach { sx += it.x; sy += it.y; sz += it.z }
            val cx = sx / negVertices.size
            val cy = sy / negVertices.size
            val cz = sz / negVertices.size
            val localNeg = negVertices.map { Point3D(it.x - cx, it.y - cy, it.z - cz) }
            
            parts.add(
                CadEntity(
                    name = "${entity.name} (분할_B)",
                    type = EntityType.COMBINED,
                    x = cx, y = cy, z = cz,
                    mergedVertices = localNeg,
                    mergedFaces = negFaces,
                    colorHex = "#FF4CAF50",
                    layerId = entity.layerId
                )
            )
        }
        
        if (parts.size >= 2) {
            _entities.value = list.filter { it.id != id } + parts
            _selectedEntityId.value = parts.first().id
            updateDistanceMeasure()
            showStatus("도형 ${entity.name} 분할 완료. 2개의 분할된 독립 객체 생성!")
        } else {
            showStatus("분할 실패: 분할 평면이 도형을 완전히 교차하지 않습니다.")
        }
    }

    /**
     * Splitting a Polyline at any selected vertex index.
     */
    fun splitPolylineAtVertex(id: String, vertexIndex: Int) {
        val list = _entities.value
        val entity = list.find { it.id == id } ?: return
        if (entity.type != EntityType.POLYLINE) return
        val pts = entity.polylinePoints ?: return
        if (vertexIndex <= 0 || vertexIndex >= pts.size - 1) {
            showStatus("분할 불가능: 양 끝점에서는 분할할 수 없습니다.")
            return
        }
        
        saveHistory()
        
        val ptsA = pts.subList(0, vertexIndex + 1)
        val ptsB = pts.subList(vertexIndex, pts.size)
        
        var sx = 0f; var sy = 0f; var sz = 0f
        ptsA.forEach { sx += it.x; sy += it.y; sz += it.z }
        val cxA = entity.x + sx / ptsA.size
        val cyA = entity.y + sy / ptsA.size
        val czA = entity.z + sz / ptsA.size
        val localA = ptsA.map { Point3D(it.x - sx / ptsA.size, it.y - sy / ptsA.size, it.z - sz / ptsA.size) }
        
        sx = 0f; sy = 0f; sz = 0f
        ptsB.forEach { sx += it.x; sy += it.y; sz += it.z }
        val cxB = entity.x + sx / ptsB.size
        val cyB = entity.y + sy / ptsB.size
        val czB = entity.z + sz / ptsB.size
        val localB = ptsB.map { Point3D(it.x - sx / ptsB.size, it.y - sy / ptsB.size, it.z - sz / ptsB.size) }
        
        val partA = CadEntity(
            name = "${entity.name}_A",
            type = EntityType.POLYLINE,
            x = cxA, y = cyA, z = czA,
            polylinePoints = localA,
            colorHex = entity.colorHex,
            layerId = entity.layerId
        )
        
        val partB = CadEntity(
            name = "${entity.name}_B",
            type = EntityType.POLYLINE,
            x = cxB, y = cyB, z = czB,
            polylinePoints = localB,
            colorHex = "#FF4CAF50",
            layerId = entity.layerId
        )
        
        _entities.value = list.filter { it.id != id } + listOf(partA, partB)
        _selectedEntityId.value = partA.id
        updateDistanceMeasure()
        showStatus("배선 꼭짓점 분할 완료: ${entity.name}")
    }

    private fun showStatus(msg: String) {
        _operationStatus.value = msg
    }
}

class CadViewModelFactory(
    private val application: Application,
    private val repository: ProjectRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(CadViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return CadViewModel(application, repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
