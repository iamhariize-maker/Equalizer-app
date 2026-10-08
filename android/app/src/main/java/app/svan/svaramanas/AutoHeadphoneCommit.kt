package app.svan.svaramanas

/** Validate a downloaded correction again on the UI thread before changing the current tuning. */
internal object AutoHeadphoneCommit {
    fun allowed(request: SmartRequest, currentDevice: String?, expectedDevice: String,
                expectedTuning: Any?, currentTuning: Any?): Boolean =
        request.enabled && request.mode == SmartMode.SVARESA && request.autoHeadphone &&
            currentDevice == expectedDevice && currentTuning === expectedTuning
}
