package app.svan.lab;

import java.io.*;

public final class WavWriter {
  public static void write(OutputStream output, int rate) throws IOException {
    // Quiet stereo PCM16: silence, impulse, log sweep, and a multitone tail.
    int frames = 20 * rate, bytes = frames * 4;
    DataOutputStream out = new DataOutputStream(new BufferedOutputStream(output));
    out.writeBytes("RIFF");
    i32(out, bytes + 36);
    out.writeBytes("WAVEfmt ");
    i32(out, 16);
    i16(out, 1);
    i16(out, 2);
    i32(out, rate);
    i32(out, rate * 4);
    i16(out, 4);
    i16(out, 16);
    out.writeBytes("data");
    i32(out, bytes);
    double phase = 0;
    for (int n = 0; n < frames; n++) {
      double t = n / (double) rate, v = 0;
      if (n == rate) v = .1;
      if (t >= 2 && t < 14) {
        double elapsed = t - 2, f = 20 * Math.pow(1000, elapsed / 12);
        double fade = Math.min(1, Math.min(elapsed / .05, (14 - t) / .05));
        v = .025 * fade * Math.sin(phase);
        phase = (phase + 2 * Math.PI * f / rate) % (2 * Math.PI);
      }
      if (t >= 16 && t < 19) {
        double fade = Math.min(1, Math.min((t - 16) / .05, (19 - t) / .05));
        for (double f : new double[] {31.5, 60, 125, 250, 1000, 8000})
          v += .004 * fade * Math.sin(2 * Math.PI * f * t);
      }
      int sample = (int) Math.round(v * 32767);
      i16(out, sample);
      i16(out, sample);
    }
    out.flush();
  }

  private static void i16(DataOutputStream o, int n) throws IOException {
    o.write(n & 255);
    o.write(n >>> 8 & 255);
  }

  private static void i32(DataOutputStream o, int n) throws IOException {
    i16(o, n);
    i16(o, n >>> 16);
  }
}
