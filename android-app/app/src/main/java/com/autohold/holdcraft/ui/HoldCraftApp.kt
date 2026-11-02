package com.autohold.holdcraft.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.autohold.holdcraft.data.ProblemRepository
import com.autohold.holdcraft.inference.HoldColorExtractor
import com.autohold.holdcraft.inference.HoldInferenceEngine
import com.autohold.holdcraft.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

sealed class ScreenState {
    data object Home : ScreenState()
    data class Editor(
        val bitmap: Bitmap,
        val holds: List<HoldCandidate>,
        val existingProblem: ClimbingProblem? = null
    ) : ScreenState()
    data class Solver(
        val problem: ClimbingProblem,
        val bitmap: Bitmap
    ) : ScreenState()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HoldCraftApp(
    repository: ProblemRepository,
    inferenceEngineProvider: suspend () -> HoldInferenceEngine
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var screenState by remember { mutableStateOf<ScreenState>(ScreenState.Home) }
    var savedProblems by remember { mutableStateOf(emptyList<ClimbingProblem>()) }
    var isInferring by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("") }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }

    fun refreshProblems() {
        scope.launch(Dispatchers.IO) {
            val list = repository.getAllProblems()
            withContext(Dispatchers.Main) {
                savedProblems = list
            }
        }
    }

    LaunchedEffect(Unit) {
        refreshProblems()
    }

    fun runInferenceAndOpenEditor(bitmap: Bitmap) {
        isInferring = true
        statusMessage = "YOLO11m-seg 순전파 추론 중 (홀드·볼륨 분할)..."
        scope.launch(Dispatchers.Default) {
            try {
                val scaled = scaleDownIfNeeded(bitmap, 1600)
                val engine = inferenceEngineProvider()
                val holds = engine.detectHolds(scaled)
                withContext(Dispatchers.Main) {
                    isInferring = false
                    Toast.makeText(
                        context,
                        "홀드 ${holds.count { it.className == "hold" }}개, 볼륨 ${holds.count { it.className == "volume" }}개 검출 완료",
                        Toast.LENGTH_SHORT
                    ).show()
                    screenState = ScreenState.Editor(scaled, holds)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isInferring = false
                    Toast.makeText(context, "추론 실패: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                val bmp = decodeUriToBitmap(context, uri)
                withContext(Dispatchers.Main) {
                    if (bmp != null) {
                        runInferenceAndOpenEditor(bmp)
                    } else {
                        Toast.makeText(context, "이미지를 불러올 수 없습니다.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    val takePictureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success: Boolean ->
        val uri = pendingCameraUri
        if (success && uri != null) {
            scope.launch(Dispatchers.IO) {
                val bmp = decodeUriToBitmap(context, uri)
                withContext(Dispatchers.Main) {
                    if (bmp != null) {
                        runInferenceAndOpenEditor(bmp)
                    }
                }
            }
        }
    }

    fun launchCamera() {
        val file = File(context.cacheDir, "capture_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        pendingCameraUri = uri
        takePictureLauncher.launch(uri)
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted: Boolean ->
        if (granted) {
            launchCamera()
        } else {
            Toast.makeText(context, "카메라 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF12161A))) {
        when (val state = screenState) {
            is ScreenState.Home -> {
                HomeScreen(
                    problems = savedProblems,
                    onPickGallery = {
                        photoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    onTakePhoto = {
                        val hasPerm = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.CAMERA
                        ) == PackageManager.PERMISSION_GRANTED
                        if (hasPerm) launchCamera()
                        else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    },
                    onOpenProblem = { problem ->
                        scope.launch(Dispatchers.IO) {
                            val bmp = BitmapFactory.decodeFile(problem.imagePath)
                            withContext(Dispatchers.Main) {
                                if (bmp != null) {
                                    screenState = ScreenState.Solver(problem, bmp)
                                } else {
                                    Toast.makeText(context, "저장된 이미지를 찾을 수 없습니다.", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    },
                    onDeleteProblem = { problem ->
                        scope.launch(Dispatchers.IO) {
                            repository.deleteProblem(problem)
                            val updated = repository.getAllProblems()
                            withContext(Dispatchers.Main) {
                                savedProblems = updated
                            }
                        }
                    }
                )
            }

            is ScreenState.Editor -> {
                BackHandler { screenState = ScreenState.Home }
                EditorScreen(
                    bitmap = state.bitmap,
                    initialHolds = state.holds,
                    existingProblem = state.existingProblem,
                    onCancel = { screenState = ScreenState.Home },
                    onSave = { title, grade, mode, editedHolds, selectedList ->
                        scope.launch(Dispatchers.IO) {
                            val imgPath = state.existingProblem?.imagePath
                                ?: repository.saveWallBitmap(state.bitmap)
                            val problem = ClimbingProblem(
                                id = state.existingProblem?.id ?: 0L,
                                title = title,
                                grade = grade,
                                mode = mode,
                                imagePath = imgPath,
                                imageWidth = state.bitmap.width,
                                imageHeight = state.bitmap.height,
                                holds = editedHolds,
                                selectedHolds = selectedList
                            )
                            repository.saveProblem(problem)
                            val updated = repository.getAllProblems()
                            withContext(Dispatchers.Main) {
                                savedProblems = updated
                                Toast.makeText(context, "문제가 저장되었습니다.", Toast.LENGTH_SHORT).show()
                                screenState = ScreenState.Home
                            }
                        }
                    }
                )
            }

            is ScreenState.Solver -> {
                BackHandler { screenState = ScreenState.Home }
                SolverScreen(
                    problem = state.problem,
                    bitmap = state.bitmap,
                    onBack = { screenState = ScreenState.Home },
                    onEdit = {
                        screenState = ScreenState.Editor(
                            bitmap = state.bitmap,
                            holds = state.problem.holds,
                            existingProblem = state.problem
                        )
                    }
                )
            }
        }

        if (isInferring) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xBB000000))
                    .clickable(enabled = false) {},
                contentAlignment = Alignment.Center
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E242B)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.padding(32.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(color = Color(0xFF00E676))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = statusMessage,
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(
    problems: List<ClimbingProblem>,
    onPickGallery: () -> Unit,
    onTakePhoto: () -> Unit,
    onOpenProblem: (ClimbingProblem) -> Unit,
    onDeleteProblem: (ClimbingProblem) -> Unit
) {
    Scaffold(
        containerColor = Color(0xFF12161A),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "AutoHold · HoldCraft",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )
                        Text(
                            text = "AI 클라이밍 홀드 세그멘테이션 & 나만의 루트 메이커",
                            color = Color(0xFF90A4AE),
                            fontSize = 12.sp
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1A2026))
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            // 상단 액션 카드 (사진 촬영 / 갤러리 업로드 / 샘플 암벽 테스트)
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E262E)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "새 클라이밍 문제 만들기",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "암벽 사진을 찍거나 업로드하면 YOLO11m-seg 모델이 홀드와 볼륨을 자동 추출합니다.",
                        color = Color(0xFFB0BEC5),
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onTakePhoto,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00C853)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("카메라 촬영", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = onPickGallery,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00B0FF)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("갤러리 업로드", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = "저장된 문제 목록 (${problems.size})",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
            Spacer(modifier = Modifier.height(10.dp))

            if (problems.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "아직 저장된 문제가 없습니다.\n위 버튼으로 암벽 이미지를 불러와 첫 문제를 만들어 보세요.",
                        color = Color(0xFF78909C),
                        fontSize = 14.sp
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(problems, key = { it.id }) { problem ->
                        ProblemItemCard(
                            problem = problem,
                            onClick = { onOpenProblem(problem) },
                            onDelete = { onDeleteProblem(problem) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProblemItemCard(
    problem: ClimbingProblem,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val thumbBitmap = remember(problem.imagePath) {
        val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
        BitmapFactory.decodeFile(problem.imagePath, opts)
    }
    val dateFormat = remember { SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.getDefault()) }

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E262E)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (thumbBitmap != null) {
                Image(
                    bitmap = thumbBitmap.asImageBitmap(),
                    contentDescription = problem.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF263238))
                )
            }

            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        color = if (problem.mode == RouteMode.BOULDERING) Color(0xFF00E676) else Color(0xFFFFD600),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = if (problem.mode == RouteMode.BOULDERING) "볼더링" else "순서(지구력)",
                            color = Color.Black,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        color = Color(0xFF37474F),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = problem.grade,
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = problem.title,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "선택 홀드 ${problem.selectedHolds.size}개 · ${dateFormat.format(Date(problem.createdAt))}",
                    color = Color(0xFF90A4AE),
                    fontSize = 12.sp
                )
            }

            TextButton(onClick = onDelete) {
                Text("삭제", color = Color(0xFFFF5252), fontSize = 13.sp)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorScreen(
    bitmap: Bitmap,
    initialHolds: List<HoldCandidate>,
    existingProblem: ClimbingProblem?,
    onCancel: () -> Unit,
    onSave: (
        title: String,
        grade: String,
        mode: RouteMode,
        editedHolds: List<HoldCandidate>,
        selected: List<SelectedHold>
    ) -> Unit
) {
    val context = LocalContext.current
    var holdsList by remember { mutableStateOf(initialHolds) }
    var activeTool by remember { mutableStateOf(EditorTool.SELECT_ROUTE) }
    var mode by remember { mutableStateOf(existingProblem?.mode ?: RouteMode.BOULDERING) }
    var activeBrush by remember { mutableStateOf(HoldRole.START) }
    var selectedMap by remember {
        mutableStateOf<Map<Int, SelectedHold>>(
            existingProblem?.selectedHolds?.associateBy { it.holdId } ?: emptyMap()
        )
    }
    // 실행 취소(Undo)를 위한 히스토리 스택
    var historyStack by remember {
        mutableStateOf<List<Pair<List<HoldCandidate>, Map<Int, SelectedHold>>>>(emptyList())
    }

    fun pushHistory() {
        historyStack = (historyStack + (holdsList to selectedMap)).takeLast(20)
    }

    fun commitManualHold(polygonPoints: List<Point2D>) {
        if (polygonPoints.size < 3) return
        val minX = polygonPoints.minOf { it.x }.coerceIn(0, bitmap.width - 1)
        val maxX = polygonPoints.maxOf { it.x }.coerceIn(0, bitmap.width - 1)
        val minY = polygonPoints.minOf { it.y }.coerceIn(0, bitmap.height - 1)
        val maxY = polygonPoints.maxOf { it.y }.coerceIn(0, bitmap.height - 1)
        if (maxX - minX < 4 || maxY - minY < 4) return

        pushHistory()
        val nextId = (holdsList.maxOfOrNull { it.id } ?: 0) + 1
        val rawNewHold = HoldCandidate(
            id = nextId,
            className = "hold",
            confidence = 1.0f,
            bbox = listOf(minX, minY, maxX, maxY),
            polygon = polygonPoints
        )
        val enrichedHold = HoldColorExtractor.enrichSingleHold(bitmap, rawNewHold)
        holdsList = holdsList + enrichedHold
        Toast.makeText(
            context,
            "새 홀드 추가 완료 (${enrichedHold.colorName})",
            Toast.LENGTH_SHORT
        ).show()
    }

    var showUnselectedHolds by remember { mutableStateOf(true) }
    var showColorLabels by remember { mutableStateOf(true) }
    var dimBackground by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }

    var titleInput by remember { mutableStateOf(existingProblem?.title ?: "") }
    var gradeInput by remember { mutableStateOf(existingProblem?.grade ?: "V3") }

    Scaffold(
        containerColor = Color(0xFF101418),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = if (existingProblem == null) "문제 만들기" else "문제 수정",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "전체 홀드 ${holdsList.size}개 · 선택 ${selectedMap.size}개",
                            color = Color(0xFF90A4AE),
                            fontSize = 11.sp
                        )
                    }
                },
                navigationIcon = {
                    TextButton(onClick = onCancel) {
                        Text("취소", color = Color(0xFFB0BEC5))
                    }
                },
                actions = {
                    if (historyStack.isNotEmpty()) {
                        TextButton(
                            onClick = {
                                val last = historyStack.last()
                                holdsList = last.first
                                selectedMap = last.second
                                historyStack = historyStack.dropLast(1)
                            }
                        ) {
                            Text("되돌리기", color = Color(0xFF80D8FF), fontSize = 12.sp)
                        }
                    }
                    TextButton(
                        onClick = {
                            pushHistory()
                            holdsList = initialHolds
                            selectedMap = emptyMap()
                        }
                    ) {
                        Text("초기화", color = Color(0xFFFF8A80), fontSize = 12.sp)
                    }
                    Button(
                        onClick = { showSaveDialog = true },
                        enabled = selectedMap.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676)),
                        modifier = Modifier.padding(end = 8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text("저장 (${selectedMap.size})", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1A2026))
            )
        },
        bottomBar = {
            Surface(color = Color(0xFF1A2026)) {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
                    // 1. 도구 툴박스 (루트 선택 / 홀드 그리기(올가미·원형) / 오검출 홀드 삭제)
                    Text(
                        text = "편집 도구 툴박스 (객체 보정 & 루트 세팅)",
                        color = Color(0xFF80D8FF),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ToolBoxButton(
                            label = "루트 선택",
                            activeColor = Color(0xFF00E676),
                            selected = activeTool == EditorTool.SELECT_ROUTE,
                            onClick = { activeTool = EditorTool.SELECT_ROUTE },
                            modifier = Modifier.weight(1f)
                        )
                        ToolBoxButton(
                            label = "+ 홀드 그리기",
                            activeColor = Color(0xFF00E5FF),
                            selected = activeTool == EditorTool.DRAW_LASSO,
                            onClick = { activeTool = EditorTool.DRAW_LASSO },
                            modifier = Modifier.weight(1f)
                        )
                        ToolBoxButton(
                            label = "객체 삭제",
                            activeColor = Color(0xFFFF5252),
                            selected = activeTool == EditorTool.DELETE_HOLD,
                            onClick = { activeTool = EditorTool.DELETE_HOLD },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // 2. 선택된 도구별 세부 설정 영역
                    when (activeTool) {
                        EditorTool.SELECT_ROUTE -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FilterChip(
                                    selected = mode == RouteMode.BOULDERING,
                                    onClick = { mode = RouteMode.BOULDERING },
                                    label = { Text("모드 1: 볼더링 (Start/Move/Top)", fontSize = 11.sp) },
                                    modifier = Modifier.weight(1f)
                                )
                                FilterChip(
                                    selected = mode == RouteMode.SEQUENCE,
                                    onClick = {
                                        mode = RouteMode.SEQUENCE
                                        var idx = 1
                                        selectedMap = selectedMap.mapValues { (_, v) ->
                                            if (v.orderNumber > 0) v else v.copy(orderNumber = idx++)
                                        }
                                    },
                                    label = { Text("모드 2: 순서 지정 (1, 2, 3...)", fontSize = 11.sp) },
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            if (mode == RouteMode.BOULDERING) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("브러시:", color = Color(0xFFB0BEC5), fontSize = 12.sp)
                                    RoleChip(
                                        label = "START (초록)",
                                        color = Color(0xFF00E676),
                                        selected = activeBrush == HoldRole.START,
                                        onClick = { activeBrush = HoldRole.START }
                                    )
                                    RoleChip(
                                        label = "MOVE (파랑)",
                                        color = Color(0xFF00B0FF),
                                        selected = activeBrush == HoldRole.MOVE,
                                        onClick = { activeBrush = HoldRole.MOVE }
                                    )
                                    RoleChip(
                                        label = "TOP (빨강)",
                                        color = Color(0xFFFF1744),
                                        selected = activeBrush == HoldRole.TOP,
                                        onClick = { activeBrush = HoldRole.TOP }
                                    )
                                }
                            } else {
                                Text(
                                    text = "홀드를 터치한 순서대로 1, 2, 3... 번호가 부여됩니다. 재터치 시 선택이 해제됩니다.",
                                    color = Color(0xFFFFD600),
                                    fontSize = 12.sp
                                )
                            }
                        }

                        EditorTool.DRAW_LASSO -> {
                            Text(
                                text = "홀드 테두리를 한 바퀴 둘러 선이 닫히게(폐루프) 그리면 닫힌 내부 영역만 홀드 윤곽선으로 생성됩니다.",
                                color = Color(0xFF00E5FF),
                                fontSize = 12.sp
                            )
                        }

                        EditorTool.DELETE_HOLD -> {
                            Text(
                                text = "잘못 인식된 홀드(오검출 객체)를 터치하면 즉시 삭제됩니다. 실수로 지웠다면 상단 '되돌리기'를 누르세요.",
                                color = Color(0xFFFF5252),
                                fontSize = 12.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = showUnselectedHolds,
                                onCheckedChange = { showUnselectedHolds = it }
                            )
                            Text("인식 홀드 색상 표시", color = Color.White, fontSize = 11.sp)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = showColorLabels,
                                onCheckedChange = { showColorLabels = it }
                            )
                            Text("색상명 태그", color = Color.White, fontSize = 11.sp)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = dimBackground,
                                onCheckedChange = { dimBackground = it }
                            )
                            Text("배경 어둡게", color = Color.White, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    ) { padding ->
        InteractiveWallCanvas(
            bitmap = bitmap,
            holds = holdsList,
            selectedMap = selectedMap,
            mode = mode,
            activeTool = activeTool,
            readOnly = false,
            showUnselectedHolds = showUnselectedHolds,
            showColorLabels = showColorLabels,
            dimBackground = dimBackground,
            onHoldTapped = { hold ->
                if (activeTool == EditorTool.DELETE_HOLD) {
                    pushHistory()
                    holdsList = holdsList.filterNot { it.id == hold.id }
                    val removedSel = selectedMap[hold.id]
                    if (removedSel != null) {
                        val rem = selectedMap - hold.id
                        selectedMap = if (mode == RouteMode.SEQUENCE) {
                            rem.mapValues { (_, v) ->
                                if (v.orderNumber > removedSel.orderNumber) {
                                    v.copy(orderNumber = v.orderNumber - 1)
                                } else v
                            }
                        } else rem
                    }
                    Toast.makeText(context, "홀드(${hold.colorName.ifEmpty { "객체" }}) 삭제됨", Toast.LENGTH_SHORT).show()
                    return@InteractiveWallCanvas
                }

                // SELECT_ROUTE 모드: 루트 홀드 선택/해제
                pushHistory()
                val current = selectedMap[hold.id]
                if (mode == RouteMode.BOULDERING) {
                    selectedMap = if (current != null && current.role == activeBrush) {
                        selectedMap - hold.id
                    } else {
                        val nextOrder = (selectedMap.values.maxOfOrNull { it.orderNumber } ?: 0) + 1
                        selectedMap + (hold.id to SelectedHold(
                            holdId = hold.id,
                            role = activeBrush,
                            orderNumber = current?.orderNumber ?: nextOrder
                        ))
                    }
                } else {
                    if (current != null) {
                        val removedOrder = current.orderNumber
                        val remaining = (selectedMap - hold.id).mapValues { (_, v) ->
                            if (v.orderNumber > removedOrder) v.copy(orderNumber = v.orderNumber - 1)
                            else v
                        }
                        selectedMap = remaining
                    } else {
                        val nextOrder = selectedMap.size + 1
                        selectedMap = selectedMap + (hold.id to SelectedHold(
                            holdId = hold.id,
                            role = HoldRole.MOVE,
                            orderNumber = nextOrder
                        ))
                    }
                }
            },
            onAddManualHold = { polygonPoints ->
                commitManualHold(polygonPoints)
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        )
    }

    if (showSaveDialog) {
        val grades = listOf("V0", "V1", "V2", "V3", "V4", "V5", "V6", "V7+", "지구력")
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text("클라이밍 문제 저장") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = titleInput,
                        onValueChange = { titleInput = it },
                        label = { Text("문제 이름 (예: 오버행 파란 볼륨 루트)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("난이도 (Grade) 선택:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        grades.take(5).forEach { g ->
                            FilterChip(
                                selected = gradeInput == g,
                                onClick = { gradeInput = g },
                                label = { Text(g, fontSize = 12.sp) }
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        grades.drop(5).forEach { g ->
                            FilterChip(
                                selected = gradeInput == g,
                                onClick = { gradeInput = g },
                                label = { Text(g, fontSize = 12.sp) }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val finalTitle = titleInput.trim().ifEmpty {
                            "나만의 문제 (${SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(Date())})"
                        }
                        showSaveDialog = false
                        onSave(
                            finalTitle,
                            gradeInput,
                            mode,
                            holdsList,
                            selectedMap.values.sortedBy { it.orderNumber }
                        )
                    }
                ) {
                    Text("저장하기")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) {
                    Text("취소")
                }
            }
        )
    }
}

@Composable
private fun ToolBoxButton(
    label: String,
    activeColor: Color,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = if (selected) activeColor else Color(0xFF263238),
        shape = RoundedCornerShape(10.dp),
        modifier = modifier.clickable { onClick() }
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                color = if (selected) Color.Black else Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun RoleChip(
    label: String,
    color: Color,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        color = if (selected) color else Color(0xFF263238),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!selected) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(color)
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            Text(
                text = label,
                color = if (selected) Color.Black else Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SolverScreen(
    problem: ClimbingProblem,
    bitmap: Bitmap,
    onBack: () -> Unit,
    onEdit: () -> Unit
) {
    var showAllDetectedHolds by remember { mutableStateOf(false) }
    var dimBackground by remember { mutableStateOf(true) }
    var currentStepProgress by remember { mutableIntStateOf(problem.selectedHolds.size) }

    val selectedMap = remember(problem, currentStepProgress) {
        if (problem.mode == RouteMode.SEQUENCE) {
            problem.selectedHolds
                .filter { it.orderNumber <= currentStepProgress }
                .associateBy { it.holdId }
        } else {
            problem.selectedHolds.associateBy { it.holdId }
        }
    }

    Scaffold(
        containerColor = Color(0xFF101418),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "${problem.title} (${problem.grade})",
                            color = Color.White,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (problem.mode == RouteMode.BOULDERING) {
                                "볼더링 모드 · 홀드 ${problem.selectedHolds.size}개"
                            } else {
                                "순서(지구력) 모드 · 총 ${problem.selectedHolds.size}수"
                            },
                            color = Color(0xFF90A4AE),
                            fontSize = 12.sp
                        )
                    }
                },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("목록", color = Color(0xFF80D8FF))
                    }
                },
                actions = {
                    TextButton(onClick = onEdit) {
                        Text("루트 수정", color = Color(0xFF00E676), fontWeight = FontWeight.Bold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1A2026))
            )
        },
        bottomBar = {
            Surface(color = Color(0xFF1A2026)) {
                Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                    if (problem.mode == RouteMode.SEQUENCE && problem.selectedHolds.size > 1) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "수순 재생: 1 ~ $currentStepProgress / ${problem.selectedHolds.size}",
                                color = Color(0xFFFFD600),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedButton(
                                    onClick = { currentStepProgress = (currentStepProgress - 1).coerceAtLeast(1) },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text("이전 수", fontSize = 12.sp)
                                }
                                OutlinedButton(
                                    onClick = {
                                        currentStepProgress =
                                            (currentStepProgress + 1).coerceAtMost(problem.selectedHolds.size)
                                    },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text("다음 수", fontSize = 12.sp)
                                }
                                Button(
                                    onClick = { currentStepProgress = problem.selectedHolds.size },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text("전체 보기", fontSize = 12.sp)
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = dimBackground,
                                onCheckedChange = { dimBackground = it }
                            )
                            Text("문제 홀드만 강조 (배경 어둡게)", color = Color.White, fontSize = 12.sp)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = showAllDetectedHolds,
                                onCheckedChange = { showAllDetectedHolds = it }
                            )
                            Text("전체 홀드 보기", color = Color.White, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    ) { padding ->
        InteractiveWallCanvas(
            bitmap = bitmap,
            holds = problem.holds,
            selectedMap = selectedMap,
            mode = problem.mode,
            readOnly = true,
            showUnselectedHolds = showAllDetectedHolds,
            dimBackground = dimBackground,
            onHoldTapped = {},
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        )
    }
}

private fun decodeUriToBitmap(context: Context, uri: Uri): Bitmap? {
    return try {
        val raw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = true
            }
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
        }
        raw?.copy(Bitmap.Config.ARGB_8888, false)
    } catch (e: Exception) {
        null
    }
}

private fun scaleDownIfNeeded(bitmap: Bitmap, maxDimension: Int): Bitmap {
    val w = bitmap.width
    val h = bitmap.height
    if (w <= maxDimension && h <= maxDimension) return bitmap
    val ratio = maxDimension.toFloat() / maxOf(w, h).toFloat()
    val nw = (w * ratio).toInt().coerceAtLeast(1)
    val nh = (h * ratio).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(bitmap, nw, nh, true)
}

