package com.phlox.tvwebbrowser.sofa

import android.content.Context
import android.opengl.GLES20
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

/**
 * Lumen's anime clean-up, run on the GPU by the built-in player (and, as a WebGL copy, by the page
 * script for videos inside web pages):
 *  1. Comb remover: removes the horizontal lines interlaced anime masters show on movement, also
 *     when the master was scaled up (see [DEINTERLACE_SHADER]).
 *  2. Deband: mpv's deband filter as used by Hayase (see [mpvDebandShader]).
 */
@OptIn(UnstableApi::class)
object LumenVideoCleanup {
    /**
     * Off; Light = line remover + a lighter deband (half the work, for TV chips);
     * On = Hayase's deband settings; Strong = for heavily compressed streams.
     */
    enum class Level(val label: String) { OFF("Off"), LIGHT("Light"), ANIME("On"), STRONG("Strong") }

    /** Frames that went through the filters (shown in the player to prove the filter really runs). */
    @Volatile var framesFiltered = 0L

    fun effectsFor(level: Level): List<Effect> = when (level) {
        Level.OFF -> emptyList()
        //anime: big flat areas and wide gradients -> wide search radius, 3 passes, firm colour
        //smoothing; dark outlines differ far more than the threshold, so line art stays crisp.
        //Radius is given for 1080p and scaled to the video's resolution in the shader.
        //first remove interlacing "comb" lines (only where they are found, so progressive video is
        //left untouched), then deband: the deband pass would otherwise smear the combs
        Level.LIGHT -> listOf(
            CleanupEffect(DEINTERLACE_SHADER, floatArrayOf(0.7f / 255f, 2.5f / 255f, 0.8f / 255f)),
            CleanupEffect(mpvDebandShader(threshold = 88, range = 17, grain = 12, iterations = 2), floatArrayOf(0f, 0f, 0f))
        )
        Level.ANIME -> listOf(
            CleanupEffect(DEINTERLACE_SHADER, floatArrayOf(0.7f / 255f, 2.5f / 255f, 0.8f / 255f)),
            CleanupEffect(mpvDebandShader(threshold = 88, range = 17, grain = 12), floatArrayOf(0f, 0f, 0f))
        )
        Level.STRONG -> listOf(
            CleanupEffect(DEINTERLACE_SHADER, floatArrayOf(0.7f / 255f, 2.5f / 255f, 0.8f / 255f)),
            CleanupEffect(mpvDebandShader(threshold = 200, range = 24, grain = 28), floatArrayOf(0f, 0f, 0f))
        )
    }

    /** Only the line (interlacing comb) remover: the deinterlacer of Lumen's player. */
    fun linesOnly(): List<Effect> = listOf(
        CleanupEffect(DEINTERLACE_SHADER, floatArrayOf(0.7f / 255f, 2.5f / 255f, 0.8f / 255f))
    )

    /** One GPU pass. [params] are passed to the shader as uParams (meaning depends on the pass). */
    private class CleanupEffect(private val fragmentShader: String, private val params: FloatArray) : GlEffect {
        override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
            CleanupShaderProgram(fragmentShader, params, useHdr)
    }

    private class CleanupShaderProgram(fragmentShader: String, params: FloatArray, useHdr: Boolean) :
        BaseGlShaderProgram(/* useHighPrecisionColorComponents= */ useHdr, /* texturePoolCapacity= */ 1) {

        private val glProgram: GlProgram
        private var frameIndex = 0f

        init {
            try {
                glProgram = GlProgram(VERTEX_SHADER, fragmentShader)
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e)
            }
            glProgram.setBufferAttribute(
                "aFramePosition",
                GlUtil.getNormalizedCoordinateBounds(),
                GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE
            )
            glProgram.setFloatsUniformIfPresent("uParams", params)
        }

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            glProgram.setFloatsUniformIfPresent("uTexSize", floatArrayOf(inputWidth.toFloat(), inputHeight.toFloat()))
            return Size(inputWidth, inputHeight)
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            try {
                glProgram.use()
                glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, /* texUnitIndex= */ 0)
                frameIndex = (frameIndex + 1f) % 1024f
                framesFiltered++
                glProgram.setFloatsUniformIfPresent("uFrame", floatArrayOf(frameIndex))
                glProgram.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e, presentationTimeUs)
            }
        }

        override fun release() {
            super.release()
            try {
                glProgram.delete()
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e)
            }
        }
    }

    private const val VERTEX_SHADER = """
attribute vec4 aFramePosition;
varying vec2 vTexSamplingCoord;
void main() {
  gl_Position = aFramePosition;
  vTexSamplingCoord = aFramePosition.xy * 0.5 + 0.5;
}
"""

    private const val COMMON = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform sampler2D uTexSampler;
