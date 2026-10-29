package com.volleylive.app

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.pedro.encoder.input.audio.CustomAudioEffect
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Mixe le son envoyé dans le live. Branché sur le micro (« effet audio » de RootEncoder),
 * il reçoit chaque bloc de son du micro et le remplace par :
 *   micro × gainMicro + musique × gainMusique + bande-son de la transition Intro → Match.
 *
 * Sur les écrans d'intro et de fin ([musicOn] = true) : musique, micro coupé.
 * En match : micro seul. Pendant une transition, le son en cours s'éteint doucement jusqu'au
 * changement d'écran ([fadeOutFor]), puis le son du nouvel écran remonte progressivement ([fadeInFor]).
 *
 * Format attendu partout : PCM 16 bits, 44 100 Hz, stéréo.
 */
class AudioMixer(context: Context) : CustomAudioEffect() {

    /** true : musique, micro coupé. false : micro seul. */
    @Volatile var musicOn = false

    /** true : plus aucun son dans le live (coupure d'urgence), en fondu rapide. */
    @Volatile var muted = false
    private var masterGain = 1f

    private var musicGain = 0f
    private var micGain = 1f
    private val music = ShortRing(SAMPLE_RATE * CHANNELS * 2) // ~2 s d'avance
    private var musicBlock = ShortArray(8192)

    /** Pendant une transition : tout le son descend à zéro (avant le changement d'écran). */
    @Volatile private var dip = false
    @Volatile private var fadeDownStep = FADE_STEP
    @Volatile private var fadeUpStep = FADE_STEP

    @Volatile private var running = true

    /** Bande-son de la transition Intro → Match, décodée d'avance ; jouée par-dessus le reste. */
    @Volatile private var effect: ShortArray? = null
    /** Position de lecture dans [effect], -1 : arrêtée. */
    private val effectPos = AtomicInteger(-1)

    init {
        val appContext = context.applicationContext
        thread(name = "music-decoder", isDaemon = true) { decodeMusicLoop(appContext) }
        thread(name = "effect-decoder", isDaemon = true) { effect = decodeAll(appContext, R.raw.stinger_sound) }
    }

    /** Lance la bande-son de la transition depuis le début (en même temps que sa vidéo). */
    fun playEffect() {
        if (effect != null) effectPos.set(0)
    }

    /** Arrête la bande-son (transition interrompue). */
    fun stopEffect() {
        effectPos.set(-1)
    }

    /** Début de transition : le son actuel (musique ou micro) s'éteint en [ms] millisecondes. */
    fun fadeOutFor(ms: Long) {
        fadeDownStep = stepFor(ms)
        dip = true
    }

    /** Changement d'écran pendant la transition : le son de la nouvelle scène remonte en [ms] ms. */
    fun fadeInFor(ms: Long, music: Boolean) {
        fadeUpStep = stepFor(ms)
        musicOn = music
        dip = false
    }

    /** Changement d'écran sans transition : fondu enchaîné classique (~0,8 s). */
    fun crossfade(music: Boolean) {
        fadeDownStep = FADE_STEP
        fadeUpStep = FADE_STEP
        dip = false
        musicOn = music
    }

    private fun stepFor(ms: Long) = 1f / (SAMPLE_RATE * CHANNELS * (ms.coerceAtLeast(100L) / 1000f))

    fun release() {
        running = false
        music.close()
    }

    override fun process(pcmBuffer: ByteArray): ByteArray {
        val samples = ByteBuffer.wrap(pcmBuffer).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val count = samples.remaining()
        val musicTarget = if (!dip && musicOn) 1f else 0f
        val micTarget = if (dip) 0f else 1f - musicTarget
        val masterTarget = if (muted) 0f else 1f

        // La musique n'avance que lorsqu'elle est audible : elle reprend là où elle s'était arrêtée
        val needMusic = musicOn || musicGain > 0f
        if (musicBlock.size < count) musicBlock = ShortArray(count)
        val got = if (needMusic) music.read(musicBlock, count) else 0

        val fx = effect
        val fxStart = effectPos.get()
        var fxPos = fxStart

        for (i in 0 until count) {
            musicGain = approach(musicGain, musicTarget)
            micGain = approach(micGain, micTarget)
            val mic = samples.get(i) * micGain
            val m = if (i < got) musicBlock[i] * musicGain * MUSIC_VOLUME else 0f
            // Bande-son de la transition : à plein volume, indépendante des fondus micro / musique
            var e = 0f
            if (fx != null && fxPos >= 0) {
                if (fxPos < fx.size) e = fx[fxPos++] * EFFECT_VOLUME else fxPos = -1
            }
            masterGain = when {
                masterGain < masterTarget -> minOf(masterTarget, masterGain + MUTE_STEP)
                masterGain > masterTarget -> maxOf(masterTarget, masterGain - MUTE_STEP)
                else -> masterGain
            }
            samples.put(i, ((mic + m + e) * masterGain).toInt().coerceIn(-32768, 32767).toShort())
        }
        if (fxStart >= 0) {
            val next = if (fx == null || fxPos < 0 || fxPos >= fx.size) -1 else fxPos
            // Relancée entre-temps (playEffect) : on ne l'écrase pas
            effectPos.compareAndSet(fxStart, next)
        }
        return pcmBuffer
    }

