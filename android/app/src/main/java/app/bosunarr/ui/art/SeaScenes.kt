package app.bosunarr.ui.art

import androidx.compose.ui.geometry.Offset

/*
 * Vector data for each [SeaScene], in a 132 x 120 viewBox. The backdrop disc (centre 66,60, radius 52) is drawn by
 * [SeaArt] and is also the clip for layers marked `clip`. A scene is a back-to-front list of flat layers: SVG path
 * data, a palette role ([Ink]), an optional stroke width (round caps and joins), opacity, a [Motion], and the clip flag.
 *
 * The paths are plain SVG path syntax: wrap a layer in <path d="..."/> inside <svg viewBox="0 0 132 120"> to edit it
 * in any vector tool, then paste it back. Pivots use the same units: [SceneSpec.pivot] is the bob/rock centre (where
 * the hull meets the water) and [SceneSpec.swing] the hinge for Swing/Wander (telescope eyepiece, quill in its
 * inkwell, compass needle, seaweed root, pennant).
 */
internal fun sceneSpec(scene: SeaScene): SceneSpec = when (scene) {
    SeaScene.CalmSeas -> calmSeas()
    SeaScene.EmptyDock -> emptyDock()
    SeaScene.Horizon -> horizon()
    SeaScene.MessageBottle -> messageBottle()
    SeaScene.Spyglass -> spyglass()
    SeaScene.Harbor -> harbor()
    SeaScene.Fog -> fog()
    SeaScene.Storm -> storm()
    SeaScene.Logbook -> logbook()
    SeaScene.SmoothSailing -> smoothSailing()
    SeaScene.Crew -> crew()
    SeaScene.Compass -> compass()
    SeaScene.SetSail -> setSail()
}

private fun calmSeas() = SceneSpec(
    pivot = Offset(66f, 86f),
    swing = Offset(66f, 60f),
    layers = listOf(
        Layer("M78 32A16 16 0 1 1 110 32A16 16 0 1 1 78 32Z", Ink.Glow, alpha = 0.6f, motion = Motion.Pulse),
        Layer("M84 32A10 10 0 1 1 104 32A10 10 0 1 1 84 32Z", Ink.Sun),
        Layer("M24 36L40 36A4 4 0 0 1 44 40L44 40A4 4 0 0 1 40 44L24 44A4 4 0 0 1 20 40L20 40A4 4 0 0 1 24 36ZM24.32 36A4.8 4.8 0 1 1 33.92 36A4.8 4.8 0 1 1 24.32 36ZM32.48 37A3.36 3.36 0 1 1 39.2 37A3.36 3.36 0 1 1 32.48 37Z", Ink.Cloud, motion = Motion.Drift),
        Layer("M64.5 21L64.5 76", Ink.Hull, stroke = 2.5f, motion = Motion.Bob),
        Layer("M62 24C56 40 47 56 37 71L62 71Z", Ink.Sail, motion = Motion.Bob),
        Layer("M62 24C58 34 55 42 52 50L62 50Z", Ink.Shade, alpha = 0.06f, motion = Motion.Bob),
        Layer("M67 30C73 44 81 58 90 71L67 71Z", Ink.SailShade, motion = Motion.Bob),
        Layer("M65.5 20L74 23L65.5 26Z", Ink.Accent, motion = Motion.Bob),
        Layer("M30 74L102 74Q98 83 90 91L42 91Q34 83 30 74Z", Ink.Hull, motion = Motion.Bob),
        Layer("M36 79.5L96 79.5", Ink.HullStripe, stroke = 2f, motion = Motion.Bob),
        Layer("M4 86C7.67 83.61 11.33 83.61 15 86C18.67 88.39 22.33 88.39 26 86C29.67 83.61 33.33 83.61 37 86C40.67 88.39 44.33 88.39 48 86C51.67 83.61 55.33 83.61 59 86C62.67 88.39 66.33 88.39 70 86C73.67 83.61 77.33 83.61 81 86C84.67 88.39 88.33 88.39 92 86C95.67 83.61 99.33 83.61 103 86C106.67 88.39 110.33 88.39 114 86C117.67 83.61 121.33 83.61 125 86C126 86.65 127 86.65 128 86L128 124L4 124Z", Ink.Sea, motion = Motion.Wave, clip = true),
        Layer("M4 101C8.77 99.08 13.53 99.08 18.3 101C23.07 102.92 27.83 102.92 32.6 101C37.37 99.08 42.13 99.08 46.9 101C51.67 102.92 56.43 102.92 61.2 101C65.97 99.08 70.73 99.08 75.5 101C80.27 102.92 85.03 102.92 89.8 101C94.57 99.08 99.33 99.08 104.1 101C108.87 102.92 113.63 102.92 118.4 101C121.6 99.71 124.8 99.71 128 101L128 124L4 124Z", Ink.SeaDeep, motion = Motion.WaveAlt, clip = true),
        Layer("M4 86C7.67 83.61 11.33 83.61 15 86C18.67 88.39 22.33 88.39 26 86C29.67 83.61 33.33 83.61 37 86C40.67 88.39 44.33 88.39 48 86C51.67 83.61 55.33 83.61 59 86C62.67 88.39 66.33 88.39 70 86C73.67 83.61 77.33 83.61 81 86C84.67 88.39 88.33 88.39 92 86C95.67 83.61 99.33 83.61 103 86C106.67 88.39 110.33 88.39 114 86C117.67 83.61 121.33 83.61 125 86C126 86.65 127 86.65 128 86", Ink.Wave, stroke = 3.5f, motion = Motion.Wave, clip = true),
        Layer("M34 95C36.93 93.08 39.87 93.08 42.8 95C45.73 96.92 48.67 96.92 51.6 95C54.53 93.08 57.47 93.08 60.4 95C63.33 96.92 66.27 96.92 69.2 95C72.13 93.08 75.07 93.08 78 95C80.93 96.92 83.87 96.92 86.8 95C89.73 93.08 92.67 93.08 95.6 95C95.73 95.09 95.87 95.09 96 95", Ink.Foam, stroke = 2.5f, alpha = 0.9f, motion = Motion.WaveAlt, clip = true),
        Layer("M42 104L50 104", Ink.Foam, stroke = 2.5f, motion = Motion.Twinkle, clip = true),
        Layer("M78 108L84 108", Ink.Foam, stroke = 2.5f, motion = Motion.TwinkleAlt, clip = true),
    ),
)

private fun emptyDock() = SceneSpec(
    pivot = Offset(77f, 55f),
    swing = Offset(66f, 60f),
    layers = listOf(
        Layer("M31 26L45 26A4 4 0 0 1 49 30L49 30A4 4 0 0 1 45 34L31 34A4 4 0 0 1 27 30L27 30A4 4 0 0 1 31 26ZM30.96 26A4.4 4.4 0 1 1 39.76 26A4.4 4.4 0 1 1 30.96 26ZM38.44 27A3.08 3.08 0 1 1 44.6 27A3.08 3.08 0 1 1 38.44 27Z", Ink.Cloud, motion = Motion.Drift),
        Layer("M87 18L93 18A4 4 0 0 1 97 22L97 22A4 4 0 0 1 93 26L87 26A4 4 0 0 1 83 22L83 22A4 4 0 0 1 87 18ZM85.52 18A2.8 2.8 0 1 1 91.12 18A2.8 2.8 0 1 1 85.52 18ZM90.28 19A1.96 1.96 0 1 1 94.2 19A1.96 1.96 0 1 1 90.28 19Z", Ink.Cloud, alpha = 0.8f, motion = Motion.DriftAlt),
        Layer("M83.5 62L85.5 62A1.5 1.5 0 0 1 87 63.5L87 110.5A1.5 1.5 0 0 1 85.5 112L83.5 112A1.5 1.5 0 0 1 82 110.5L82 63.5A1.5 1.5 0 0 1 83.5 62Z", Ink.WoodShade, clip = true),
        Layer("M102.5 62L104.5 62A1.5 1.5 0 0 1 106 63.5L106 110.5A1.5 1.5 0 0 1 104.5 112L102.5 112A1.5 1.5 0 0 1 101 110.5L101 63.5A1.5 1.5 0 0 1 102.5 62Z", Ink.WoodShade, clip = true),
        Layer("M74 58L134 58A2 2 0 0 1 136 60L136 62A2 2 0 0 1 134 64L74 64A2 2 0 0 1 72 62L72 60A2 2 0 0 1 74 58Z", Ink.Wood, clip = true),
        Layer("M76 58.6L130 58.6", Ink.Foam, stroke = 1.2f, alpha = 0.35f, clip = true),
        Layer("M76 47L78 47A2.5 2.5 0 0 1 80.5 49.5L80.5 58.5A2.5 2.5 0 0 1 78 61L76 61A2.5 2.5 0 0 1 73.5 58.5L73.5 49.5A2.5 2.5 0 0 1 76 47Z", Ink.Wood),
        Layer("M74.5 45.5L79.5 45.5A2 2 0 0 1 81.5 47.5L81.5 47.5A2 2 0 0 1 79.5 49.5L74.5 49.5A2 2 0 0 1 72.5 47.5L72.5 47.5A2 2 0 0 1 74.5 45.5Z", Ink.WoodShade),
        Layer("M64 73C70 72 73 64 77 55", Ink.WoodShade, stroke = 1.8f, motion = Motion.Rock),
        Layer("M18 71L67 71Q64 82.5 56 89L31 89Q23 83 18 71Z", Ink.Hull, motion = Motion.Rock),
        Layer("M21.5 71L63.5 71", Ink.HullStripe, stroke = 2.5f, motion = Motion.Rock),
        Layer("M28 79L58 79", Ink.HullStripe, stroke = 1.6f, alpha = 0.6f, motion = Motion.Rock),
        Layer("M34.2 67L35.8 67A1.2 1.2 0 0 1 37 68.2L37 70.3A1.2 1.2 0 0 1 35.8 71.5L34.2 71.5A1.2 1.2 0 0 1 33 70.3L33 68.2A1.2 1.2 0 0 1 34.2 67Z", Ink.Wood, motion = Motion.Rock),
        Layer("M4 84C7.67 81.61 11.33 81.61 15 84C18.67 86.39 22.33 86.39 26 84C29.67 81.61 33.33 81.61 37 84C40.67 86.39 44.33 86.39 48 84C51.67 81.61 55.33 81.61 59 84C62.67 86.39 66.33 86.39 70 84C73.67 81.61 77.33 81.61 81 84C84.67 86.39 88.33 86.39 92 84C95.67 81.61 99.33 81.61 103 84C106.67 86.39 110.33 86.39 114 84C117.67 81.61 121.33 81.61 125 84C126 84.65 127 84.65 128 84L128 124L4 124Z", Ink.Sea, motion = Motion.Wave, clip = true),
        Layer("M4 99C8.77 97.08 13.53 97.08 18.3 99C23.07 100.92 27.83 100.92 32.6 99C37.37 97.08 42.13 97.08 46.9 99C51.67 100.92 56.43 100.92 61.2 99C65.97 97.08 70.73 97.08 75.5 99C80.27 100.92 85.03 100.92 89.8 99C94.57 97.08 99.33 97.08 104.1 99C108.87 100.92 113.63 100.92 118.4 99C121.6 97.71 124.8 97.71 128 99L128 124L4 124Z", Ink.SeaDeep, motion = Motion.WaveAlt, clip = true),
        Layer("M4 84C7.67 81.61 11.33 81.61 15 84C18.67 86.39 22.33 86.39 26 84C29.67 81.61 33.33 81.61 37 84C40.67 86.39 44.33 86.39 48 84C51.67 81.61 55.33 81.61 59 84C62.67 86.39 66.33 86.39 70 84C73.67 81.61 77.33 81.61 81 84C84.67 86.39 88.33 86.39 92 84C95.67 81.61 99.33 81.61 103 84C106.67 86.39 110.33 86.39 114 84C117.67 81.61 121.33 81.61 125 84C126 84.65 127 84.65 128 84", Ink.Wave, stroke = 3.5f, motion = Motion.Wave, clip = true),
        Layer("M34 93C36.93 91.08 39.87 91.08 42.8 93C45.73 94.92 48.67 94.92 51.6 93C54.53 91.08 57.47 91.08 60.4 93C63.33 94.92 66.27 94.92 69.2 93C72.13 91.08 75.07 91.08 78 93C80.93 94.92 83.87 94.92 86.8 93C89.73 91.08 92.67 91.08 95.6 93C95.73 93.09 95.87 93.09 96 93", Ink.Foam, stroke = 2.5f, alpha = 0.9f, motion = Motion.WaveAlt, clip = true),
        Layer("M40 100L48 100", Ink.Foam, stroke = 2.5f, motion = Motion.Twinkle, clip = true),
    ),
)