uniform vec2 uTexSize;
uniform vec3 uParams;
varying vec2 vTexSamplingCoord;
float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }
vec4 at(vec2 px) { return texture2D(uTexSampler, px / uTexSize); }
"""

    /**
     * mpv's deband algorithm (as used by Hayase's "video-deband", MIT, github.com/ThaUnknown/video-deband):
     * for 4 growing radii, average 4 random samples around the pixel; where the pixel differs from
     * that average by less than a small threshold (a banding step), take the average. Then add fine
     * grain.
     */
    private fun mpvDebandShader(threshold: Int, range: Int, grain: Int, iterations: Int = 4) = COMMON + """
uniform float uFrame;
#define THRESHOLD $threshold
#define RANGE $range
#define ITERATIONS $iterations
#define GRAIN $grain
float mod289(float x)  { return x - floor(x / 289.0) * 289.0; }
float permute(float x) { return mod289((34.0 * x + 1.0) * x); }
float rand(float x)    { return fract(x / 41.0); }
vec4 average(vec2 pos, float range, inout float h) {
  float dist = rand(h) * range;     h = permute(h);
  float dir  = rand(h) * 6.2831853; h = permute(h);
  vec2 pt = dist / uTexSize;
  vec2 o = vec2(cos(dir), sin(dir));
  vec4 r0 = texture2D(uTexSampler, pos + pt * vec2( o.x,  o.y));
  vec4 r1 = texture2D(uTexSampler, pos + pt * vec2(-o.y,  o.x));
  vec4 r2 = texture2D(uTexSampler, pos + pt * vec2(-o.x, -o.y));
  vec4 r3 = texture2D(uTexSampler, pos + pt * vec2( o.y, -o.x));
  return (r0 + r1 + r2 + r3) * 0.25;
}
void main() {
  vec2 pos = vTexSamplingCoord;
  vec4 col = texture2D(uTexSampler, pos);
  float random = fract(uFrame * 0.61803398875 + 0.1);
  vec3 m = vec3(pos, random) + vec3(1.0);
  float h = permute(permute(permute(m.x) + m.y) + m.z);
  for (int i = 1; i <= ITERATIONS; i++) {
    vec4 avg = average(pos, float(i * RANGE), h);
    vec4 diff = abs(col - avg);
    col = mix(avg, col, step(vec4(float(THRESHOLD) / (float(i) * 16384.0)), diff));
  }
  vec3 noise;
  noise.x = rand(h); h = permute(h);
  noise.y = rand(h); h = permute(h);
  noise.z = rand(h); h = permute(h);
  col.rgb += (float(GRAIN) / 8192.0) * (noise - vec3(0.5));
  gl_FragColor = vec4(col.rgb, 1.0);
}
"""

    /**
     * Adaptive comb/interlace remover. Interlaced anime masters (DVD/TV, 480 or 576 lines) show
     * fine horizontal lines on movement: brightness lines and, from the 4:2:0 colour, colour stripes
     * twice as wide. When the master was scaled up, the lines were scaled too: in a 720-line video
     * from a 480-line master they repeat every 3 lines (colour every 6), in 1080p every 4.5 (9).
     * The possible repeat distances follow from the video height (native 2 lines, 576- and 480-line
     * masters), so only those are tested - real detail such as text strokes rarely matches them.
     * For each one the shader measures how strongly the column oscillates with exactly that period
     * (phase independent: samples at P/4 and P/2) and checks that the pattern repeats P lines away.
     * Where it does, the pixel is averaged over one full period (removes the lines and their finer
     * harmonics), separately for brightness and colour. Still areas, edges and gradients stay.
     * uParams = (brightness threshold low, high, colour threshold). Verified on frames of a real
     * 1080×720 stream: line strength 3.2 → 1.0 (the level of clean frames), text practically unchanged.
     */
    private const val DEINTERLACE_SHADER = COMMON + """
