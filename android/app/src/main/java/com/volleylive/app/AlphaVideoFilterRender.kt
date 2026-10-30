package com.volleylive.app

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.pedro.encoder.input.gl.render.filters.`object`.BaseObjectFilterRender
import com.pedro.encoder.utils.gl.GlUtil

/**
 * Calque vidéo avec transparence. La vidéo (lue par un MediaPlayer dans [surface]) est encodée
 * « côte à côte » : couleurs à gauche, masque de transparence à droite (voir alpha_video_fragment.glsl).
 * Même fonctionnement que SurfaceFilterRender de RootEncoder, avec un shader différent.
 */
class AlphaVideoFilterRender(private val onSurfaceReady: (Surface) -> Unit) : BaseObjectFilterRender() {

    private var surfaceTexture: SurfaceTexture? = null
    private var surface: Surface? = null

    override fun initGlFilter(context: Context) {
        fragment = R.raw.alpha_video_fragment
        super.initGlFilter(context)
        GlUtil.createExternalTextures(streamObjectTextureId.size, streamObjectTextureId, 0)
        val texture = SurfaceTexture(streamObjectTextureId[0])
        texture.setDefaultBufferSize(width, height)
        val newSurface = Surface(texture)
        surfaceTexture = texture
        surface = newSurface
        Handler(Looper.getMainLooper()).post { onSurfaceReady(newSurface) }
    }

    override fun drawFilter() {
        surfaceTexture?.updateTexImage()
        super.drawFilter()
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, streamObjectTextureId[0])
        GLES20.glUniform1f(uAlphaHandle, if (streamObjectTextureId[0] == -1) 0f else alpha)
    }

    override fun release() {
        super.release()
        surfaceTexture?.release()
        surface?.release()
    }
}