private fun horizon() = SceneSpec(
    pivot = Offset(66f, 60f),
    swing = Offset(66f, 60f),
    layers = listOf(
        Layer("M30 25.5Q30.72 29.28 34.5 30Q30.72 30.72 30 34.5Q29.28 30.72 25.5 30Q29.28 29.28 30 25.5Z", Ink.Star, motion = Motion.Twinkle),
        Layer("M46 15.4Q46.42 17.58 48.6 18Q46.42 18.42 46 20.6Q45.58 18.42 43.4 18Q45.58 17.58 46 15.4Z", Ink.Star, motion = Motion.TwinkleAlt),
        Layer("M100 48.6Q100.54 51.46 103.4 52Q100.54 52.54 100 55.4Q99.46 52.54 96.6 52Q99.46 51.46 100 48.6Z", Ink.Star, motion = Motion.TwinkleLate),
        Layer("M102.6 26A1.4 1.4 0 1 1 105.4 26A1.4 1.4 0 1 1 102.6 26Z", Ink.Star, motion = Motion.TwinkleAlt),
        Layer("M22.8 56A1.2 1.2 0 1 1 25.2 56A1.2 1.2 0 1 1 22.8 56Z", Ink.Star, motion = Motion.TwinkleLate),
        Layer("M65 29.5L2 10L2 50L65 32.5Z", Ink.Glow, alpha = 0.7f, motion = Motion.Pulse, clip = true),
        Layer("M65 29.5L130 16L130 40L65 32.5Z", Ink.Glow, alpha = 0.35f, motion = Motion.PulseAlt, clip = true),
        Layer("M56 31A9 9 0 1 1 74 31A9 9 0 1 1 56 31Z", Ink.Glow, alpha = 0.8f, motion = Motion.Pulse),
        Layer("M58 40L72 40L77 92L53 92Z", Ink.Paper),
        Layer("M56.85 52L73.15 52L73.83 59L56.17 59Z", Ink.Accent),
        Layer("M55.12 70L74.88 70L75.56 77L54.44 77Z", Ink.Accent),
        Layer("M65 40L72 40L77 92L65 92Z", Ink.Shade, alpha = 0.1f),
        Layer("M65 62L65 62A1.5 1.5 0 0 1 66.5 63.5L66.5 65.5A1.5 1.5 0 0 1 65 67L65 67A1.5 1.5 0 0 1 63.5 65.5L63.5 63.5A1.5 1.5 0 0 1 65 62Z", Ink.Hull),
        Layer("M62 92L62 86A3 3 0 0 1 68 86L68 92Z", Ink.Hull),
        Layer("M61.5 26L68.5 26A1.5 1.5 0 0 1 70 27.5L70 35.5A1.5 1.5 0 0 1 68.5 37L61.5 37A1.5 1.5 0 0 1 60 35.5L60 27.5A1.5 1.5 0 0 1 61.5 26Z", Ink.Sun),
        Layer("M65 26L65 37", Ink.Hull, stroke = 1.2f),
        Layer("M56 36.5L74 36.5A1.5 1.5 0 0 1 75.5 38L75.5 39A1.5 1.5 0 0 1 74 40.5L56 40.5A1.5 1.5 0 0 1 54.5 39L54.5 38A1.5 1.5 0 0 1 56 36.5Z", Ink.Hull),
        Layer("M57 27L65 17.5L73 27Z", Ink.Accent),
        Layer("M63.2 16A1.8 1.8 0 1 1 66.8 16A1.8 1.8 0 1 1 63.2 16Z", Ink.Hull),
        Layer("M24 104C30 92 44 88 55 90L75 90C86 88 98 92 106 104Z", Ink.Rock, clip = true),
        Layer("M72 92C84 90 96 94 102 102L84 104Z", Ink.RockShade, clip = true),
        Layer("M4 100C7.33 97.87 10.67 97.87 14 100C17.33 102.13 20.67 102.13 24 100C27.33 97.87 30.67 97.87 34 100C37.33 102.13 40.67 102.13 44 100C47.33 97.87 50.67 97.87 54 100C57.33 102.13 60.67 102.13 64 100C67.33 97.87 70.67 97.87 74 100C77.33 102.13 80.67 102.13 84 100C87.33 97.87 90.67 97.87 94 100C97.33 102.13 100.67 102.13 104 100C107.33 97.87 110.67 97.87 114 100C117.33 102.13 120.67 102.13 124 100C125.33 99.15 126.67 99.15 128 100L128 124L4 124Z", Ink.Sea, motion = Motion.Wave, clip = true),
        Layer("M4 115C8.33 113.3 12.67 113.3 17 115C21.33 116.7 25.67 116.7 30 115C34.33 113.3 38.67 113.3 43 115C47.33 116.7 51.67 116.7 56 115C60.33 113.3 64.67 113.3 69 115C73.33 116.7 77.67 116.7 82 115C86.33 113.3 90.67 113.3 95 115C99.33 116.7 103.67 116.7 108 115C112.33 113.3 116.67 113.3 121 115C123.33 115.92 125.67 115.92 128 115L128 124L4 124Z", Ink.SeaDeep, motion = Motion.WaveAlt, clip = true),
        Layer("M4 100C7.33 97.87 10.67 97.87 14 100C17.33 102.13 20.67 102.13 24 100C27.33 97.87 30.67 97.87 34 100C37.33 102.13 40.67 102.13 44 100C47.33 97.87 50.67 97.87 54 100C57.33 102.13 60.67 102.13 64 100C67.33 97.87 70.67 97.87 74 100C77.33 102.13 80.67 102.13 84 100C87.33 97.87 90.67 97.87 94 100C97.33 102.13 100.67 102.13 104 100C107.33 97.87 110.67 97.87 114 100C117.33 102.13 120.67 102.13 124 100C125.33 99.15 126.67 99.15 128 100", Ink.Wave, stroke = 3.5f, motion = Motion.Wave, clip = true),
    ),
)

