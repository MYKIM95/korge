package com.soywiz.korge.render

import com.soywiz.kds.*
import com.soywiz.kds.iterators.*
import com.soywiz.korag.*
import com.soywiz.korag.log.*
import com.soywiz.korag.shader.Uniform
import com.soywiz.korge.internal.*
import com.soywiz.korge.stat.*
import com.soywiz.korge.view.*
import com.soywiz.korim.bitmap.*
import com.soywiz.korim.color.*
import com.soywiz.korio.async.*
import com.soywiz.korio.lang.*
import com.soywiz.korma.geom.*
import kotlin.coroutines.*

/**
 * A context that allows to render objects.
 *
 * The [RenderContext] contains the [ag] [AG] (Accelerated Graphics),
 * that allow to render triangles and other primitives to the current render buffer.
 *
 * When doing 2D, you should usually use the [batch] to buffer vertices,
 * so they can be rendered at once when flushing.
 *
 * If you plan to do a custom drawing using [ag] directly, you should call [flush],
 * so all the pending vertices are rendered.
 *
 * If you want to perform drawing using a context allowing non-precomputed transformations
 * you can use [ctx2d].
 *
 * If you need to get textures from [Bitmap] that are allocated and deallocated as required
 * preventing leaks, you should use [getTex].
 */