    private fun approach(value: Float, target: Float): Float = when {
        value < target -> minOf(target, value + fadeUpStep)
        value > target -> maxOf(target, value - fadeDownStep)
        else -> value
    }

    /** Décode la musique en boucle et remplit le tampon (bloque quand il est plein). */
    private fun decodeMusicLoop(context: Context) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            context.resources.openRawResourceFd(R.raw.music).use { afd ->
                extractor.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            }
            val track = (0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var block = ShortArray(0)
            var inputDone = false
            while (running) {
                val inIndex = if (inputDone) -1 else codec.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val buffer = codec.getInputBuffer(inIndex)!!
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        // Fin du morceau : on reboucle au début
                        codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                if (outIndex >= 0) {
                    val out = codec.getOutputBuffer(outIndex)!!
                    out.position(info.offset)
                    out.limit(info.offset + info.size)
                    val shorts = out.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    val n = shorts.remaining()
                    if (block.size < n) block = ShortArray(n)
                    shorts.get(block, 0, n)
                    codec.releaseOutputBuffer(outIndex, false)
                    if (!music.write(block, n)) break // tampon fermé
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                        codec.flush()
                        inputDone = false
                    }
                }
            }
        } catch (e: Exception) {
            // Musique indisponible : le mixeur continuera simplement sans musique
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    /** Décode un son entier en mémoire (PCM 16 bits entrelacé), null s'il est illisible. */
    private fun decodeAll(context: Context, resId: Int): ShortArray? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            context.resources.openRawResourceFd(resId).use { afd ->
                extractor.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            }
            val track = (0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()

            var out = ShortArray(SAMPLE_RATE * CHANNELS * 11)
            var size = 0
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var spins = 0
            while (running && spins++ < 200_000) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buffer = codec.getInputBuffer(inIndex)!!
                        val n = extractor.readSampleData(buffer, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                if (outIndex >= 0) {
                    val buf = codec.getOutputBuffer(outIndex)!!
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    val shorts = buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    val n = shorts.remaining()
                    if (size + n > out.size) out = out.copyOf(maxOf(out.size * 2, size + n))
                    shorts.get(out, size, n)
                    size += n
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            return if (size > 0) out.copyOf(size) else null
        } catch (e: Exception) {
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    companion object {
        private const val SAMPLE_RATE = 44_100
        private const val CHANNELS = 2
        /** Fondu micro ↔ musique en ~0,8 s. */
        private const val FADE_STEP = 1f / (SAMPLE_RATE * CHANNELS * 0.8f)
        private const val MUSIC_VOLUME = 0.8f
        /** Bande-son de la transition Intro → Match (déjà mixée fort). */
        private const val EFFECT_VOLUME = 0.85f
        /** Coupure / remise du son en ~0,1 s (évite un « clic »). */
        private const val MUTE_STEP = 1f / (SAMPLE_RATE * CHANNELS * 0.1f)
    }
}

/** Tampon circulaire d'échantillons : un thread écrit (bloquant si plein), un autre lit (jamais bloquant). */
private class ShortRing(capacity: Int) {
    private val data = ShortArray(capacity)
    private val lock = Object()
    private var readPos = 0
    private var size = 0
    private var closed = false

    /** @return false si le tampon a été fermé. */
    fun write(src: ShortArray, count: Int): Boolean {
        var written = 0
        synchronized(lock) {
            while (written < count) {
                while (size == data.size && !closed) lock.wait()
                if (closed) return false
                val writePos = (readPos + size) % data.size
                val n = minOf(count - written, data.size - size, data.size - writePos)
                System.arraycopy(src, written, data, writePos, n)
                size += n
                written += n
            }
        }
        return true
    }

    /** Copie jusqu'à [count] échantillons dans [dst] ; renvoie le nombre réellement disponible. */
    fun read(dst: ShortArray, count: Int): Int = synchronized(lock) {
        var read = 0
        while (read < count && size > 0) {
            val n = minOf(count - read, size, data.size - readPos)
            System.arraycopy(data, readPos, dst, read, n)
            readPos = (readPos + n) % data.size
            size -= n
            read += n
        }
        if (read > 0) lock.notifyAll()
        read
    }

    fun close() = synchronized(lock) {
        closed = true
        lock.notifyAll()
    }
}