private fun messageBottle() = SceneSpec(
    pivot = Offset(64f, 78f),
    swing = Offset(66f, 60f),
    layers = listOf(
        Layer("M98 25Q98.8 29.2 103 30Q98.8 30.8 98 35Q97.2 30.8 93 30Q97.2 29.2 98 25Z", Ink.Star, motion = Motion.Twinkle),
        Layer("M32 35Q32.48 37.52 35 38Q32.48 38.48 32 41Q31.52 38.48 29 38Q31.52 37.52 32 35Z", Ink.Star, motion = Motion.TwinkleAlt),
        Layer("M86 45.8Q86.35 47.65 88.2 48Q86.35 48.35 86 50.2Q85.65 48.35 83.8 48Q85.65 47.65 86 45.8Z", Ink.Star, motion = Motion.TwinkleLate),
        Layer("M18 70C21.33 68.14 24.67 68.14 28 70C31.33 71.86 34.67 71.86 38 70C41.33 68.14 44.67 68.14 48 70C51.33 71.86 54.67 71.86 58 70C61.33 68.14 64.67 68.14 68 70C71.33 71.86 74.67 71.86 78 70C81.33 68.14 84.67 68.14 88 70C91.33 71.86 94.67 71.86 98 70C101.33 68.14 104.67 68.14 108 70C110 71.12 112 71.12 114 70", Ink.WaveSoft, stroke = 2.5f, motion = Motion.WaveAlt, clip = true),
        Layer("M42.66 64.95L66.92 58.9C72.74 57.45 76.13 62.79 80.98 61.58L87.78 59.89Q89.72 59.4 90.2 61.35L92.14 69.11Q92.62 71.05 90.68 71.53L83.89 73.23C79.04 74.43 78.55 80.74 72.72 82.19L48.47 88.24C42.65 89.69 38.77 86.54 36.83 78.77C34.9 71.01 36.84 66.4 42.66 64.95Z", Ink.Glass, motion = Motion.Bob),
        Layer("M44.11 70.77L61.58 66.42A2 2 0 0 1 64 67.88L65.94 75.64A2 2 0 0 1 64.48 78.06L47.02 82.42A2 2 0 0 1 44.59 80.96L42.66 73.2A2 2 0 0 1 44.11 70.77Z", Ink.Paper, motion = Motion.Bob),
        Layer("M41.93 70.29L41.93 70.29A2 2 0 0 1 44.35 71.75L46.77 81.45A2 2 0 0 1 45.32 83.87L45.32 83.87A2 2 0 0 1 42.89 82.42L40.47 72.71A2 2 0 0 1 41.93 70.29Z", Ink.PaperShade, motion = Motion.Bob),
        Layer("M63.28 64.97L63.28 64.97A2 2 0 0 1 65.7 66.42L68.12 76.13A2 2 0 0 1 66.66 78.55L66.66 78.55A2 2 0 0 1 64.24 77.09L61.82 67.39A2 2 0 0 1 63.28 64.97Z", Ink.PaperShade, motion = Motion.Bob),
        Layer("M52.85 68.6L55.75 80.24", Ink.Accent, stroke = 2f, motion = Motion.Bob),
        Layer("M47.02 74.17L50.9 73.2", Ink.PaperLine, stroke = 1.2f, motion = Motion.Bob),
        Layer("M57.69 75.63L61.57 74.67", Ink.PaperLine, stroke = 1.2f, motion = Motion.Bob),
        Layer("M42.66 64.95L66.92 58.9C72.74 57.45 76.13 62.79 80.98 61.58L87.78 59.89Q89.72 59.4 90.2 61.35L92.14 69.11Q92.62 71.05 90.68 71.53L83.89 73.23C79.04 74.43 78.55 80.74 72.72 82.19L48.47 88.24C42.65 89.69 38.77 86.54 36.83 78.77C34.9 71.01 36.84 66.4 42.66 64.95Z", Ink.Wave, stroke = 2.5f, motion = Motion.Bob),
        Layer("M41.57 68.83Q48.05 65.88 65.66 62.11", Ink.Foam, stroke = 2f, alpha = 0.9f, motion = Motion.Bob),
        Layer("M90.56 60.74L93.48 60.01A2 2 0 0 1 95.9 61.47L97.11 66.32A2 2 0 0 1 95.65 68.75L92.74 69.47A2 2 0 0 1 90.32 68.01L89.11 63.16A2 2 0 0 1 90.56 60.74Z", Ink.Wood, motion = Motion.Bob),
        Layer("M4 82C7.67 79.61 11.33 79.61 15 82C18.67 84.39 22.33 84.39 26 82C29.67 79.61 33.33 79.61 37 82C40.67 84.39 44.33 84.39 48 82C51.67 79.61 55.33 79.61 59 82C62.67 84.39 66.33 84.39 70 82C73.67 79.61 77.33 79.61 81 82C84.67 84.39 88.33 84.39 92 82C95.67 79.61 99.33 79.61 103 82C106.67 84.39 110.33 84.39 114 82C117.67 79.61 121.33 79.61 125 82C126 82.65 127 82.65 128 82L128 124L4 124Z", Ink.Sea, motion = Motion.Wave, clip = true),
        Layer("M4 97C8.77 95.08 13.53 95.08 18.3 97C23.07 98.92 27.83 98.92 32.6 97C37.37 95.08 42.13 95.08 46.9 97C51.67 98.92 56.43 98.92 61.2 97C65.97 95.08 70.73 95.08 75.5 97C80.27 98.92 85.03 98.92 89.8 97C94.57 95.08 99.33 95.08 104.1 97C108.87 98.92 113.63 98.92 118.4 97C121.6 95.71 124.8 95.71 128 97L128 124L4 124Z", Ink.SeaDeep, motion = Motion.WaveAlt, clip = true),
        Layer("M4 82C7.67 79.61 11.33 79.61 15 82C18.67 84.39 22.33 84.39 26 82C29.67 79.61 33.33 79.61 37 82C40.67 84.39 44.33 84.39 48 82C51.67 79.61 55.33 79.61 59 82C62.67 84.39 66.33 84.39 70 82C73.67 79.61 77.33 79.61 81 82C84.67 84.39 88.33 84.39 92 82C95.67 79.61 99.33 79.61 103 82C106.67 84.39 110.33 84.39 114 82C117.67 79.61 121.33 79.61 125 82C126 82.65 127 82.65 128 82", Ink.Wave, stroke = 3.5f, motion = Motion.Wave, clip = true),
        Layer("M34 91C36.93 89.08 39.87 89.08 42.8 91C45.73 92.92 48.67 92.92 51.6 91C54.53 89.08 57.47 89.08 60.4 91C63.33 92.92 66.27 92.92 69.2 91C72.13 89.08 75.07 89.08 78 91C80.93 92.92 83.87 92.92 86.8 91C89.73 89.08 92.67 89.08 95.6 91C95.73 91.09 95.87 91.09 96 91", Ink.Foam, stroke = 2.5f, alpha = 0.9f, motion = Motion.WaveAlt, clip = true),
    ),
)

