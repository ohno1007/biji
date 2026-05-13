package com.biji.notes.ui.webview

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import com.biji.notes.ui.glass.bouncyClickable

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebViewScreen(
    initialUrl: String,
    onClose: () -> Unit
) {
    val ctx = LocalContext.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    var currentUrl by remember { mutableStateOf(initialUrl) }
    var pageTitle by remember { mutableStateOf("") }
    var progress by remember { mutableFloatStateOf(0f) }
    var canGoBack by remember { mutableStateOf(false) }

    BackHandler {
        if (webView?.canGoBack() == true) webView?.goBack()
        else onClose()
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        TopBar(
            title = pageTitle.ifBlank { currentUrl },
            host = currentUrl.toUri().host.orEmpty(),
            canGoBack = canGoBack,
            onBack = {
                if (webView?.canGoBack() == true) webView?.goBack() else onClose()
            },
            onReload = { webView?.reload() },
            onOpenExternal = {
                runCatching {
                    ctx.startActivity(
                        android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            currentUrl.toUri()
                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            },
            onClose = onClose,
            modifier = Modifier.statusBarsPadding()
        )
        if (progress in 0.01f..0.99f) {
            LinearProgressIndicator(
                progress = { progress },
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth().height(2.dp)
            )
        } else {
            Spacer(Modifier.height(2.dp))
        }

        AndroidView(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            factory = { c ->
                WebView(c).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    settings.userAgentString = settings.userAgentString
                        ?.replace("; wv", "") // pretend to be Chrome, not WebView
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            progress = newProgress / 100f
                        }
                        override fun onReceivedTitle(view: WebView?, title: String?) {
                            pageTitle = title.orEmpty()
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            if (url != null) currentUrl = url
                            progress = 0.05f
                        }
                        override fun onPageFinished(view: WebView?, url: String?) {
                            if (url != null) currentUrl = url
                            canGoBack = view?.canGoBack() ?: false
                            progress = 1f
                        }
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            // keep navigation inside the WebView
                            return false
                        }
                    }
                    loadUrl(initialUrl)
                    webView = this
                }
            },
            update = { /* state changes are pushed through the WebView API */ }
        )

        Spacer(Modifier.navigationBarsPadding())
    }
}

@Composable
private fun TopBar(
    title: String,
    host: String,
    canGoBack: Boolean,
    onBack: () -> Unit,
    onReload: () -> Unit,
    onOpenExternal: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        IconChip(
            icon = Icons.AutoMirrored.Rounded.ArrowBack,
            contentDescription = if (canGoBack) "返回上一页" else "退出",
            onClick = onBack
        )
        Spacer(Modifier.width(4.dp))
        Column(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(50))
                .background(cs.surfaceContainerHigh)
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = cs.onSurface,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (host.isNotBlank()) {
                Text(
                    host,
                    style = MaterialTheme.typography.labelLarge,
                    color = cs.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
        IconChip(
            icon = Icons.Rounded.Refresh,
            contentDescription = "刷新",
            onClick = onReload
        )
        IconChip(
            icon = Icons.Outlined.OpenInNew,
            contentDescription = "外部浏览器打开",
            onClick = onOpenExternal
        )
        IconChip(
            icon = Icons.Rounded.Close,
            contentDescription = "关闭",
            onClick = onClose
        )
    }
}

@Composable
private fun IconChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String?,
    onClick: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .bouncyClickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(18.dp),
            tint = cs.onSurface
        )
    }
}
