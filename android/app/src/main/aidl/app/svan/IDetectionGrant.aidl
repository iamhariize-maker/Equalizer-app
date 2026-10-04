package app.svan;

// A single-purpose, user-authorized Shizuku service. No arbitrary shell input.
interface IDetectionGrant {
    String enableDetection() = 1;
    void destroy() = 16777114;
}