private fun spyglass() = SceneSpec(
    pivot = Offset(66f, 60f),
    swing = Offset(19f, 101f),
    layers = listOf(
        Layer("M31 36L41 36A4 4 0 0 1 45 40L45 40A4 4 0 0 1 41 44L31 44A4 4 0 0 1 27 40L27 40A4 4 0 0 1 31 36ZM30.24 36A3.6 3.6 0 1 1 37.44 36A3.6 3.6 0 1 1 30.24 36ZM36.36 37A2.52 2.52 0 1 1 41.4 37A2.52 2.52 0 1 1 36.36 37Z", Ink.Cloud, alpha = 0.9f, motion = Motion.Drift),
        Layer("M80 19Q80.48 21.52 83 22Q80.48 22.48 80 25Q79.52 22.48 77 22Q79.52 21.52 80 19Z", Ink.Star, motion = Motion.TwinkleAlt),
        Layer("M112 49.6Q112.38 51.62 114.4 52Q112.38 52.38 112 54.4Q111.62 52.38 109.6 52Q111.62 51.62 112 49.6Z", Ink.Star, motion = Motion.TwinkleLate),
        Layer("M91 34C91 27.5 101 26.5 101 33C101 37 96 37.5 96 42.5", Ink.Accent, stroke = 3.6f, motion = Motion.Glide),
        Layer("M93.9 49A2.1 2.1 0 1 1 98.1 49A2.1 2.1 0 1 1 93.9 49Z", Ink.Accent, motion = Motion.Glide),
        Layer("M4 78C8.33 76.4 12.67 76.4 17 78C21.33 79.6 25.67 79.6 30 78C34.33 76.4 38.67 76.4 43 78C47.33 79.6 51.67 79.6 56 78C60.33 76.4 64.67 76.4 69 78C73.33 79.6 77.67 79.6 82 78C86.33 76.4 90.67 76.4 95 78C99.33 79.6 103.67 79.6 108 78C112.33 76.4 116.67 76.4 121 78C123.33 78.86 125.67 78.86 128 78L128 124L4 124Z", Ink.Sea, motion = Motion.Wave, clip = true),
        Layer("M4 93C9.63 91.72 15.27 91.72 20.9 93C26.53 94.28 32.17 94.28 37.8 93C43.43 91.72 49.07 91.72 54.7 93C60.33 94.28 65.97 94.28 71.6 93C77.23 91.72 82.87 91.72 88.5 93C94.13 94.28 99.77 94.28 105.4 93C111.03 91.72 116.67 91.72 122.3 93C124.2 93.43 126.1 93.43 128 93L128 124L4 124Z", Ink.SeaDeep, motion = Motion.WaveAlt, clip = true),
        Layer("M4 78C8.33 76.4 12.67 76.4 17 78C21.33 79.6 25.67 79.6 30 78C34.33 76.4 38.67 76.4 43 78C47.33 79.6 51.67 79.6 56 78C60.33 76.4 64.67 76.4 69 78C73.33 79.6 77.67 79.6 82 78C86.33 76.4 90.67 76.4 95 78C99.33 79.6 103.67 79.6 108 78C112.33 76.4 116.67 76.4 121 78C123.33 78.86 125.67 78.86 128 78", Ink.Wave, stroke = 3.5f, motion = Motion.Wave, clip = true),
        Layer("M34 87C37.47 85.72 40.93 85.72 44.4 87C47.87 88.28 51.33 88.28 54.8 87C58.27 85.72 61.73 85.72 65.2 87C68.67 88.28 72.13 88.28 75.6 87C79.07 85.72 82.53 85.72 86 87C89.33 88.23 92.67 88.23 96 87", Ink.Foam, stroke = 2.5f, alpha = 0.9f, motion = Motion.WaveAlt, clip = true),
        Layer("M18.65 96.54L28.94 90.36A2 2 0 0 1 31.68 91.05L33.74 94.47A2 2 0 0 1 33.06 97.22L22.77 103.4A2 2 0 0 1 20.03 102.71L17.97 99.29A2 2 0 0 1 18.65 96.54Z", Ink.Hull, motion = Motion.Swing),
        Layer("M30.31 87.79L44.88 79.03A2.5 2.5 0 0 1 48.31 79.89L51.4 85.03A2.5 2.5 0 0 1 50.55 88.46L35.98 97.22A2.5 2.5 0 0 1 32.55 96.36L29.46 91.22A2.5 2.5 0 0 1 30.31 87.79Z", Ink.Hull, motion = Motion.Swing),
        Layer("M47.11 75.94L67.68 63.58A3 3 0 0 1 71.8 64.61L75.92 71.47A3 3 0 0 1 74.89 75.58L54.32 87.94A3 3 0 0 1 50.2 86.92L46.08 80.06A3 3 0 0 1 47.11 75.94Z", Ink.Hull, motion = Motion.Swing),
        Layer("M20.97 100.4L72.4 69.5A2 2 0 0 1 75.15 70.18L76.43 72.32A2 2 0 0 1 75.75 75.07L24.32 105.97A2 2 0 0 1 21.58 105.29L20.29 103.14A2 2 0 0 1 20.97 100.4Z", Ink.Shade, alpha = 0.12f, motion = Motion.Swing),
        Layer("M20.54 97.74L28.26 93.11", Ink.HullStripe, stroke = 1.4f, alpha = 0.7f, motion = Motion.Swing),
        Layer("M33.74 88.64L44.03 82.46", Ink.HullStripe, stroke = 1.4f, alpha = 0.7f, motion = Motion.Swing),
        Layer("M51.23 76.97L66.66 67.7", Ink.HullStripe, stroke = 1.4f, alpha = 0.7f, motion = Motion.Swing),
        Layer("M27.65 88.22L28.51 87.7A1.5 1.5 0 0 1 30.57 88.22L35.72 96.79A1.5 1.5 0 0 1 35.21 98.85L34.35 99.36A1.5 1.5 0 0 1 32.29 98.85L27.14 90.28A1.5 1.5 0 0 1 27.65 88.22Z", Ink.Sun, motion = Motion.Swing),
        Layer("M44.02 76.63L44.88 76.12A1.5 1.5 0 0 1 46.94 76.63L53.63 87.77A1.5 1.5 0 0 1 53.12 89.83L52.26 90.35A1.5 1.5 0 0 1 50.21 89.83L43.51 78.69A1.5 1.5 0 0 1 44.02 76.63Z", Ink.Sun, motion = Motion.Swing),
        Layer("M69.5 60.39L71.73 59.05A2.2 2.2 0 0 1 74.75 59.81L81.54 71.12A2.2 2.2 0 0 1 80.79 74.14L78.56 75.48A2.2 2.2 0 0 1 75.54 74.72L68.75 63.41A2.2 2.2 0 0 1 69.5 60.39Z", Ink.Sun, motion = Motion.Swing),
        Layer("M16.77 98.84L17.28 98.53A1.2 1.2 0 0 1 18.93 98.94L20.78 102.03A1.2 1.2 0 0 1 20.37 103.67L19.86 103.98A1.2 1.2 0 0 1 18.21 103.57L16.36 100.49A1.2 1.2 0 0 1 16.77 98.84Z", Ink.Sun, motion = Motion.Swing),
    ),
)

private fun harbor() = SceneSpec(
    pivot = Offset(66f, 47f),
    swing = Offset(36f, 106f),
    layers = listOf(
        Layer("M31 22L41 22A4 4 0 0 1 45 26L45 26A4 4 0 0 1 41 30L31 30A4 4 0 0 1 27 26L27 26A4 4 0 0 1 31 22ZM30.24 22A3.6 3.6 0 1 1 37.44 22A3.6 3.6 0 1 1 30.24 22ZM36.36 23A2.52 2.52 0 1 1 41.4 23A2.52 2.52 0 1 1 36.36 23Z", Ink.Cloud, motion = Motion.Drift),
        Layer("M4 46C7.33 44.14 10.67 44.14 14 46C17.33 47.86 20.67 47.86 24 46C27.33 44.14 30.67 44.14 34 46C37.33 47.86 40.67 47.86 44 46C47.33 44.14 50.67 44.14 54 46C57.33 47.86 60.67 47.86 64 46C67.33 44.14 70.67 44.14 74 46C77.33 47.86 80.67 47.86 84 46C87.33 44.14 90.67 44.14 94 46C97.33 47.86 100.67 47.86 104 46C107.33 44.14 110.67 44.14 114 46C117.33 47.86 120.67 47.86 124 46C125.33 45.26 126.67 45.26 128 46L128 124L4 124Z", Ink.Sea, motion = Motion.Wave, clip = true),
        Layer("M4 78C8.67 76.14 13.33 76.14 18 78C22.67 79.86 27.33 79.86 32 78C36.67 76.14 41.33 76.14 46 78C50.67 79.86 55.33 79.86 60 78C64.67 76.14 69.33 76.14 74 78C78.67 79.86 83.33 79.86 88 78C92.67 76.14 97.33 76.14 102 78C106.67 79.86 111.33 79.86 116 78C120 76.4 124 76.4 128 78L128 124L4 124Z", Ink.SeaDeep, motion = Motion.WaveAlt, clip = true),
        Layer("M66 50C58 60 76 70 85.48 89.9", Ink.WoodShade, stroke = 1.8f),
        Layer("M60 18A6 6 0 1 1 72 18A6 6 0 1 1 60 18Z", Ink.Glow, alpha = 0.8f, motion = Motion.Pulse),
        Layer("M59 44L73 44A3.5 3.5 0 0 1 76.5 47.5L76.5 47.5A3.5 3.5 0 0 1 73 51L59 51A3.5 3.5 0 0 1 55.5 47.5L55.5 47.5A3.5 3.5 0 0 1 59 44Z", Ink.Hull, motion = Motion.Rock),
        Layer("M59 46L63 26L69 26L73 46Z", Ink.Paper, motion = Motion.Rock),
        Layer("M61.8 32L70.2 32L71.4 38L60.6 38Z", Ink.Accent, motion = Motion.Rock),
        Layer("M66 26L69 26L73 46L66 46Z", Ink.Shade, alpha = 0.1f, motion = Motion.Rock),
        Layer("M65.5 21L66.5 21A1.5 1.5 0 0 1 68 22.5L68 25.5A1.5 1.5 0 0 1 66.5 27L65.5 27A1.5 1.5 0 0 1 64 25.5L64 22.5A1.5 1.5 0 0 1 65.5 21Z", Ink.Hull, motion = Motion.Rock),
        Layer("M63.4 18.5A2.6 2.6 0 1 1 68.6 18.5A2.6 2.6 0 1 1 63.4 18.5Z", Ink.Sun, motion = Motion.Rock),
        Layer("M4 46C7.33 44.14 10.67 44.14 14 46C17.33 47.86 20.67 47.86 24 46C27.33 44.14 30.67 44.14 34 46C37.33 47.86 40.67 47.86 44 46C47.33 44.14 50.67 44.14 54 46C57.33 47.86 60.67 47.86 64 46C67.33 44.14 70.67 44.14 74 46C77.33 47.86 80.67 47.86 84 46C87.33 44.14 90.67 44.14 94 46C97.33 47.86 100.67 47.86 104 46C107.33 44.14 110.67 44.14 114 46C117.33 47.86 120.67 47.86 124 46C125.33 45.26 126.67 45.26 128 46", Ink.Wave, stroke = 3.5f, motion = Motion.Wave, clip = true),
        Layer("M10 104C28 97 46 99 62 102C80 105 98 97 124 101L124 124L10 124Z", Ink.Rock, clip = true),
        Layer("M36 106C31 98 40 92 35 84", Ink.Weed, stroke = 3f, motion = Motion.Swing),
        Layer("M41 106C44 100 38 95 42 89", Ink.Weed, stroke = 2.5f, motion = Motion.SwingAlt),
        Layer("M100 104C96 98 104 93 100 87", Ink.Weed, stroke = 2.5f, motion = Motion.SwingAlt),
        Layer("M83.79 86.72A3.6 3.6 0 1 1 87.17 93.08A3.6 3.6 0 1 1 83.79 86.72Z", Ink.Hull, stroke = 2.4f),
        Layer("M82.3 91.59L62.96 101.87", Ink.Hull, stroke = 3f),
        Layer("M75.13 87.47L81.7 99.83", Ink.Hull, stroke = 3f),
        Layer("M65.3 88.17C60.06 93.22 60.64 98.57 62.52 102.1C64.4 105.63 68.51 109.11 75.63 107.59", Ink.Hull, stroke = 3f),
        Layer("M66.34 84.78L66.92 90.14L61.51 88.48Z", Ink.Hull),
        Layer("M79.01 108.62L74.9 105.15L73.25 110.56Z", Ink.Hull),
        Layer("M49.8 72A2.2 2.2 0 1 1 54.2 72A2.2 2.2 0 1 1 49.8 72Z", Ink.Foam, stroke = 1.5f, motion = Motion.Twinkle),
        Layer("M54.4 63A1.6 1.6 0 1 1 57.6 63A1.6 1.6 0 1 1 54.4 63Z", Ink.Foam, stroke = 1.4f, motion = Motion.TwinkleAlt),
        Layer("M50.9 56A1.1 1.1 0 1 1 53.1 56A1.1 1.1 0 1 1 50.9 56Z", Ink.Foam, stroke = 1.2f, motion = Motion.TwinkleLate),
    ),
)