class RenderContext constructor(
    /** The Accelerated Graphics object that allows direct rendering */
	val ag: AG,
	val bp: BoundsProvider = BoundsProvider.Base(),
    /** Object storing all the rendering [Stats] like number of batches, number of vertices etc. */
	val stats: Stats = Stats(),
	val coroutineContext: CoroutineContext = EmptyCoroutineContext,
    val batchMaxQuads: Int = BatchBuilder2D.DEFAULT_BATCH_QUADS
) : Extra by Extra.Mixin(), BoundsProvider by bp, AGFeatures by ag, Closeable {
    val projectionMatrixTransform = Matrix()
    val projectionMatrixTransformInv = Matrix()
    private val projMat: Matrix3D = Matrix3D()
    @KorgeInternal
    val viewMat: Matrix3D = Matrix3D()
    @KorgeInternal
    val viewMat2D: Matrix = Matrix()

    @KorgeInternal
    val uniforms: AGUniformValues by lazy {
        AGUniformValues {
            it[DefaultShaders.u_ProjMat] = projMat
            it[DefaultShaders.u_ViewMat] = viewMat
        }
    }

    inline fun <T> setTemporalProjectionMatrixTransform(m: Matrix, block: () -> T): T =
        this.projectionMatrixTransform.keepMatrix {
            flush()
            this.projectionMatrixTransform.copyFrom(m)
            try {
                block()
            } finally {
                flush()
            }
        }

    var flipRenderTexture = true
    //var flipRenderTexture = false
    private val tempRect = Rectangle()
    private val tempMat3d = Matrix3D()

    fun updateStandardUniforms() {
        //println("updateStandardUniforms: ag.currentSize(${ag.currentWidth}, ${ag.currentHeight}) : ${ag.currentRenderBuffer}")
        if (flipRenderTexture && ag.isRenderingToTexture) {
            projMat.setToOrtho(tempRect.setBounds(0, ag.currentHeight, ag.currentWidth, 0), -1f, 1f)
        } else {
            projMat.setToOrtho(tempRect.setBounds(0, 0, ag.currentWidth, ag.currentHeight), -1f, 1f)
            projMat.multiply(projMat, projectionMatrixTransform.toMatrix3D(tempMat3d))
        }
        uniforms[DefaultShaders.u_ProjMat] = projMat
        uniforms[DefaultShaders.u_ViewMat] = viewMat
    }

    /**
     * Executes [callback] while setting temporarily the view matrix to [matrix]
     */
    inline fun setViewMatrixTemp(matrix: Matrix, crossinline callback: () -> Unit) {
        matrix3DPool.alloc { temp ->
            matrixPool.alloc { temp2d ->
                flush()
                temp.copyFrom(this.viewMat)
                temp2d.copyFrom(this.viewMat2D)
                this.viewMat2D.copyFrom(matrix)
                this.viewMat.copyFrom(matrix)
                //println("viewMat: $viewMat, matrix: $matrix")
                try {
                    callback()
                } finally {
                    flush()
                    this.viewMat.copyFrom(temp)
                    this.viewMat2D.copyFrom(temp2d)
                }
            }
        }
    }

    @PublishedApi internal val uniformsPool = Pool { AGUniformValues() }

    /**
     * Executes [callback] while restoring [uniform] to its current value after [callback] is exexcuted.
     */
    inline fun keepUniform(uniform: Uniform, flush: Boolean = true, callback: (AGUniformValues) -> Unit) {
        uniformsPool.alloc { tempUniforms ->
            tempUniforms[uniform].set(this.uniforms[uniform])
            if (flush) flush()
            try {
                callback(this.uniforms)
            } finally {
                if (flush) flush()
                this.uniforms[uniform].set(tempUniforms[uniform])
            }
        }
    }

    /**
     * Executes [callback] while restoring [uniforms] to its current value after [callback] is exexcuted.
     */
    inline fun keepUniforms(uniforms: Array<Uniform>, flush: Boolean = true, callback: (AGUniformValues) -> Unit) {
        uniformsPool.alloc { tempUniforms ->
            uniforms.fastForEach { tempUniforms[it].set(this.uniforms[it]) }
            if (flush) flush()
            try {
                callback(this.uniforms)
            } finally {
                if (flush) flush()
                uniforms.fastForEach { this.uniforms[it].set(tempUniforms[it]) }
            }
        }
    }

    @PublishedApi
    internal val tempOldUniformsList: Pool<AGUniformValues> = Pool { AGUniformValues() }

    /**
     * Executes [callback] while setting temporarily a set of [uniforms]
     */
    inline fun setTemporalUniforms(uniforms: AGUniformValues?, callback: (AGUniformValues) -> Unit) {
        tempOldUniformsList { tempOldUniforms ->
            if (uniforms != null && uniforms.isNotEmpty()) {
                flush()
                tempOldUniforms.setTo(this.uniforms)
                this.uniforms.put(uniforms)
            }
            try {
                callback(this.uniforms)
            } finally {
                if (uniforms != null && uniforms.isNotEmpty()) {
                    flush()
                    this.uniforms.setTo(tempOldUniforms)
                }
            }
        }
    }

    val agAutoFreeManager = AgAutoFreeManager()
	val agBitmapTextureManager = AgBitmapTextureManager(ag)
    val agBufferManager = AgBufferManager(ag)

    /** Allows to register handlers when the [flush] method is called */
    val flushers = Signal<Unit>()

    val views: Views? = bp as? Views?

    var debugAnnotateView: View? = null
        set(value) {
            views?.invalidatedView(field)
            field = value
            views?.invalidatedView(field)
        }
    var debugExtraFontScale : Double = 1.0
    var debugExtraFontColor : RGBA = Colors.WHITE

    val debugOverlayScale: Double get() = kotlin.math.round(ag.computedPixelRatio * debugExtraFontScale).coerceAtLeast(1.0)

    var stencilIndex: Int = 0

    /** Allows to draw quads, sprites and nine patches using a precomputed global matrix or raw vertices */
    @Deprecated("Use useBatcher instead")
    @KorgeInternal
    val batch = BatchBuilder2D(this, batchMaxQuads)

    val dynamicVertexBufferPool = Pool { ag.createBuffer() }
    val dynamicVertexDataPool = Pool { ag.createVertexData() }
    val dynamicIndexBufferPool = Pool { ag.createBuffer() }

    @OptIn(KorgeInternal::class)
    inline fun useBatcher(block: (BatchBuilder2D) -> Unit) = batch.use(block)

    /** [RenderContext2D] similar to the one from JS, that keeps a matrix (affine transformation) and allows to draw shapes using the current matrix */
    @KorgeInternal
    @Deprecated("Use useCtx2d instead")
    val ctx2d = RenderContext2D(batch, agBitmapTextureManager)

    @Suppress("DEPRECATION")
    @OptIn(KorgeInternal::class)
    inline fun useCtx2d(block: (RenderContext2D) -> Unit) { useBatcher(batch) { block(ctx2d) } }

    /** Pool of [Matrix] objects that could be used temporarily by renders */
    val matrixPool = Pool(reset = { it.identity() }, preallocate = 8) { Matrix() }
    /** Pool of [Matrix3D] objects that could be used temporarily by renders */
    val matrix3DPool = Pool(reset = { it.identity() }, preallocate = 8) { Matrix3D() }
    /** Pool of [Point] objects that could be used temporarily by renders */
    val pointPool = Pool(reset = { it.setTo(0, 0) }, preallocate = 8) { Point() }
    /** Pool of [Rectangle] objects that could be used temporarily by renders */
    val rectPool = Pool(reset = { it.setTo(0, 0, 0, 0) }, preallocate = 8) { Rectangle() }

    val tempMargin: MutableMarginInt = MutableMarginInt()

    val identityMatrix = Matrix()

    /**
     * Allows to toggle whether stencil-based masks are enabled or not.
     */
	var masksEnabled = true
    var currentBatcher: Any? = null

    /**
     * Flushes all the pending renderings. This is called automatically at the end of the frame.
     * You should call this if you plan to render something else not managed via [batch],
     * so all the pending vertices are drawn.
     */
	fun flush() {
        currentBatcher = null
        flushers(Unit)
	}

    inline fun renderToFrameBuffer(
        frameBuffer: AGFrameBuffer,
        clear: Boolean = true,
        render: (AGFrameBuffer) -> Unit,
    ) {
        flush()
        ag.setRenderBufferTemporally(frameBuffer) {
            useBatcher { batch ->
                val oldScissors = batch.scissor
                batch.scissor = AGScissor(0, 0, frameBuffer.width, frameBuffer.height)
                //batch.scissor = null
                try {
                    if (clear) ag.clear(Colors.TRANSPARENT_BLACK)
                    render(frameBuffer)
                    flush()
                } finally {
                    batch.scissor = oldScissors
                }
            }
        }
    }

    /**
     * Temporarily sets the render buffer to a temporal texture of the size [width] and [height] that can be used later in the [use] method.
     * First the texture is created, then [render] method is called once the render buffer is set to the texture,
     * and later the context is restored and the [use] method is called providing as first argument the rendered [Texture].
     * This method is useful for per-frame filters. If you plan to keep the texture data, consider using the [renderToBitmap] method.
     */
    inline fun renderToTexture(
        width: Int, height: Int,
        render: (AGFrameBuffer) -> Unit = {},
        hasDepth: Boolean = false, hasStencil: Boolean = true, msamples: Int = 1,
        use: (texture: Texture) -> Unit
    ) {
		flush()
        ag.tempAllocateFrameBuffer(width, height, hasDepth = hasDepth, hasStencil = hasStencil, msamples = msamples) { fb ->
            renderToFrameBuffer(fb) { render(it) }
            use(Texture(fb).slice(0, 0, width, height))
            flush()
        }
	}

    /**
     * Sets the render buffer temporarily to [bmp] [Bitmap32] and calls the [callback] render method that should perform all the renderings inside.
     */
	inline fun renderToBitmap(
        bmp: Bitmap32,
        hasDepth: Boolean = false, hasStencil: Boolean = false, msamples: Int = 1,
        callback: () -> Unit
    ): Bitmap32 {
		flush()
		ag.renderToBitmap(bmp, hasDepth, hasStencil, msamples) {
			callback()
			flush()
		}
		return bmp
	}

    inline fun renderToBitmap(
        width: Int, height: Int,
        hasDepth: Boolean = false, hasStencil: Boolean = false, msamples: Int = 1,
        callback: () -> Unit
    ): Bitmap32 =
        renderToBitmap(
            Bitmap32(width, height),
            hasDepth = hasDepth, hasStencil = hasStencil, msamples = msamples,
            callback = callback
        )

    /**
     * Finishes the drawing and flips the screen. Called by the KorGe engine at the end of the frame.
     */
	fun finish() {
		ag.flip()
	}

    /**
     * Temporarily allocates a [Texture] with its coords from a [BmpSlice].
     * Textures are managed (allocated and de-allocated) automatically by the engine as required.
     * The texture coords matches the region in the [BmpSlice].
     */
    fun getTex(bmp: BmpSlice): Texture = agBitmapTextureManager.getTexture(bmp)
	fun getTex(bmp: BitmapCoords): TextureCoords = agBitmapTextureManager.getTexture(bmp)
    fun getBuffer(buffer: AgCachedBuffer): AGBuffer = agBufferManager.getBuffer(buffer)

    /**
     * Allocates a [Texture.Base] from a [Bitmap]. A Texture.Base doesn't have region information.
     * It is just the whole texture/bitmap.
     */
    fun getTex(bmp: Bitmap): TextureBase = agBitmapTextureManager.getTextureBase(bmp)

    /**
     * References a [closeable] for this frame that will be tracked in next frames.
     * If after a period of time, this closeable has not been referenced in between frames,
     * the [Closeable.close] method will be called so the object can be freed.
     *
     * This can be use for example to automatically manage temporal/cached textures.
     */
    fun refGcCloseable(closeable: Closeable) = agAutoFreeManager.reference(closeable)

    internal fun afterRender() {
        flush()
        finish()
        agAutoFreeManager.afterRender()
        agBitmapTextureManager.afterRender()
        agBufferManager.afterRender()
    }

    inline fun <T> useBatcher(batcher: T, block: (T) -> Unit) {
        if (currentBatcher !== batcher) {
            flush()
            currentBatcher = batcher
        }
        block(batcher)
    }

    override fun close() {
        agBitmapTextureManager.close()
        agAutoFreeManager.close()
    }
}

inline fun <T : AG> testRenderContext(ag: T, bp: BoundsProvider = BoundsProvider.Base(), block: (RenderContext) -> Unit): T {
    val ctx = RenderContext(ag, bp)
    block(ctx)
    ctx.flush()
    return ag
}

inline fun testRenderContext(bp: BoundsProvider = BoundsProvider.Base(), block: (RenderContext) -> Unit): LogAG = testRenderContext(LogAG(), bp, block)
