package com.limelight.shell.ui

import androidx.compose.animation.core.CubicBezierEasing

/** The prototype's --ease-out: fast start, long soft landing. */
val EaseOut = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

/** The prototype's --ease-spring: overshoots a little, used for focus scaling. */
val EaseSpring = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)
