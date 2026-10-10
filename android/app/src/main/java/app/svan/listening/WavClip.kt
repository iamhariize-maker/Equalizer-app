package app.svan.listening

/** Stereo float samples for the in-app release quality checks. Synthetic input only: Svan never loads user audio. */
data class WavClip(val rate: Int, val samples: FloatArray)
