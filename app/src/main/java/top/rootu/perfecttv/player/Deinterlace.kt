package top.rootu.perfecttv.player

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

/**
 * Деинтерлейсинг по одному кадру (шейдер для `ExoPlayer.setVideoEffects`). Программный декодер отдаёт
 * кадр с двумя полями вперемешку: на движении края «гребёнкой». Гребёнка определяется по строкам: пиксель
 * заметно отличается от верхнего и нижнего соседей в одну сторону, а соседи между собой близки. Там
 * строка заменяется смесью соседей (плавно, по силе гребёнки); статичная картинка остаётся как есть.
 * Частота кадров не удваивается (второе поле не показывается отдельным кадром), вертикальная чёткость на
 * движении ниже, зато гребёнки нет.
 */
@UnstableApi
class DeinterlaceEffect : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = DeinterlaceProgram()
}

@UnstableApi
private class DeinterlaceProgram : BaseGlShaderProgram(false, 1) {
    private val program: GlProgram
    private var texelY = 1f / 1080f

    init {
        try {
            program = GlProgram(VERTEX, FRAGMENT)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
        program.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
        program.setFloatsUniform("uTransformationMatrix", GlUtil.create4x4IdentityMatrix())
        program.setFloatsUniform("uTexTransformationMatrix", GlUtil.create4x4IdentityMatrix())
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        texelY = 1f / inputHeight.coerceAtLeast(1)
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            program.setFloatUniform("uTexelY", texelY)
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    override fun release() {
        super.release()
        try {
            program.delete()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    private companion object {
        const val VERTEX = """
attribute vec4 aFramePosition;
uniform mat4 uTransformationMatrix;
uniform mat4 uTexTransformationMatrix;
varying vec2 vTexSamplingCoord;
void main() {
  gl_Position = uTransformationMatrix * aFramePosition;
  vec4 texturePosition = vec4(aFramePosition.x * 0.5 + 0.5, aFramePosition.y * 0.5 + 0.5, 0.0, 1.0);
  vTexSamplingCoord = (uTexTransformationMatrix * texturePosition).xy;
}
"""
        const val FRAGMENT = """
precision mediump float;
uniform sampler2D uTexSampler;
uniform float uTexelY;
varying vec2 vTexSamplingCoord;
void main() {
  vec2 uv = vTexSamplingCoord;
  vec3 c = texture2D(uTexSampler, uv).rgb;
  vec3 a = texture2D(uTexSampler, uv - vec2(0.0, uTexelY)).rgb;
  vec3 b = texture2D(uTexSampler, uv + vec2(0.0, uTexelY)).rgb;
  vec3 k = vec3(0.299, 0.587, 0.114);
  float yc = dot(c, k);
  float ya = dot(a, k);
  float yb = dot(b, k);
  // гребёнка: центр выбивается из соседей в одну сторону (оба знака разности совпадают)
  float comb = (yc - ya) * (yc - yb);
  float amount = smoothstep(0.002, 0.02, comb);
  gl_FragColor = vec4(mix(c, (a + b) * 0.5, amount), 1.0);
}
"""
    }
}
