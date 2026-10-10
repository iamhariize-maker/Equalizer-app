package app.svan.lab.core;

import java.io.*;
import java.nio.*;
import java.util.*;

/** Offline control planner. All curves are AOSP-reference predictions. */
public final class Planner {
  public static final class Filter {
    public int type;
    public double hz, gain, q;

    public Filter(int t, double f, double g, double q) {
      type = t;
      hz = f;
      gain = g;
      this.q = q;
    }

    public Filter copy() {
      return new Filter(type, hz, gain, q);
    }
  }

  public static final class Model {
    public int rate, block, bands;
    public int[] stops;
    public double[] frequencies, fixed, modFrequencies;
    public double[][] matrix;
    public double[][][] quadratics;

    public static Model read(InputStream source) throws IOException {
      byte[] bytes;
      try (InputStream input = source;
          ByteArrayOutputStream output = new ByteArrayOutputStream()) {
        byte[] chunk = new byte[16384];
        int count;
        while ((count = input.read(chunk)) != -1) {
          cancelled();
          if (output.size() + count > 12 * 1024 * 1024)
            throw new IOException("Model exceeds size limit");
          output.write(chunk, 0, count);
        }
        bytes = output.toByteArray();
      }
      if (bytes.length < 24) throw new IOException("Truncated model header");
      ByteBuffer in = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
      {
        if (in.getInt() != 0x44504d31) throw new IOException("Unknown model format");
        Model m = new Model();
        m.rate = in.getInt();
        m.block = in.getInt();
        m.bands = in.getInt();
        int f = in.getInt(), r = in.getInt();
        if (m.bands < 1 || m.bands > 128 || f < 1 || f > 1000 || r < 1 || r > 128)
          throw new IOException("Invalid model size");
        long floats = f + (long) f * m.bands + f + r + (long) r * (m.bands + 1) * (m.bands + 1);
        if (in.remaining() != 4L * (m.bands + floats))
          throw new IOException("Model length does not match its dimensions");
        m.stops = new int[m.bands];
        for (int i = 0; i < m.bands; i++) m.stops[i] = in.getInt();
        FloatBuffer values = in.asFloatBuffer();
        m.frequencies = readVector(values, f);
        m.matrix = new double[f][];
        for (int i = 0; i < f; i++) m.matrix[i] = readVector(values, m.bands);
        m.fixed = readVector(values, f);
        m.modFrequencies = readVector(values, r);
        m.quadratics = new double[r][m.bands + 1][];
        for (int k = 0; k < r; k++) {
          cancelled();
          for (int i = 0; i <= m.bands; i++) m.quadratics[k][i] = readVector(values, m.bands + 1);
        }
        return m;
      }
    }

    private static double[] readVector(FloatBuffer in, int n) throws IOException {
      float[] values = new float[n];
      in.get(values);
      double[] v = new double[n];
      for (int i = 0; i < n; i++) {
        v[i] = values[i];
        if (!Double.isFinite(v[i])) throw new IOException("Non-finite model");
      }
      return v;
    }

    /** Collapse controls and their exact quadratic model together for vendor limits. */
    public Model reduce(int count) {
      if (count >= bands) return this;
      if (count < 4) throw new IllegalArgumentException("At least four bands");
      Model m = new Model();
      m.rate = rate;
      m.block = block;
      m.bands = count;
      m.stops = new int[count];
      m.frequencies = frequencies;
      m.fixed = fixed;
      m.modFrequencies = modFrequencies;
      int[] map = new int[bands + 1];
      int keep = Math.min(count / 2, bands / 3);
      for (int i = 0; i < bands; i++) {
        map[i] = i < keep ? i : keep + (i - keep) * (count - keep) / (bands - keep);
        m.stops[map[i]] = stops[i];
      }
      map[bands] = count;
      m.matrix = new double[frequencies.length][count];
      for (int f = 0; f < frequencies.length; f++)
        for (int i = 0; i < bands; i++) m.matrix[f][map[i]] += matrix[f][i];
      m.quadratics = new double[modFrequencies.length][count + 1][count + 1];
      for (int f = 0; f < modFrequencies.length; f++)
        for (int i = 0; i <= bands; i++)
          for (int j = 0; j <= bands; j++) m.quadratics[f][map[i]][map[j]] += quadratics[f][i][j];
      return m;
    }
  }

  public static final class EqTable {
    private final Map<String, double[]> data = new HashMap<>();