private fun fog() = SceneSpec(
    pivot = Offset(46f, 88f),
    swing = Offset(66f, 60f),
    layers = listOf(
        Layer("M93.5 55.5A4.5 4.5 0 1 1 102.5 55.5A4.5 4.5 0 1 1 93.5 55.5Z", Ink.Glow, alpha = 0.36f, motion = Motion.Pulse),
        Layer("M94.5 60L101.5 60L104 86L92 86Z", Ink.Paper, alpha = 0.45f),
        Layer("M93.92 66L102.08 66L102.41 69.5L93.59 69.5Z", Ink.Accent, alpha = 0.45f),
        Layer("M93.06 75L102.94 75L103.28 78.5L92.72 78.5Z", Ink.Accent, alpha = 0.45f),
        Layer("M98 60L101.5 60L104 86L98 86Z", Ink.Shade, alpha = 0.04f),
        Layer("M98 71L98 71A0.75 0.75 0 0 1 98.75 71.75L98.75 72.75A0.75 0.75 0 0 1 98 73.5L98 73.5A0.75 0.75 0 0 1 97.25 72.75L97.25 71.75A0.75 0.75 0 0 1 98 71Z", Ink.Hull, alpha = 0.45f),
        Layer("M96.5 86L96.5 83A1.5 1.5 0 0 1 99.5 83L99.5 86Z", Ink.Hull, alpha = 0.45f),
        Layer("M96.25 53L99.75 53A0.75 0.75 0 0 1 100.5 53.75L100.5 57.75A0.75 0.75 0 0 1 99.75 58.5L96.25 58.5A0.75 0.75 0 0 1 95.5 57.75L95.5 53.75A0.75 0.75 0 0 1 96.25 53Z", Ink.Sun, alpha = 0.45f),
        Layer("M98 53L98 58.5", Ink.Hull, stroke = 1.2f, alpha = 0.45f),
        Layer("M93.5 58.25L102.5 58.25A0.75 0.75 0 0 1 103.25 59L103.25 59.5A0.75 0.75 0 0 1 102.5 60.25L93.5 60.25A0.75 0.75 0 0 1 92.75 59.5L92.75 59A0.75 0.75 0 0 1 93.5 58.25Z", Ink.Hull, alpha = 0.45f),
        Layer("M94 53.5L98 48.75L102 53.5Z", Ink.Accent, alpha = 0.45f),
        Layer("M97.1 48A0.9 0.9 0 1 1 98.9 48A0.9 0.9 0 1 1 97.1 48Z", Ink.Hull, alpha = 0.45f),
        Layer("M98 55.5L70 46L70 64L98 57Z", Ink.Glow, alpha = 0.35f, motion = Motion.Pulse, clip = true),
        Layer("M84 87C90 82 106 82 112 87Z", Ink.Rock, alpha = 0.45f, clip = true),
        Layer("M-0.5 40L58.5 40A5.5 5.5 0 0 1 64 45.5L64 45.5A5.5 5.5 0 0 1 58.5 51L-0.5 51A5.5 5.5 0 0 1 -6 45.5L-6 45.5A5.5 5.5 0 0 1 -0.5 40Z", Ink.Fog, alpha = 0.9f, motion = Motion.Drift, clip = true),
        Layer("M45.07 45.22L45.07 79.32", Ink.Hull, stroke = 2.5f, motion = Motion.Bob),
        Layer("M43.52 47.08C39.8 57 34.22 66.92 28.02 76.22L43.52 76.22Z", Ink.Sail, motion = Motion.Bob),
        Layer("M43.52 47.08C41.04 53.28 39.18 58.24 37.32 63.2L43.52 63.2Z", Ink.Shade, alpha = 0.06f, motion = Motion.Bob),
        Layer("M46.62 50.8C50.34 59.48 55.3 68.16 60.88 76.22L46.62 76.22Z", Ink.SailShade, motion = Motion.Bob),
        Layer("M45.69 44.6L50.96 46.46L45.69 48.32Z", Ink.Accent, motion = Motion.Bob),
        Layer("M23.68 78.08L68.32 78.08Q65.84 83.66 60.88 88.62L31.12 88.62Q26.16 83.66 23.68 78.08Z", Ink.Hull, motion = Motion.Bob),
        Layer("M27.4 81.49L64.6 81.49", Ink.HullStripe, stroke = 2f, motion = Motion.Bob),
        Layer("M4 88C7.67 85.61 11.33 85.61 15 88C18.67 90.39 22.33 90.39 26 88C29.67 85.61 33.33 85.61 37 88C40.67 90.39 44.33 90.39 48 88C51.67 85.61 55.33 85.61 59 88C62.67 90.39 66.33 90.39 70 88C73.67 85.61 77.33 85.61 81 88C84.67 90.39 88.33 90.39 92 88C95.67 85.61 99.33 85.61 103 88C106.67 90.39 110.33 90.39 114 88C117.67 85.61 121.33 85.61 125 88C126 88.65 127 88.65 128 88L128 124L4 124Z", Ink.Sea, motion = Motion.Wave, clip = true),
        Layer("M4 103C8.77 101.08 13.53 101.08 18.3 103C23.07 104.92 27.83 104.92 32.6 103C37.37 101.08 42.13 101.08 46.9 103C51.67 104.92 56.43 104.92 61.2 103C65.97 101.08 70.73 101.08 75.5 103C80.27 104.92 85.03 104.92 89.8 103C94.57 101.08 99.33 101.08 104.1 103C108.87 104.92 113.63 104.92 118.4 103C121.6 101.71 124.8 101.71 128 103L128 124L4 124Z", Ink.SeaDeep, motion = Motion.WaveAlt, clip = true),
        Layer("M4 88C7.67 85.61 11.33 85.61 15 88C18.67 90.39 22.33 90.39 26 88C29.67 85.61 33.33 85.61 37 88C40.67 90.39 44.33 90.39 48 88C51.67 85.61 55.33 85.61 59 88C62.67 90.39 66.33 90.39 70 88C73.67 85.61 77.33 85.61 81 88C84.67 90.39 88.33 90.39 92 88C95.67 85.61 99.33 85.61 103 88C106.67 90.39 110.33 90.39 114 88C117.67 85.61 121.33 85.61 125 88C126 88.65 127 88.65 128 88", Ink.Wave, stroke = 3.5f, motion = Motion.Wave, clip = true),
        Layer("M57.5 62L130.5 62A5.5 5.5 0 0 1 136 67.5L136 67.5A5.5 5.5 0 0 1 130.5 73L57.5 73A5.5 5.5 0 0 1 52 67.5L52 67.5A5.5 5.5 0 0 1 57.5 62Z", Ink.Fog, alpha = 0.9f, motion = Motion.DriftAlt, clip = true),
        Layer("M19 76L65 76A5 5 0 0 1 70 81L70 81A5 5 0 0 1 65 86L19 86A5 5 0 0 1 14 81L14 81A5 5 0 0 1 19 76Z", Ink.Fog, alpha = 0.85f, motion = Motion.Drift, clip = true),
        Layer("M65 96L125 96A5 5 0 0 1 130 101L130 101A5 5 0 0 1 125 106L65 106A5 5 0 0 1 60 101L60 101A5 5 0 0 1 65 96Z", Ink.Fog, alpha = 0.8f, motion = Motion.DriftAlt, clip = true),
    ),
)

