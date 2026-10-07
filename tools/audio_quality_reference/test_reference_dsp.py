import json
import unittest
import numpy as np
from reference_dsp import ResidualDetail, render_delta_wola, window_size


class ReferenceTests(unittest.TestCase):
    def test_zero_delta_is_exact_identity(self):
        rng = np.random.default_rng(67)
        for fs in (44100,48000,96000,192000):
            x = rng.normal(0,.03,(fs//4,2))
            y = render_delta_wola(x,fs,lambda m,s,hz,dt:np.zeros_like(s))
            self.assertTrue(np.array_equal(x,y))

    def test_any_active_detail_preserves_coherent_panned_sources(self):
        for fs in (44100,48000,96000):
            t=np.arange(fs)/fs
            source=.1*np.sin(2*np.pi*180*t)+.05*np.sin(2*np.pi*1600*t)
            for right in (0.,.3,1.):
                x=np.column_stack([source,right*source])
                y=render_delta_wola(x,fs,ResidualDetail(1,1))
                self.assertLess(np.max(np.abs(y-x)),1e-12)

    def test_pure_side_is_not_made_wider(self):
        fs=48000
        x=np.random.default_rng(5).normal(0,.02,fs)
        source=np.column_stack([x,-x])
        y=render_delta_wola(source,fs,ResidualDetail(1,1))
        self.assertTrue(np.array_equal(source,y))

    def test_real_residual_lift_and_mono_identity(self):
        fs=48000; n=fs*2; rng=np.random.default_rng(17)
        # Broadband synthetic M/S. This is not a music/preference experiment.
        m=rng.normal(0,.08,n); s=rng.normal(0,.012,n)
        x=np.column_stack([m+s,m-s])
        y=render_delta_wola(x,fs,ResidualDetail(1,1))
        out_side=.5*(y[:,0]-y[:,1]); sl=slice(fs,None)
        lift=10*np.log10(np.mean(out_side[sl]**2)/np.mean(s[sl]**2))
        self.assertGreater(lift,.1)
        self.assertLess(lift,4.1)
        self.assertLess(np.max(np.abs(y.sum(axis=1)-x.sum(axis=1))),1e-12)
        print(json.dumps({'fixture':'broadband_residual','side_rms_lift_db':float(lift),
                          'mono_sum_max_error':float(np.max(np.abs(y.sum(axis=1)-x.sum(axis=1)))),
                          'streaming_latency_frames_required':window_size(fs)}))


if __name__=='__main__':
    unittest.main()