    public EqTable(InputStream stream) throws IOException {
      try (BufferedReader r = new BufferedReader(new InputStreamReader(stream))) {
        r.readLine();
        String line;
        while ((line = r.readLine()) != null) {
          String[] p = line.split(",");
          if (p.length != 7) throw new IOException("Invalid EQ table");
          double[] c = new double[4];
          for (int i = 0; i < 4; i++) c[i] = Double.parseDouble(p[i + 3]);
          data.put(p[0] + ":" + p[1] + ":" + p[2], c);
        }
      }
    }

    public double db(double f, int rate, int g0, int g1) {
      return band(f, rate, 60, g0) + band(f, rate, 230, g1);
    }

    private double band(double f, int rate, int fc, int g) {
      // Zero gain is the exact identity; avoid evaluating two identical
      // transfer functions on every FFT bin and fitting sample.
      if (g == 0) return 0;
      double[] c = data.get(rate + ":" + fc + ":" + g);
      if (c == null) throw new IllegalArgumentException("No EQ model");
      double a = c[0], b1 = c[1], b2 = c[2], gain = c[3];
      return response(
          f, rate, new double[] {1 + gain * a, -b1, -b2 - gain * a}, new double[] {1, -b1, -b2});
    }
  }

  public static final class Plan {
    public Model model;
    public double[] gains, curve, target;
    public int eq0, eq1;
    public double rms, maxError, modulationDb, baselineRms, attenuationDb, weight;
    public boolean hybrid, guardPassed;
    public String origin = "AOSP reference model";

    public double cutoff(int i) {
      return Math.max(
          model.stops[i] * (double) model.rate / model.block,
          model.rate / (double) model.block * .25);
    }
  }

  private final EqTable eq;
  private java.util.function.DoubleUnaryOperator suppliedResponse;

  /** Fits a frozen response from Svan's shared curve engine, including its combined smart layers. */
  public Plan planResponse(Model m, java.util.function.DoubleUnaryOperator response, boolean blend, double margin) {
    suppliedResponse = response;
    try {
      return plan(m, Collections.singletonList(new Filter(0, 1000, 1, 1)), blend, margin);
    } finally {
      suppliedResponse = null;
    }
  }

  private double desired(List<Filter> filters, double f, int rate) {
    return suppliedResponse == null ? target(filters, f, rate) : suppliedResponse.applyAsDouble(f);
  }

  public Planner(EqTable eq) {
    this.eq = eq;
  }

  public Plan plan(Model m, List<Filter> filters, boolean blend, double margin) {
    check(filters, m.rate);
    cancelled();
    boolean unity = true;
    for (Filter f : filters) if (f.gain != 0) unity = false;
    if (unity) {
      // Flat means unity controls. Preserve the reference window's tiny
      // insertion loss instead of repeatedly fitting that window artifact.
      double[] values = new double[m.bands];
      Arrays.fill(values, 1);
      Plan flat = evaluate(m, filters, values, 0, 0);
      flat.guardPassed = true;
      flat.baselineRms = flat.rms;
      flat.attenuationDb = -Math.max(0, margin);
      return flat;
    }
    double[] prior = initial(m, filters, 0, 0);
    double limit = modulation(m, filters, prior, 0, 0) + .5;
    Plan base = guarded(m, filters, 0, 0, limit), best = base;
    if (blend) {
      int[][] candidates = {{3, 0}, {6, 0}, {6, -2}, {9, -2}, {-3, -2}, {-6, -2}};
      for (int[] g : candidates) {
        cancelled();
        Plan p = guarded(m, filters, g[0], g[1], limit);
        if (p.guardPassed && p.rms < best.rms) best = p;
      }
      if (!(base.rms - best.rms >= .01
          && best.rms <= .75 * base.rms
          && best.modulationDb <= base.modulationDb)) best = base;
    }
    best.baselineRms = base.rms;
    best.hybrid = best.eq0 != 0 || best.eq1 != 0;
    double maximum = 0;
    for (double v : best.curve) maximum = Math.max(maximum, v);
    // A user-visible operating margin, not an arbitrary-waveform peak bound.
    best.attenuationDb = -(maximum + Math.max(0, margin));
    return best;
  }

