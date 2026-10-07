package app.svan;
import android.os.ParcelFileDescriptor;
// Fixed read-only reports. No commands, grants or caller paths.
interface IAudioReports {
    void destroy() = 16777114;
    ParcelFileDescriptor openReport(int report) = 0;
}
