package com.example.cad.ui

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
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

    // AutoCAD 2D/3D Workspace state
    var workspaceLayout by remember { mutableStateOf("SPLIT") } // "3D", "2D", "SPLIT"
    var current2DPlane by remember { mutableStateOf(ViewportMode.TOP) }
    var showDimEditDialog by remember { mutableStateOf(false) }
    var editingDim by remember { mutableStateOf<BlueprintDim?>(null) }
    var editValueInput by remember { mutableStateOf("") }

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
                Column(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(12.dp)
                ) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.SquareFoot,
                                    contentDescription = "치수",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "정밀 치수 및 측정",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
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
                                modifier = Modifier.width(135.dp)
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

        // Display value
        Text(
            text = value.toInt().toString(),
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(42.dp)
        )

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

        // Large slider input range
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

        Text(
            text = value.toInt().toString(),
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(36.dp)
        )

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

        Text(
            text = "${value.toInt()}°",
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(42.dp)
        )

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

// Extension Helpers for custom layout elements
fun Modifier.scaleRelative(scale: Float) = this.then(
    Modifier.padding(0.dp) // Just standard formatting
)

fun Modifier.maxHeight(max: androidx.compose.ui.unit.Dp) = this.then(
    Modifier.heightIn(max = max)
)

@Composable
fun Solid3DWorkspace(
    entities: List<CadEntity>,
    selectedEntityId: String?,
    cameraState: ViewportState,
    viewMode: ViewportMode,
    gridSize: Float,
    dragControlMode: String,
    viewModel: CadViewModel,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF111622)) // Ultra deep slate/navy color
            .pointerInput(dragControlMode) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    if (dragControlMode == "ORBIT") {
                        viewModel.rotateYaw(-dragAmount.x * 0.35f)
                        viewModel.rotatePitch(dragAmount.y * 0.35f)
                    } else {
                        viewModel.panViewport(dragAmount.x, dragAmount.y)
                    }
                }
            }
            .pointerInput(entities, selectedEntityId, cameraState, viewMode) {
                detectTapGestures { offset ->
                    val hit = CadRenderer.hitTestEntity(
                        tapX = offset.x,
                        tapY = offset.y,
                        entities = entities,
                        camera = cameraState,
                        viewMode = viewMode,
                        screenWidth = size.width.toFloat(),
                        screenHeight = size.height.toFloat()
                    )
                    viewModel.selectEntity(hit?.id)
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
            showGrid = true
        )

        // Draw all 3D solid elements with Painter overlap and dynamic shading
        CadRenderer.renderEntities(
            drawScope = this,
            entities = entities,
            selectedEntityId = selectedEntityId,
            camera = cameraState,
            viewMode = viewMode
        )
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
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    var dragStartOffset by remember { mutableStateOf<Offset?>(null) }
    var selectedEntityDragOffsetStart by remember { mutableStateOf<Point3D?>(null) }

    val selectedEntity = remember(entities, selectedEntityId) {
        entities.find { it.id == selectedEntityId }
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0F1524)) // Dark AutoCAD grid background style
            .pointerInput(entities, selectedEntityId, cameraState, current2DPlane) {
                detectTapGestures { tapOffset ->
                    val centerX = size.width / 2f
                    val centerY = size.height / 2f
                    val zoom = cameraState.zoom * 1.5f
                    val panX = cameraState.panX
                    val panY = cameraState.panY

                    // Check if tapped near any active dimension label
                    if (selectedEntity != null) {
                        val dims = getBlueprintDims(selectedEntity, centerX, centerY, zoom, panX, panY, current2DPlane)
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

                    entities.filter { it.isVisible }.forEach { entity ->
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
                        val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                        if (dist < minDistance) {
                            minDistance = dist
                            nearestEntity = entity
                        }
                    }

                    if (minDistance < 60f && nearestEntity != null) {
                        viewModel.selectEntity(nearestEntity!!.id)
                    } else {
                        viewModel.selectEntity(null)
                    }
                }
            }
            .pointerInput(entities, selectedEntityId, cameraState, current2DPlane) {
                detectDragGestures(
                    onDragStart = { startOffset ->
                        val centerX = size.width / 2f
                        val centerY = size.height / 2f
                        val zoom = cameraState.zoom * 1.5f
                        val panX = cameraState.panX
                        val panY = cameraState.panY

                        dragStartOffset = startOffset
                        if (selectedEntity != null) {
                            val wx = when (current2DPlane) {
                                ViewportMode.TOP -> selectedEntity.x
                                ViewportMode.FRONT -> selectedEntity.x
                                ViewportMode.RIGHT -> selectedEntity.y
                                else -> selectedEntity.x
                            }
                            val wy = when (current2DPlane) {
                                ViewportMode.TOP -> selectedEntity.y
                                ViewportMode.FRONT -> selectedEntity.z
                                ViewportMode.RIGHT -> selectedEntity.z
                                else -> selectedEntity.y
                            }
                            val screenPos = Offset(
                                centerX + panX + (wx * zoom),
                                centerY + panY - (wy * zoom)
                            )
                            val distToCenter = kotlin.math.sqrt((startOffset.x - screenPos.x) * (startOffset.x - screenPos.x) + (startOffset.y - screenPos.y) * (startOffset.y - screenPos.y))
                            
                            // If user clicked close to center -> drag object
                            if (distToCenter < 100f) {
                                selectedEntityDragOffsetStart = Point3D(selectedEntity.x, selectedEntity.y, selectedEntity.z)
                            } else {
                                selectedEntityDragOffsetStart = null
                            }
                        } else {
                            selectedEntityDragOffsetStart = null
                        }
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        if (selectedEntityDragOffsetStart != null && selectedEntity != null) {
                            // Translate the physical object inside the 2D plane
                            val zoom = cameraState.zoom * 1.5f
                            val dxWorld = dragAmount.x / zoom
                            val dyWorld = -dragAmount.y / zoom // Standard Y inversion

                            when (current2DPlane) {
                                ViewportMode.TOP -> {
                                    viewModel.updateSelectedProperties(
                                        x = selectedEntity.x + dxWorld,
                                        y = selectedEntity.y + dyWorld
                                    )
                                }
                                ViewportMode.FRONT -> {
                                    viewModel.updateSelectedProperties(
                                        x = selectedEntity.x + dxWorld,
                                        z = selectedEntity.z + dyWorld
                                    )
                                }
                                ViewportMode.RIGHT -> {
                                    viewModel.updateSelectedProperties(
                                        y = selectedEntity.y + dxWorld,
                                        z = selectedEntity.z + dyWorld
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
                    }
                )
            }
            .testTag("cad_blueprint_canvas")
    ) {
        val centerX = size.width / 2f
        val centerY = size.height / 2f
        val zoom = cameraState.zoom * 1.5f
        val panX = cameraState.panX
        val panY = cameraState.panY

        // 1. Draw Blueprint Orthogonal Grid Lines
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

            // Calculate bounding elements depending on shape
            when (entity.type) {
                EntityType.BOX -> {
                    val scaleW = when (current2DPlane) {
                        ViewportMode.TOP -> entity.width
                        ViewportMode.FRONT -> entity.width
                        ViewportMode.RIGHT -> entity.depth
                        else -> entity.width
                    }
                    val scaleH = when (current2DPlane) {
                        ViewportMode.TOP -> entity.depth
                        ViewportMode.FRONT -> entity.height
                        ViewportMode.RIGHT -> entity.height
                        else -> entity.depth
                    }

                    val rawLeft = screenCenter.x - (scaleW / 2) * zoom
                    val rawTop = screenCenter.y - (scaleH / 2) * zoom
                    val rawWidth = scaleW * zoom
                    val rawHeight = scaleH * zoom

                    // Draw translucent face fill
                    drawRect(
                        color = layerColor.copy(alpha = if (isSelected) 0.35f else 0.15f),
                        topLeft = Offset(rawLeft, rawTop),
                        size = androidx.compose.ui.geometry.Size(rawWidth, rawHeight)
                    )

                    // Draw thick geometry contour lines
                    drawRect(
                        color = if (isSelected) Color(0xFFFFD54F) else layerColor,
                        topLeft = Offset(rawLeft, rawTop),
                        size = androidx.compose.ui.geometry.Size(rawWidth, rawHeight),
                        style = Stroke(width = if (isSelected) 3f else 1.8f)
                    )
                }
                EntityType.CYLINDER -> {
                    val isCircleView = (current2DPlane == ViewportMode.TOP)
                    if (isCircleView) {
                        val screenRad = entity.radius * zoom
                        drawCircle(
                            color = layerColor.copy(alpha = if (isSelected) 0.35f else 0.15f),
                            center = screenCenter,
                            radius = screenRad
                        )
                        drawCircle(
                            color = if (isSelected) Color(0xFFFFD54F) else layerColor,
                            center = screenCenter,
                            radius = screenRad,
                            style = Stroke(width = if (isSelected) 3f else 1.8f)
                        )
                    } else {
                        // Rectangular elevation projection of cylinder
                        val rawLeft = screenCenter.x - entity.radius * zoom
                        val rawTop = screenCenter.y - (entity.height / 2) * zoom
                        val rawWidth = entity.radius * 2 * zoom
                        val rawHeight = entity.height * zoom

                        drawRect(
                            color = layerColor.copy(alpha = if (isSelected) 0.35f else 0.15f),
                            topLeft = Offset(rawLeft, rawTop),
                            size = androidx.compose.ui.geometry.Size(rawWidth, rawHeight)
                        )
                        drawRect(
                            color = if (isSelected) Color(0xFFFFD54F) else layerColor,
                            topLeft = Offset(rawLeft, rawTop),
                            size = androidx.compose.ui.geometry.Size(rawWidth, rawHeight),
                            style = Stroke(width = if (isSelected) 3f else 1.8f)
                        )
                    }
                }
                EntityType.SPHERE -> {
                    val screenRad = entity.radius * zoom
                    drawCircle(
                        color = layerColor.copy(alpha = if (isSelected) 0.35f else 0.15f),
                        center = screenCenter,
                        radius = screenRad
                    )
                    drawCircle(
                        color = if (isSelected) Color(0xFFFFD54F) else layerColor,
                        center = screenCenter,
                        radius = screenRad,
                        style = Stroke(width = if (isSelected) 3f else 1.8f)
                    )
                }
                EntityType.CONE -> {
                    val isCircleView = (current2DPlane == ViewportMode.TOP)
                    if (isCircleView) {
                        val screenRad = entity.radius * zoom
                        drawCircle(
                            color = layerColor.copy(alpha = if (isSelected) 0.35f else 0.15f),
                            center = screenCenter,
                            radius = screenRad
                        )
                        drawCircle(
                            color = if (isSelected) Color(0xFFFFD54F) else layerColor,
                            center = screenCenter,
                            radius = screenRad,
                            style = Stroke(width = if (isSelected) 3f else 1.8f)
                        )
                        // Nested origin center vertex crosshair index icon
                        drawLine(
                            color = if (isSelected) Color(0xFFFFD54F) else layerColor,
                            start = Offset(screenCenter.x - 12f, screenCenter.y),
                            end = Offset(screenCenter.x + 12f, screenCenter.y),
                            strokeWidth = 1f
                        )
                        drawLine(
                            color = if (isSelected) Color(0xFFFFD54F) else layerColor,
                            start = Offset(screenCenter.x, screenCenter.y - 12f),
                            end = Offset(screenCenter.x, screenCenter.y + 12f),
                            strokeWidth = 1f
                        )
                    } else {
                        // Triangle Elevation Profile Projection
                        val baseW = entity.radius * 2 * zoom
                        val h = entity.height * zoom
                        val path = Path().apply {
                            moveTo(screenCenter.x, screenCenter.y - h / 2) // peak
                            lineTo(screenCenter.x - baseW / 2, screenCenter.y + h / 2) // base-left
                            lineTo(screenCenter.x + baseW / 2, screenCenter.y + h / 2) // base-right
                            close()
                        }
                        drawPath(
                            path = path,
                            color = layerColor.copy(alpha = if (isSelected) 0.35f else 0.15f)
                        )
                        drawPath(
                            path = path,
                            color = if (isSelected) Color(0xFFFFD54F) else layerColor,
                            style = Stroke(width = if (isSelected) 3f else 1.8f)
                        )
                    }
                }
                else -> {
                    // Fallback to bounding box contour
                    val scaleW = 80f * zoom
                    val scaleH = 80f * zoom
                    drawRect(
                        color = layerColor.copy(alpha = 0.12f),
                        topLeft = Offset(screenCenter.x - scaleW / 2, screenCenter.y - scaleH / 2),
                        size = androidx.compose.ui.geometry.Size(scaleW, scaleH)
                    )
                    drawRect(
                        color = if (isSelected) Color(0xFFFFD54F) else layerColor,
                        topLeft = Offset(screenCenter.x - scaleW / 2, screenCenter.y - scaleH / 2),
                        size = androidx.compose.ui.geometry.Size(scaleW, scaleH),
                        style = Stroke(width = 1f)
                    )
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