private fun storm() = SceneSpec(
    pivot = Offset(62f, 82f),
    swing = Offset(66f, 60f),
    layers = listOf(
        Layer("M46 56L42.5 63", Ink.WaveSoft, stroke = 2.4f, motion = Motion.Twinkle),
        Layer("M55 58L51.5 65", Ink.WaveSoft, stroke = 2.4f, motion = Motion.TwinkleLate),
        Layer("M83 56L79.5 63", Ink.WaveSoft, stroke = 2.4f, motion = Motion.TwinkleAlt),
        Layer("M92 54L88.5 61", Ink.WaveSoft, stroke = 2.4f, motion = Motion.Twinkle),
        Layer("M70 46L62 60L69 60L64 72L78 55L71 55L76 46Z", Ink.Bolt, motion = Motion.Flash),
        Layer("M37 38A11 11 0 1 1 59 38A11 11 0 1 1 37 38ZM51 30A15 15 0 1 1 81 30A15 15 0 1 1 51 30ZM74 38A10 10 0 1 1 94 38A10 10 0 1 1 74 38ZM42.5 36L87.5 36A6.5 6.5 0 0 1 94 42.5L94 42.5A6.5 6.5 0 0 1 87.5 49L42.5 49A6.5 6.5 0 0 1 36 42.5L36 42.5A6.5 6.5 0 0 1 42.5 36Z", Ink.Cloud2, motion = Motion.Drift),
        Layer("M58 22Q64 18 72 20", Ink.Foam, stroke = 2f, alpha = 0.6f, motion = Motion.Drift),
        Layer("M42 74L82 74L75 86L49 86Z", Ink.Paper, motion = Motion.Toss),
        Layer("M50 74L62 51L74 74Z", Ink.PaperShade, motion = Motion.Toss),
        Layer("M62 51L74 74L62 74Z", Ink.Shade, alpha = 0.08f, motion = Motion.Toss),
        Layer("M42 74L50 74L54 80.5Z", Ink.PaperShade, motion = Motion.Toss),
        Layer("M82 74L74 74L70 80.5Z", Ink.PaperShade, motion = Motion.Toss),
        Layer("M4 86C6.33 81.74 8.67 81.74 11 86C13.33 90.26 15.67 90.26 18 86C20.33 81.74 22.67 81.74 25 86C27.33 90.26 29.67 90.26 32 86C34.33 81.74 36.67 81.74 39 86C41.33 90.26 43.67 90.26 46 86C48.33 81.74 50.67 81.74 53 86C55.33 90.26 57.67 90.26 60 86C62.33 81.74 64.67 81.74 67 86C69.33 90.26 71.67 90.26 74 86C76.33 81.74 78.67 81.74 81 86C83.33 90.26 85.67 90.26 88 86C90.33 81.74 92.67 81.74 95 86C97.33 90.26 99.67 90.26 102 86C104.33 81.74 106.67 81.74 109 86C111.33 90.26 113.67 90.26 116 86C118.33 81.74 120.67 81.74 123 86C124.67 89.04 126.33 89.04 128 86L128 124L4 124Z", Ink.Sea, motion = Motion.Wave, clip = true),
        Layer("M4 101C7.03 97.6 10.07 97.6 13.1 101C16.13 104.4 19.17 104.4 22.2 101C25.23 97.6 28.27 97.6 31.3 101C34.33 104.4 37.37 104.4 40.4 101C43.43 97.6 46.47 97.6 49.5 101C52.53 104.4 55.57 104.4 58.6 101C61.63 97.6 64.67 97.6 67.7 101C70.73 104.4 73.77 104.4 76.8 101C79.83 97.6 82.87 97.6 85.9 101C88.93 104.4 91.97 104.4 95 101C98.03 97.6 101.07 97.6 104.1 101C107.13 104.4 110.17 104.4 113.2 101C116.23 97.6 119.27 97.6 122.3 101C124.2 103.13 126.1 103.13 128 101L128 124L4 124Z", Ink.SeaDeep, motion = Motion.WaveAlt, clip = true),
        Layer("M4 86C6.33 81.74 8.67 81.74 11 86C13.33 90.26 15.67 90.26 18 86C20.33 81.74 22.67 81.74 25 86C27.33 90.26 29.67 90.26 32 86C34.33 81.74 36.67 81.74 39 86C41.33 90.26 43.67 90.26 46 86C48.33 81.74 50.67 81.74 53 86C55.33 90.26 57.67 90.26 60 86C62.33 81.74 64.67 81.74 67 86C69.33 90.26 71.67 90.26 74 86C76.33 81.74 78.67 81.74 81 86C83.33 90.26 85.67 90.26 88 86C90.33 81.74 92.67 81.74 95 86C97.33 90.26 99.67 90.26 102 86C104.33 81.74 106.67 81.74 109 86C111.33 90.26 113.67 90.26 116 86C118.33 81.74 120.67 81.74 123 86C124.67 89.04 126.33 89.04 128 86", Ink.Wave, stroke = 3.5f, motion = Motion.Wave, clip = true),
        Layer("M34 95C35.87 91.6 37.73 91.6 39.6 95C41.47 98.4 43.33 98.4 45.2 95C47.07 91.6 48.93 91.6 50.8 95C52.67 98.4 54.53 98.4 56.4 95C58.27 91.6 60.13 91.6 62 95C63.87 98.4 65.73 98.4 67.6 95C69.47 91.6 71.33 91.6 73.2 95C75.07 98.4 76.93 98.4 78.8 95C80.67 91.6 82.53 91.6 84.4 95C86.27 98.4 88.13 98.4 90 95C91.87 91.6 93.73 91.6 95.6 95C95.73 95.24 95.87 95.24 96 95", Ink.Foam, stroke = 2.5f, alpha = 0.9f, motion = Motion.WaveAlt, clip = true),
    ),
)

private fun logbook() = SceneSpec(
    pivot = Offset(66f, 60f),
    swing = Offset(108f, 78f),
    layers = listOf(
        Layer("M30 26Q30.64 29.36 34 30Q30.64 30.64 30 34Q29.36 30.64 26 30Q29.36 29.36 30 26Z", Ink.Star, motion = Motion.Twinkle),
        Layer("M44 17.8Q44.35 19.65 46.2 20Q44.35 20.35 44 22.2Q43.65 20.35 41.8 20Q43.65 19.65 44 17.8Z", Ink.Star, motion = Motion.TwinkleLate),
        Layer("M0 90L132 90A0 0 0 0 1 132 90L132 130A0 0 0 0 1 132 130L0 130A0 0 0 0 1 0 130L0 90A0 0 0 0 1 0 90Z", Ink.Wood, clip = true),
        Layer("M10 90.6L122 90.6", Ink.Foam, stroke = 1.2f, alpha = 0.3f, clip = true),
        Layer("M14 102L60 102", Ink.WoodShade, stroke = 1.5f, alpha = 0.6f, clip = true),
        Layer("M74 110L118 110", Ink.WoodShade, stroke = 1.5f, alpha = 0.6f, clip = true),
        Layer("M18 89L58 93L98 89L95 55L58 59L21 55Z", Ink.Hull),
        Layer("M22 52C33 48 47 48 58 54L58 89C47 84 33 84 22 87Z", Ink.Paper),
        Layer("M94 52C83 48 69 48 58 54L58 89C69 84 83 84 94 87Z", Ink.Paper),
        Layer("M58 54C54 51.5 50 50.4 46 50.2L46 85.5C50 85.8 54 87 58 89Z", Ink.Shade, alpha = 0.06f),
        Layer("M27 60Q40 56 54 61.2", Ink.PaperLine, stroke = 1.4f),
        Layer("M89 60Q76 56 62 61.2", Ink.PaperLine, stroke = 1.4f),
        Layer("M27 66.2Q40 62.2 54 67.4", Ink.PaperLine, stroke = 1.4f),
        Layer("M89 66.2Q76 62.2 62 67.4", Ink.PaperLine, stroke = 1.4f),
        Layer("M27 72.4Q40 68.4 54 73.6", Ink.PaperLine, stroke = 1.4f),
        Layer("M89 72.4Q76 68.4 62 73.6", Ink.PaperLine, stroke = 1.4f),
        Layer("M27 78.6Q40 74.6 54 79.8", Ink.PaperLine, stroke = 1.4f),
        Layer("M89 78.6Q76 74.6 62 79.8", Ink.PaperLine, stroke = 1.4f),
        Layer("M58 54L58 89", Ink.Shade, stroke = 1.2f, alpha = 0.18f),
        Layer("M66 86.5L66 98L68.5 95.5L71 98L71 86Z", Ink.Accent),
        Layer("M73.8 64A4.2 4.2 0 1 1 82.2 64A4.2 4.2 0 1 1 73.8 64Z", Ink.PaperLine, stroke = 1.2f),
        Layer("M78 59.5L79.2 64L78 68.5L76.8 64Z", Ink.PaperLine),
        Layer("M104 79L113 79A4 4 0 0 1 117 83L117 87A4 4 0 0 1 113 91L104 91A4 4 0 0 1 100 87L100 83A4 4 0 0 1 104 79Z", Ink.Hull),
        Layer("M105.1 75L111.9 75A1.6 1.6 0 0 1 113.5 76.6L113.5 78.4A1.6 1.6 0 0 1 111.9 80L105.1 80A1.6 1.6 0 0 1 103.5 78.4L103.5 76.6A1.6 1.6 0 0 1 105.1 75Z", Ink.Hull),
        Layer("M103 82L110 82", Ink.HullStripe, stroke = 1.4f, alpha = 0.7f),
        Layer("M107.45 76.08L96.97 39.55", Ink.SailShade, stroke = 1.6f, motion = Motion.Swing),
        Layer("M104.69 66.46C96.31 62.63 90.73 48.62 94.77 31.86C103.09 40.92 108.46 56.02 104.69 66.46Z", Ink.Sail, motion = Motion.Swing),
        Layer("M104.69 66.46C107.5 56.3 103.36 41.88 94.77 31.86L104.69 66.46Z", Ink.Shade, alpha = 0.08f, motion = Motion.Swing),
        Layer("M103.59 62.62L95.32 33.78", Ink.SailShade, stroke = 1.2f, motion = Motion.Swing),
    ),
)

