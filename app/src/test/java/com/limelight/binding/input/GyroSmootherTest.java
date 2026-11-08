package com.limelight.binding.input;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Random;

public class GyroSmootherTest {
    private static final long STEP_NS = 5_000_000L; // 200 Hz, the report rate Delos asks the sensors for
    private static final int[] SLIDERS = {1, 10, 25, 50, 75, 100};

    private final float[] out = new float[3];

    private float run(GyroSmoother smoother, long timestampNs, float x) {
        smoother.filter(timestampNs, x, x, x, out);
        return out[0];
    }

    /** Variance of the output over variance of the input for white noise around zero. */
    private static double noiseVarianceRatio(int slider, double sigma) {
        GyroSmoother smoother = new GyroSmoother(slider);
        float[] o = new float[3];
        Random random = new Random(42);
        double in = 0, filtered = 0;
        int counted = 0;
        for (int i = 0; i < 20000; i++) {
            float x = (float) (random.nextGaussian() * sigma);
            smoother.filter(i * STEP_NS, x, x, x, o);
            if (i >= 2000) {
                in += (double) x * x;
                filtered += (double) o[0] * o[0];
                counted++;
            }
        }
        return (filtered / counted) / (in / counted);
    }

    /** Delay in ms between a sine of [hz] and [amplitude] deg/s and the filtered sine (best-fitting shift). */
    private static double sineLagMs(int slider, double hz, double amplitude) {
        GyroSmoother smoother = new GyroSmoother(slider);
        float[] o = new float[3];
        int n = 1200;
        float[] in = new float[n];
        float[] filtered = new float[n];
        for (int i = 0; i < n; i++) {
            in[i] = (float) (amplitude * Math.sin(2 * Math.PI * hz * i * STEP_NS * 1e-9));
            smoother.filter(i * STEP_NS, in[i], in[i], in[i], o);
            filtered[i] = o[0];
        }
        int best = 0;
        double bestError = Double.MAX_VALUE;
        for (int shift = 0; shift < 30; shift++) {
            double error = 0;
            for (int i = n / 2; i < n; i++) {
                double d = filtered[i] - in[i - shift];
                error += d * d;
            }
            if (error < bestError) {
                bestError = error;
                best = shift;
            }
        }
        return best * STEP_NS * 1e-6;
    }

    /** Output at the end of a 0 -> 300 deg/s ramp that takes 100 ms, after a long rest at 0. */
    private static double flickEndValue(int slider) {
        GyroSmoother smoother = new GyroSmoother(slider);
        float[] o = new float[3];
        long t = 0;
        for (int i = 0; i < 400; i++, t += STEP_NS) smoother.filter(t, 0f, 0f, 0f, o);
        for (int i = 1; i <= 20; i++, t += STEP_NS) {
            float x = 300f * i / 20f;
            smoother.filter(t, x, x, x, o);
        }
        return o[0];
    }

    @Test
    public void offMeansNoFilterAndTheSliderIsClamped() {
        assertTrue(!GyroSmoother.isActive(0));
        assertTrue(!GyroSmoother.isActive(-5));
        assertTrue(GyroSmoother.isActive(1));
        assertEquals(GyroSmoother.minCutoffHz(100), GyroSmoother.minCutoffHz(250), 0f);
        assertEquals(GyroSmoother.minCutoffHz(0), GyroSmoother.minCutoffHz(-3), 0f);
    }

    @Test
    public void mappingIsMonotonicWithDocumentedEndpoints() {
        assertEquals(8.0f, GyroSmoother.minCutoffHz(0), 1e-4f);
        assertEquals(0.2f, GyroSmoother.minCutoffHz(100), 1e-4f);
        assertEquals(0.02f, GyroSmoother.beta(0), 1e-6f);
        assertEquals(0.005f, GyroSmoother.beta(100), 1e-6f);
        for (int slider = 1; slider <= 100; slider++) {
            assertTrue(GyroSmoother.minCutoffHz(slider) < GyroSmoother.minCutoffHz(slider - 1));
            assertTrue(GyroSmoother.beta(slider) < GyroSmoother.beta(slider - 1));
        }
        assertEquals(1.26f, GyroSmoother.minCutoffHz(50), 0.01f);
    }

