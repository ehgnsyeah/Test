package com.example.cad.ui

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.cad.model.*
import com.example.cad.math.*
import kotlinx.coroutines.launch
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.text.TextStyle

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun CadScreen(
    viewModel: CadViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    // State Collection
    val entities by viewModel.entities.collectAsStateWithLifecycle()
    val selectedEntityId by viewModel.selectedEntityId.collectAsStateWithLifecycle()
    val cameraState by viewModel.cameraState.collectAsStateWithLifecycle()
    val viewMode by viewModel.viewMode.collectAsStateWithLifecycle()
    val layers by viewModel.layers.collectAsStateWithLifecycle()
    val gridSize by viewModel.gridSize.collectAsStateWithLifecycle()
    val snapToGrid by viewModel.snapToGrid.collectAsStateWithLifecycle()
    val activeProjectName by viewModel.activeProjectName.collectAsStateWithLifecycle()
    val savedProjects by viewModel.savedProjects.collectAsStateWithLifecycle()
    val operationStatus by viewModel.operationStatus.collectAsStateWithLifecycle()
    val measuredDistance by viewModel.measuredDistance.collectAsStateWithLifecycle()

    // UI Dialog / Sheet visibility toggles
    var showProjectDialog by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var showPresetProfilesDialog by remember { mutableStateOf(false) }
    var saveProjectNameInput by remember { mutableStateOf("") }
    var showLayersPanel by remember { mutableStateOf(false) }
    var showImagePanel by remember { mutableStateOf(false) }
    var showMeasurementPanel by remember { mutableStateOf(true) }
    var showGridPanel by remember { mutableStateOf(false) }

    // AutoCAD 2D/3D Workspace state
    var workspaceLayout by remember { mutableStateOf("SPLIT") } // "3D", "2D", "SPLIT"
    var current2DPlane by remember { mutableStateOf(ViewportMode.TOP) }
    var showDimEditDialog by remember { mutableStateOf(false) }
    var editingDim by remember { mutableStateOf<BlueprintDim?>(null) }
    var editValueInput by remember { mutableStateOf("") }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val defaultPlane = when (current2DPlane) {
                ViewportMode.FRONT -> "FRONT"
                ViewportMode.RIGHT -> "RIGHT"
                else -> "TOP"
            }
            viewModel.addReferenceImage("수동 추가 도면", uri.toString(), defaultPlane)
        }
    }

    // Direct Vertex coordinates editing states
    var activeVertexEditIndex by remember { mutableStateOf(-1) }
    var activeVertexEditEntityId by remember { mutableStateOf<String?>(null) }
    var vertexEditXInput by remember { mutableStateOf("") }
    var vertexEditYInput by remember { mutableStateOf("") }
    var vertexEditZInput by remember { mutableStateOf("") }

    val onVertexClick: (Int, CadEntity) -> Unit = { idx, entity ->
        activeVertexEditIndex = idx
        activeVertexEditEntityId = entity.id
        when (entity.type) {
            EntityType.POLYLINE -> {
                val pt = if (entity.polylinePoints != null && idx < entity.polylinePoints.size) entity.polylinePoints[idx] else Point3D(0f, 0f, 0f)
                vertexEditXInput = pt.x.toString()
                vertexEditYInput = pt.y.toString()
                vertexEditZInput = pt.z.toString()
            }
            EntityType.EXTRUSION -> {
                val profile = if (entity.extrusionProfile != null && entity.extrusionProfile.isNotEmpty()) entity.extrusionProfile else CadDefaults.ProfileHexagon
                val pt = if (idx < profile.size) profile[idx] else Point2D(0f, 0f)
                vertexEditXInput = pt.x.toString()
                vertexEditYInput = pt.y.toString()
                vertexEditZInput = ""
            }
            else -> {
                val offsets = entity.vertexOffsets ?: emptyList()
                val offset = if (idx < offsets.size) offsets[idx] else Point3D(0f, 0f, 0f)
                vertexEditXInput = offset.x.toString()
                vertexEditYInput = offset.y.toString()
                vertexEditZInput = offset.z.toString()
            }
        }
    }

    // Canvas Touch Mode: Orbit vs Pan
    var dragControlMode by remember { mutableStateOf("ORBIT") } // "ORBIT" or "PAN"

    // Display temporary operation status with Toast if updated
    LaunchedEffect(operationStatus) {
        if (operationStatus.isNotEmpty()) {
            Toast.makeText(context, operationStatus, Toast.LENGTH_SHORT).show()
        }
    }

    val selectedEntity = remember(entities, selectedEntityId) {
        entities.find { it.id == selectedEntityId }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "3D CAD Modeler",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = activeProjectName,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    // Open / Save draft database operations
                    IconButton(onClick = {
                        saveProjectNameInput = if (activeProjectName.startsWith("새 도면")) "" else activeProjectName
                        showProjectDialog = true
                    }) {
                        Icon(Icons.Default.Folder, contentDescription = "도면 관리", modifier = Modifier.testTag("btn_folder_projects"))
                    }

                    // Undo Button
                    IconButton(onClick = { viewModel.undo() }) {
                        Icon(Icons.Default.Undo, contentDescription = "되돌리기", modifier = Modifier.testTag("btn_undo"))
                    }

                    // Redo Button
                    IconButton(onClick = { viewModel.redo() }) {
                        Icon(Icons.Default.Redo, contentDescription = "다시 실행", modifier = Modifier.testTag("btn_redo"))
                    }

                    // Layers Toggler
                    IconButton(onClick = { showLayersPanel = !showLayersPanel }) {
                        Icon(
                            Icons.Default.Layers,
                            contentDescription = "레이어 설정",
                            tint = if (showLayersPanel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.testTag("btn_layers_toggle")
                        )
                    }

                    // Reference Image Blueprint Tracer Toggler
                    IconButton(onClick = { showImagePanel = !showImagePanel }) {
                        Icon(
                            Icons.Default.Image,
                            contentDescription = "참조 백그라운드 도면 이미지 설정",
                            tint = if (showImagePanel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.testTag("btn_images_toggle")
                        )
                    }

                    // Measurement Toggler
                    IconButton(onClick = { showMeasurementPanel = !showMeasurementPanel }) {
                        Icon(
                            Icons.Default.SquareFoot,
                            contentDescription = "치수 측정 창 토글",
                            tint = if (showMeasurementPanel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.testTag("btn_measurement_toggle")
                        )
                    }

                    // Grid Settings Toggler
                    IconButton(onClick = { showGridPanel = !showGridPanel }) {
                        Icon(
                            Icons.Default.GridView,
                            contentDescription = "그리드 설정",
                            tint = if (showGridPanel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.testTag("btn_grid_toggle")
                        )
                    }

                    // 3D OBJ Export
                    IconButton(
                        onClick = { showExportDialog = true },
                        modifier = Modifier.testTag("btn_export_obj")
                    ) {
                        Icon(Icons.Default.Share, contentDescription = "OBJ 내보내기", tint = Color(0xFF4CAF50))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp)
                )
            )
        },
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            // Main workspace (Left: optional layer            // 1. Workspace Tab Row Switcher (for 2D / 3D CAD Switch)
            TabRow(
                selectedTabIndex = when (workspaceLayout) {
                    "3D" -> 0
                    "2D" -> 1
                    else -> 2
                },
                modifier = Modifier.fillMaxWidth().height(42.dp),
                containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)
            ) {
                Tab(
                    selected = workspaceLayout == "3D",
                    onClick = { workspaceLayout = "3D" },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.ViewInAr, contentDescription = "3D View", modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("3D 솔리드 모델", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                )
                Tab(
                    selected = workspaceLayout == "2D",
                    onClick = {
                        workspaceLayout = "2D"
                        viewModel.changeViewMode(current2DPlane)
                    },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Architecture, contentDescription = "2D View", modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("2D CAD 도면", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                )
                Tab(
                    selected = workspaceLayout == "SPLIT",
                    onClick = { workspaceLayout = "SPLIT" },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.GridView, contentDescription = "Split View", modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("3D/2D 분할 화면", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val isLandscape = maxWidth > 600.dp
                    when (workspaceLayout) {
                        "3D" -> {
                            Solid3DWorkspace(
                                entities = entities,
                                selectedEntityId = selectedEntityId,
                                cameraState = cameraState,
                                viewMode = viewMode,
                                gridSize = gridSize,
                                dragControlMode = dragControlMode,
                                viewModel = viewModel,
                                onVertexClick = onVertexClick,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        "2D" -> {
                            Blueprint2DCanvas(
                                viewModel = viewModel,
                                entities = entities,
                                selectedEntityId = selectedEntityId,
                                cameraState = cameraState,
                                current2DPlane = current2DPlane,
                                onDimClick = { dim ->
                                    editingDim = dim
                                    editValueInput = dim.value.toInt().toString()
                                    showDimEditDialog = true
                                },
                                onVertexClick = onVertexClick,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        else -> {
                            // SPLIT Dual Monitor Mode
                            if (isLandscape) {
                                Row(modifier = Modifier.fillMaxSize()) {
                                    Solid3DWorkspace(
                                        entities = entities,
                                        selectedEntityId = selectedEntityId,
                                        cameraState = cameraState,
                                        viewMode = viewMode,
                                        gridSize = gridSize,
                                        dragControlMode = dragControlMode,
                                        viewModel = viewModel,
                                        onVertexClick = onVertexClick,
                                        modifier = Modifier.weight(1f).fillMaxHeight()
                                    )
                                    Box(
                                        modifier = Modifier
                                            .width(2.dp)
                                            .fillMaxHeight()
                                            .background(MaterialTheme.colorScheme.outlineVariant)
                                    )
                                    Blueprint2DCanvas(
                                        viewModel = viewModel,
                                        entities = entities,
                                        selectedEntityId = selectedEntityId,
                                        cameraState = cameraState,
                                        current2DPlane = current2DPlane,
                                        onDimClick = { dim ->
                                            editingDim = dim
                                            editValueInput = dim.value.toInt().toString()
                                            showDimEditDialog = true
                                        },
                                        onVertexClick = onVertexClick,
                                        modifier = Modifier.weight(1f).fillMaxHeight()
                                    )
                                }
                            } else {
                                Column(modifier = Modifier.fillMaxSize()) {
                                    Solid3DWorkspace(
                                        entities = entities,
                                        selectedEntityId = selectedEntityId,
                                        cameraState = cameraState,
                                        viewMode = viewMode,
                                        gridSize = gridSize,
                                        dragControlMode = dragControlMode,
                                        viewModel = viewModel,
                                        onVertexClick = onVertexClick,
                                        modifier = Modifier.weight(1f).fillMaxWidth()
                                    )
                                    Box(
                                        modifier = Modifier
                                            .height(2.dp)
                                            .fillMaxWidth()
                                            .background(MaterialTheme.colorScheme.outlineVariant)
                                    )
                                    Blueprint2DCanvas(
                                        viewModel = viewModel,
                                        entities = entities,
                                        selectedEntityId = selectedEntityId,
                                        cameraState = cameraState,
                                        current2DPlane = current2DPlane,
                                        onDimClick = { dim ->
                                            editingDim = dim
                                            editValueInput = dim.value.toInt().toString()
                                            showDimEditDialog = true
                                        },
                                        onVertexClick = onVertexClick,
                                        modifier = Modifier.weight(1f).fillMaxWidth()
                                    )
                                }
                            }
                        }
                    }
                }

                // --- Floating Overlay 1: Upper Right Camera HUD (shown in 3D / Split) ---
                if (workspaceLayout != "2D") {
                    Column(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(12.dp),
                        horizontalAlignment = Alignment.End
                    ) {
                        // Camera Preset Selector Card
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f)
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(modifier = Modifier.padding(6.dp)) {
                                Text(
                                    text = "뷰포트 각도",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                                Row {
                                    AssistChip(
                                        onClick = { viewModel.changeViewMode(ViewportMode.PERSPECTIVE) },
                                        label = { Text("원근3D", fontSize = 10.sp) },
                                        colors = AssistChipDefaults.assistChipColors(
                                            containerColor = if (viewMode == ViewportMode.PERSPECTIVE) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                                        ),
                                        modifier = Modifier.padding(2.dp)
                                    )
                                    AssistChip(
                                        onClick = { viewModel.changeViewMode(ViewportMode.ISOMETRIC) },
                                        label = { Text("등각", fontSize = 10.sp) },
                                        colors = AssistChipDefaults.assistChipColors(
                                            containerColor = if (viewMode == ViewportMode.ISOMETRIC) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                                        ),
                                        modifier = Modifier.padding(2.dp)
                                    )
                                    AssistChip(
                                        onClick = { viewModel.changeViewMode(ViewportMode.TOP) },
                                        label = { Text("평면(Top)", fontSize = 10.sp) },
                                        colors = AssistChipDefaults.assistChipColors(
                                            containerColor = if (viewMode == ViewportMode.TOP) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                                        ),
                                        modifier = Modifier.padding(2.dp)
                                    )
                                }
                                Row {
                                    AssistChip(
                                        onClick = { viewModel.changeViewMode(ViewportMode.FRONT) },
                                        label = { Text("정면(Front)", fontSize = 10.sp) },
                                        colors = AssistChipDefaults.assistChipColors(
                                            containerColor = if (viewMode == ViewportMode.FRONT) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                                        ),
                                        modifier = Modifier.padding(2.dp)
                                    )
                                    AssistChip(
                                        onClick = { viewModel.changeViewMode(ViewportMode.RIGHT) },
                                        label = { Text("우측(Right)", fontSize = 10.sp) },
                                        colors = AssistChipDefaults.assistChipColors(
                                            containerColor = if (viewMode == ViewportMode.RIGHT) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                                        ),
                                        modifier = Modifier.padding(2.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Tactical zoom & touch controls card
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f)
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(onClick = { viewModel.zoomIn() }) {
                                    Icon(Icons.Default.ZoomIn, contentDescription = "확대")
                                }
                                IconButton(onClick = { viewModel.zoomOut() }) {
                                    Icon(Icons.Default.ZoomOut, contentDescription = "축소")
                                }
                                IconButton(onClick = { viewModel.resetCamera() }) {
                                    Icon(Icons.Default.CenterFocusStrong, contentDescription = "가운데 맞춤")
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Gesture translation mode button
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f)
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "동작: ",
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(start = 8.dp)
                                )
                                FilterChip(
                                    selected = dragControlMode == "ORBIT",
                                    onClick = { dragControlMode = "ORBIT" },
                                    label = { Text("회전 (Orbit)", fontSize = 10.sp) },
                                    modifier = Modifier.padding(horizontal = 2.dp)
                                )
                                FilterChip(
                                    selected = dragControlMode == "PAN",
                                    onClick = { dragControlMode = "PAN" },
                                    label = { Text("이동 (Pan)", fontSize = 10.sp) },
                                    modifier = Modifier.padding(horizontal = 2.dp)
                                )
                            }
                        }
                    }
                } else {
                    // Show a custom 2D plane layout switcher in the upper right corner of full 2D screen!
                    Card(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text(
                                text = "도면 투영 기준면",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                            )
                            Row {
                                FilterChip(
                                    selected = current2DPlane == ViewportMode.TOP,
                                    onClick = { 
                                        current2DPlane = ViewportMode.TOP 
                                        viewModel.changeViewMode(ViewportMode.TOP)
                                    },
                                    label = { Text("평면(XY)", fontSize = 9.sp) },
                                    modifier = Modifier.padding(horizontal = 2.dp)
                                )
                                FilterChip(
                                    selected = current2DPlane == ViewportMode.FRONT,
                                    onClick = { 
                                        current2DPlane = ViewportMode.FRONT 
                                        viewModel.changeViewMode(ViewportMode.FRONT)
                                    },
                                    label = { Text("정면(XZ)", fontSize = 9.sp) },
                                    modifier = Modifier.padding(horizontal = 2.dp)
                                )
                                FilterChip(
                                    selected = current2DPlane == ViewportMode.RIGHT,
                                    onClick = { 
                                        current2DPlane = ViewportMode.RIGHT 
                                        viewModel.changeViewMode(ViewportMode.RIGHT)
                                    },
                                    label = { Text("측면(YZ)", fontSize = 9.sp) },
                                    modifier = Modifier.padding(horizontal = 2.dp)
                                )
                            }
                        }
                    }
                }

                // Measurement and snapping state HUD (Top-Left inside canvas)
                androidx.compose.animation.AnimatedVisibility(
                    visible = showMeasurementPanel,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(12.dp)
                ) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.width(180.dp)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.SquareFoot,
                                        contentDescription = "치수",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "정밀 치수 및 측정",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                IconButton(
                                    onClick = { showMeasurementPanel = false },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "닫기",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            if (selectedEntity != null) {
                                Text(
                                    text = "선택: ${selectedEntity.name}",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "이동 좌표: [X: ${selectedEntity.x.toInt()}, Y: ${selectedEntity.y.toInt()}, Z: ${selectedEntity.z.toInt()}]",
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                                if (measuredDistance != null) {
                                    Spacer(modifier = Modifier.height(3.dp))
                                    Text(
                                        text = "타 항목간 직선거리: ${String.format("%.2f", measuredDistance)} units",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFFF5252)
                                    )
                                }
                            } else {
                                Text(
                                    text = "화면의 요소를 선택하여\n좌표와 치수를 측정해 보세요.",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Divider(modifier = Modifier.padding(vertical = 8.dp))

                            // Snap to grid
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("격자 맞춤", fontSize = 11.sp)
                                Switch(
                                    checked = snapToGrid,
                                    onCheckedChange = { viewModel.toggleSnapToGrid() },
                                    modifier = Modifier
                                        .scaleRelative(0.7f)
                                        .testTag("switch_snap")
                                )
                            }
                        }
                    }
                }

                // Grid Settings Panel (Animated overlay on the left top)
                androidx.compose.animation.AnimatedVisibility(
                    visible = showGridPanel,
                    enter = slideInHorizontally { -it } + fadeIn(),
                    exit = slideOutHorizontally { -it } + fadeOut(),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 12.dp, top = 80.dp)
                ) {
                    Card(
                        modifier = Modifier
                            .width(220.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(6.dp)
                        ),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.GridView,
                                        contentDescription = "그리드",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "그리드 켜기/끄기 및 방향",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                IconButton(
                                    onClick = { showGridPanel = false },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "닫기",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            val showGridVal by viewModel.showGrid.collectAsStateWithLifecycle()
                            val gridPlaneVal by viewModel.gridPlane.collectAsStateWithLifecycle()

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("보조 그리드망 표시", fontSize = 11.sp, fontWeight = FontWeight.Medium)
                                Switch(
                                    checked = showGridVal,
                                    onCheckedChange = { viewModel.toggleShowGrid() },
                                    modifier = Modifier.scaleRelative(0.7f).testTag("switch_show_grid")
                                )
                            }

                            if (showGridVal) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("격자면 기준 설정 (3D)", fontSize = 10.sp, color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.SemiBold)
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    listOf("XY", "XZ", "YZ").forEach { plane ->
                                        val isSelected = gridPlaneVal == plane
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(30.dp)
                                                .background(
                                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                                    shape = RoundedCornerShape(6.dp)
                                                )
                                                .clickable { viewModel.setGridPlane(plane) },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = plane,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Collapsible Layers management panel (Animated overlay on the left check)
                androidx.compose.animation.AnimatedVisibility(
                    visible = showLayersPanel,
                    enter = slideInHorizontally { -it } + fadeIn(),
                    exit = slideOutHorizontally { -it } + fadeOut(),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 12.dp, top = 175.dp)
                ) {
                    Card(
                        modifier = Modifier
                            .width(220.dp)
                            .maxHeight(250.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(6.dp)
                        ),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "CAD 도면 레이어",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                                IconButton(
                                    onClick = { showLayersPanel = false },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "닫기", modifier = Modifier.size(16.dp))
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))

                            Column(
                                modifier = Modifier
                                    .verticalScroll(rememberScrollState())
                                    .weight(1f, fill = false)
                            ) {
                                layers.forEach { layer ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(Color(android.graphics.Color.parseColor(layer.colorHex)))
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = layer.name,
                                            fontSize = 11.sp,
                                            modifier = Modifier.weight(1f),
                                            maxLines = 1
                                        )

                                        // Visible Toggler
                                        IconButton(
                                            onClick = { viewModel.toggleLayerVisibility(layer.id) },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(
                                                imageVector = if (layer.isVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                                contentDescription = "가시성",
                                                modifier = Modifier.size(14.dp),
                                                tint = if (layer.isVisible) MaterialTheme.colorScheme.primary else Color.Gray
                                            )
                                        }

                                        // Lock Toggler
                                        IconButton(
                                            onClick = { viewModel.toggleLayerLock(layer.id) },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(
                                                imageVector = if (layer.isLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                                                contentDescription = "잠금",
                                                modifier = Modifier.size(14.dp),
                                                tint = if (layer.isLocked) Color(0xFFFF5252) else Color.Gray
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // --- Floating Overlay: Reference Image Tracer Settings Panel (Left Top/Side) ---
                val referenceImages by viewModel.referenceImages.collectAsStateWithLifecycle()
                androidx.compose.animation.AnimatedVisibility(
                    visible = showImagePanel,
                    enter = slideInHorizontally { -it } + fadeIn(),
                    exit = slideOutHorizontally { -it } + fadeOut(),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 12.dp, top = 220.dp)
                ) {
                    Card(
                        modifier = Modifier
                            .width(280.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(6.dp)
                        ),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            // Header Row
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Image,
                                        contentDescription = "Tracer",
                                        tint = Color(0xFF00E5FF),
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "참조 백그라운드 도면",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                IconButton(
                                    onClick = { showImagePanel = false },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "닫기", modifier = Modifier.size(16.dp))
                                }
                            }
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            // Load and Pick Buttons
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        // Load default sample blueprint from Unsplash/direct URL
                                        val testUrl = "https://images.unsplash.com/photo-1580587771525-78b9dba3b914?auto=format&fit=crop&w=600&q=80"
                                        viewModel.addReferenceImage("기본 가구 배치 도안", testUrl, "TOP")
                                    },
                                    modifier = Modifier.weight(1f).testTag("btn_load_sample_tracer"),
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text("샘플 가이드 추가", fontSize = 10.sp)
                                }
                                
                                Button(
                                    onClick = {
                                        imagePicker.launch("image/*")
                                    },
                                    modifier = Modifier.weight(1f).testTag("btn_pick_device_tracer"),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text("기기 사진 선택", fontSize = 10.sp)
                                }
                            }
                            
                            if (referenceImages.isEmpty()) {
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "추가된 참조 도안이 없습니다. 샘플 추가 또는 기기 도안을 추가하고 정렬해 보세요.",
                                    fontSize = 10.sp,
                                    color = Color.Gray,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                    textAlign = TextAlign.Center
                                )
                            } else {
                                Divider(modifier = Modifier.padding(vertical = 10.dp))
                                
                                LazyColumn(
                                    modifier = Modifier.heightIn(max = 240.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    items(referenceImages) { img ->
                                        Card(
                                            colors = CardDefaults.cardColors(
                                                containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp)
                                            ),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Column(modifier = Modifier.padding(8.dp)) {
                                                // Item title + Delete icon
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    modifier = Modifier.fillMaxWidth()
                                                ) {
                                                    Text(
                                                        text = img.name,
                                                        fontWeight = FontWeight.SemiBold,
                                                        fontSize = 11.sp,
                                                        maxLines = 1,
                                                        modifier = Modifier.weight(1f),
                                                        color = Color(0xFF00E5FF)
                                                    )
                                                    
                                                    // Visibility Switch and Delete button
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        IconButton(
                                                            onClick = { 
                                                                viewModel.updateReferenceImage(img.copy(isVisible = !img.isVisible))
                                                            },
                                                            modifier = Modifier.size(24.dp)
                                                        ) {
                                                            Icon(
                                                                imageVector = if (img.isVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                                                contentDescription = "Tracer 가시성",
                                                                modifier = Modifier.size(14.dp),
                                                                tint = if (img.isVisible) Color(0xFF00E5FF) else Color.Gray
                                                            )
                                                        }
                                                        
                                                        IconButton(
                                                            onClick = { viewModel.removeReferenceImage(img.id) },
                                                            modifier = Modifier.size(24.dp)
                                                        ) {
                                                            Icon(
                                                                imageVector = Icons.Default.Delete,
                                                                contentDescription = "Tracer 삭제",
                                                                modifier = Modifier.size(14.dp),
                                                                tint = Color(0xFFFF5252)
                                                            )
                                                        }
                                                    }
                                                }
                                                
                                                Spacer(modifier = Modifier.height(4.dp))
                                                
                                                // Plane Projection Choice (TOP, FRONT, RIGHT)
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                                    modifier = Modifier.fillMaxWidth()
                                                ) {
                                                    Text("정사영 투영면:", fontSize = 9.sp, modifier = Modifier.weight(1f))
                                                    listOf("TOP", "FRONT", "RIGHT").forEach { pName ->
                                                        val isSelected = img.plane == pName
                                                        Text(
                                                            text = pName,
                                                            fontSize = 8.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = if (isSelected) Color.Black else Color.White,
                                                            modifier = Modifier
                                                                .clip(RoundedCornerShape(4.dp))
                                                                .background(if (isSelected) Color(0xFF00E5FF) else Color(0xFF333D52))
                                                                .clickable {
                                                                    viewModel.updateReferenceImage(img.copy(plane = pName))
                                                                }
                                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                                        )
                                                    }
                                                }
                                                
                                                Spacer(modifier = Modifier.height(4.dp))
                                                
                                                // Opacity Slider
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    modifier = Modifier.fillMaxWidth()
                                                ) {
                                                    Text("투명도", fontSize = 9.sp, modifier = Modifier.width(32.dp))
                                                    Slider(
                                                        value = img.opacity,
                                                        onValueChange = { newVal ->
                                                            viewModel.updateReferenceImage(img.copy(opacity = newVal))
                                                        },
                                                        valueRange = 0.1f..1f,
                                                        modifier = Modifier.weight(1f).height(18.dp)
                                                    )
                                                    Text("${(img.opacity * 100).toInt()}%", fontSize = 8.sp, modifier = Modifier.width(24.dp), textAlign = TextAlign.End)
                                                }
                                                
                                                // Scale Slider
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    modifier = Modifier.fillMaxWidth()
                                                ) {
                                                    Text("배율", fontSize = 9.sp, modifier = Modifier.width(32.dp))
                                                    Slider(
                                                        value = img.scale,
                                                        onValueChange = { newVal ->
                                                            viewModel.updateReferenceImage(img.copy(scale = newVal))
                                                        },
                                                        valueRange = 0.2f..4.0f,
                                                        modifier = Modifier.weight(1f).height(18.dp)
                                                    )
                                                    Text(String.format("%.1fx", img.scale), fontSize = 8.sp, modifier = Modifier.width(28.dp), textAlign = TextAlign.End)
                                                }

                                                // Position alignment sliders/buttons
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    modifier = Modifier.fillMaxWidth()
                                                ) {
                                                    Text("좌우 X", fontSize = 9.sp, modifier = Modifier.width(32.dp))
                                                    Slider(
                                                        value = img.x,
                                                        onValueChange = { newVal ->
                                                            viewModel.updateReferenceImage(img.copy(x = newVal))
                                                        },
                                                        valueRange = -400f..400f,
                                                        modifier = Modifier.weight(1f).height(18.dp)
                                                    )
                                                    Text("${img.x.toInt()}", fontSize = 8.sp, modifier = Modifier.width(24.dp), textAlign = TextAlign.End)
                                                }

                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    modifier = Modifier.fillMaxWidth()
                                                ) {
                                                    Text("상하 Y", fontSize = 9.sp, modifier = Modifier.width(32.dp))
                                                    Slider(
                                                        value = img.y,
                                                        onValueChange = { newVal ->
                                                            viewModel.updateReferenceImage(img.copy(y = newVal))
                                                        },
                                                        valueRange = -400f..400f,
                                                        modifier = Modifier.weight(1f).height(18.dp)
                                                    )
                                                    Text("${img.y.toInt()}", fontSize = 8.sp, modifier = Modifier.width(24.dp), textAlign = TextAlign.End)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // --- Floating Overlay 4: Non-blocking Vertex Coordinates Editor (Bottom Left of Canvas) ---
                val vertexEntity = remember(entities, activeVertexEditEntityId) {
                    entities.find { it.id == activeVertexEditEntityId }
                }
                androidx.compose.animation.AnimatedVisibility(
                    visible = activeVertexEditIndex >= 0 && vertexEntity != null,
                    enter = slideInVertically { it } + fadeIn(),
                    exit = slideOutVertically { it } + fadeOut(),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(12.dp)
                ) {
                    if (vertexEntity != null) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.width(280.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.Edit,
                                            contentDescription = "수정",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        val isPoly = vertexEntity.type == EntityType.POLYLINE
                                        val isExtru = vertexEntity.type == EntityType.EXTRUSION
                                        val tText = if (isPoly) "P$activeVertexEditIndex 꼭짓점 좌표" else if (isExtru) "V$activeVertexEditIndex 프로파일 좌표" else "V$activeVertexEditIndex 미세조정 오프셋"
                                        Text(
                                            text = tText,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    IconButton(
                                        onClick = { activeVertexEditIndex = -1; activeVertexEditEntityId = null },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = "닫기",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                                
                                Spacer(modifier = Modifier.height(4.dp))
                                
                                val dText = if (vertexEntity.type == EntityType.POLYLINE) {
                                    "폴리라인 꼭짓점 로컬 3D 좌표 수치 조작"
                                } else if (vertexEntity.type == EntityType.EXTRUSION) {
                                    "돌출 단면 2D 꼭짓점 로컬 좌표 수치 조작"
                                } else {
                                    "선택한 도형 꼭짓점 번호인 $activeVertexEditIndex 미세 조정 오프셋 dX, dY, dZ 입력"
                                }
                                Text(
                                    text = dText,
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(8.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    OutlinedTextField(
                                        value = vertexEditXInput,
                                        onValueChange = { vertexEditXInput = it },
                                        label = { Text("X (mm)", fontSize = 8.sp) },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        modifier = Modifier.weight(1f).testTag("input_vertex_x"),
                                        singleLine = true,
                                        textStyle = LocalTextStyle.current.copy(fontSize = 11.sp)
                                    )
                                    OutlinedTextField(
                                        value = vertexEditYInput,
                                        onValueChange = { vertexEditYInput = it },
                                        label = { Text("Y (mm)", fontSize = 8.sp) },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        modifier = Modifier.weight(1f).testTag("input_vertex_y"),
                                        singleLine = true,
                                        textStyle = LocalTextStyle.current.copy(fontSize = 11.sp)
                                    )
                                    if (vertexEntity.type != EntityType.EXTRUSION) {
                                        OutlinedTextField(
                                            value = vertexEditZInput,
                                            onValueChange = { vertexEditZInput = it },
                                            label = { Text("Z (mm)", fontSize = 8.sp) },
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                            modifier = Modifier.weight(1f).testTag("input_vertex_z"),
                                            singleLine = true,
                                            textStyle = LocalTextStyle.current.copy(fontSize = 11.sp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(10.dp))
                                
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    TextButton(
                                        onClick = { activeVertexEditIndex = -1; activeVertexEditEntityId = null },
                                        modifier = Modifier.height(32.dp)
                                    ) {
                                        Text("취소", fontSize = 11.sp)
                                    }
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Button(
                                        onClick = {
                                            val xVal = vertexEditXInput.toFloatOrNull() ?: 0f
                                            val yVal = vertexEditYInput.toFloatOrNull() ?: 0f
                                            val zVal = vertexEditZInput.toFloatOrNull() ?: 0f
                                            when (vertexEntity.type) {
                                                EntityType.POLYLINE -> {
                                                    viewModel.updatePolylinePoint(activeVertexEditIndex, xVal, yVal, zVal)
                                                }
                                                EntityType.EXTRUSION -> {
                                                    viewModel.updateExtrusionProfilePoint(activeVertexEditIndex, xVal, yVal)
                                                }
                                                else -> {
                                                    viewModel.updateVertexOffset(activeVertexEditIndex, xVal, yVal, zVal)
                                                }
                                            }
                                            activeVertexEditIndex = -1
                                            activeVertexEditEntityId = null
                                            Toast.makeText(context, "꼭짓점 수치가 성공적으로 반영되었습니다.", Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier.height(32.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                                    ) {
                                        Text("적용", fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Bottom drawer toolbox containing Shape insertion and properties editing inputs
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(12.dp)
            ) {
                // Main Category switcher buttons
                var toolTab by remember { mutableStateOf("INSERT") } // "INSERT" or "MODIFY"

                TabRow(
                    selectedTabIndex = if (toolTab == "INSERT") 0 else 1,
                    modifier = Modifier.height(38.dp)
                ) {
                    Tab(
                        selected = toolTab == "INSERT",
                        onClick = { toolTab = "INSERT" },
                        text = { Text("3D 도형 삽입", fontSize = 12.sp, fontWeight = FontWeight.Bold) },
                        modifier = Modifier.testTag("tab_insert")
                    )
                    Tab(
                        selected = toolTab == "MODIFY",
                        onClick = { toolTab = "MODIFY" },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("선택 객체 정밀 수정", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                if (selectedEntity != null) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(RoundedCornerShape(3.dp))
                                            .background(Color(0xFF4CAF50))
                                    )
                                }
                            }
                        },
                        modifier = Modifier.testTag("tab_modify")
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Render active toolbox
                when (toolTab) {
                    "INSERT" -> {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "3D 솔리드 기본 프리미티브",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                TextButton(
                                    onClick = { showPresetProfilesDialog = true },
                                    modifier = Modifier.height(28.dp).padding(0.dp)
                                ) {
                                    Icon(Icons.Default.PlaylistAdd, contentDescription = "압출 도면", modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("커스텀 압출(Extrude)", fontSize = 10.sp)
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = { viewModel.addEntity(EntityType.BOX) },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer),
                                    modifier = Modifier.testTag("btn_add_box")
                                ) {
                                    Icon(Icons.Default.Hexagon, contentDescription = "상자", modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("상자 (Box)", fontSize = 11.sp)
                                }

                                Button(
                                    onClick = { viewModel.addEntity(EntityType.CYLINDER) },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer),
                                    modifier = Modifier.testTag("btn_add_cylinder")
                                ) {
                                    Icon(Icons.Default.Architecture, contentDescription = "실린더", modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("원기둥", fontSize = 11.sp)
                                }

                                Button(
                                    onClick = { viewModel.addEntity(EntityType.SPHERE) },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer),
                                    modifier = Modifier.testTag("btn_add_sphere")
                                ) {
                                    Icon(Icons.Default.Language, contentDescription = "구체", modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("구체", fontSize = 11.sp)
                                }

                                Button(
                                    onClick = { viewModel.addEntity(EntityType.CONE) },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer),
                                    modifier = Modifier.testTag("btn_add_cone")
                                ) {
                                    Icon(Icons.Default.Details, contentDescription = "원뿔", modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("원뿔", fontSize = 11.sp)
                                }

                                Button(
                                    onClick = { viewModel.addEntity(EntityType.POLYLINE) },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer),
                                    modifier = Modifier.testTag("btn_add_polyline")
                                ) {
                                    Icon(Icons.Default.Timeline, contentDescription = "배선", modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("3D 배선", fontSize = 11.sp)
                                }
                            }
                        }
                    }

                    "MODIFY" -> {
                        if (selectedEntity != null) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState())
                                    .weight(1f, fill = false)
                            ) {
                                // Object fast actions
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    var editName by remember(selectedEntity.id) { mutableStateOf(selectedEntity.name) }

                                    OutlinedTextField(
                                        value = editName,
                                        onValueChange = {
                                            editName = it
                                            viewModel.updateSelectedProperties(name = it)
                                        },
                                        label = { Text("객체명", fontSize = 10.sp) },
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(52.dp)
                                            .testTag("tf_entity_name"),
                                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
                                        singleLine = true
                                    )

                                    Spacer(modifier = Modifier.width(8.dp))

                                    FilledTonalIconButton(
                                        onClick = { viewModel.duplicateSelected() },
                                        modifier = Modifier.testTag("btn_duplicate")
                                    ) {
                                        Icon(Icons.Default.ContentCopy, contentDescription = "복제", modifier = Modifier.size(18.dp))
                                    }

                                    FilledTonalIconButton(
                                        onClick = { viewModel.deleteSelected() },
                                        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = Color(0x22FF5252), contentColor = Color(0xFFFF5252)),
                                        modifier = Modifier.testTag("btn_delete")
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = "삭제", modifier = Modifier.size(18.dp))
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                // Coordinate translation sliders and manual +/- actions
                                Text(
                                    text = "위치 좌표 (Position X, Y, Z)",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )

                                CoordinateManipulator(
                                    label = "X",
                                    value = selectedEntity.x,
                                    onValueChange = { viewModel.updateSelectedProperties(x = it) }
                                )
                                CoordinateManipulator(
                                    label = "Y",
                                    value = selectedEntity.y,
                                    onValueChange = { viewModel.updateSelectedProperties(y = it) }
                                )
                                CoordinateManipulator(
                                    label = "Z",
                                    value = selectedEntity.z,
                                    onValueChange = { viewModel.updateSelectedProperties(z = it) }
                                )

                                Spacer(modifier = Modifier.height(10.dp))

                                // Shape-specific scale dimensions modifier
                                Text(
                                    text = "객체 규격 (Dimensions & Scale)",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )

                                when (selectedEntity.type) {
                                    EntityType.BOX -> {
                                        DimensionManipulator(
                                            label = "너비 (Width X)",
                                            value = selectedEntity.width,
                                            range = 5f..400f,
                                            onValueChange = { viewModel.updateSelectedProperties(w = it) }
                                        )
                                        DimensionManipulator(
                                            label = "높이 (Height Y)",
                                            value = selectedEntity.height,
                                            range = 5f..400f,
                                            onValueChange = { viewModel.updateSelectedProperties(h = it) }
                                        )
                                        DimensionManipulator(
                                            label = "두께 (Depth Z)",
                                            value = selectedEntity.depth,
                                            range = 5f..400f,
                                            onValueChange = { viewModel.updateSelectedProperties(d = it) }
                                        )
                                    }
                                    EntityType.CYLINDER, EntityType.CONE -> {
                                        DimensionManipulator(
                                            label = "반지름 (Radius)",
                                            value = selectedEntity.radius,
                                            range = 5f..200f,
                                            onValueChange = { viewModel.updateSelectedProperties(r = it) }
                                        )
                                        DimensionManipulator(
                                            label = "높이 (Height)",
                                            value = selectedEntity.height,
                                            range = 5f..400f,
                                            onValueChange = { viewModel.updateSelectedProperties(h = it) }
                                        )
                                    }
                                    EntityType.SPHERE -> {
                                        DimensionManipulator(
                                            label = "반지름 (Radius)",
                                            value = selectedEntity.radius,
                                            range = 5f..250f,
                                            onValueChange = { viewModel.updateSelectedProperties(r = it) }
                                        )
                                    }
                                    EntityType.EXTRUSION -> {
                                        DimensionManipulator(
                                            label = "압출 높이 (Height Z)",
                                            value = selectedEntity.height,
                                            range = 5f..400f,
                                            onValueChange = { viewModel.updateSelectedProperties(h = it) }
                                        )
                                    }
                                    EntityType.POLYLINE -> {
                                        Text(
                                            text = "3D 배선은 좌표점들을 따라 3D 파이프로 고정 렌더링 됩니다.",
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(vertical = 4.dp)
                                        )
                                    }
                                    EntityType.COMBINED -> {
                                        Text(
                                            text = "복잡하게 결합 및 분할된 기하 3D 메쉬입니다. 위치(X, Y, Z) 및 회전각을 그대로 조절할 수 있습니다.",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(vertical = 4.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                // Rotations
                                Text(
                                    text = "Euler 각도 회전 (Rotation RX, RY, RZ)",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )

                                RotationManipulator(
                                    label = "RX",
                                    value = selectedEntity.rx,
                                    onValueChange = { viewModel.updateSelectedProperties(rx = it) }
                                )
                                RotationManipulator(
                                    label = "RY",
                                    value = selectedEntity.ry,
                                    onValueChange = { viewModel.updateSelectedProperties(ry = it) }
                                )
                                RotationManipulator(
                                    label = "RZ",
                                    value = selectedEntity.rz,
                                    onValueChange = { viewModel.updateSelectedProperties(rz = it) }
                                )

                                Spacer(modifier = Modifier.height(10.dp))

                                // Assign layer list & color selector row
                                Text(
                                    text = "레이어 및 요소 배색",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 6.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Colors list row
                                    val colors = listOf(
                                        "#FF2196F3", // Blue
                                        "#FF4CAF50", // Green
                                        "#FFE91E63", // Pink
                                        "#FFFFC107", // Amber
                                        "#FF9C27B0", // Purple
                                        "#FF00BCD4", // Cyan
                                        "#FFFF5722", // Deep orange
                                        "#FFFFFFFF"  // White
                                    )

                                    colors.forEach { hex ->
                                        Box(
                                            modifier = Modifier
                                                .size(24.dp)
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(Color(android.graphics.Color.parseColor(hex)))
                                                .border(
                                                    width = if (selectedEntity.colorHex == hex) 2.dp else 1.dp,
                                                    color = if (selectedEntity.colorHex == hex) MaterialTheme.colorScheme.primary else Color.LightGray,
                                                    shape = RoundedCornerShape(12.dp)
                                                )
                                                .clickable { viewModel.updateSelectedProperties(color = hex) }
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(4.dp))

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("레이어 지정: ", fontSize = 11.sp)
                                    LazyRow(
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        items(layers) { layer ->
                                            FilterChip(
                                                selected = selectedEntity.layerId == layer.id,
                                                onClick = { viewModel.updateSelectedProperties(layerId = layer.id) },
                                                label = { Text(layer.name, fontSize = 10.sp) }
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                // NEW: Vertex-specific fine tuning control panel
                                VertexControlPanel(
                                    entity = selectedEntity,
                                    viewModel = viewModel,
                                    selectedVertexIndex = if (activeVertexEditIndex >= 0) activeVertexEditIndex else 0,
                                    onVertexIndexChange = { idx ->
                                        onVertexClick(idx, selectedEntity)
                                    }
                                )

                                Spacer(modifier = Modifier.height(12.dp))

                                // NEW: Connect & Split Panel (도형 결합 및 분할 가공 Panel)
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                ) {
                                    Column(
                                        modifier = Modifier.padding(12.dp)
                                    ) {
                                        Text(
                                            text = "도형 결합 및 분할 가공",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.secondary
                                        )

                                        Spacer(modifier = Modifier.height(8.dp))

                                        // Part 1: CONNECT/JOIN (도형 결합)
                                        Text(
                                            text = "1. 다른 도형과 결합 (Connect & Merge)",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )

                                        val otherEntities = entities.filter { it.id != selectedEntity.id && it.isVisible }
                                        if (otherEntities.isEmpty()) {
                                            Text(
                                                text = "결합할 수 있는 다른 활성 도형이 없습니다.",
                                                fontSize = 10.sp,
                                                color = Color.Gray,
                                                modifier = Modifier.padding(vertical = 4.dp)
                                            )
                                        } else {
                                            var mergeTargetId by remember(selectedEntity.id) { 
                                                mutableStateOf(otherEntities.firstOrNull()?.id ?: "") 
                                            }
                                            
                                            Row(
                                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                // Dropdown selection box
                                                Box(
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(4.dp))
                                                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
                                                        .clickable {
                                                            val currIndex = otherEntities.indexOfFirst { it.id == mergeTargetId }
                                                            val nextIndex = (currIndex + 1) % otherEntities.size
                                                            mergeTargetId = otherEntities[nextIndex].id
                                                        }
                                                        .padding(horizontal = 8.dp, vertical = 6.dp)
                                                ) {
                                                    val selectedName = otherEntities.find { it.id == mergeTargetId }?.name ?: "선택 없음"
                                                    Text(text = selectedName, fontSize = 11.sp, maxLines = 1)
                                                }

                                                Button(
                                                    onClick = { 
                                                        if (mergeTargetId.isNotEmpty()) {
                                                            viewModel.mergeEntities(selectedEntity.id, mergeTargetId)
                                                        }
                                                    },
                                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                                    modifier = Modifier.height(32.dp).testTag("btn_merge_entities")
                                                ) {
                                                    Text("결합", fontSize = 11.sp)
                                                }
                                            }
                                            Text(
                                                text = "* 다른 요소를 탭하여 순차적으로 선택하거나 순환 탭하여 병합할 대상을 고르세요.",
                                                fontSize = 9.sp,
                                                color = Color.Gray
                                            )
                                        }

                                        Divider(modifier = Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))

                                        // Part 2: SPLIT/SLICE BY PLANE (단면 분할)
                                        Text(
                                            text = "2. 실시간 가상 물리 평면 분할 (Slice & Split)",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )

                                        Spacer(modifier = Modifier.height(4.dp))

                                        // Collect states
                                        val showSplitPreviewVal by viewModel.showSplittingPreview.collectAsStateWithLifecycle()
                                        val sliceNormalVal by viewModel.slicePlaneNormal.collectAsStateWithLifecycle()
                                        val slicePosVal by viewModel.slicePlanePos.collectAsStateWithLifecycle()

                                        // Show visual preview switch toggler
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text("가상 분할선 가이드 활성화 (Preview)", fontSize = 10.sp)
                                            Switch(
                                                checked = showSplitPreviewVal,
                                                onCheckedChange = { viewModel.toggleSplittingPreview() },
                                                modifier = Modifier.testTag("switch_splitting_preview")
                                            )
                                        }

                                        if (showSplitPreviewVal) {
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Text("가공 방향 (Normal Plane Axis):", fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
                                            Row(
                                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                listOf("X", "Y", "Z").forEach { axis ->
                                                    Box(
                                                        modifier = Modifier
                                                            .weight(1f)
                                                            .clip(RoundedCornerShape(4.dp))
                                                            .background(if (sliceNormalVal == axis) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer)
                                                            .clickable { viewModel.setSlicePlaneNormal(axis) }
                                                            .padding(vertical = 6.dp),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        Text(
                                                            text = axis + "축 수직",
                                                            color = if (sliceNormalVal == axis) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
                                                            fontSize = 10.sp,
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                    }
                                                }
                                            }

                                            Spacer(modifier = Modifier.height(4.dp))

                                            // Slider position offset manipulator
                                            Row(
                                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text("위치 절단선:", fontSize = 10.sp, modifier = Modifier.width(65.dp))
                                                Slider(
                                                    value = slicePosVal,
                                                    onValueChange = { viewModel.setSlicePlanePos(it) },
                                                    valueRange = -100f..100f,
                                                    modifier = Modifier.weight(1f).testTag("slider_slice_pos")
                                                )
                                                Text(
                                                    text = String.format("%.0f", slicePosVal),
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.width(30.dp),
                                                    textAlign = TextAlign.End
                                                )
                                            }

                                            Spacer(modifier = Modifier.height(6.dp))

                                            Button(
                                                onClick = { viewModel.splitEntityWithPlane(selectedEntity.id) },
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5722)), // High-contrast orange for action commit
                                                modifier = Modifier.fillMaxWidth().height(36.dp).testTag("btn_split_solid")
                                            ) {
                                                Icon(Icons.Default.ContentCut, contentDescription = "분할", modifier = Modifier.size(14.dp))
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text("평면 분할 실행 (Split!)", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            }
                                        } else {
                                            Text(
                                                text = "위 가이드를 켜시면, 실시간 컷팅 평면과 표면에 생성될 추가 꼭짓점 및 분할 선분이 연두색으로 3D 뷰포트에 렌더링됩니다.",
                                                fontSize = 9.sp,
                                                color = Color.Gray,
                                                modifier = Modifier.padding(vertical = 4.dp)
                                            )
                                        }

                                        // If polyline, add selected vertex-based split options
                                        if (selectedEntity.type == EntityType.POLYLINE && activeVertexEditIndex >= 0) {
                                            Divider(modifier = Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                                            
                                            Text(
                                                text = "3. 선택 배선 꼭짓점 기준 분할",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            
                                            Spacer(modifier = Modifier.height(4.dp))
                                            
                                            Button(
                                                onClick = { viewModel.splitPolylineAtVertex(selectedEntity.id, activeVertexEditIndex) },
                                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary),
                                                modifier = Modifier.fillMaxWidth().height(36.dp).testTag("btn_split_polyline_vertex")
                                            ) {
                                                Text("선택 점(Index: $activeVertexEditIndex) 기준으로 배선 끊기/분할", fontSize = 10.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        Icons.Default.AdsClick,
                                        contentDescription = "요소 선택 필요",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(32.dp)
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = "3D 뷰포트에서 요소를 탭하여 선택하면\n치수, 좌표, 회전 및 가공이 가능합니다.",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Projects Draft Database list dialog
    if (showProjectDialog) {
        Dialog(onDismissRequest = { showProjectDialog = false }) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .padding(16.dp)
                        .maxHeight(450.dp)
                ) {
                    Text(
                        text = "로컬 CAD 도면 보관소",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    // Text Field for saving
                    OutlinedTextField(
                        value = saveProjectNameInput,
                        onValueChange = { saveProjectNameInput = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("tf_project_name_save"),
                        label = { Text("도면 이름 입력") }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                if (saveProjectNameInput.trim().isNotEmpty()) {
                                    viewModel.saveProjectDraft(saveProjectNameInput.trim())
                                    showProjectDialog = false
                                } else {
                                    Toast.makeText(context, "도면 이름을 지정해주세요.", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("btn_save_project_submit")
                        ) {
                            Text("현재 도면 저장")
                        }

                        Button(
                            onClick = {
                                viewModel.startNewDraft()
                                showProjectDialog = false
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("새 도면 작성")
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "저장된 도면 목록",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                    ) {
                        if (savedProjects.isEmpty()) {
                            Text(
                                "저장된 도면 파일이 없습니다.",
                                fontSize = 11.sp,
                                modifier = Modifier.padding(vertical = 12.dp),
                                color = Color.Gray
                            )
                        } else {
                            savedProjects.forEach { proj ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                        .clickable {
                                            viewModel.loadProjectDraft(proj.id)
                                            showProjectDialog = false
                                        },
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(proj.name, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                        Text(
                                            "수정일: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(proj.timeModified))}",
                                            fontSize = 9.sp,
                                            color = Color.Gray
                                        )
                                    }
                                    IconButton(
                                        onClick = { viewModel.deleteProjectFromDb(proj.id) },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = "삭제", tint = Color.Gray)
                                    }
                                }
                                Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    TextButton(
                        onClick = { showProjectDialog = false },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("닫기")
                    }
                }
            }
        }
    }

    // Extrusion Presets Selector Dialog
    if (showPresetProfilesDialog) {
        Dialog(onDismissRequest = { showPresetProfilesDialog = false }) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "압출가공 커스텀 2D 프로필",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    // Column of presets
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                viewModel.addEntity(EntityType.EXTRUSION, CadDefaults.ProfileHexagon)
                                showPresetProfilesDialog = false
                            }
                            .padding(vertical = 4.dp)
                    ) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Hexagon, contentDescription = "육각기둥")
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text("육각 기둥 프리즘 (Hexagonal Prism)", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                Text("정육각형 베이스의 수직 돌출 기둥", fontSize = 10.sp, color = Color.Gray)
                            }
                        }
                    }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                viewModel.addEntity(EntityType.EXTRUSION, CadDefaults.ProfileLBracket)
                                showPresetProfilesDialog = false
                            }
                            .padding(vertical = 4.dp)
                    ) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Category, contentDescription = "L 브라켓")
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text("L자형 빔 브라켓 (L-Beam Bracket)", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                Text("공학용 패널 지지용 꺾쇠형 돌출 압출재", fontSize = 10.sp, color = Color.Gray)
                            }
                        }
                    }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                viewModel.addEntity(EntityType.EXTRUSION, CadDefaults.ProfileStar)
                                showPresetProfilesDialog = false
                            }
                            .padding(vertical = 4.dp)
                    ) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Star, contentDescription = "별형 기어")
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text("기어 스타 기둥 (Gear Star Shaft)", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                Text("톱니 또는 기어 로드가 결합되는 별모양 프로필", fontSize = 10.sp, color = Color.Gray)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    TextButton(
                        onClick = { showPresetProfilesDialog = false },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("취소")
                    }
                }
            }
        }
    }

    // Export OBJ Dialog
    if (showExportDialog) {
        Dialog(onDismissRequest = { showExportDialog = false }) {
            val objText = remember(entities, layers) {
                exportToObj(entities, layers)
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Wavefront OBJ 3D 내보내기",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "이 코드는 표준 3D 포맷(OBJ)으로, 복사하여 Blender, Autodesk CAD 등 모든 3D 편집기에서 불러와 즉시 활용할 수 있습니다.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    // Text display of OBJ code
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .background(Color(0xFFEFEFEF))
                            .border(1.dp, Color.LightGray)
                            .padding(8.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = objText,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.sp,
                            color = Color(0xFF333333)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                clipboardManager.setText(AnnotatedString(objText))
                                Toast.makeText(context, "3D OBJ 코드가 클립보드에 복사되었습니다.", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("btn_copy_obj_code")
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "복사", modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("클립보드 복사")
                        }

                        TextButton(
                            onClick = { showExportDialog = false }
                        ) {
                            Text("닫기")
                        }
                    }
                }
            }
        }
    }

    // Parametric Dimension Editing Dialog
    if (showDimEditDialog && editingDim != null) {
        Dialog(onDismissRequest = { showDimEditDialog = false }) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "수정",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "치수 직접 수정 (AutoCAD Dim)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "${editingDim!!.labelText}의 수치를 밀리미터 단위로 직접 입력하세요. 수정 즉시 3D 형상 모델에 실시간 반영되며, 등각/원근 뷰에 즉각 동기화됩니다.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(14.dp))

                    OutlinedTextField(
                        value = editValueInput,
                        onValueChange = { editValueInput = it },
                        label = { Text("밀리미터 치수 값 입력") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth().testTag("input_dim_value"),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { showDimEditDialog = false }) {
                            Text("취소")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                val newVal = editValueInput.toFloatOrNull()
                                if (newVal != null && newVal > 0f) {
                                    val dim = editingDim!!
                                    when (dim.id) {
                                        "width" -> viewModel.updateSelectedProperties(w = newVal)
                                        "height" -> viewModel.updateSelectedProperties(h = newVal)
                                        "depth" -> viewModel.updateSelectedProperties(d = newVal)
                                        "radius" -> viewModel.updateSelectedProperties(r = newVal)
                                        "x" -> viewModel.updateSelectedProperties(x = newVal)
                                        "y" -> viewModel.updateSelectedProperties(y = newVal)
                                        "z" -> viewModel.updateSelectedProperties(z = newVal)
                                    }
                                    showDimEditDialog = false
                                    Toast.makeText(context, "${dim.labelText}의 수치를 ${newVal.toInt()}mm로 수정 하였습니다.", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "올바른 양의 실수를 입력해 주세요.", Toast.LENGTH_SHORT).show()
                                }
                            }
                        ) {
                            Text("치수 입력 적용")
                        }
                    }
                }
            }
        }
    }


}

@Composable
fun CoordinateManipulator(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "$label:",
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            modifier = Modifier.width(20.dp),
            fontFamily = FontFamily.Monospace
        )

        IconButton(
            onClick = { onValueChange(value - 10f) },
            modifier = Modifier.size(28.dp)
        ) {
            Text("-10", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
        IconButton(
            onClick = { onValueChange(value - 1f) },
            modifier = Modifier.size(28.dp)
        ) {
            Text("-1", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }

        var showDirectEditDialog by remember { mutableStateOf(false) }
        var directEditValue by remember { mutableStateOf(value.toString()) }

        if (showDirectEditDialog) {
            AlertDialog(
                onDismissRequest = { showDirectEditDialog = false },
                title = { Text(text = "$label 좌표 직접 입력 (mm)", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
                text = {
                    OutlinedTextField(
                        value = directEditValue,
                        onValueChange = { directEditValue = it },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            directEditValue.toFloatOrNull()?.let { onValueChange(it) }
                            showDirectEditDialog = false
                        }
                    ) {
                        Text("적용")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDirectEditDialog = false }) {
                        Text("취소")
                    }
                }
            )
        }

        Box(
            modifier = Modifier
                .width(42.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable {
                    directEditValue = value.toString()
                    showDirectEditDialog = true
                }
                .padding(vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = value.toInt().toString(),
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        IconButton(
            onClick = { onValueChange(value + 1f) },
            modifier = Modifier.size(28.dp)
        ) {
            Text("+1", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
        IconButton(
            onClick = { onValueChange(value + 10f) },
            modifier = Modifier.size(28.dp)
        ) {
            Text("+10", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }

        Spacer(modifier = Modifier.width(6.dp))

        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = -500f..500f,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun DimensionManipulator(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            modifier = Modifier.width(80.dp),
            maxLines = 1
        )

        IconButton(
            onClick = { onValueChange((value - 5f).coerceIn(range)) },
            modifier = Modifier.size(28.dp)
        ) {
            Text("-5", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }

        var showDirectEditDialog by remember { mutableStateOf(false) }
        var directEditValue by remember { mutableStateOf(value.toString()) }

        if (showDirectEditDialog) {
            AlertDialog(
                onDismissRequest = { showDirectEditDialog = false },
                title = { Text(text = "$label 수치 직접 입력 (mm)", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
                text = {
                    OutlinedTextField(
                        value = directEditValue,
                        onValueChange = { directEditValue = it },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            directEditValue.toFloatOrNull()?.let {
                                val clamped = it.coerceIn(range)
                                onValueChange(clamped)
                            }
                            showDirectEditDialog = false
                        }
                    ) {
                        Text("적용")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDirectEditDialog = false }) {
                        Text("취소")
                    }
                }
            )
        }

        Box(
            modifier = Modifier
                .width(36.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable {
                    directEditValue = value.toString()
                    showDirectEditDialog = true
                }
                .padding(vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = value.toInt().toString(),
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        IconButton(
            onClick = { onValueChange((value + 5f).coerceIn(range)) },
            modifier = Modifier.size(28.dp)
        ) {
            Text("+5", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }

        Spacer(modifier = Modifier.width(6.dp))

        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun RotationManipulator(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "$label:",
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            modifier = Modifier.width(24.dp),
            fontFamily = FontFamily.Monospace
        )

        IconButton(
            onClick = {
                var next = value - 15f
                if (next < -180f) next += 360f
                onValueChange(next)
            },
            modifier = Modifier.size(28.dp)
        ) {
            Text("-15°", fontSize = 8.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }

        var showDirectEditDialog by remember { mutableStateOf(false) }
        var directEditValue by remember { mutableStateOf(value.toString()) }

        if (showDirectEditDialog) {
            AlertDialog(
                onDismissRequest = { showDirectEditDialog = false },
                title = { Text(text = "$label 각도 직접 입력 (도)", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
                text = {
                    OutlinedTextField(
                        value = directEditValue,
                        onValueChange = { directEditValue = it },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            directEditValue.toFloatOrNull()?.let {
                                var norm = it % 360f
                                if (norm < -180f) norm += 360f
                                if (norm > 180f) norm -= 360f
                                onValueChange(norm)
                            }
                            showDirectEditDialog = false
                        }
                    ) {
                        Text("적용")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDirectEditDialog = false }) {
                        Text("취소")
                    }
                }
            )
        }

        Box(
            modifier = Modifier
                .width(42.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable {
                    directEditValue = value.toString()
                    showDirectEditDialog = true
                }
                .padding(vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "${value.toInt()}°",
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        IconButton(
            onClick = {
                var next = value + 15f
                if (next > 180f) next -= 360f
                onValueChange(next)
            },
            modifier = Modifier.size(28.dp)
        ) {
            Text("+15°", fontSize = 8.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }

        Spacer(modifier = Modifier.width(6.dp))

        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = -180f..180f,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun CoordinateValueManipulator(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "$label:",
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            modifier = Modifier.width(22.dp),
            fontFamily = FontFamily.Monospace
        )

        IconButton(
            onClick = { onValueChange(value - 5f) },
            modifier = Modifier.size(24.dp)
        ) {
            Text("-5", fontSize = 8.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }

        var showDirectEditDialog by remember { mutableStateOf(false) }
        var directEditValue by remember { mutableStateOf(value.toString()) }

        if (showDirectEditDialog) {
            AlertDialog(
                onDismissRequest = { showDirectEditDialog = false },
                title = { Text(text = "$label 수치 직접 입력", fontSize = 15.sp, fontWeight = FontWeight.Bold) },
                text = {
                    OutlinedTextField(
                        value = directEditValue,
                        onValueChange = { directEditValue = it },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            directEditValue.toFloatOrNull()?.let { onValueChange(it) }
                            showDirectEditDialog = false
                        }
                    ) {
                        Text("적용")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDirectEditDialog = false }) {
                        Text("취소")
                    }
                }
            )
        }

        Box(
            modifier = Modifier
                .width(36.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable {
                    directEditValue = value.toString()
                    showDirectEditDialog = true
                }
                .padding(vertical = 2.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = value.toInt().toString(),
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        IconButton(
            onClick = { onValueChange(value + 5f) },
            modifier = Modifier.size(24.dp)
        ) {
            Text("+5", fontSize = 8.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }

        Spacer(modifier = Modifier.width(4.dp))

        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = -200f..200f,
            modifier = Modifier.weight(1f).height(24.dp)
        )
    }
}

@Composable
fun VertexControlPanel(
    entity: CadEntity,
    viewModel: CadViewModel,
    selectedVertexIndex: Int,
    onVertexIndexChange: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .padding(10.dp)
    ) {
        Text(
            text = "★ 꼭짓점 개별 편집 (Vertex Tuning)",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(4.dp))

        when (entity.type) {
            EntityType.POLYLINE -> {
                val pts = entity.polylinePoints ?: emptyList()
                if (pts.isEmpty()) {
                    Text("조절 가능한 꼭짓점이 없습니다.", fontSize = 11.sp, color = Color.Gray)
                } else {
                    val activeIndex = if (selectedVertexIndex < pts.size) selectedVertexIndex else 0

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("선택한 꼭짓점: ", fontSize = 11.sp)
                        ScrollableTabRow(
                            selectedTabIndex = activeIndex,
                            edgePadding = 0.dp,
                            modifier = Modifier.weight(1f).height(36.dp),
                            divider = {},
                            indicator = {}
                        ) {
                            pts.forEachIndexed { index, _ ->
                                Tab(
                                    selected = activeIndex == index,
                                    onClick = { onVertexIndexChange(index) },
                                    text = { Text("P$index", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    val activePt = pts[activeIndex]
                    Text("꼭짓점 ${activeIndex} 좌표 (X, Y, Z)", fontSize = 10.sp, color = MaterialTheme.colorScheme.secondary)

                    CoordinateValueManipulator(
                        label = "X",
                        value = activePt.x,
                        onValueChange = { viewModel.updatePolylinePoint(activeIndex, it, activePt.y, activePt.z) }
                    )
                    CoordinateValueManipulator(
                        label = "Y",
                        value = activePt.y,
                        onValueChange = { viewModel.updatePolylinePoint(activeIndex, activePt.x, it, activePt.z) }
                    )
                    CoordinateValueManipulator(
                        label = "Z",
                        value = activePt.z,
                        onValueChange = { viewModel.updatePolylinePoint(activeIndex, activePt.x, activePt.y, it) }
                    )
                }
            }
            EntityType.EXTRUSION -> {
                val profile = if (entity.extrusionProfile != null && entity.extrusionProfile.isNotEmpty()) {
                    entity.extrusionProfile
                } else {
                    CadDefaults.ProfileHexagon
                }
                if (profile.isEmpty()) {
                    Text("조절 가능한 프로파일 꼭짓점이 없습니다.", fontSize = 11.sp, color = Color.Gray)
                } else {
                    val activeIndex = if (selectedVertexIndex < profile.size) selectedVertexIndex else 0

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("선택한 단면 점: ", fontSize = 11.sp)
                        ScrollableTabRow(
                            selectedTabIndex = activeIndex,
                            edgePadding = 0.dp,
                            modifier = Modifier.weight(1f).height(36.dp),
                            divider = {},
                            indicator = {}
                        ) {
                            profile.forEachIndexed { index, _ ->
                                Tab(
                                    selected = activeIndex == index,
                                    onClick = { onVertexIndexChange(index) },
                                    text = { Text("V$index", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    val activePt = profile[activeIndex]
                    Text("단면 꼭짓점 ${activeIndex} 좌표 (Local X, Y)", fontSize = 10.sp, color = MaterialTheme.colorScheme.secondary)

                    CoordinateValueManipulator(
                        label = "X",
                        value = activePt.x,
                        onValueChange = { viewModel.updateExtrusionProfilePoint(activeIndex, it, activePt.y) }
                    )
                    CoordinateValueManipulator(
                        label = "Y",
                        value = activePt.y,
                        onValueChange = { viewModel.updateExtrusionProfilePoint(activeIndex, activePt.x, it) }
                    )
                }
            }
            else -> {
                val (defaultVertices, _) = generateEntityGeometry(entity.copy(vertexOffsets = emptyList()))
                val vertexCount = defaultVertices.size

                if (vertexCount == 0) {
                    Text("조절할 수 있는 꼭짓점이 없습니다.", fontSize = 11.sp, color = Color.Gray)
                } else {
                    val maxShowVertices = kotlin.math.min(vertexCount, 16)
                    val activeIndex = if (selectedVertexIndex < maxShowVertices) selectedVertexIndex else 0

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("선택 꼭짓점: ", fontSize = 11.sp)
                        ScrollableTabRow(
                            selectedTabIndex = activeIndex,
                            edgePadding = 0.dp,
                            modifier = Modifier.weight(1f).height(36.dp),
                            divider = {},
                            indicator = {}
                        ) {
                            for (index in 0 until maxShowVertices) {
                                Tab(
                                    selected = activeIndex == index,
                                    onClick = { onVertexIndexChange(index) },
                                    text = { Text("V$index", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    val offsets = entity.vertexOffsets ?: emptyList()
                    val existingOffset = if (activeIndex < offsets.size) {
                        offsets[activeIndex]
                    } else {
                        Point3D(0f, 0f, 0f)
                    }

                    val defV = defaultVertices[activeIndex]
                    Text(
                        text = "V$activeIndex 기본 위치: X:${defV.x.toInt()}, Y:${defV.y.toInt()}, Z:${defV.z.toInt()}",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text("꼭짓점 개별 미세조정 오프셋 (Offset Translation)", fontSize = 10.sp, color = MaterialTheme.colorScheme.secondary)

                    CoordinateValueManipulator(
                        label = "dX",
                        value = existingOffset.x,
                        onValueChange = { viewModel.updateVertexOffset(activeIndex, it, existingOffset.y, existingOffset.z) }
                    )
                    CoordinateValueManipulator(
                        label = "dY",
                        value = existingOffset.y,
                        onValueChange = { viewModel.updateVertexOffset(activeIndex, existingOffset.x, it, existingOffset.z) }
                    )
                    CoordinateValueManipulator(
                        label = "dZ",
                        value = existingOffset.z,
                        onValueChange = { viewModel.updateVertexOffset(activeIndex, existingOffset.x, existingOffset.y, it) }
                    )
                }
            }
        }
    }
}

// Extension Helpers for custom layout elements
fun Modifier.scaleRelative(scale: Float) = this.then(
    Modifier.padding(0.dp) // Just standard formatting
)

fun Modifier.maxHeight(max: androidx.compose.ui.unit.Dp) = this.then(
    Modifier.heightIn(max = max)
)

data class EditableVertex(
    val index: Int,
    val label: String,
    val worldPos: Point3D,
    val screenPos: Offset
)

fun getEditableVertices(
    entity: CadEntity,
    cameraState: ViewportState,
    viewMode: ViewportMode,
    screenWidth: Float,
    screenHeight: Float
): List<EditableVertex> {
    val cx = screenWidth / 2f
    val cy = screenHeight / 2f
    val (worldVertices, _) = generateEntityGeometry(entity)
    
    // Sort vertices by camera distance to prioritize the ones closest to the eye
    val yawRad = Math.toRadians(cameraState.yaw.toDouble()).toFloat()
    val pitchRad = Math.toRadians(cameraState.pitch.toDouble()).toFloat()

    val indexWithDepth = worldVertices.indices.map { idx ->
        val wp = worldVertices[idx]
        // Rotate point to camera space
        var v_cam = MathUtils.rotateY(wp, yawRad)
        v_cam = MathUtils.rotateX(v_cam, pitchRad)
        val depth = v_cam.z + 800f
        idx to depth
    }
    // Sort by depth ascending so that closest to the camera/observer are first
    val sortedIndices = indexWithDepth.sortedBy { it.second }.map { it.first }
    val maxCount = 40
    
    val list = mutableListOf<EditableVertex>()
    when (entity.type) {
        EntityType.POLYLINE -> {
            val pts = entity.polylinePoints ?: emptyList()
            // Retain the top N closest points, ordered by camera depth priority (closest first)
            val prioritizedIndices = sortedIndices.filter { it < pts.size && it < worldVertices.size }.take(maxCount)
            for (idx in prioritizedIndices) {
                val wp = worldVertices[idx]
                val sp = CadRenderer.projectPoint(wp, cameraState, viewMode, cx, cy)
                list.add(EditableVertex(idx, "P$idx", wp, Offset(sp.x, sp.y)))
            }
        }
        EntityType.EXTRUSION -> {
            val profile = if (entity.extrusionProfile != null && entity.extrusionProfile.isNotEmpty()) {
                entity.extrusionProfile
            } else {
                CadDefaults.ProfileHexagon
            }
            val numPoints = profile.size
            val totalCount = 2 * numPoints
            // Retain the top N closest points, ordered by camera depth priority (closest first)
            val prioritizedIndices = sortedIndices.filter { it < worldVertices.size && it < totalCount }.take(maxCount)
            for (idx in prioritizedIndices) {
                val wp = worldVertices[idx]
                val sp = CadRenderer.projectPoint(wp, cameraState, viewMode, cx, cy)
                val label = if (idx < numPoints) "V$idx (Bottom)" else "V${idx - numPoints} (Top)"
                list.add(EditableVertex(idx, label, wp, Offset(sp.x, sp.y)))
            }
        }
        else -> {
            val vertexCount = worldVertices.size
            // Retain the top N closest points, ordered by camera depth priority (closest first)
            val prioritizedIndices = sortedIndices.filter { it < worldVertices.size && it < vertexCount }.take(maxCount)
            for (idx in prioritizedIndices) {
                val wp = worldVertices[idx]
                val sp = CadRenderer.projectPoint(wp, cameraState, viewMode, cx, cy)
                list.add(EditableVertex(idx, "V$idx", wp, Offset(sp.x, sp.y)))
            }
        }
    }
    return list
}

fun getEditableVertices2D(
    entity: CadEntity,
    cameraState: ViewportState,
    plane: ViewportMode,
    screenWidth: Float,
    screenHeight: Float
): List<EditableVertex> {
    val centerX = screenWidth / 2f
    val centerY = screenHeight / 2f
    val zoom = cameraState.zoom * 1.5f
    val panX = cameraState.panX
    val panY = cameraState.panY
    val (worldVertices, _) = generateEntityGeometry(entity)
    
    // Sort vertices by 2D depth to prioritize the ones closest to the eye
    val indexWithDepth = worldVertices.indices.map { idx ->
        val wp = worldVertices[idx]
        val depth = when (plane) {
            ViewportMode.TOP -> wp.z - entity.z
            ViewportMode.FRONT -> wp.y - entity.y
            ViewportMode.RIGHT -> wp.x - entity.x
            else -> 0f
        }
        idx to depth
    }
    // Sort by depth descending so that closest to the camera/observer are first
    val sortedIndices = indexWithDepth.sortedByDescending { it.second }.map { it.first }
    val maxCount = 40

    val list = mutableListOf<EditableVertex>()
    when (entity.type) {
        EntityType.POLYLINE -> {
            val pts = entity.polylinePoints ?: emptyList()
            val prioritizedSet = sortedIndices.filter { it < pts.size }.take(maxCount).toSet()
            pts.forEachIndexed { idx, _ ->
                if (idx < worldVertices.size && idx in prioritizedSet) {
                    val wp = worldVertices[idx]
                    val wx = when (plane) {
                        ViewportMode.TOP -> wp.x
                        ViewportMode.FRONT -> wp.x
                        ViewportMode.RIGHT -> wp.y
                        else -> wp.x
                    }
                    val wy = when (plane) {
                        ViewportMode.TOP -> wp.y
                        ViewportMode.FRONT -> wp.z
                        ViewportMode.RIGHT -> wp.z
                        else -> wp.y
                    }
                    val depth = when (plane) {
                        ViewportMode.TOP -> wp.z - entity.z
                        ViewportMode.FRONT -> wp.y - entity.y
                        ViewportMode.RIGHT -> wp.x - entity.x
                        else -> 0f
                    }
                    val staggerX = if (depth > 0.1f) 14f else if (depth < -0.1f) -14f else 0f
                    val staggerY = if (depth > 0.1f) -14f else if (depth < -0.1f) 14f else 0f
                    val sp = Offset(centerX + panX + (wx * zoom) + staggerX, centerY + panY - (wy * zoom) + staggerY)
                    list.add(EditableVertex(idx, "P$idx", wp, sp))
                }
            }
        }
        EntityType.EXTRUSION -> {
            val profile = if (entity.extrusionProfile != null && entity.extrusionProfile.isNotEmpty()) {
                entity.extrusionProfile
            } else {
                CadDefaults.ProfileHexagon
            }
            val numPoints = profile.size
            val totalCount = 2 * numPoints
            val prioritizedSet = sortedIndices.filter { it < worldVertices.size && it < totalCount }.take(maxCount).toSet()

            for (idx in 0 until totalCount) {
                if (idx < worldVertices.size && idx in prioritizedSet) {
                    val wp = worldVertices[idx]
                    val wx = when (plane) {
                        ViewportMode.TOP -> wp.x
                        ViewportMode.FRONT -> wp.x
                        ViewportMode.RIGHT -> wp.y
                        else -> wp.x
                    }
                    val wy = when (plane) {
                        ViewportMode.TOP -> wp.y
                        ViewportMode.FRONT -> wp.z
                        ViewportMode.RIGHT -> wp.z
                        else -> wp.y
                    }
                    val depth = when (plane) {
                        ViewportMode.TOP -> wp.z - entity.z
                        ViewportMode.FRONT -> wp.y - entity.y
                        ViewportMode.RIGHT -> wp.x - entity.x
                        else -> 0f
                    }
                    val staggerX = if (depth > 0.1f) 14f else if (depth < -0.1f) -14f else 0f
                    val staggerY = if (depth > 0.1f) -14f else if (depth < -0.1f) 14f else 0f
                    val sp = Offset(centerX + panX + (wx * zoom) + staggerX, centerY + panY - (wy * zoom) + staggerY)
                    val label = if (idx < numPoints) "V$idx (Bottom)" else "V${idx - numPoints} (Top)"
                    list.add(EditableVertex(idx, label, wp, sp))
                }
            }
        }
        else -> {
            val vertexCount = worldVertices.size
            val prioritizedSet = sortedIndices.filter { it < worldVertices.size && it < vertexCount }.take(maxCount).toSet()

            for (idx in 0 until vertexCount) {
                if (idx < worldVertices.size && idx in prioritizedSet) {
                    val wp = worldVertices[idx]
                    val wx = when (plane) {
                        ViewportMode.TOP -> wp.x
                        ViewportMode.FRONT -> wp.x
                        ViewportMode.RIGHT -> wp.y
                        else -> wp.x
                    }
                    val wy = when (plane) {
                        ViewportMode.TOP -> wp.y
                        ViewportMode.FRONT -> wp.z
                        ViewportMode.RIGHT -> wp.z
                        else -> wp.y
                    }
                    val depth = when (plane) {
                        ViewportMode.TOP -> wp.z - entity.z
                        ViewportMode.FRONT -> wp.y - entity.y
                        ViewportMode.RIGHT -> wp.x - entity.x
                        else -> 0f
                    }
                    val staggerX = if (depth > 0.1f) 14f else if (depth < -0.1f) -14f else 0f
                    val staggerY = if (depth > 0.1f) -14f else if (depth < -0.1f) 14f else 0f
                    val sp = Offset(centerX + panX + (wx * zoom) + staggerX, centerY + panY - (wy * zoom) + staggerY)
                    list.add(EditableVertex(idx, "V$idx", wp, sp))
                }
            }
        }
    }
    return list
}

@Composable
fun rememberReferenceImagesBitmaps(referenceImages: List<ReferenceImage>): Map<String, ImageBitmap> {
    val context = LocalContext.current
    val bitmaps = remember { mutableStateMapOf<String, ImageBitmap>() }
    
    LaunchedEffect(referenceImages) {
        referenceImages.forEach { img ->
            if (!bitmaps.containsKey(img.id)) {
                kotlinx.coroutines.Dispatchers.IO.let { ioDispatcher ->
                    kotlinx.coroutines.withContext(ioDispatcher) {
                        try {
                            val bmp = if (img.uriString.startsWith("http")) {
                                val url = java.net.URL(img.uriString)
                                val connection = url.openConnection() as java.net.HttpURLConnection
                                connection.doInput = true
                                connection.connect()
                                val input = connection.inputStream
                                android.graphics.BitmapFactory.decodeStream(input)
                            } else {
                                val uri = android.net.Uri.parse(img.uriString)
                                val inputStream = context.contentResolver.openInputStream(uri)
                                android.graphics.BitmapFactory.decodeStream(inputStream)
                            }
                            if (bmp != null) {
                                bitmaps[img.id] = bmp.asImageBitmap()
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            }
        }
    }
    return bitmaps
}

@Composable
fun Solid3DWorkspace(
    entities: List<CadEntity>,
    selectedEntityId: String?,
    cameraState: ViewportState,
    viewMode: ViewportMode,
    gridSize: Float,
    dragControlMode: String,
    viewModel: CadViewModel,
    onVertexClick: (Int, CadEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    var draggedVertexIndex by remember { mutableStateOf(-1) }
    val selectedEntity = remember(entities, selectedEntityId) {
        entities.find { it.id == selectedEntityId }
    }

    val showSplitPreviewVal by viewModel.showSplittingPreview.collectAsStateWithLifecycle()
    val sliceNormalVal by viewModel.slicePlaneNormal.collectAsStateWithLifecycle()
    val slicePosVal by viewModel.slicePlanePos.collectAsStateWithLifecycle()
    val showGrid by viewModel.showGrid.collectAsStateWithLifecycle()
    val gridPlane by viewModel.gridPlane.collectAsStateWithLifecycle()
    val referenceImages by viewModel.referenceImages.collectAsStateWithLifecycle()
    val textMeasurer = rememberTextMeasurer()

    // Key Gesture performance optimization fields (avoids re-creating pointerInput on every micro-drag frame)
    val currentCameraState by rememberUpdatedState(cameraState)
    val currentSelectedEntity by rememberUpdatedState(selectedEntity)
    val currentDragControlMode by rememberUpdatedState(dragControlMode)
    val currentEntities by rememberUpdatedState(entities)

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF111622)) // Ultra deep slate/navy color
            .pointerInput(viewMode) {
                detectDragGestures(
                    onDragStart = { startOffset ->
                        viewModel.recordHistoryState()
                        val selEntity = currentSelectedEntity
                        val camState = currentCameraState
                        if (selEntity != null) {
                            val vertices = getEditableVertices(selEntity, camState, viewMode, size.width.toFloat(), size.height.toFloat())
                            val clicked = vertices.find { ev ->
                                val dist = kotlin.math.sqrt((startOffset.x - ev.screenPos.x) * (startOffset.x - ev.screenPos.x) + (startOffset.y - ev.screenPos.y) * (startOffset.y - ev.screenPos.y))
                                dist < 45f // touch target sensitivity
                            }
                            if (clicked != null) {
                                draggedVertexIndex = clicked.index
                            } else {
                                draggedVertexIndex = -1
                            }
                        } else {
                            draggedVertexIndex = -1
                        }
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val selEntity = currentSelectedEntity
                        val camState = currentCameraState
                        val dragCtrlMode = currentDragControlMode
                        if (draggedVertexIndex != -1 && selEntity != null) {
                            val rx_rad = Math.toRadians(selEntity.rx.toDouble()).toFloat()
                            val ry_rad = Math.toRadians(selEntity.ry.toDouble()).toFloat()
                            val rz_rad = Math.toRadians(selEntity.rz.toDouble()).toFloat()
                            val yawRad = Math.toRadians(camState.yaw.toDouble()).toFloat()
                            val pitchRad = Math.toRadians(camState.pitch.toDouble()).toFloat()

                            val geom = generateEntityGeometry(selEntity)
                            val worldVertices = geom.first
                            if (draggedVertexIndex < worldVertices.size) {
                                val v_world = worldVertices[draggedVertexIndex]

                                var v_cam = MathUtils.rotateY(v_world, yawRad)
                                v_cam = MathUtils.rotateX(v_cam, pitchRad)

                                val factor = if (viewMode == ViewportMode.PERSPECTIVE) {
                                    val distZ = v_cam.z + 800f
                                    if (distZ > 50f) 600f / distZ else 12f
                                } else {
                                    0.8f
                                }
                                val zoomScale = factor * camState.zoom

                                val dcx = dragAmount.x / zoomScale
                                val dcy = -dragAmount.y / zoomScale

                                var d_world = MathUtils.rotateX(Point3D(dcx, dcy, 0f), -pitchRad)
                                d_world = MathUtils.rotateY(d_world, -yawRad)

                                var d_local = MathUtils.rotateZ(d_world, -rz_rad)
                                d_local = MathUtils.rotateY(d_local, -ry_rad)
                                d_local = MathUtils.rotateX(d_local, -rx_rad)

                                when (selEntity.type) {
                                    EntityType.POLYLINE -> {
                                        val currentPts = selEntity.polylinePoints ?: emptyList()
                                        if (draggedVertexIndex < currentPts.size) {
                                            val currPt = currentPts[draggedVertexIndex]
                                            viewModel.updatePolylinePoint(draggedVertexIndex, currPt.x + d_local.x, currPt.y + d_local.y, currPt.z + d_local.z, saveToHistory = false)
                                        }
                                    }
                                    EntityType.EXTRUSION -> {
                                        val profile = if (selEntity.extrusionProfile != null && selEntity.extrusionProfile.isNotEmpty()) selEntity.extrusionProfile else CadDefaults.ProfileHexagon
                                        val numPoints = profile.size
                                        val profileIndex = draggedVertexIndex % numPoints
                                        if (profileIndex < profile.size) {
                                            val currPt = profile[profileIndex]
                                            viewModel.updateExtrusionProfilePoint(profileIndex, currPt.x + d_local.x, currPt.y + d_local.y, saveToHistory = false)
                                        }
                                    }
                                    else -> {
                                        val offsets = selEntity.vertexOffsets ?: emptyList()
                                        val currOffset = if (draggedVertexIndex < offsets.size) offsets[draggedVertexIndex] else Point3D(0f, 0f, 0f)
                                        viewModel.updateVertexOffset(draggedVertexIndex, currOffset.x + d_local.x, currOffset.y + d_local.y, currOffset.z + d_local.z, saveToHistory = false)
                                    }
                                }
                            }
                        } else {
                            if (dragCtrlMode == "ORBIT") {
                                viewModel.rotateYaw(-dragAmount.x * 0.35f)
                                viewModel.rotatePitch(dragAmount.y * 0.35f)
                            } else {
                                viewModel.panViewport(dragAmount.x, dragAmount.y)
                            }
                        }
                    },
                    onDragEnd = {
                        draggedVertexIndex = -1
                    }
                )
            }
            .pointerInput(viewMode) {
                detectTapGestures { offset ->
                    val selEntity = currentSelectedEntity
                    val camState = currentCameraState
                    val ents = currentEntities
                    if (selEntity != null) {
                        val vertices = getEditableVertices(selEntity, camState, viewMode, size.width.toFloat(), size.height.toFloat())
                        val clicked = vertices.find { ev ->
                            val dist = kotlin.math.sqrt((offset.x - ev.screenPos.x) * (offset.x - ev.screenPos.x) + (offset.y - ev.screenPos.y) * (offset.y - ev.screenPos.y))
                            dist < 45f
                        }
                        if (clicked != null) {
                            onVertexClick(clicked.index, selEntity)
                            return@detectTapGestures
                        }
                    }

                    val hit = CadRenderer.hitTestEntity(
                        tapX = offset.x,
                        tapY = offset.y,
                        entities = ents,
                        camera = camState,
                        viewMode = viewMode,
                        screenWidth = size.width.toFloat(),
                        screenHeight = size.height.toFloat()
                    )
                    viewModel.selectEntity(hit?.id)
                    if (hit != null) {
                        val vertices = getEditableVertices(hit, camState, viewMode, size.width.toFloat(), size.height.toFloat())
                        if (vertices.isNotEmpty()) {
                            val closest = vertices.minByOrNull { ev ->
                                val dx = offset.x - ev.screenPos.x
                                val dy = offset.y - ev.screenPos.y
                                dx * dx + dy * dy
                            }
                            if (closest != null) {
                                onVertexClick(closest.index, hit)
                            }
                        }
                    }
                }
            }
            .testTag("cad_canvas_solid")
    ) {
        // Draw spatial grid and axis lines
        CadRenderer.drawGridAndAxes(
            drawScope = this,
            camera = cameraState,
            viewMode = viewMode,
            gridSize = gridSize,
            showGrid = showGrid,
            gridPlane = gridPlane
        )

        // Draw 3D spatial reference image projection wireframes
        val cx = size.width / 2f
        val cy = size.height / 2f
        referenceImages.filter { it.isVisible }.forEach { img ->
            val iw = 300f * img.scale
            val ih = 300f * img.scale
            val corners = when (img.plane) {
                "TOP" -> listOf(
                    Point3D(img.x - iw/2, img.y - ih/2, img.z),
                    Point3D(img.x + iw/2, img.y - ih/2, img.z),
                    Point3D(img.x + iw/2, img.y + ih/2, img.z),
                    Point3D(img.x - iw/2, img.y + ih/2, img.z)
                )
                "FRONT" -> listOf(
                    Point3D(img.x - iw/2, img.y, img.z - ih/2),
                    Point3D(img.x + iw/2, img.y, img.z - ih/2),
                    Point3D(img.x + iw/2, img.y, img.z + ih/2),
                    Point3D(img.x - iw/2, img.y, img.z + ih/2)
                )
                "RIGHT" -> listOf(
                    Point3D(img.x, img.y - iw/2, img.z - ih/2),
                    Point3D(img.x, img.y + iw/2, img.z - ih/2),
                    Point3D(img.x, img.y + iw/2, img.z + ih/2),
                    Point3D(img.x, img.y - iw/2, img.z + ih/2)
                )
                else -> emptyList()
            }
            if (corners.isNotEmpty()) {
                val screenPts = corners.map { p ->
                    CadRenderer.projectPoint(p, cameraState, viewMode, cx, cy)
                }
                val path = Path().apply {
                    moveTo(screenPts[0].x, screenPts[0].y)
                    lineTo(screenPts[1].x, screenPts[1].y)
                    lineTo(screenPts[2].x, screenPts[2].y)
                    lineTo(screenPts[3].x, screenPts[3].y)
                    close()
                }
                drawPath(path = path, color = Color(0x2000E5FF))
                drawPath(path = path, color = Color(0xFF00E5FF).copy(alpha = img.opacity), style = Stroke(width = 2f))
                
                val labelPos = screenPts[0]
                drawText(
                    textMeasurer = textMeasurer,
                    text = "Tracer [${img.plane}]: ${img.name}",
                    topLeft = Offset(labelPos.x + 8f, labelPos.y + 8f),
                    style = TextStyle(color = Color(0xFF00E5FF), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                )
            }
        }

        // Draw all 3D solid elements with Painter overlap and dynamic shading
        CadRenderer.renderEntities(
            drawScope = this,
            entities = entities,
            selectedEntityId = selectedEntityId,
            camera = cameraState,
            viewMode = viewMode
        )

        // Draw real-time slicing plane splitting preview
        if (showSplitPreviewVal && selectedEntity != null) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            
            val nx: Float; val ny: Float; val nz: Float
            when (sliceNormalVal) {
                "X" -> { nx = 1f; ny = 0f; nz = 0f }
                "Y" -> { nx = 0f; ny = 1f; nz = 0f }
                else -> { nx = 0f; ny = 0f; nz = 1f }
            }
            
            // Plane center point
            val px = selectedEntity.x + nx * slicePosVal
            val py = selectedEntity.y + ny * slicePosVal
            val pz = selectedEntity.z + nz * slicePosVal
            
            // Size of visualization plane
            val sizeH = 150f
            val corners = when (sliceNormalVal) {
                "X" -> listOf(
                    Point3D(px, py - sizeH, pz - sizeH),
                    Point3D(px, py + sizeH, pz - sizeH),
                    Point3D(px, py + sizeH, pz + sizeH),
                    Point3D(px, py - sizeH, pz + sizeH)
                )
                "Y" -> listOf(
                    Point3D(px - sizeH, py, pz - sizeH),
                    Point3D(px + sizeH, py, pz - sizeH),
                    Point3D(px + sizeH, py, pz + sizeH),
                    Point3D(px - sizeH, py, pz + sizeH)
                )
                else -> listOf(
                    Point3D(px - sizeH, py - sizeH, pz),
                    Point3D(px + sizeH, py - sizeH, pz),
                    Point3D(px + sizeH, py + sizeH, pz),
                    Point3D(px - sizeH, py + sizeH, pz)
                )
            }
            
            val screenCorners = corners.map { 
                val sp = CadRenderer.projectPoint(it, cameraState, viewMode, cx, cy)
                Offset(sp.x, sp.y)
            }
            
            val path = Path().apply {
                moveTo(screenCorners[0].x, screenCorners[0].y)
                lineTo(screenCorners[1].x, screenCorners[1].y)
                lineTo(screenCorners[2].x, screenCorners[2].y)
                lineTo(screenCorners[3].x, screenCorners[3].y)
                close()
            }
            drawPath(
                path = path,
                color = Color(0x3300FFFF) // Translucent Cyan
            )
            drawPath(
                path = path,
                color = Color(0xFF00FFFF), // Solid Cyan border outline
                style = Stroke(width = 3f)
            )
            
            val (worldVertices, faces) = generateEntityGeometry(selectedEntity)
            val dPlane = -(nx * px + ny * py + nz * pz)
            fun signedDist(v: Point3D): Float {
                return nx * v.x + ny * v.y + nz * v.z + dPlane
            }
            
            val faceIntersections = mutableListOf<Point3D>()
            faces.forEach { face ->
                val n = face.size
                val faceInterPts = mutableListOf<Point3D>()
                for (i in 0 until n) {
                    val idxCurr = face[i]
                    val idxNext = face[(i + 1) % n]
                    if (idxCurr < worldVertices.size && idxNext < worldVertices.size) {
                        val vCurr = worldVertices[idxCurr]
                        val vNext = worldVertices[idxNext]
                        val dCurr = signedDist(vCurr)
                        val dNext = signedDist(vNext)
                        
                        if (dCurr * dNext < -0.001f) {
                            val t = -dCurr / (dNext - dCurr)
                            val pInter = Point3D(
                                vCurr.x + t * (vNext.x - vCurr.x),
                                vCurr.y + t * (vNext.y - vCurr.y),
                                vCurr.z + t * (vNext.z - vCurr.z)
                            )
                            faceInterPts.add(pInter)
                        }
                    }
                }
                
                if (faceInterPts.size >= 2) {
                    val pInter1 = faceInterPts[0]
                    val pInter2 = faceInterPts[1]
                    val sp1 = CadRenderer.projectPoint(pInter1, cameraState, viewMode, cx, cy)
                    val sp2 = CadRenderer.projectPoint(pInter2, cameraState, viewMode, cx, cy)
                    
                    drawLine(
                        color = Color(0xFF00FF00), // Brilliant neon green!
                        start = Offset(sp1.x, sp1.y),
                        end = Offset(sp2.x, sp2.y),
                        strokeWidth = 6f
                    )
                    
                    drawCircle(
                        color = Color(0xFF00FF00),
                        radius = 8f,
                        center = Offset(sp1.x, sp1.y)
                    )
                    drawCircle(
                        color = Color(0xFF00FF00),
                        radius = 8f,
                        center = Offset(sp2.x, sp2.y)
                    )
                    
                    faceIntersections.addAll(faceInterPts)
                }
            }
            
            if (faceIntersections.size >= 3) {
                var sx = 0f; var sy = 0f; var sz = 0f
                val uniquePts = faceIntersections.distinctBy { 
                    "${((it.x * 10).toInt())}_${((it.y * 10).toInt())}_${((it.z * 10).toInt())}" 
                }
                uniquePts.forEach { sx += it.x; sy += it.y; sz += it.z }
                val clCent = Point3D(sx / uniquePts.size, sy / uniquePts.size, sz / uniquePts.size)
                
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
                
                val sortedLoops = uniquePts.sortedBy { pt ->
                    val dx = pt.x - clCent.x
                    val dy = pt.y - clCent.y
                    val dz = pt.z - clCent.z
                    val u = dx * ux + dy * uy + dz * uz
                    val v = dx * vx + dy * vy + dz * vz
                    kotlin.math.atan2(v.toDouble(), u.toDouble()).toFloat()
                }
                
                if (sortedLoops.size >= 3) {
                    val loopPath = Path().apply {
                        val firstSp = CadRenderer.projectPoint(sortedLoops[0], cameraState, viewMode, cx, cy)
                        moveTo(firstSp.x, firstSp.y)
                        for (i in 1 until sortedLoops.size) {
                            val nextSp = CadRenderer.projectPoint(sortedLoops[i], cameraState, viewMode, cx, cy)
                            lineTo(nextSp.x, nextSp.y)
                        }
                        close()
                    }
                    drawPath(
                        path = loopPath,
                        color = Color(0x6600FF00) // Transparent neon green
                    )
                }
            }
        }

        // Draw Interactive Vertex Handles in 3D Canvas
        if (selectedEntity != null) {
            val vertices = getEditableVertices(selectedEntity, cameraState, viewMode, size.width, size.height)
            vertices.forEach { ev ->
                // Outer glow
                drawCircle(
                    color = Color(0x33FFFFFF),
                    radius = 20f,
                    center = ev.screenPos
                )
                // Middle border ring
                drawCircle(
                    color = Color(0x991E2E4E),
                    radius = 12f,
                    center = ev.screenPos,
                    style = Stroke(width = 3f)
                )
                // Inner filled color (active Yellow, or Blue)
                val isDragged = (draggedVertexIndex == ev.index)
                val color = if (isDragged) Color(0xFFFFEB3B) else Color(0xFF03A9F4)
                drawCircle(
                    color = color,
                    radius = 8f,
                    center = ev.screenPos
                )
                // White accent ring
                drawCircle(
                    color = Color.White,
                    radius = 8f,
                    center = ev.screenPos,
                    style = Stroke(width = 2f)
                )
            }
        }
    }
}

data class BlueprintDim(
    val id: String,
    val labelText: String,
    val value: Float,
    val screenP1: Offset,
    val screenP2: Offset,
    val labelPos: Offset
)

@Composable
fun Blueprint2DCanvas(
    viewModel: CadViewModel,
    entities: List<CadEntity>,
    selectedEntityId: String?,
    cameraState: ViewportState,
    current2DPlane: ViewportMode,
    onDimClick: (BlueprintDim) -> Unit,
    onVertexClick: (Int, CadEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    var dragStartOffset by remember { mutableStateOf<Offset?>(null) }
    var selectedEntityDragOffsetStart by remember { mutableStateOf<Point3D?>(null) }
    var draggedVertexIndex by remember { mutableStateOf(-1) }

    val selectedEntity = remember(entities, selectedEntityId) {
        entities.find { it.id == selectedEntityId }
    }

    val showGrid by viewModel.showGrid.collectAsStateWithLifecycle()
    val referenceImages by viewModel.referenceImages.collectAsStateWithLifecycle()
    val refBitmaps = rememberReferenceImagesBitmaps(referenceImages)

    // Key Gesture performance optimization fields (avoids re-creating pointerInput on every micro-drag frame)
    val currentCameraState by rememberUpdatedState(cameraState)
    val currentSelectedEntity by rememberUpdatedState(selectedEntity)
    val currentEntities by rememberUpdatedState(entities)

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0F1524)) // Dark AutoCAD grid background style
            .pointerInput(current2DPlane) {
                detectDragGestures(
                    onDragStart = { startOffset ->
                        viewModel.recordHistoryState()
                        dragStartOffset = startOffset
                        val selEntity = currentSelectedEntity
                        val camState = currentCameraState
                        if (selEntity != null) {
                            // Check if click is near an editable vertex
                            val vertices = getEditableVertices2D(selEntity, camState, current2DPlane, size.width.toFloat(), size.height.toFloat())
                            val clicked = vertices.find { ev ->
                                val dist = kotlin.math.sqrt((startOffset.x - ev.screenPos.x) * (startOffset.x - ev.screenPos.x) + (startOffset.y - ev.screenPos.y) * (startOffset.y - ev.screenPos.y))
                                dist < 45f
                            }
                            if (clicked != null) {
                                draggedVertexIndex = clicked.index
                                selectedEntityDragOffsetStart = null
                            } else {
                                draggedVertexIndex = -1
                                // Else, fallback to regular center-dragging of entire shape
                                val wx = when (current2DPlane) {
                                    ViewportMode.TOP -> selEntity.x
                                    ViewportMode.FRONT -> selEntity.x
                                    ViewportMode.RIGHT -> selEntity.y
                                    else -> selEntity.x
                                }
                                val wy = when (current2DPlane) {
                                    ViewportMode.TOP -> selEntity.y
                                    ViewportMode.FRONT -> selEntity.z
                                    ViewportMode.RIGHT -> selEntity.z
                                    else -> selEntity.y
                                }
                                val centerX = size.width / 2f
                                val centerY = size.height / 2f
                                val zoom = camState.zoom * 1.5f
                                val panX = camState.panX
                                val panY = camState.panY
                                val screenPos = Offset(
                                    centerX + panX + (wx * zoom),
                                    centerY + panY - (wy * zoom)
                                )
                                val distToCenter = kotlin.math.sqrt((startOffset.x - screenPos.x) * (startOffset.x - screenPos.x) + (startOffset.y - screenPos.y) * (startOffset.y - screenPos.y))
                                if (distToCenter < 100f) {
                                    selectedEntityDragOffsetStart = Point3D(selEntity.x, selEntity.y, selEntity.z)
                                } else {
                                    selectedEntityDragOffsetStart = null
                                }
                            }
                        } else {
                            draggedVertexIndex = -1
                            selectedEntityDragOffsetStart = null
                        }
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val camState = currentCameraState
                        val selEntity = currentSelectedEntity
                        val zoom = camState.zoom * 1.5f
                        if (draggedVertexIndex != -1 && selEntity != null) {
                            val dxWorld = dragAmount.x / zoom
                            val dyWorld = -dragAmount.y / zoom

                            // Transform local rotation back
                            val rx_rad = Math.toRadians(selEntity.rx.toDouble()).toFloat()
                            val ry_rad = Math.toRadians(selEntity.ry.toDouble()).toFloat()
                            val rz_rad = Math.toRadians(selEntity.rz.toDouble()).toFloat()

                            // 2D plane displacement as world spatial displacement
                            val d_world = when (current2DPlane) {
                                ViewportMode.TOP -> Point3D(dxWorld, dyWorld, 0f)
                                ViewportMode.FRONT -> Point3D(dxWorld, 0f, dyWorld)
                                ViewportMode.RIGHT -> Point3D(0f, dxWorld, dyWorld)
                                else -> Point3D(dxWorld, dyWorld, 0f)
                            }

                            var d_local = MathUtils.rotateZ(d_world, -rz_rad)
                            d_local = MathUtils.rotateY(d_local, -ry_rad)
                            d_local = MathUtils.rotateX(d_local, -rx_rad)

                            when (selEntity.type) {
                                EntityType.POLYLINE -> {
                                    val currentPts = selEntity.polylinePoints ?: emptyList()
                                    if (draggedVertexIndex < currentPts.size) {
                                        val currPt = currentPts[draggedVertexIndex]
                                        viewModel.updatePolylinePoint(draggedVertexIndex, currPt.x + d_local.x, currPt.y + d_local.y, currPt.z + d_local.z, saveToHistory = false)
                                    }
                                }
                                EntityType.EXTRUSION -> {
                                    val profile = if (selEntity.extrusionProfile != null && selEntity.extrusionProfile.isNotEmpty()) selEntity.extrusionProfile else CadDefaults.ProfileHexagon
                                    val numPoints = profile.size
                                    val profileIndex = draggedVertexIndex % numPoints
                                    if (profileIndex < profile.size) {
                                        val currPt = profile[profileIndex]
                                        viewModel.updateExtrusionProfilePoint(profileIndex, currPt.x + d_local.x, currPt.y + d_local.y, saveToHistory = false)
                                    }
                                }
                                else -> {
                                    val offsets = selEntity.vertexOffsets ?: emptyList()
                                    val currOffset = if (draggedVertexIndex < offsets.size) offsets[draggedVertexIndex] else Point3D(0f, 0f, 0f)
                                    viewModel.updateVertexOffset(draggedVertexIndex, currOffset.x + d_local.x, currOffset.y + d_local.y, currOffset.z + d_local.z, saveToHistory = false)
                                }
                            }
                        } else if (selectedEntityDragOffsetStart != null && selEntity != null) {
                            val dxWorld = dragAmount.x / zoom
                            val dyWorld = -dragAmount.y / zoom // Standard Y inversion

                            when (current2DPlane) {
                                ViewportMode.TOP -> {
                                    viewModel.updateSelectedProperties(
                                        x = selEntity.x + dxWorld,
                                        y = selEntity.y + dyWorld,
                                        saveToHistory = false
                                    )
                                }
                                ViewportMode.FRONT -> {
                                    viewModel.updateSelectedProperties(
                                        x = selEntity.x + dxWorld,
                                        z = selEntity.z + dyWorld,
                                        saveToHistory = false
                                    )
                                }
                                ViewportMode.RIGHT -> {
                                    viewModel.updateSelectedProperties(
                                        y = selEntity.y + dxWorld,
                                        z = selEntity.z + dyWorld,
                                        saveToHistory = false
                                    )
                                }
                                else -> {}
                            }
                        } else {
                            // Pan viewport sheet
                            viewModel.panViewport(dragAmount.x, dragAmount.y)
                        }
                    },
                    onDragEnd = {
                        dragStartOffset = null
                        selectedEntityDragOffsetStart = null
                        draggedVertexIndex = -1
                    }
                )
            }
            .pointerInput(current2DPlane) {
                detectTapGestures { tapOffset ->
                    val centerX = size.width / 2f
                    val centerY = size.height / 2f
                    val camState = currentCameraState
                    val zoom = camState.zoom * 1.5f
                    val panX = camState.panX
                    val panY = camState.panY
                    val selEntity = currentSelectedEntity
                    val ents = currentEntities

                    // First, check if clicked on high-priority vertex handle!
                    if (selEntity != null) {
                        val vertices = getEditableVertices2D(selEntity, camState, current2DPlane, size.width.toFloat(), size.height.toFloat())
                        val clicked = vertices.find { ev ->
                            val dist = kotlin.math.sqrt((tapOffset.x - ev.screenPos.x) * (tapOffset.x - ev.screenPos.x) + (tapOffset.y - ev.screenPos.y) * (tapOffset.y - ev.screenPos.y))
                            dist < 45f
                        }
                        if (clicked != null) {
                            onVertexClick(clicked.index, selEntity)
                            return@detectTapGestures
                        }
                    }

                    // Second, check if tapped near any active dimension label
                    if (selEntity != null) {
                        val dims = getBlueprintDims(selEntity, centerX, centerY, zoom, panX, panY, current2DPlane)
                        val clickedDim = dims.find { dim ->
                            val dx = tapOffset.x - dim.labelPos.x
                            val dy = tapOffset.y - dim.labelPos.y
                            (dx * dx + dy * dy) < 1200f // touch bounding region
                        }
                        if (clickedDim != null) {
                            onDimClick(clickedDim)
                            return@detectTapGestures
                        }
                    }

                    // Else, standard entity selection
                    var nearestEntity: CadEntity? = null
                    var minDistance = Float.MAX_VALUE

                    ents.filter { it.isVisible }.forEach { entity ->
                         val wx = when (current2DPlane) {
                             ViewportMode.TOP -> entity.x
                             ViewportMode.FRONT -> entity.x
                             ViewportMode.RIGHT -> entity.y
                             else -> entity.x
                         }
                         val wy = when (current2DPlane) {
                             ViewportMode.TOP -> entity.y
                             ViewportMode.FRONT -> entity.z
                             ViewportMode.RIGHT -> entity.z
                             else -> entity.y
                         }

                         val screenPos = Offset(
                             centerX + panX + (wx * zoom),
                             centerY + panY - (wy * zoom)
                         )

                         val dx = tapOffset.x - screenPos.x
                         val dy = tapOffset.y - screenPos.y
                         val centerDist = kotlin.math.sqrt(dx * dx + dy * dy)

                         // Measure distance to any vertex point too!
                         val geom = generateEntityGeometry(entity)
                         val worldVertices = geom.first
                         var minVertexDist = Float.MAX_VALUE
                         worldVertices.forEach { wp ->
                             val px = when (current2DPlane) {
                                 ViewportMode.TOP -> wp.x
                                 ViewportMode.FRONT -> wp.x
                                 ViewportMode.RIGHT -> wp.y
                                 else -> wp.x
                             }
                             val py = when (current2DPlane) {
                                 ViewportMode.TOP -> wp.y
                                 ViewportMode.FRONT -> wp.z
                                 ViewportMode.RIGHT -> wp.z
                                 else -> wp.y
                             }
                             val vScreenPos = Offset(centerX + panX + (px * zoom), centerY + panY - (py * zoom))
                             val dist = kotlin.math.sqrt((tapOffset.x - vScreenPos.x) * (tapOffset.x - vScreenPos.x) + (tapOffset.y - vScreenPos.y) * (tapOffset.y - vScreenPos.y))
                             if (dist < minVertexDist) {
                                 minVertexDist = dist
                             }
                         }

                         val finalDist = kotlin.math.min(centerDist, minVertexDist)
                         if (finalDist < minDistance) {
                             minDistance = finalDist
                             nearestEntity = entity
                         }
                    }

                    if (minDistance < 60f && nearestEntity != null) {
                        viewModel.selectEntity(nearestEntity.id)
                        val vertices = getEditableVertices2D(nearestEntity, camState, current2DPlane, size.width.toFloat(), size.height.toFloat())
                        if (vertices.isNotEmpty()) {
                            val closest = vertices.minByOrNull { ev ->
                                val dx = tapOffset.x - ev.screenPos.x
                                val dy = tapOffset.y - ev.screenPos.y
                                dx * dx + dy * dy
                            }
                            if (closest != null) {
                                onVertexClick(closest.index, nearestEntity)
                            }
                        }
                    } else {
                        viewModel.selectEntity(null)
                    }
                }
            }
            .testTag("cad_blueprint_canvas")
    ) {
        val centerX = size.width / 2f
        val centerY = size.height / 2f
        val zoom = cameraState.zoom * 1.5f
        val panX = cameraState.panX
        val panY = cameraState.panY

        // 1. Draw Blueprint Orthogonal Grid Lines
        if (showGrid) {
            val gridInterval = 50f * (zoom / 150f).coerceIn(0.2f, 5f)
            val startGridX = ((centerX + panX) % gridInterval) - gridInterval
            val startGridY = ((centerY + panY) % gridInterval) - gridInterval

            // Grid lines
            var gridX = startGridX
            while (gridX < size.width) {
                drawLine(
                    color = Color(0xFF1E2E4E),
                    start = Offset(gridX, 0f),
                    end = Offset(gridX, size.height),
                    strokeWidth = 1f
                )
                gridX += gridInterval
            }
            var gridY = startGridY
            while (gridY < size.height) {
                drawLine(
                    color = Color(0xFF1E2E4E),
                    start = Offset(0f, gridY),
                    end = Offset(size.width, gridY),
                    strokeWidth = 1f
                )
                gridY += gridInterval
            }
        }

        // Standard origin XY reference projection axes standard colored
        val originScreenPos = Offset(centerX + panX, centerY + panY)
        drawLine(
            color = Color(0xFFFF5252), // Primary plane horizontal axis (usually X)
            start = Offset(0f, originScreenPos.y),
            end = Offset(size.width, originScreenPos.y),
            strokeWidth = 2f
        )
        drawLine(
            color = Color(0xFF4CAF50), // Primary plane vertical axis
            start = Offset(originScreenPos.x, 0f),
            end = Offset(originScreenPos.x, size.height),
            strokeWidth = 2f
        )

        // Draw absolute origin dot indicator
        drawCircle(
            color = Color.White,
            radius = 5f,
            center = originScreenPos
        )

        // 1.5 Draw 2D Orthogonal Reference Trace Images
        referenceImages.filter { it.isVisible && it.plane == current2DPlane.name }.forEach { img ->
            val bitmap = refBitmaps[img.id]
            if (bitmap != null) {
                val iw = bitmap.width.toFloat()
                val ih = bitmap.height.toFloat()
                val maxDim = maxOf(iw, ih)
                
                // Keep image sized in world coordinates around center
                val worldW = (iw / maxDim) * 300f * img.scale
                val worldH = (ih / maxDim) * 300f * img.scale

                val screenW = worldW * zoom
                val screenH = worldH * zoom

                val screenX = centerX + panX + (img.x * zoom) - (screenW / 2)
                val screenY = centerY + panY - (img.y * zoom) - (screenH / 2)

                drawImage(
                    image = bitmap,
                    dstOffset = IntOffset(screenX.toInt(), screenY.toInt()),
                    dstSize = IntSize(screenW.toInt(), screenH.toInt()),
                    alpha = img.opacity
                )
            }
        }

        // 2. Render 2D Draft Outlines for visible elements
        entities.filter { it.isVisible }.forEach { entity ->
            val isSelected = entity.id == selectedEntityId
            val layerColor = try {
                Color(android.graphics.Color.parseColor(entity.colorHex))
            } catch (e: Exception) {
                Color(0xFF2196F3)
            }

            val wx = when (current2DPlane) {
                ViewportMode.TOP -> entity.x
                ViewportMode.FRONT -> entity.x
                ViewportMode.RIGHT -> entity.y
                else -> entity.x
            }
            val wy = when (current2DPlane) {
                ViewportMode.TOP -> entity.y
                ViewportMode.FRONT -> entity.z
                ViewportMode.RIGHT -> entity.z
                else -> entity.y
            }

            val screenCenter = Offset(
                centerX + panX + (wx * zoom),
                centerY + panY - (wy * zoom)
            )

            // Render 2D projected wireframe faces for the entity based on actual geometry
            if (entity.type == EntityType.POLYLINE) {
                val geom = generateEntityGeometry(entity)
                val vertices = geom.first
                for (i in 0 until vertices.size - 1) {
                    val p1 = vertices[i]
                    val p2 = vertices[i + 1]
                    
                    val p1x = when (current2DPlane) {
                        ViewportMode.TOP -> p1.x
                        ViewportMode.FRONT -> p1.x
                        ViewportMode.RIGHT -> p1.y
                        else -> p1.x
                    }
                    val p1y = when (current2DPlane) {
                        ViewportMode.TOP -> p1.y
                        ViewportMode.FRONT -> p1.z
                        ViewportMode.RIGHT -> p1.z
                        else -> p1.y
                    }
                    val p1Screen = Offset(centerX + panX + (p1x * zoom), centerY + panY - (p1y * zoom))
                    
                    val p2x = when (current2DPlane) {
                        ViewportMode.TOP -> p2.x
                        ViewportMode.FRONT -> p2.x
                        ViewportMode.RIGHT -> p2.y
                        else -> p2.x
                    }
                    val p2y = when (current2DPlane) {
                        ViewportMode.TOP -> p2.y
                        ViewportMode.FRONT -> p2.z
                        ViewportMode.RIGHT -> p2.z
                        else -> p2.y
                    }
                    val p2Screen = Offset(centerX + panX + (p2x * zoom), centerY + panY - (p2y * zoom))
                    
                    drawLine(
                        color = if (isSelected) Color(0xFFFFD54F) else layerColor,
                        start = p1Screen,
                        end = p2Screen,
                        strokeWidth = if (isSelected) 3.5f else 2.0f
                    )
                }
            } else {
                val geom = generateEntityGeometry(entity)
                val vertices = geom.first
                val faces = geom.second
                faces.forEach { face ->
                    val path = Path()
                    var valid = false
                    face.forEachIndexed { i, vIdx ->
                        if (vIdx < vertices.size) {
                            val wp = vertices[vIdx]
                            val px = when (current2DPlane) {
                                ViewportMode.TOP -> wp.x
                                ViewportMode.FRONT -> wp.x
                                ViewportMode.RIGHT -> wp.y
                                else -> wp.x
                            }
                            val py = when (current2DPlane) {
                                ViewportMode.TOP -> wp.y
                                ViewportMode.FRONT -> wp.z
                                ViewportMode.RIGHT -> wp.z
                                else -> wp.y
                            }
                            val screenPos = Offset(centerX + panX + (px * zoom), centerY + panY - (py * zoom))
                            if (i == 0) {
                                path.moveTo(screenPos.x, screenPos.y)
                            } else {
                                path.lineTo(screenPos.x, screenPos.y)
                            }
                            valid = true
                        }
                    }
                    if (valid && face.size > 2) {
                        path.close()
                        drawPath(
                            path = path,
                            color = layerColor.copy(alpha = if (isSelected) 0.28f else 0.08f)
                        )
                        drawPath(
                            path = path,
                            color = if (isSelected) Color(0xFFFFD54F) else layerColor,
                            style = Stroke(width = if (isSelected) 2.5f else 1.2f)
                        )
                    }
                }
            }

            // Draw micro center coordinate anchor point tag
            drawCircle(
                color = if (isSelected) Color(0xFFFFC107) else Color.White,
                radius = if (isSelected) 6f else 3.5f,
                center = screenCenter
            )
        }

        // 3. Render Metric Dimension Overlay Tags for selected item (AutoCAD standard lines)
        if (selectedEntity != null) {
            val dims = getBlueprintDims(selectedEntity, centerX, centerY, zoom, panX, panY, current2DPlane)
            dims.forEach { dim ->
                // Draw dimension line (e.g. extension limits, connector with arrowheads)
                drawCadDimensionLabelArrows(
                    drawScope = this,
                    p1 = dim.screenP1,
                    p2 = dim.screenP2,
                    color = Color(0xFF00E5FF)
                )

                // Render dynamic text card label pill
                val labelText = "${dim.value.toInt()} mm"
                val textLayoutResult = textMeasurer.measure(
                    text = labelText,
                    style = TextStyle(
                        color = Color(0xFF001122),
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                )
                val tw = textLayoutResult.size.width.toFloat()
                val th = textLayoutResult.size.height.toFloat()

                // Draw background touch bubble pill
                drawRoundRect(
                    color = Color(0xFF00E5FF),
                    topLeft = Offset(dim.labelPos.x - tw / 2 - 8f, dim.labelPos.y - th / 2 - 4f),
                    size = androidx.compose.ui.geometry.Size(tw + 16f, th + 8f),
                    cornerRadius = CornerRadius(12f, 12f)
                )

                // Write actual size numeric label text
                drawText(
                    textMeasurer = textMeasurer,
                    text = labelText,
                    topLeft = Offset(dim.labelPos.x - tw / 2, dim.labelPos.y - th / 2),
                    style = TextStyle(
                        color = Color(0xFF000F1F),
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                )
            }
        }

        // 4. Draw interactive 2D Vertex Handles
        if (selectedEntity != null) {
            val vertices = getEditableVertices2D(selectedEntity, cameraState, current2DPlane, size.width, size.height)
            vertices.forEach { ev ->
                // Outer glow
                drawCircle(
                    color = Color(0x33FFFFFF),
                    radius = 18f,
                    center = ev.screenPos
                )
                // Middle border ring
                drawCircle(
                    color = Color(0x991E2E4E),
                    radius = 11f,
                    center = ev.screenPos,
                    style = Stroke(width = 3f)
                )
                // Inner filled color (active Yellow, or Blue/Green)
                val isDragged = (draggedVertexIndex == ev.index)
                val color = if (isDragged) Color(0xFFFFEB3B) else Color(0xFF00FFD5)
                drawCircle(
                    color = color,
                    radius = 7.5f,
                    center = ev.screenPos
                )
                // White accent ring
                drawCircle(
                    color = Color.White,
                    radius = 7.5f,
                    center = ev.screenPos,
                    style = Stroke(width = 1.8f)
                )
            }
        }
    }
}

/**
 * Calculates dimension points for ortho blueprint components
 */
fun getBlueprintDims(
    entity: CadEntity,
    centerX: Float,
    centerY: Float,
    zoom: Float,
    panX: Float,
    panY: Float,
    plane: ViewportMode
): List<BlueprintDim> {
    val list = mutableListOf<BlueprintDim>()

    val wx = when (plane) {
        ViewportMode.TOP -> entity.x
        ViewportMode.FRONT -> entity.x
        ViewportMode.RIGHT -> entity.y
        else -> entity.x
    }
    val wy = when (plane) {
        ViewportMode.TOP -> entity.y
        ViewportMode.FRONT -> entity.z
        ViewportMode.RIGHT -> entity.z
        else -> entity.y
    }

    val sc = Offset(centerX + panX + (wx * zoom), centerY + panY - (wy * zoom))

    when (entity.type) {
        EntityType.BOX -> {
            val scaleW = when (plane) {
                ViewportMode.TOP -> entity.width
                ViewportMode.FRONT -> entity.width
                ViewportMode.RIGHT -> entity.depth
                else -> entity.width
            }
            val scaleH = when (plane) {
                ViewportMode.TOP -> entity.depth
                ViewportMode.FRONT -> entity.height
                ViewportMode.RIGHT -> entity.height
                else -> entity.depth
            }

            val dx = scaleW * zoom
            val dy = scaleH * zoom

            val xLeft = sc.x - dx / 2
            val xRight = sc.x + dx / 2
            val yTop = sc.y - dy / 2
            val yBottom = sc.y + dy / 2

            // Dimension 1: Horizontal Dimension (Width / Depth)
            val dim1Id = when (plane) {
                ViewportMode.TOP -> "width"
                ViewportMode.FRONT -> "width"
                ViewportMode.RIGHT -> "depth"
                else -> "width"
            }
            val dim1Label = when (plane) {
                ViewportMode.TOP -> "가로 폭 (Width)"
                ViewportMode.FRONT -> "가로 폭 (Width)"
                ViewportMode.RIGHT -> "깊이 (Depth)"
                else -> "가로 폭 (Width)"
            }
            list.add(
                BlueprintDim(
                    id = dim1Id,
                    labelText = dim1Label,
                    value = scaleW,
                    screenP1 = Offset(xLeft, yBottom + 35f),
                    screenP2 = Offset(xRight, yBottom + 35f),
                    labelPos = Offset(sc.x, yBottom + 35f)
                )
            )

            // Dimension 2: Vertical Dimension (Depth / Height)
            val dim2Id = when (plane) {
                ViewportMode.TOP -> "depth"
                ViewportMode.FRONT -> "height"
                ViewportMode.RIGHT -> "height"
                else -> "height"
            }
            val dim2Label = when (plane) {
                ViewportMode.TOP -> "깊이 (Depth)"
                ViewportMode.FRONT -> "세로 높이 (Height)"
                ViewportMode.RIGHT -> "세로 높이 (Height)"
                else -> "세로 높이 (Height)"
            }
            list.add(
                BlueprintDim(
                    id = dim2Id,
                    labelText = dim2Label,
                    value = scaleH,
                    screenP1 = Offset(xLeft - 35f, yTop),
                    screenP2 = Offset(xLeft - 35f, yBottom),
                    labelPos = Offset(xLeft - 35f, sc.y)
                )
            )
        }
        EntityType.CYLINDER -> {
            val isCircle = (plane == ViewportMode.TOP)
            if (isCircle) {
                // Radius Dimension
                val r = entity.radius * zoom
                list.add(
                    BlueprintDim(
                        id = "radius",
                        labelText = "원 기둥 반경 (Radius)",
                        value = entity.radius,
                        screenP1 = sc,
                        screenP2 = Offset(sc.x + r * 0.707f, sc.y - r * 0.707f),
                        labelPos = Offset(sc.x + r * 0.353f - 15f, sc.y - r * 0.353f - 15f)
                    )
                )
            } else {
                // Height & Diameter bounds
                val r = entity.radius * zoom
                val h = entity.height * zoom
                val xLeft = sc.x - r
                val xRight = sc.x + r
                val yTop = sc.y - h / 2
                val yBottom = sc.y + h / 2

                // Height Dim
                list.add(
                    BlueprintDim(
                        id = "height",
                        labelText = "실린더 높이 (Height)",
                        value = entity.height,
                        screenP1 = Offset(xLeft - 35f, yTop),
                        screenP2 = Offset(xLeft - 35f, yBottom),
                        labelPos = Offset(xLeft - 35f, sc.y)
                    )
                )
                // Diameter Dim
                list.add(
                    BlueprintDim(
                        id = "radius",
                        labelText = "실린더 기저 직경 (Diameter)",
                        value = entity.radius * 2,
                        screenP1 = Offset(xLeft, yBottom + 35f),
                        screenP2 = Offset(xRight, yBottom + 35f),
                        labelPos = Offset(sc.x, yBottom + 35f)
                    )
                )
            }
        }
        EntityType.SPHERE -> {
            val r = entity.radius * zoom
            list.add(
                BlueprintDim(
                    id = "radius",
                    labelText = "구체 반경 (Radius)",
                    value = entity.radius,
                    screenP1 = sc,
                    screenP2 = Offset(sc.x + r * 0.707f, sc.y - r * 0.707f),
                    labelPos = Offset(sc.x + r * 0.353f, sc.y - r * 0.353f - 10f)
                )
            )
        }
        EntityType.CONE -> {
            val isCircle = (plane == ViewportMode.TOP)
            if (isCircle) {
                val r = entity.radius * zoom
                list.add(
                    BlueprintDim(
                        id = "radius",
                        labelText = "원뿔 기저 반경 (Radius)",
                        value = entity.radius,
                        screenP1 = sc,
                        screenP2 = Offset(sc.x + r * 0.707f, sc.y - r * 0.707f),
                        labelPos = Offset(sc.x + r * 0.353f, sc.y - r * 0.353f)
                    )
                )
            } else {
                val r = entity.radius * zoom
                val h = entity.height * zoom
                val xLeft = sc.x - r
                val xRight = sc.x + r
                val yTop = sc.y - h / 2
                val yBottom = sc.y + h / 2

                list.add(
                    BlueprintDim(
                        id = "height",
                        labelText = "원뿔 높이 (Height)",
                        value = entity.height,
                        screenP1 = Offset(xLeft - 35f, yTop),
                        screenP2 = Offset(xLeft - 35f, yBottom),
                        labelPos = Offset(xLeft - 35f, sc.y)
                    )
                )
                list.add(
                    BlueprintDim(
                        id = "radius",
                        labelText = "원뿔 직경 (Diameter)",
                        value = entity.radius * 2,
                        screenP1 = Offset(xLeft, yBottom + 35f),
                        screenP2 = Offset(xRight, yBottom + 35f),
                        labelPos = Offset(sc.x, yBottom + 35f)
                    )
                )
            }
        }
        else -> {}
    }

    // Always add position offset indicator relative to coordinate origin!
    list.add(
        BlueprintDim(
            id = when (plane) {
                ViewportMode.TOP -> "x"
                ViewportMode.FRONT -> "x"
                ViewportMode.RIGHT -> "y"
                else -> "x"
            },
            labelText = when (plane) {
                ViewportMode.TOP -> "중심점 X좌표"
                ViewportMode.FRONT -> "중심점 X좌표"
                ViewportMode.RIGHT -> "중심점 Y좌표"
                else -> "중심점 X좌표"
            },
            value = when (plane) {
                ViewportMode.TOP -> entity.x
                ViewportMode.FRONT -> entity.x
                ViewportMode.RIGHT -> entity.y
                else -> entity.x
            },
            screenP1 = Offset(centerX + panX, sc.y),
            screenP2 = sc,
            labelPos = Offset((sc.x + (centerX + panX)) / 2, sc.y + 12f)
        )
    )

    list.add(
        BlueprintDim(
            id = when (plane) {
                ViewportMode.TOP -> "y"
                ViewportMode.FRONT -> "z"
                ViewportMode.RIGHT -> "z"
                else -> "y"
            },
            labelText = when (plane) {
                ViewportMode.TOP -> "중심점 Y좌표"
                ViewportMode.FRONT -> "중심점 Z높이"
                ViewportMode.RIGHT -> "중심점 Z높이"
                else -> "중심점 Y좌표"
            },
            value = when (plane) {
                ViewportMode.TOP -> entity.y
                ViewportMode.FRONT -> entity.z
                ViewportMode.RIGHT -> entity.z
                else -> entity.y
            },
            screenP1 = Offset(sc.x, centerY + panY),
            screenP2 = sc,
            labelPos = Offset(sc.x - 12f, (sc.y + (centerY + panY)) / 2)
        )
    )

    return list
}

/**
 * Draws standard engineering dimension extension ticks and arrow heads dynamically
 */
fun drawCadDimensionLabelArrows(
    drawScope: DrawScope,
    p1: Offset,
    p2: Offset,
    color: Color
) {
    drawScope.drawLine(
        color = color,
        start = p1,
        end = p2,
        strokeWidth = 1.2f
    )

    // Calculate direction vectors to draw elegant mechanical arrows
    val dx = p2.x - p1.x
    val dy = p2.y - p1.y
    val len = kotlin.math.sqrt(dx * dx + dy * dy)
    if (len > 15f) {
        val ux = dx / len
        val uy = dy / len

        // Orthogonal components
        val ox = -uy
        val oy = ux

        // Dimension Arrow 1 at p1 pointing towards direction
        drawScope.drawLine(
            color = color,
            start = p1,
            end = Offset(p1.x + ux * 10f - ox * 3.5f, p1.y + uy * 10f - oy * 3.5f),
            strokeWidth = 1.2f
        )
        drawScope.drawLine(
            color = color,
            start = p1,
            end = Offset(p1.x + ux * 10f + ox * 3.5f, p1.y + uy * 10f + oy * 3.5f),
            strokeWidth = 1.2f
        )

        // Dimension Arrow 2 at p2 pointing back to p1
        drawScope.drawLine(
            color = color,
            start = p2,
            end = Offset(p2.x - ux * 10f - ox * 3.5f, p2.y - uy * 10f - oy * 3.5f),
            strokeWidth = 1.2f
        )
        drawScope.drawLine(
            color = color,
            start = p2,
            end = Offset(p2.x - ux * 10f + ox * 3.5f, p2.y - uy * 10f + oy * 3.5f),
            strokeWidth = 1.2f
        )
    }
}
