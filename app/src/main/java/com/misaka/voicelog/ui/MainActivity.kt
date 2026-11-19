package com.misaka.voicelog.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PermissionGate {
                AppRoot()
            }
        }
    }
}

/**
 * 权限闸门。
 *
 * RECORD_AUDIO 是「使用时」权限 —— Android 14+ 规定，
 * 只有应用处于前台（有可见 Activity）时才能创建 microphone 类型的前台服务，
 * 因此权限必须在界面上拿，拿完再在界面上点按钮启动服务。
 */
@Composable
private fun PermissionGate(content: @Composable () -> Unit) {
    val ctx = LocalContext.current

    val requested = remember {
        buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            /* 蓝牙麦克风要用 BLUETOOTH_CONNECT（Android 12+）。
               ⚠️ 它只是「可选」：拒绝了照样能用手机麦录，所以不参与下面的 granted 判定。
               之前没申报这个权限，导致「选蓝牙麦」实际回落内置麦却显示成功。 */
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
    }

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        // 通知、蓝牙权限被拒都不影响手机麦录音，只有麦克风是硬门槛
        granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(requested.toTypedArray())
    }

    if (granted) {
        content()
    } else {
        Box(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("需要麦克风权限才能开始", color = VlColor.Text, fontSize = 13.sp)
        }
    }
}