  private Plan guarded(Model m, List<Filter> filters, int g0, int g1, double limit) {
    Plan p = null;
    for (double weight : new double[] {1, 2, 4, 8, 16, 32, 64, 128, 256}) {
      cancelled();
      p = fit(m, filters, g0, g1, weight);
      if (p.modulationDb <= limit) {
        p.guardPassed = true;
        return p;
      }
    }
    // The sampled baseline is a feasible modulation reference. Do not return
    // an unguarded deconvolution as though it passed the numerical gate.
    if (g0 == 0 && g1 == 0) {
      p = evaluate(m, filters, initial(m, filters, 0, 0), 0, 0);
      p.guardPassed = true;
      p.weight = 0;
    }
    return p;
  }

  private double[] initial(Model m, List<Filter> filters, int g0, int g1) {
    double[] x = new double[m.bands];
    double lower = linear(-18), upper = linear(12);
    int start = 0;
    for (int b = 0; b < m.bands; b++) {
      double sum = 0;
      for (int k = start; k <= m.stops[b]; k++) {
        double f = k * (double) m.rate / m.block;
        sum += linear(desired(filters, f, m.rate) - eq.db(f, m.rate, g0, g1));
      }
      x[b] = clamp(sum / (m.stops[b] - start + 1), lower, upper);
      start = m.stops[b] + 1;
    }
    return x;
  }

  private Plan fit(Model m, List<Filter> filters, int g0, int g1, double weight) {
    int n = m.bands, nf = m.frequencies.length;
    double lower = linear(-18), upper = linear(12);
    double[][] h = new double[n][n];
    double[] rhs = new double[n];
    double[] x = initial(m, filters, g0, g1), row = new double[n];
    for (int f = 0; f < nf; f++) {
      double ratio =
          linear(
              eq.db(m.frequencies[f], m.rate, g0, g1) - desired(filters, m.frequencies[f], m.rate));
      double b = 1 - m.fixed[f] * ratio;
      for (int i = 0; i < n; i++) row[i] = m.matrix[f][i] * ratio;
      for (int i = 0; i < n; i++) {
        rhs[i] += row[i] * b / nf;
        for (int j = 0; j <= i; j++) h[i][j] += row[i] * row[j] / nf;
      }
    }
    for (int f = 0; f < m.modFrequencies.length; f++) {
      double ratio =
          linear(
              eq.db(m.modFrequencies[f], m.rate, g0, g1)
                  - desired(filters, m.modFrequencies[f], m.rate));
      double w = weight * ratio * ratio / m.modFrequencies.length;
      double[][] q = m.quadratics[f];
      for (int i = 0; i < n; i++) {
        rhs[i] -= w * q[i][n];
        for (int j = 0; j <= i; j++) h[i][j] += w * q[i][j];
      }
    }
    for (int i = 0; i < n; i++) {
      h[i][i] += .006 * .006 / nf;
      rhs[i] += .006 * .006 * x[i] / nf;
      for (int j = 0; j < i; j++) h[j][i] = h[i][j];
    }
    double[] gradient = new double[n];
    for (int i = 0; i < n; i++) {
      gradient[i] = -rhs[i];
      for (int j = 0; j < n; j++) gradient[i] += h[i][j] * x[j];
    }
    for (int pass = 0; pass < 1400; pass++) {
      if ((pass & 31) == 0) cancelled();
      double largest = 0;
      for (int i = 0; i < n; i++) {
        double next = clamp(x[i] - gradient[i] / Math.max(h[i][i], 1e-15), lower, upper);
        double delta = next - x[i];
        x[i] = next;
        largest = Math.max(largest, Math.abs(delta));
        if (delta != 0) for (int j = 0; j < n; j++) gradient[j] += h[j][i] * delta;
      }
      if (largest < 1e-7) break;
    }
    Plan p = evaluate(m, filters, x, g0, g1);
    p.weight = weight;
    return p;
  }

  private Plan evaluate(Model m, List<Filter> filters, double[] x, int g0, int g1) {
    Plan p = new Plan();
    p.model = m;
    p.eq0 = g0;
    p.eq1 = g1;
    p.gains = new double[x.length];
    for (int i = 0; i < x.length; i++) p.gains[i] = db(x[i]);
    p.curve = new double[m.frequencies.length];
    p.target = new double[p.curve.length];
    int count = 0;
    for (int f = 0; f < p.curve.length; f++) {
      double v = m.fixed[f];
      for (int i = 0; i < x.length; i++) v += m.matrix[f][i] * x[i];
      p.curve[f] = db(v) + eq.db(m.frequencies[f], m.rate, g0, g1);
      p.target[f] = desired(filters, m.frequencies[f], m.rate);
      if (m.frequencies[f] <= 300) {
        double error = p.curve[f] - p.target[f];
        p.rms += error * error;
        p.maxError = Math.max(p.maxError, Math.abs(error));
        count++;
      }
    }
    p.rms = Math.sqrt(p.rms / count);
    p.modulationDb = modulation(m, filters, x, g0, g1);
    return p;
  }

