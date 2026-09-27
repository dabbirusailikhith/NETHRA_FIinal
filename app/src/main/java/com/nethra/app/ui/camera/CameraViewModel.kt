package com.nethra.app.ui.camera

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nethra.app.AppContainer
import com.nethra.app.speech.Speaker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class CameraUi(
    val front: Boolean = false,
    val cameraError: String? = null
)

/** The plain CAMERA mode: record video with either lens, by button or by voice. */
class CameraViewModel(app: Application, c: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow(CameraUi())
    val ui: StateFlow<CameraUi> = _ui

    private val speaker = Speaker(app)
    val recorder = HandsFreeRecorder(app, c, viewModelScope, speaker, "NethraCamera")

    /** Switch lens (not during a take — CameraX can't move a recording between cameras). */
    fun flip() {
        if (recorder.state.value.isRecordingActive || recorder.state.value.countdown != null) return
        _ui.update { it.copy(front = !it.front) }
    }

    fun onCameraError(message: String?) = _ui.update { it.copy(cameraError = message) }

    override fun onCleared() {
        recorder.release()
        speaker.shutdown()
    }
}
