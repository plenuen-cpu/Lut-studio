package com.plenuen.filmcam

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.plenuen.filmcam.ui.CameraScreen
import com.plenuen.filmcam.ui.FilmCamTheme
import com.plenuen.filmcam.ui.FilmPalette

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            FilmCamTheme {
                FilmCamRoot()
            }
        }
    }
}

@Composable
private fun FilmCamRoot(vm: FilmCamViewModel = viewModel()) {
    val context = LocalContext.current
    var cameraGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var cameraAsked by remember { mutableStateOf(false) }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> cameraGranted = granted }

    val storageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) vm.showMessage("사진을 저장하려면 저장 공간 권한이 필요합니다.")
    }

    LaunchedEffect(Unit) {
        if (!cameraGranted) {
            cameraAsked = true
            cameraLauncher.launch(Manifest.permission.CAMERA)
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val permission = Manifest.permission.WRITE_EXTERNAL_STORAGE
            if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
                storageLauncher.launch(permission)
            }
        }
    }

    if (cameraGranted) {
        CameraScreen(vm = vm)
    } else {
        PermissionGate(
            asked = cameraAsked,
            onRequest = { cameraLauncher.launch(Manifest.permission.CAMERA) },
        )
    }
}

@Composable
private fun PermissionGate(asked: Boolean, onRequest: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(FilmPalette.Background)
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "FilmCam",
                color = FilmPalette.Text,
                fontSize = 22.sp,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                text = "필름 57종을 실시간으로 입혀 촬영합니다.\n카메라 권한이 필요합니다.",
                color = FilmPalette.TextDim,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 14.dp, bottom = 22.dp),
            )
            Button(
                onClick = onRequest,
                colors = ButtonDefaults.buttonColors(
                    containerColor = FilmPalette.Accent,
                    contentColor = Color(0xFF141414),
                ),
                shape = RoundedCornerShape(50),
                modifier = Modifier.width(180.dp),
            ) {
                Text(text = if (asked) "권한 다시 요청" else "카메라 허용", fontSize = 13.sp)
            }
            if (asked) {
                Text(
                    text = "설정 > 앱 > FilmCam > 권한에서 카메라를 허용할 수 있습니다.",
                    color = FilmPalette.TextDim,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 18.dp),
                )
            }
        }
    }
}
