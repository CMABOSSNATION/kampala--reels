package com.cma.kreels

import android.content.ComponentCallbacks2
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.cma.kreels.render.ReelRenderer
import com.cma.kreels.ui.EditorScreen
import com.cma.kreels.ui.EditorState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var renderer: ReelRenderer
    private lateinit var st: EditorState

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        renderer = ReelRenderer(applicationContext)
        st = EditorState(applicationContext, renderer)
        setContent { MaterialTheme { Surface { EditorScreen(renderer, st) } } }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) st.status = "Low memory: close other apps or use a smaller avatar/textures."
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) CoroutineScope(Dispatchers.Default).launch { runCatching { renderer.release() } }
    }
}
