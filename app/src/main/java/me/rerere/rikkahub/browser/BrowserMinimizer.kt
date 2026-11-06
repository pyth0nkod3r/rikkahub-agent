package me.rerere.rikkahub.browser

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.petterp.floatingx.app.appHost
import com.petterp.floatingx.compose.compose
import com.petterp.floatingx.core.FloatingX
import com.petterp.floatingx.core.FxControl
import com.petterp.floatingx.core.FxState
import com.petterp.floatingx.core.animation.FxAnimations
import com.petterp.floatingx.core.layout.FxAdsorb
import com.petterp.floatingx.core.layout.FxGravity
import com.petterp.floatingx.system.permission.FxPermission
import com.petterp.floatingx.system.permission.FxPermissionStrategy
import com.petterp.floatingx.system.systemHost
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Earth
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.ui.theme.RikkahubTheme

/**
 * Manages the minimized state and floating bubble for [BrowserActivity].
 *
 * When minimized, the live WebView in [BrowserActivity] remains alive in the background and bound
 * to [BrowserController], so AI browser tools (in the current conversation or any new chat) can
 * continue operating on the page without interruption.
 */
object BrowserMinimizer {
    private const val TAG = "BrowserMinimizer"
    private const val BUBBLE_TAG = "browser_floating_bubble"

    data class State(
        val isMinimized: Boolean = false,
        val url: String = "",
        val title: String = "",
    )

    private val _state = MutableStateFlow(State())
    val stateFlow: StateFlow<State> = _state.asStateFlow()

    @Volatile
    private var control: FxControl? = null

    fun isMinimized(): Boolean = _state.value.isMinimized

    /**
     * Minimizes [BrowserActivity] into a floating bubble and brings the main app ([RouteActivity])
     * to the foreground. The browser Activity and its WebView stay alive.
     */
    fun minimize(activity: Activity) {
        val app = activity.applicationContext as Application
        val currentUrl = BrowserController.currentUrl() ?: "about:blank"
        val currentTitle = BrowserController.currentTitle().orEmpty()

        _state.value = State(
            isMinimized = true,
            url = currentUrl,
            title = currentTitle,
        )

        showBubble(app)

        try {
            val intent = Intent(activity, RouteActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to reorder RouteActivity to front, moving task to back", e)
            activity.moveTaskToBack(true)
        }
    }

    /**
     * Restores [BrowserActivity] to full screen and hides the floating bubble.
     */
    fun restore(context: Context) {
        _state.value = _state.value.copy(isMinimized = false)
        control?.hide()

        try {
            val intent = BrowserActivity.intent(context).apply {
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to restore BrowserActivity", e)
        }
    }

    /**
     * Completely closes the browser, dismisses the floating bubble, and finishes [BrowserActivity].
     */
    fun close() {
        _state.value = State(isMinimized = false)
        control?.hide()
        control?.cancel()
        control = null

        BrowserActivity.instance?.finish()
    }

    /**
     * Called when [BrowserActivity] comes into the foreground so the floating bubble hides.
     */
    fun onBrowserForeground() {
        if (_state.value.isMinimized) {
            _state.value = _state.value.copy(isMinimized = false)
        }
        control?.hide()
    }

    /**
     * Called when [BrowserActivity] is destroyed.
     */
    fun onBrowserDestroyed(activity: Activity) {
        if (BrowserActivity.instance === activity) {
            _state.value = State(isMinimized = false)
            control?.hide()
            control?.cancel()
            control = null
        }
    }

    /**
     * Updates page info reactively while minimized (e.g. as AI agents navigate).
     */
    fun updatePage(url: String, title: String) {
        if (_state.value.isMinimized) {
            _state.value = _state.value.copy(
                url = url.ifBlank { _state.value.url },
                title = title.ifBlank { _state.value.title },
            )
        }
    }

    private fun showBubble(app: Application) {
        val currentControl = control
        if (currentControl != null && currentControl.state != FxState.CANCELLED) {
            currentControl.show()
            return
        }

        control = FloatingX.install(BUBBLE_TAG) {
            if (FxPermission.isGranted(app)) {
                systemHost(app) { permission(FxPermissionStrategy.skip()) }
            } else {
                appHost(app)
            }
            anchor(FxGravity.CENTER_END, dy = -dp(app, 40f))
            margin(left = dp(app, 8f), right = dp(app, 8f))
            adsorb(FxAdsorb.horizontal())
            animation(FxAnimations.scale())
            compose {
                val state by stateFlow.collectAsState()
                RikkahubTheme {
                    BrowserFloatingBubble(
                        title = state.title,
                        url = state.url,
                        onRestore = { restore(app) },
                        onClose = { close() },
                    )
                }
            }
        }.also {
            it.show()
        }
    }

    private fun dp(context: Context, value: Float): Float =
        value * context.resources.displayMetrics.density
}

@Composable
fun BrowserFloatingBubble(
    title: String,
    url: String,
    onRestore: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val displayTitle = title.ifBlank {
        url.removePrefix("https://").removePrefix("http://").takeWhile { it != '/' && it != '?' }.ifBlank {
            stringResource(R.string.setting_browser_title)
        }
    }

    Box(modifier = Modifier.padding(6.dp)) {
        Row(
            modifier = Modifier
                .shadow(elevation = 6.dp, shape = RoundedCornerShape(20.dp))
                .clip(RoundedCornerShape(20.dp))
                .background(colors.surfaceContainerHigh)
                .border(1.dp, colors.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                .clickable(onClick = onRestore)
                .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = HugeIcons.Earth,
                contentDescription = null,
                tint = colors.primary,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = displayTitle,
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 120.dp),
            )
            IconButton(
                onClick = onClose,
                modifier = Modifier.size(24.dp),
            ) {
                Icon(
                    imageVector = HugeIcons.Cancel01,
                    contentDescription = stringResource(R.string.browser_address_bar_close),
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}
