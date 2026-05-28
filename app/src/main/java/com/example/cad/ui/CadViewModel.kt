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
        name: String? = null
    ) {
        val selectedId = _selectedEntityId.value ?: return
        saveHistory()

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
