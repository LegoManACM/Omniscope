package dev.omniscope.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.omniscope.R

/** Placeholder: driving mode comes later. */
@Composable
fun NavScreen() {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        PhIcon(R.drawable.ph_steering_wheel, Color(0xFF8C9EFF), 64.dp)
        Text("Driving mode", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 16.dp))
        Text("Coming later: a map tuned for driving, with few, large, easy-to-read alerts, and optional routes that steer " +
            "around plate-reader cameras (offline routing with BRouter).",
            color = Color(0xFF9E9E9E), fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
    }
}