  private double modulation(Model m, List<Filter> filters, double[] values, int g0, int g1) {
    double[] x = Arrays.copyOf(values, values.length + 1);
    x[values.length] = 1;
    double max = 1e-30;
    for (int f = 0; f < m.modFrequencies.length; f++) {
      double energy = 0;
      for (int i = 0; i < x.length; i++)
        for (int j = 0; j < x.length; j++) energy += x[i] * m.quadratics[f][i][j] * x[j];
      double ratio =
          linear(
              eq.db(m.modFrequencies[f], m.rate, g0, g1)
                  - desired(filters, m.modFrequencies[f], m.rate));
      max = Math.max(max, energy * ratio * ratio);
    }
    return 10 * Math.log10(max);
  }

  public static double target(List<Filter> filters, double f, int rate) {
    double sum = 0;
    for (Filter v : filters) sum += filter(v, f, rate);
    return sum;
  }

  public static double filter(Filter v, double f, int rate) {
    if (v.gain == 0) return 0;
    double a = Math.pow(10, v.gain / 40),
        w = 2 * Math.PI * v.hz / rate,
        c = Math.cos(w),
        s = Math.sin(w);
    double alpha = s / (2 * v.q);
    double[] b, d;
    if (v.type == 0) {
      b = new double[] {1 + alpha * a, -2 * c, 1 - alpha * a};
      d = new double[] {1 + alpha / a, -2 * c, 1 - alpha / a};
    } else {
      double r = 2 * Math.sqrt(a) * s / Math.sqrt(2);
      if (v.type == 1) {
        b =
            new double[] {
              a * ((a + 1) - (a - 1) * c + r),
              2 * a * ((a - 1) - (a + 1) * c),
              a * ((a + 1) - (a - 1) * c - r)
            };
        d =
            new double[] {
              (a + 1) + (a - 1) * c + r, -2 * ((a - 1) + (a + 1) * c), (a + 1) + (a - 1) * c - r
            };
      } else {
        b =
            new double[] {
              a * ((a + 1) + (a - 1) * c + r),
              -2 * a * ((a - 1) + (a + 1) * c),
              a * ((a + 1) + (a - 1) * c - r)
            };
        d =
            new double[] {
              (a + 1) - (a - 1) * c + r, 2 * ((a - 1) - (a + 1) * c), (a + 1) - (a - 1) * c - r
            };
      }
    }
    return response(f, rate, b, d);
  }

  private static double response(double f, int rate, double[] b, double[] a) {
    double w = 2 * Math.PI * f / rate,
        br = b[0] + b[1] * Math.cos(w) + b[2] * Math.cos(2 * w),
        bi = -b[1] * Math.sin(w) - b[2] * Math.sin(2 * w);
    double ar = a[0] + a[1] * Math.cos(w) + a[2] * Math.cos(2 * w),
        ai = -a[1] * Math.sin(w) - a[2] * Math.sin(2 * w);
    return 10 * Math.log10(Math.max(1e-30, (br * br + bi * bi) / (ar * ar + ai * ai)));
  }

  public static void check(List<Filter> filters, int rate) {
    if (filters.size() > 8) throw new IllegalArgumentException("Maximum eight filters");
    for (Filter f : filters)
      if (!Double.isFinite(f.hz)
          || !Double.isFinite(f.gain)
          || !Double.isFinite(f.q)
          || f.type < 0
          || f.type > 2
          || f.hz < 20
          || f.hz > 20000
          || f.hz >= rate / 2.0
          || Math.abs(f.gain) > 12
          || f.q < .2
          || f.q > 8) throw new IllegalArgumentException("Filter outside supported range");
  }

  private static void cancelled() {
    if (Thread.currentThread().isInterrupted())
      throw new java.util.concurrent.CancellationException();
  }

  public static double clamp(double v, double lo, double hi) {
    return Math.max(lo, Math.min(hi, v));
  }

  public static double linear(double db) {
    return Math.pow(10, db / 20);
  }

  public static double db(double value) {
    return 20 * Math.log10(Math.max(value, 1e-30));
  }
}
