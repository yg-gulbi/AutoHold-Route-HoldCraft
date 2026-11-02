package com.autohold.holdcraft

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import com.autohold.holdcraft.data.ProblemRepository
import com.autohold.holdcraft.inference.HoldInferenceEngine
import com.autohold.holdcraft.ui.HoldCraftApp
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MainActivity : ComponentActivity() {
    private lateinit var repository: ProblemRepository
    private var inferenceEngine: HoldInferenceEngine? = null
    private val engineMutex = Mutex()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = ProblemRepository(applicationContext)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                HoldCraftApp(
                    repository = repository,
                    inferenceEngineProvider = {
                        engineMutex.withLock {
                            inferenceEngine ?: HoldInferenceEngine(applicationContext).also {
                                inferenceEngine = it
                            }
                        }
                    }
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        inferenceEngine?.close()
        inferenceEngine = null
    }
}