vec3 chroma(vec3 c) { return c - vec3(luma(c)); }
vec3 S(vec2 px, float dy) { return at(px + vec2(0.0, dy)).rgb; }
// one full period, averaged (luma and colour of the average = average of luma and colour)
vec3 box(vec2 px, float p) {
  return 0.25 * (S(px, -0.375 * p) + S(px, -0.125 * p) + S(px, 0.125 * p) + S(px, 0.375 * p));
}
void metY(float yc, float lq1, float lq2, float lb1, float lb2, float lr1, float lr2, float p,
          inout float best, inout float bestP, inout float bestM) {
  float ii = 0.5 * (yc - 0.5 * (lb1 + lb2));
  float qq = 0.5 * (lq1 - lq2);
  float amp = sqrt(ii * ii + qq * qq);
  float rep = max(abs(yc - lr1), abs(yc - lr2));
  float m = smoothstep(uParams.x, uParams.y, amp) * (1.0 - smoothstep(0.6, 1.2, rep / max(2.0 * amp, 0.004)));
  if (m * amp > best) { best = m * amp; bestP = p; bestM = m; }
}
void metC(vec3 cc, vec3 kq1, vec3 kq2, vec3 kb1, vec3 kb2, vec3 kr1, vec3 kr2, float p,
          inout float best, inout float bestP, inout float bestM) {
  vec3 ic = 0.5 * (cc - 0.5 * (kb1 + kb2));
  vec3 qc = 0.5 * (kq1 - kq2);
  float amp = sqrt(dot(ic, ic) + dot(qc, qc));
  float rep = max(length(cc - kr1), length(cc - kr2));
  float m = smoothstep(uParams.z, uParams.z * 3.0, amp) * (1.0 - smoothstep(0.6, 1.2, rep / max(2.0 * amp, 0.004)));
  if (m * amp > best) { best = m * amp; bestP = p; bestM = m; }
}
// tests line spacing p for brightness and colour, and 2p for colour (4:2:0). The 2p test reuses
// the p samples (its quarter and half period are p's half and full period): 8 reads instead of 18.
void period(vec2 px, float p, float yc, vec3 cc,
            inout float bY, inout float pY, inout float mY, inout float bC, inout float pC, inout float mC) {
  vec3 q1 = S(px, 0.25 * p); vec3 q2 = S(px, -0.25 * p);
  vec3 b1 = S(px, 0.5 * p);  vec3 b2 = S(px, -0.5 * p);
  vec3 r1 = S(px, p);        vec3 r2 = S(px, -p);
  vec3 d1 = S(px, 2.0 * p);  vec3 d2 = S(px, -2.0 * p);
  metY(yc, luma(q1), luma(q2), luma(b1), luma(b2), luma(r1), luma(r2), p, bY, pY, mY);
  vec3 cb1 = chroma(b1); vec3 cb2 = chroma(b2); vec3 cr1 = chroma(r1); vec3 cr2 = chroma(r2);
  metC(cc, chroma(q1), chroma(q2), cb1, cb2, cr1, cr2, p, bC, pC, mC);
  metC(cc, cb1, cb2, cr1, cr2, chroma(d1), chroma(d2), 2.0 * p, bC, pC, mC);
}
void main() {
  vec2 px = vTexSamplingCoord * uTexSize;
  vec4 c4 = at(px);
  float yc = luma(c4.rgb);
  vec3 cc = c4.rgb - vec3(yc);
  // line spacing: native interlace, and interlace of a 576- or 480-line master scaled to this height
  float pA = 2.0;
  float pB = max(2.0, 2.0 * uTexSize.y / 576.0);
  float pCc = max(2.0, 2.0 * uTexSize.y / 480.0);
  float bY = 0.0; float pY = 0.0; float mY = 0.0;
  float bC = 0.0; float pC = 0.0; float mC = 0.0;
  period(px, pA, yc, cc, bY, pY, mY, bC, pC, mC);
  if (pB != pA) period(px, pB, yc, cc, bY, pY, mY, bC, pC, mC);
  if (pCc != pB) period(px, pCc, yc, cc, bY, pY, mY, bC, pC, mC);
  float outY = yc; vec3 outC = cc;
  if (bY > 0.0) {
    vec3 bx = box(px, pY);
    outY = mix(yc, luma(bx), mY);
    if (bC > 0.0 && pC == pY) outC = mix(cc, chroma(bx), mC);
  }
  if (bC > 0.0 && !(bY > 0.0 && pC == pY)) outC = mix(cc, chroma(box(px, pC)), mC);
  gl_FragColor = vec4(vec3(outY) + outC, c4.a);
}
"""
}
