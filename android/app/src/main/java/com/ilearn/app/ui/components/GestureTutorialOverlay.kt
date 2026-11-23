package com.ilearn.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Modern non-intrusive gesture hints overlay educating the student about player gestures.
 */
@Composable
fun GestureTutorialOverlay(
    isVisible: Boolean,
    onDismiss: () -> Unit
) {
    LaunchedEffect(isVisible) {
        if (isVisible) {
            delay(4000L)
            onDismiss()
        }
    }

    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.65f))
                .padding(24.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 60.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left Gesture: Brightness
                Surface(
                    color = Color.White.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.width(130.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Default.BrightnessMedium, contentDescription = null, tint = Color(0xFFFBBF24), modifier = Modifier.size(32.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("السطوع ☀️", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text("اسحب عمودياً على اليسار", color = Color(0xFFCBD5E1), fontSize = 11.sp, textAlign = TextAlign.Center)
                    }
                }

                // Center Gesture: Double Tap Seek
                Surface(
                    color = Color.White.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.width(150.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Default.TouchApp, contentDescription = null, tint = Color(0xFF06B6D4), modifier = Modifier.size(32.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("نقر مزدوج ⏩", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text("تقديم أو تأخير 10 ثوانٍ", color = Color(0xFFCBD5E1), fontSize = 11.sp, textAlign = TextAlign.Center)
                    }
                }

                // Right Gesture: Volume
                Surface(
                    color = Color.White.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.width(130.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, tint = Color(0xFF10B981), modifier = Modifier.size(32.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("مستوى الصوت 🔊", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text("اسحب عمودياً على اليمين", color = Color(0xFFCBD5E1), fontSize = 11.sp, textAlign = TextAlign.Center)
                    }
                }
            }

            // Dismiss Button at bottom
            Button(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("فهمت ذلك", fontWeight = FontWeight.Bold)
            }
        }
    }
}

