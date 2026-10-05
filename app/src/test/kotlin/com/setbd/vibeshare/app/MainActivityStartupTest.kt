package com.setbd.vibeshare.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Launches the real [MainActivity] through the real [VibeShareApp] Application
 * (Koin graph included) on multiple Android API levels. This is the JVM-level
 * equivalent of "open the app" and catches startup crashes such as broken
 * themes, missing DI bindings or composition failures.
 *
 * Uses createAndroidComposeRule so animations (e.g. the infinite discovery
 * pulse) run on the Compose test clock instead of spinning the Robolectric
 * looper forever.
 *
 * SDK 28 (Android 9) exercises the pre-31 SplashScreen code path; SDK 33
 * (Android 13) exercises the modern system splash path.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [28, 33])
class MainActivityStartupTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun appLaunchesToResumedState() {
        val scenario = composeRule.activityRule.scenario
        scenario.moveToState(Lifecycle.State.RESUMED)
        scenario.onActivity { activity ->
            check(activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                "MainActivity did not reach RESUMED"
            }
        }
    }
}
