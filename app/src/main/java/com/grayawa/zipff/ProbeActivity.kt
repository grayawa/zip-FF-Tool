package com.grayawa.zipff

import android.app.Activity
import android.os.Bundle
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.platform.ComposeView
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/** 只为验证 MiuiX / Compose 工具链能编译，暂不对外暴露入口。 */
class ProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            ComposeView(this).apply {
                setContent {
                    MiuixTheme(colors = lightColorScheme()) {
                        Column {
                            Text("probe")
                        }
                    }
                }
            }
        )
    }
}