private fun smoothSailing() = SceneSpec(
    pivot = Offset(66f, 60f),
    swing = Offset(66f, 60f),
    layers = listOf(
        Layer("M20 58A18 18 0 1 1 56 58A18 18 0 1 1 20 58Z", Ink.Glow, alpha = 0.6f, motion = Motion.Pulse),
        Layer("M26.5 58A11.5 11.5 0 1 1 49.5 58A11.5 11.5 0 1 1 26.5 58Z", Ink.Sun),
        Layer("M89 26L99 26A4 4 0 0 1 103 30L103 30A4 4 0 0 1 99 34L89 34A4 4 0 0 1 85 30L85 30A4 4 0 0 1 89 26ZM88.24 26A3.6 3.6 0 1 1 95.44 26A3.6 3.6 0 1 1 88.24 26ZM94.36 27A2.52 2.52 0 1 1 99.4 27A2.52 2.52 0 1 1 94.36 27Z", Ink.Cloud, motion = Motion.DriftAlt),
        Layer("M101 60L101 73L93 73Z", Ink.Sail),
        Layer("M103 63L103 73L109 73Z", Ink.SailShade),
        Layer("M101.8 59L101.8 74", Ink.Hull, stroke = 1.2f),
        Layer("M91 74L111 74L108 78L94 78Z", Ink.Hull),
        Layer("M4 78C8.67 76.67 13.33 76.67 18 78C22.67 79.33 27.33 79.33 32 78C36.67 76.67 41.33 76.67 46 78C50.67 79.33 55.33 79.33 60 78C64.67 76.67 69.33 76.67 74 78C78.67 79.33 83.33 79.33 88 78C92.67 76.67 97.33 76.67 102 78C106.67 79.33 111.33 79.33 116 78C120 76.86 124 76.86 128 78L128 124L4 124Z", Ink.Sea, motion = Motion.Wave, clip = true),
        Layer("M4 93C10.07 91.94 16.13 91.94 22.2 93C28.27 94.06 34.33 94.06 40.4 93C46.47 91.94 52.53 91.94 58.6 93C64.67 94.06 70.73 94.06 76.8 93C82.87 91.94 88.93 91.94 95 93C101.07 94.06 107.13 94.06 113.2 93C118.13 92.13 123.07 92.13 128 93L128 124L4 124Z", Ink.SeaDeep, motion = Motion.WaveAlt, clip = true),
        Layer("M4 78C8.67 76.67 13.33 76.67 18 78C22.67 79.33 27.33 79.33 32 78C36.67 76.67 41.33 76.67 46 78C50.67 79.33 55.33 79.33 60 78C64.67 76.67 69.33 76.67 74 78C78.67 79.33 83.33 79.33 88 78C92.67 76.67 97.33 76.67 102 78C106.67 79.33 111.33 79.33 116 78C120 76.86 124 76.86 128 78", Ink.Wave, stroke = 3.5f, motion = Motion.Wave, clip = true),
        Layer("M30 88L46 88", Ink.Foam, stroke = 2.5f, motion = Motion.Twinkle, clip = true),
        Layer("M33 95L43 95", Ink.Foam, stroke = 2.5f, motion = Motion.TwinkleAlt, clip = true),
        Layer("M35.5 102L40.5 102", Ink.Foam, stroke = 2.5f, motion = Motion.TwinkleLate, clip = true),
        Layer("M60 98C63 96.67 66 96.67 69 98C72 99.33 75 99.33 78 98C81 96.67 84 96.67 87 98C90 99.33 93 99.33 96 98C97.33 97.41 98.67 97.41 100 98", Ink.Foam, stroke = 2.5f, alpha = 0.8f, motion = Motion.WaveAlt, clip = true),
        Layer("M57.9 43.15C62.5 35.1 69.4 35.1 74 42.58C78.6 35.1 85.5 35.1 90.1 43.15C84.35 39.12 78.6 39.12 74 45.45C69.4 39.12 63.65 39.12 57.9 43.15Z", Ink.Bird, motion = Motion.Glide),
        Layer("M90.2 46.7C93 41.8 97.2 41.8 100 46.35C102.8 41.8 107 41.8 109.8 46.7C106.3 44.25 102.8 44.25 100 48.1C97.2 44.25 93.7 44.25 90.2 46.7Z", Ink.Bird, motion = Motion.GlideAlt),
    ),
)

private fun crew() = SceneSpec(
    pivot = Offset(66f, 60f),
    swing = Offset(100f, 28f),
    layers = listOf(
        Layer("M30 28L42 28A4 4 0 0 1 46 32L46 32A4 4 0 0 1 42 36L30 36A4 4 0 0 1 26 32L26 32A4 4 0 0 1 30 28ZM29.6 28A4 4 0 1 1 37.6 28A4 4 0 1 1 29.6 28ZM36.4 29A2.8 2.8 0 1 1 42 29A2.8 2.8 0 1 1 36.4 29Z", Ink.Cloud, motion = Motion.Drift),
        Layer("M100 54L100 22", Ink.Hull, stroke = 2.5f),
        Layer("M100 22.5L115 26.5L100 30.5Z", Ink.Accent, motion = Motion.Swing),
        Layer("M62 52L126 52L126 94L76 94Q66 76 62 52Z", Ink.Hull, clip = true),
        Layer("M62 52L126 52", Ink.HullStripe, stroke = 2.5f, clip = true),
        Layer("M65.5 63L126 63", Ink.Accent, stroke = 3f, clip = true),
        Layer("M94.4 74A3.6 3.6 0 1 1 101.6 74A3.6 3.6 0 1 1 94.4 74Z", Ink.Sun),
        Layer("M94.4 74A3.6 3.6 0 1 1 101.6 74A3.6 3.6 0 1 1 94.4 74Z", Ink.HullStripe, stroke = 1.6f),
        Layer("M108.4 74A3.6 3.6 0 1 1 115.6 74A3.6 3.6 0 1 1 108.4 74Z", Ink.Sun),
        Layer("M108.4 74A3.6 3.6 0 1 1 115.6 74A3.6 3.6 0 1 1 108.4 74Z", Ink.HullStripe, stroke = 1.6f),
        Layer("M75.8 72A6.2 6.2 0 1 1 88.2 72A6.2 6.2 0 1 1 75.8 72Z", Ink.Paper, stroke = 4f),
        Layer("M87.62 74.62A6.2 6.2 0 0 1 84.62 77.62", Ink.Accent, stroke = 4f),
        Layer("M79.38 77.62A6.2 6.2 0 0 1 76.38 74.62", Ink.Accent, stroke = 4f),
        Layer("M76.38 69.38A6.2 6.2 0 0 1 79.38 66.38", Ink.Accent, stroke = 4f),
        Layer("M84.62 66.38A6.2 6.2 0 0 1 87.62 69.38", Ink.Accent, stroke = 4f),
        Layer("M-2 84L44 84A2 2 0 0 1 46 86L46 88A2 2 0 0 1 44 90L-2 90A2 2 0 0 1 -4 88L-4 86A2 2 0 0 1 -2 84Z", Ink.Wood, clip = true),
        Layer("M33.5 88L35.5 88A1.5 1.5 0 0 1 37 89.5L37 116.5A1.5 1.5 0 0 1 35.5 118L33.5 118A1.5 1.5 0 0 1 32 116.5L32 89.5A1.5 1.5 0 0 1 33.5 88Z", Ink.WoodShade, clip = true),
        Layer("M42 84L66 56", Ink.Wood, stroke = 4.5f),
        Layer("M41.5 75Q52 66 63 48", Ink.WoodShade, stroke = 1.5f),
        Layer("M42 84L41.5 75", Ink.WoodShade, stroke = 1.5f),
        Layer("M64 57L63 48", Ink.WoodShade, stroke = 1.5f),
        Layer("M19 84C19 73.5 37 73.5 37 84Z", Ink.Paper),
        Layer("M28 75C33 75.5 36.5 79 37 84L28 84Z", Ink.Shade, alpha = 0.08f),
        Layer("M18.2 82L37.8 82A1.7 1.7 0 0 1 39.5 83.7L39.5 83.7A1.7 1.7 0 0 1 37.8 85.4L18.2 85.4A1.7 1.7 0 0 1 16.5 83.7L16.5 83.7A1.7 1.7 0 0 1 18.2 82Z", Ink.Hull),
        Layer("M26 74.2A2 2 0 1 1 30 74.2A2 2 0 1 1 26 74.2Z", Ink.Accent),
        Layer("M4 94C7.67 91.61 11.33 91.61 15 94C18.67 96.39 22.33 96.39 26 94C29.67 91.61 33.33 91.61 37 94C40.67 96.39 44.33 96.39 48 94C51.67 91.61 55.33 91.61 59 94C62.67 96.39 66.33 96.39 70 94C73.67 91.61 77.33 91.61 81 94C84.67 96.39 88.33 96.39 92 94C95.67 91.61 99.33 91.61 103 94C106.67 96.39 110.33 96.39 114 94C117.67 91.61 121.33 91.61 125 94C126 94.65 127 94.65 128 94L128 124L4 124Z", Ink.Sea, motion = Motion.Wave, clip = true),
        Layer("M4 109C8.77 107.08 13.53 107.08 18.3 109C23.07 110.92 27.83 110.92 32.6 109C37.37 107.08 42.13 107.08 46.9 109C51.67 110.92 56.43 110.92 61.2 109C65.97 107.08 70.73 107.08 75.5 109C80.27 110.92 85.03 110.92 89.8 109C94.57 107.08 99.33 107.08 104.1 109C108.87 110.92 113.63 110.92 118.4 109C121.6 107.71 124.8 107.71 128 109L128 124L4 124Z", Ink.SeaDeep, motion = Motion.WaveAlt, clip = true),
        Layer("M4 94C7.67 91.61 11.33 91.61 15 94C18.67 96.39 22.33 96.39 26 94C29.67 91.61 33.33 91.61 37 94C40.67 96.39 44.33 96.39 48 94C51.67 91.61 55.33 91.61 59 94C62.67 96.39 66.33 96.39 70 94C73.67 91.61 77.33 91.61 81 94C84.67 96.39 88.33 96.39 92 94C95.67 91.61 99.33 91.61 103 94C106.67 96.39 110.33 96.39 114 94C117.67 91.61 121.33 91.61 125 94C126 94.65 127 94.65 128 94", Ink.Wave, stroke = 3.5f, motion = Motion.Wave, clip = true),
    ),
)

