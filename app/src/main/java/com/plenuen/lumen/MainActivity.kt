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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class MainActivity : ComponentActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var editor: EditorViewModel

    private val pickPhoto = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) editor.openPhoto(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
