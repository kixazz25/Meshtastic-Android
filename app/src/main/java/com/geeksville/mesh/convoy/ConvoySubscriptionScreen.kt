package com.geeksville.mesh.convoy

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

// ============================================================
// ConvoySubscriptionScreen.kt
// V3.0 Phase B — Value Proposition + Subscription Gate
//
// Shown when a free user taps any subscription-gated button
// on the Dashboard. Five swipe panels explaining the value
// proposition. Last panel has SUBSCRIBE and NOT NOW actions.
//
// SUBSCRIBE → enrollment terms → Google Play billing (Phase C)
// NOT NOW   → returns to Dashboard
//
// Called from: Dashboard gated button tap
// Wired in:    Phase B nav graph
// ============================================================

private val NavyDark   = Color(0xFF0A1628)
private val Navy       = Color(0xFF0F2035)
private val NavyLight  = Color(0xFF1A3050)
private val SkyBlue    = Color(0xFF4AB8E8)
private val SkyDim     = Color(0xFF2A6888)
private val White      = Color(0xFFFFFFFF)
private val WhiteDim   = Color(0xFFAABBCC)
private val WhiteFaint = Color(0xFF445566)
private val Gold       = Color(0xFFFFCC44)
private val GoldDim    = Color(0xFF886633)

// ── Value proposition slide data ──────────────────────────────────────────────

data class ValueSlide(
    val icon: String,
    val headline: String,
    val subhead: String,
    val bullets: List<String>,
    val accentColor: Color = SkyBlue
)

private val slides = listOf(
    ValueSlide(
        icon = "🏁",
        headline = "Organized Rides",
        subhead = "Your group. Your channel. No conflicts.",
        bullets = listOf(
            "Create rides with dedicated LoRa channel + encryption",
            "No mesh pollution from other groups in the area",
            "Send invite links — riders apply config with one tap",
            "Broadcast your ride to followers before you go"
        ),
        accentColor = SkyBlue
    ),
    ValueSlide(
        icon = "🗺️",
        headline = "Map Pre-Load",
        subhead = "Arrive with the map already on screen.",
        bullets = listOf(
            "Define your ride area when creating the ride",
            "Offline tiles auto-downloaded for the exact area",
            "No scrambling for signal at the trailhead",
            "Works in full dead zones — no cell needed"
        ),
        accentColor = Color(0xFF44CC88)
    ),
    ValueSlide(
        icon = "📍",
        headline = "Track Library",
        subhead = "Community tracks with real difficulty ratings.",
        bullets = listOf(
            "Browse donated GPX tracks by area and difficulty",
            "Would-ride-again % from real riders",
            "Download a track — tiles auto-download with it",
            "Donate your own tracks after the ride"
        ),
        accentColor = Color(0xFFFF8844)
    ),
    ValueSlide(
        icon = "📡",
        headline = "Ride Radio Config",
        subhead = "One tap to join the right channel.",
        bullets = listOf(
            "Ride config delivered with the invite link",
            "Apply channel + encryption to your radio in the field",
            "Verify your config before the group leaves",
            "Master config always available free — ride config is premium"
        ),
        accentColor = Color(0xFFCC88FF)
    ),
    ValueSlide(
        icon = "⭐",
        headline = "Your Ride History",
        subhead = "Every ride. Every trail. Logged.",
        bullets = listOf(
            "Full ride history in your Dashboard",
            "Rate difficulty and recommend after each ride",
            "Donate your GPX track to the community library",
            "Follow organizers — get notified of new rides in your area"
        ),
        accentColor = Gold
    )
)

// ── Main Screen ───────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConvoySubscriptionScreen(
    onSubscribe: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { slides.size })
    val isLastSlide = pagerState.currentPage == slides.size - 1

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(NavyDark, Navy, NavyLight)
                )
            )
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // ── Header ────────────────────────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 40.dp, bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "GroupTrack",
                    color = SkyBlue,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                )
                Text(
                    text = "PREMIUM",
                    color = Gold,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 4.sp
                )
            }

            // ── Swipe panels ──────────────────────────────────────────────────
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) { page ->
                ValueSlidePanel(slide = slides[page])
            }

            // ── Page indicators ───────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                slides.indices.forEach { index ->
                    val isActive = index == pagerState.currentPage
                    val color by animateColorAsState(
                        targetValue = if (isActive) SkyBlue else WhiteFaint,
                        label = "dot_color"
                    )
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .size(if (isActive) 10.dp else 6.dp)
                            .clip(CircleShape)
                            .background(color)
                            .clickable {
                                scope.launch { pagerState.animateScrollToPage(index) }
                            }
                    )
                }
            }

            // ── Action buttons ────────────────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0A1628))
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (isLastSlide) {
                    // Subscribe button — full CTA on last slide
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(SkyBlue, Color(0xFF2288CC))
                                )
                            )
                            .clickable { onSubscribe() },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "SUBSCRIBE",
                                color = White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 2.sp
                            )
                            Text(
                                text = "\$3.00 / month — cancel anytime",
                                color = Color(0xFFCCEEFF),
                                fontSize = 10.sp,
                                letterSpacing = 0.5.sp
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "NOT NOW",
                        color = WhiteFaint,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        modifier = Modifier
                            .clickable { onDismiss() }
                            .padding(8.dp)
                    )
                } else {
                    // Next slide prompt on non-last slides
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(NavyLight)
                            .clickable {
                                scope.launch {
                                    pagerState.animateScrollToPage(pagerState.currentPage + 1)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "NEXT  →",
                            color = SkyBlue,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "NOT NOW",
                        color = WhiteFaint,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        modifier = Modifier
                            .clickable { onDismiss() }
                            .padding(8.dp)
                    )
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Convoy map, radio config, and offline tiles are always free.",
                    color = WhiteFaint,
                    fontSize = 10.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 14.sp
                )
            }
        }
    }
}

// ── Individual slide panel ────────────────────────────────────────────────────

@Composable
fun ValueSlidePanel(slide: ValueSlide) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Icon
        Text(
            text = slide.icon,
            fontSize = 56.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))

        // Headline
        Text(
            text = slide.headline,
            color = slide.accentColor,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            letterSpacing = 0.5.sp
        )
        Spacer(Modifier.height(8.dp))

        // Subhead
        Text(
            text = slide.subhead,
            color = WhiteDim,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            lineHeight = 20.sp
        )
        Spacer(Modifier.height(24.dp))

        // Bullet points
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            slide.bullets.forEach { bullet ->
                Row(
                    verticalAlignment = Alignment.Top,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "▸  ",
                        color = slide.accentColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = bullet,
                        color = WhiteDim,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                }
            }
        }
    }
}

// ── Subscription check helper ─────────────────────────────────────────────────

/**
 * Check if the current user has an active subscription.
 * Phase B: checks SharedPreferences for expires_at.
 * Phase C: verified against Google Play billing receipt.
 */
fun isSubscribed(context: android.content.Context): Boolean {
    val prefs = context.getSharedPreferences("grouptrack_user", android.content.Context.MODE_PRIVATE)
    val expiresAt = prefs.getLong("subscription_expires_at", 0L)
    return expiresAt > System.currentTimeMillis()
}

/**
 * Cache subscription expiry from API response.
 * Called after successful Google Play purchase verification.
 */
fun cacheSubscription(context: android.content.Context, expiresAtMs: Long) {
    context.getSharedPreferences("grouptrack_user", android.content.Context.MODE_PRIVATE)
        .edit()
        .putLong("subscription_expires_at", expiresAtMs)
        .apply()
}
