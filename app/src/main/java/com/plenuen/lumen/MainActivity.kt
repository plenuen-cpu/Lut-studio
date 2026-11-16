package com.plenuen.lumen

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.plenuen.lumen.ui.EditorScreen
import com.plenuen.lumen.ui.EditorViewModel
import com.plenuen.lumen.ui.LumenTheme
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class MainActivity : ComponentActivity() {

    // 마지막 그물: 어떤 코루틴이 실패해도 프로세스를 죽이지 않고 기록만 남긴다.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, error ->
            LumenDiagnostics.record("scope", error)
        },
    )
    private lateinit var editor: EditorViewModel

    private val pickPhoto = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) editor.openPhoto(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 현장에서 앱이 닫혔을 때 남는 단서가 없던 문제: 이제 마지막 오류가 파일에 남는다.
        LumenDiagnostics.installProcessHandler(this)
        // targetSdk 35는 edge-to-edge가 강제된다. 창을 시스템 바 아래까지 그리고, 여백은 Compose가
        // 인셋(navigationBars·ime)으로 잡아야 키보드가 입력창을 가리지 않는다.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        editor = EditorViewModel(applicationContext, scope)
        setContent {
            LumenTheme {
                EditorScreen(
                    viewModel = editor,
                    onPickPhoto = {
                        pickPhoto.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                )
            }
        }
    }

    override fun onDestroy() {
        editor.release()
        scope.cancel()
        super.onDestroy()
    }
}