    @Test
    public void theFirstSamplePassesThroughAndEachAxisIsFilteredOnItsOwn() {
        GyroSmoother smoother = new GyroSmoother(50);
        smoother.filter(1_000_000_000L, 10f, -20f, 30f, out);
        assertEquals(10f, out[0], 0f);
        assertEquals(-20f, out[1], 0f);
        assertEquals(30f, out[2], 0f);
        smoother.filter(1_000_000_000L + STEP_NS, 10f, 40f, 30f, out);
        assertEquals(10f, out[0], 1e-6f);
        assertEquals(30f, out[2], 1e-6f);
        assertTrue(out[1] > -20f && out[1] < 40f);
    }

    @Test
    public void aConstantInputStaysConstant() {
        for (int slider : SLIDERS) {
            GyroSmoother smoother = new GyroSmoother(slider);
            for (int i = 0; i < 1000; i++) assertEquals(7.5f, run(smoother, i * STEP_NS, 7.5f), 1e-4f);
        }
    }

    @Test
    public void aStepConvergesWithoutOvershoot() {
        for (int slider : SLIDERS) {
            GyroSmoother smoother = new GyroSmoother(slider);
            for (int i = 0; i < 200; i++) run(smoother, i * STEP_NS, 0f);
            float previous = 0f;
            float last = 0f;
            for (int i = 0; i < 1000; i++) { // 5 s
                last = run(smoother, (200 + i) * STEP_NS, 100f);
                assertTrue("slider " + slider + " overshoots", last <= 100.0001f);
                assertTrue("slider " + slider + " is not monotonic", last >= previous - 1e-4f);
                previous = last;
            }
            assertEquals("slider " + slider, 100f, last, 1.0f);
        }
    }

    @Test
    public void noiseAtRestShrinksWithTheSliderAndMatchesTheDocumentedRatios() {
        double[] ceiling = {0.12, 0.09, 0.06, 0.03, 0.012, 0.006}; // measured: 0.109 0.081 0.049 0.021 0.0097 0.0047
        double previous = 1.0;
        for (int i = 0; i < SLIDERS.length; i++) {
            double ratio = noiseVarianceRatio(SLIDERS[i], 0.5);
            assertTrue("slider " + SLIDERS[i] + " ratio " + ratio, ratio < ceiling[i]);
            assertTrue(ratio < previous);
            previous = ratio;
        }
    }

    @Test
    public void aFastFlickIsTrackedWithLittleLagWhileSlowMovementIsSmoothed() {
        for (int slider : SLIDERS) {
            assertTrue("flick lag at slider " + slider, sineLagMs(slider, 3, 300) <= 15.0);
        }
        // at the strongest setting the same filter lags at least 3 times more on a slow, small movement than on a flick
        assertTrue(sineLagMs(100, 0.5, 30) >= 3 * sineLagMs(100, 3, 300));
        assertTrue(sineLagMs(100, 0.5, 30) <= 60.0);
        assertTrue(sineLagMs(25, 0.5, 30) <= 25.0);
    }

    @Test
    public void aHundredMillisecondFlickReachesMostOfItsPeak() {
        // 0 -> 300 deg/s in 100 ms; measured 289 / 286 / 283 / 278 at sliders 25 / 50 / 75 / 100
        assertTrue(flickEndValue(25) >= 285f);
        assertTrue(flickEndValue(50) >= 280f);
        assertTrue(flickEndValue(75) >= 275f);
        assertTrue(flickEndValue(100) >= 270f);
    }

    @Test
    public void aLongGapRestartsTheFilterButAShortOneDoesNot() {
        GyroSmoother smoother = new GyroSmoother(100);
        for (int i = 0; i < 100; i++) run(smoother, i * STEP_NS, 0f);
        long t = 99 * STEP_NS; // the last sample so far
        // just under the limit: still filtered: it does not reach the new value outright
        float shortGap = run(smoother, t + GyroSmoother.MAX_GAP_NS, 50f);
        assertTrue(shortGap < 50f);
        // over the limit: restarts from the new sample
        GyroSmoother other = new GyroSmoother(100);
        for (int i = 0; i < 100; i++) run(other, i * STEP_NS, 0f);
        assertEquals(50f, run(other, t + GyroSmoother.MAX_GAP_NS + 1, 50f), 0f);
        // and carries on filtering from there
        float next = run(other, t + GyroSmoother.MAX_GAP_NS + 1 + STEP_NS, 0f);
        assertTrue(next > 40f);
    }

