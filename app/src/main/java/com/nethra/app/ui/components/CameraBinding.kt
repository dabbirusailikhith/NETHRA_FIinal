package com.nethra.app.ui.components

import android.content.Context
import android.util.Size
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CancellationException

object CameraSetup {

    /** Everything is bound at 16:9 so the portrait preview shows exactly what is analysed/recorded. */
    val ratio169: ResolutionSelector = ResolutionSelector.Builder()
        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
        .build()

    fun analysisSelector(): ResolutionSelector = ResolutionSelector.Builder()
        .setAspectRatioStrategy(AspectRatioStrategy(AspectRatio.RATIO_16_9, AspectRatioStrategy.FALLBACK_RULE_AUTO))
        .setResolutionStrategy(ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
        .build()

    fun preview(view: PreviewView): Preview =
        Preview.Builder().setResolutionSelector(ratio169).build().also { it.setSurfaceProvider(view.surfaceProvider) }

    /**
     * Binds [useCases] to [owner]'s lifecycle (CameraX then stops the camera when the
     * app is backgrounded and restarts it on return).
     * @return the provider (for unbinding) or throws with a creator-readable message.
     */
    suspend fun bind(
        context: Context,
        owner: LifecycleOwner,
        selector: CameraSelector,
        vararg useCases: UseCase
    ): ProcessCameraProvider {
        val provider = try {
            ProcessCameraProvider.awaitInstance(context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException("The camera service didn't start (${e.javaClass.simpleName}). Close other camera apps and try again.")
        }
        val available = runCatching { provider.hasCamera(selector) }.getOrDefault(false)
        if (!available) {
            val which = if (selector == CameraSelector.DEFAULT_FRONT_CAMERA) "front" else "rear"
            throw IllegalStateException("This phone didn't report a $which camera.")
        }
        provider.unbindAll()
        try {
            provider.bindToLifecycle(owner, selector, *useCases)
        } catch (e: Exception) {
            throw IllegalStateException("Couldn't open the camera: ${e.message ?: e.javaClass.simpleName}. Another app may be using it.")
        }
        return provider
    }
}