private fun compass() = SceneSpec(
    pivot = Offset(66f, 60f),
    swing = Offset(66f, 56f),
    layers = listOf(
        Layer("M102 22.4Q102.58 25.42 105.6 26Q102.58 26.58 102 29.6Q101.42 26.58 98.4 26Q101.42 25.42 102 22.4Z", Ink.Star, motion = Motion.Twinkle),
        Layer("M28 41.4Q28.42 43.58 30.6 44Q28.42 44.42 28 46.6Q27.58 44.42 25.4 44Q27.58 43.58 28 41.4Z", Ink.Star, motion = Motion.TwinkleAlt),
        Layer("M4 98C7.33 96 10.67 96 14 98C17.33 100 20.67 100 24 98C27.33 96 30.67 96 34 98C37.33 100 40.67 100 44 98C47.33 96 50.67 96 54 98C57.33 100 60.67 100 64 98C67.33 96 70.67 96 74 98C77.33 100 80.67 100 84 98C87.33 96 90.67 96 94 98C97.33 100 100.67 100 104 98C107.33 96 110.67 96 114 98C117.33 100 120.67 100 124 98C125.33 97.2 126.67 97.2 128 98L128 124L4 124Z", Ink.Sea, motion = Motion.Wave, clip = true),
        Layer("M4 113C8.33 111.4 12.67 111.4 17 113C21.33 114.6 25.67 114.6 30 113C34.33 111.4 38.67 111.4 43 113C47.33 114.6 51.67 114.6 56 113C60.33 111.4 64.67 111.4 69 113C73.33 114.6 77.67 114.6 82 113C86.33 111.4 90.67 111.4 95 113C99.33 114.6 103.67 114.6 108 113C112.33 111.4 116.67 111.4 121 113C123.33 113.86 125.67 113.86 128 113L128 124L4 124Z", Ink.SeaDeep, motion = Motion.WaveAlt, clip = true),
        Layer("M4 98C7.33 96 10.67 96 14 98C17.33 100 20.67 100 24 98C27.33 96 30.67 96 34 98C37.33 100 40.67 100 44 98C47.33 96 50.67 96 54 98C57.33 100 60.67 100 64 98C67.33 96 70.67 96 74 98C77.33 100 80.67 100 84 98C87.33 96 90.67 96 94 98C97.33 100 100.67 100 104 98C107.33 96 110.67 96 114 98C117.33 100 120.67 100 124 98C125.33 97.2 126.67 97.2 128 98", Ink.Wave, stroke = 3.5f, motion = Motion.Wave, clip = true),
        Layer("M62 22.5A4 4 0 1 1 70 22.5A4 4 0 1 1 62 22.5Z", Ink.Hull, stroke = 2.6f),
        Layer("M36 57.5A30 30 0 1 1 96 57.5A30 30 0 1 1 36 57.5Z", Ink.Shade, alpha = 0.12f),
        Layer("M36 56A30 30 0 1 1 96 56A30 30 0 1 1 36 56Z", Ink.Hull),
        Layer("M39.5 56A26.5 26.5 0 1 1 92.5 56A26.5 26.5 0 1 1 39.5 56Z", Ink.Sun),
        Layer("M42 56A24 24 0 1 1 90 56A24 24 0 1 1 42 56Z", Ink.Paper),
        Layer("M66 36.5L66 33", Ink.PaperLine, stroke = 1.4f),
        Layer("M80.85 41.15L82.26 39.74", Ink.PaperLine, stroke = 1.4f),
        Layer("M85.5 56L89 56", Ink.PaperLine, stroke = 1.4f),
        Layer("M80.85 70.85L82.26 72.26", Ink.PaperLine, stroke = 1.4f),
        Layer("M66 75.5L66 79", Ink.PaperLine, stroke = 1.4f),
        Layer("M51.15 70.85L49.74 72.26", Ink.PaperLine, stroke = 1.4f),
        Layer("M46.5 56L43 56", Ink.PaperLine, stroke = 1.4f),
        Layer("M51.15 41.15L49.74 39.74", Ink.PaperLine, stroke = 1.4f),
        Layer("M75.19 46.81L69.4 56L75.19 65.19L66 59.4L56.81 65.19L62.6 56L56.81 46.81L66 52.6Z", Ink.PaperShade),
        Layer("M66 38.5L68.55 53.45L83.5 56L68.55 58.55L66 73.5L63.45 58.55L48.5 56L63.45 53.45Z", Ink.PaperLine, alpha = 0.55f),
        Layer("M66 37L69.6 56L62.4 56Z", Ink.Accent, motion = Motion.Wander),
        Layer("M66 75L69.6 56L62.4 56Z", Ink.SailShade, motion = Motion.Wander),
        Layer("M63.6 56A2.4 2.4 0 1 1 68.4 56A2.4 2.4 0 1 1 63.6 56Z", Ink.Hull),
    ),
)

private fun setSail() = SceneSpec(
    pivot = Offset(58f, 84f),
    swing = Offset(66f, 60f),
    layers = listOf(
        Layer("M56 70A28 28 0 1 1 112 70A28 28 0 1 1 56 70Z", Ink.Glow, alpha = 0.55f, motion = Motion.Pulse),
        Layer("M65 70A19 19 0 1 1 103 70A19 19 0 1 1 65 70Z", Ink.Sun),
        Layer("M28 32L40 32A4 4 0 0 1 44 36L44 36A4 4 0 0 1 40 40L28 40A4 4 0 0 1 24 36L24 36A4 4 0 0 1 28 32ZM27.6 32A4 4 0 1 1 35.6 32A4 4 0 1 1 27.6 32ZM34.4 33A2.8 2.8 0 1 1 40 33A2.8 2.8 0 1 1 34.4 33Z", Ink.Cloud, motion = Motion.Drift),
        Layer("M77.4 30.9C81 24.6 86.4 24.6 90 30.45C93.6 24.6 99 24.6 102.6 30.9C98.1 27.75 93.6 27.75 90 32.7C86.4 27.75 81.9 27.75 77.4 30.9Z", Ink.Bird, motion = Motion.Glide),
        Layer("M97.6 42.6C100 38.4 103.6 38.4 106 42.3C108.4 38.4 112 38.4 114.4 42.6C111.4 40.5 108.4 40.5 106 43.8C103.6 40.5 100.6 40.5 97.6 42.6Z", Ink.Bird, motion = Motion.GlideAlt),
        Layer("M56.83 32.18L56.83 75.08", Ink.Hull, stroke = 2.5f, motion = Motion.Bob),
        Layer("M54.88 34.52C50.2 47 43.18 59.48 35.38 71.18L54.88 71.18Z", Ink.Sail, motion = Motion.Bob),
        Layer("M54.88 34.52C51.76 42.32 49.42 48.56 47.08 54.8L54.88 54.8Z", Ink.Shade, alpha = 0.06f, motion = Motion.Bob),
        Layer("M58.78 39.2C63.46 50.12 69.7 61.04 76.72 71.18L58.78 71.18Z", Ink.SailShade, motion = Motion.Bob),
        Layer("M57.61 31.4L64.24 33.74L57.61 36.08Z", Ink.Accent, motion = Motion.Bob),
        Layer("M29.92 73.52L86.08 73.52Q82.96 80.54 76.72 86.78L39.28 86.78Q33.04 80.54 29.92 73.52Z", Ink.Hull, motion = Motion.Bob),
        Layer("M34.6 77.81L81.4 77.81", Ink.HullStripe, stroke = 2f, motion = Motion.Bob),
        Layer("M4 84C7.67 81.87 11.33 81.87 15 84C18.67 86.13 22.33 86.13 26 84C29.67 81.87 33.33 81.87 37 84C40.67 86.13 44.33 86.13 48 84C51.67 81.87 55.33 81.87 59 84C62.67 86.13 66.33 86.13 70 84C73.67 81.87 77.33 81.87 81 84C84.67 86.13 88.33 86.13 92 84C95.67 81.87 99.33 81.87 103 84C106.67 86.13 110.33 86.13 114 84C117.67 81.87 121.33 81.87 125 84C126 84.58 127 84.58 128 84L128 124L4 124Z", Ink.Sea, motion = Motion.Wave, clip = true),
        Layer("M4 99C8.77 97.3 13.53 97.3 18.3 99C23.07 100.7 27.83 100.7 32.6 99C37.37 97.3 42.13 97.3 46.9 99C51.67 100.7 56.43 100.7 61.2 99C65.97 97.3 70.73 97.3 75.5 99C80.27 100.7 85.03 100.7 89.8 99C94.57 97.3 99.33 97.3 104.1 99C108.87 100.7 113.63 100.7 118.4 99C121.6 97.86 124.8 97.86 128 99L128 124L4 124Z", Ink.SeaDeep, motion = Motion.WaveAlt, clip = true),
        Layer("M4 84C7.67 81.87 11.33 81.87 15 84C18.67 86.13 22.33 86.13 26 84C29.67 81.87 33.33 81.87 37 84C40.67 86.13 44.33 86.13 48 84C51.67 81.87 55.33 81.87 59 84C62.67 86.13 66.33 86.13 70 84C73.67 81.87 77.33 81.87 81 84C84.67 86.13 88.33 86.13 92 84C95.67 81.87 99.33 81.87 103 84C106.67 86.13 110.33 86.13 114 84C117.67 81.87 121.33 81.87 125 84C126 84.58 127 84.58 128 84", Ink.Wave, stroke = 3.5f, motion = Motion.Wave, clip = true),
        Layer("M20 92Q28 89 34 90", Ink.Foam, stroke = 2.2f, motion = Motion.WaveAlt, clip = true),
        Layer("M26 97Q32 95 38 95.5", Ink.Foam, stroke = 2.2f, alpha = 0.7f, motion = Motion.Wave, clip = true),
        Layer("M76 92L92 92", Ink.Foam, stroke = 2.5f, motion = Motion.Twinkle, clip = true),
        Layer("M80 98L88 98", Ink.Foam, stroke = 2.5f, motion = Motion.TwinkleAlt, clip = true),
    ),
)