    @Test
    public void aRepeatedTimestampRepeatsTheOutputAndABackwardsOneRestarts() {
        GyroSmoother smoother = new GyroSmoother(50);
        run(smoother, 1_000_000_000L, 0f);
        float settled = run(smoother, 1_000_000_000L + STEP_NS, 10f);
        assertEquals(settled, run(smoother, 1_000_000_000L + STEP_NS, 99f), 0f);
        assertEquals(77f, run(smoother, 500_000_000L, 77f), 0f);
    }

    @Test
    public void theOutputArrayMayBeTheInputArray() {
        GyroSmoother a = new GyroSmoother(50);
        GyroSmoother b = new GyroSmoother(50);
        float[] shared = new float[3];
        float[] separate = new float[3];
        for (int i = 0; i < 50; i++) {
            float x = (i % 7) * 3f;
            shared[0] = x; shared[1] = -x; shared[2] = 2 * x;
            a.filter(i * STEP_NS, shared[0], shared[1], shared[2], shared);
            b.filter(i * STEP_NS, x, -x, 2 * x, separate);
            assertEquals(separate[1], shared[1], 0f);
        }
    }

    @Test
    public void resetForgetsTheHistory() {
        GyroSmoother smoother = new GyroSmoother(100);
        for (int i = 0; i < 100; i++) run(smoother, i * STEP_NS, 0f);
        smoother.reset();
        assertEquals(20f, run(smoother, 100 * STEP_NS, 20f), 0f);
    }

    @Test
    public void theStrengthChangesLiveAndZeroIsAnExactBypass() {
        GyroSmoother smoother = new GyroSmoother(0);
        assertEquals(0, smoother.strength());
        out[0] = out[1] = out[2] = -1f;
        assertTrue(!smoother.filter(0, 5f, 6f, 7f, out));
        assertEquals(-1f, out[0], 0f); // untouched: the caller sends its input
        smoother.setStrength(50);
        assertEquals(50, smoother.strength());
        assertTrue(smoother.filter(10 * STEP_NS, 5f, 6f, 7f, out));
        assertEquals(5f, out[0], 0f); // fresh filter: the first sample passes through
        smoother.filter(11 * STEP_NS, 105f, 6f, 7f, out);
        assertTrue(out[0] > 5f && out[0] < 105f);
        smoother.setStrength(0);
        assertTrue(!smoother.filter(12 * STEP_NS, 105f, 6f, 7f, out));
        smoother.setStrength(50); // the state was forgotten: starts again from the sample
        smoother.filter(13 * STEP_NS, 40f, 0f, 0f, out);
        assertEquals(40f, out[0], 0f);
    }

    @Test
    public void changingBetweenPositiveStrengthsKeepsTheStateAndOnlyRetunesTheFilter() {
        GyroSmoother steady = new GyroSmoother(25);
        GyroSmoother retuned = new GyroSmoother(25);
        float[] a = new float[3];
        float[] b = new float[3];
        for (int i = 0; i < 100; i++) {
            steady.filter(i * STEP_NS, 10f, 10f, 10f, a);
            retuned.filter(i * STEP_NS, 10f, 10f, 10f, b);
        }
        retuned.setStrength(75);
        assertEquals(75, retuned.strength());
        retuned.filter(100 * STEP_NS, 10f, 10f, 10f, b);
        steady.filter(100 * STEP_NS, 10f, 10f, 10f, a);
        assertEquals(a[0], b[0], 1e-3f); // no jump on a constant input
        // and the new, stronger setting smooths a step more than the old one
        retuned.filter(101 * STEP_NS, 60f, 60f, 60f, b);
        steady.filter(101 * STEP_NS, 60f, 60f, 60f, a);
        assertTrue(b[0] < a[0]);
        retuned.setStrength(999);
        assertEquals(100, retuned.strength());
        retuned.setStrength(-4);
        assertEquals(0, retuned.strength());
    }
}
