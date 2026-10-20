package com.scorecast.app

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.pedro.encoder.input.audio.CustomAudioEffect
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.File
import kotlin.concurrent.thread

/**
 * Mixe le son envoyé dans le live. Branché sur le micro (« effet audio » de RootEncoder),
 * il reçoit chaque bloc de son du micro et le remplace par :
 *   micro × gainMicro + musique × gainMusique.
 *
 * La musique d'attente est importée par le club ([musicFile], facultative). Sur les écrans d'intro,
 * de pause et de fin ([musicOn] = true) : musique, micro coupé. En match, ou si le club n'a pas de
 * musique : micro seul. Le passage de l'un à l'autre se fait en fondu.
 *
 * Format attendu : PCM 16 bits, 44 100 Hz, stéréo (la musique doit avoir ce format pour être jouée
 * à la bonne vitesse : c'est le cas de la très grande majorité des MP3 / M4A).
 */
class AudioMixer(private val musicFile: File?) : CustomAudioEffect() {

    /** true : musique (si le club en a une), micro coupé. false : micro seul. */
    @Volatile var musicOn = false

    /** Son du live entièrement coupé (bouton « Couper le son »). */
    @Volatile var muted = false

    /**
     * Pendant une transition : le son en cours (musique ou micro) baisse doucement jusqu'au silence,
     * puis, au changement d'écran, le nouveau son remonte progressivement.
     */
    @Volatile private var dip = false
    @Volatile private var fadeDownStep = FADE_STEP
    @Volatile private var fadeUpStep = FADE_STEP

    /** Début de transition : le son actuel s'éteint en [ms] millisecondes. */
    fun fadeOutFor(ms: Long) {
        fadeDownStep = stepFor(ms)
        dip = true
    }

    /** Changement d'écran : le son de la nouvelle scène ([music]) remonte en [ms] millisecondes. */
    fun fadeInFor(ms: Long, music: Boolean) {
        fadeUpStep = stepFor(ms)
        musicOn = music
        dip = false
    }

    /** Changement d'écran sans transition : fondu enchaîné classique. */
    fun crossfade(music: Boolean) {
        fadeDownStep = FADE_STEP
        fadeUpStep = FADE_STEP
        dip = false
        musicOn = music
    }

    private fun stepFor(ms: Long) = 1f / (SAMPLE_RATE * CHANNELS * (ms.coerceAtLeast(100L) / 1000f))

    private var musicGain = 0f
    private var micGain = 1f
    private val music = ShortRing(SAMPLE_RATE * CHANNELS * 2) // ~2 s d'avance
    private var musicBlock = ShortArray(8192)

    @Volatile private var running = true

    init {
        if (musicFile != null) thread(name = "music-decoder", isDaemon = true) { decodeMusicLoop(musicFile) }
    }

    fun release() {
        running = false
        music.close()
    }

    override fun process(pcmBuffer: ByteArray): ByteArray {
        if (muted) {
            pcmBuffer.fill(0)
            return pcmBuffer
        }
        val samples = ByteBuffer.wrap(pcmBuffer).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val count = samples.remaining()
        val musicTarget = if (!dip && musicOn && musicFile != null) 1f else 0f
        val micTarget = if (dip) 0f else 1f - musicTarget

        // La musique n'avance que lorsqu'elle est audible : elle reprend là où elle s'était arrêtée
        val needMusic = musicOn || musicGain > 0f
        if (musicBlock.size < count) musicBlock = ShortArray(count)
        val got = if (needMusic) music.read(musicBlock, count) else 0

        if (musicGain == 0f && micGain == 1f && musicTarget == 0f && micTarget == 1f) return pcmBuffer // micro seul : rien à mixer
        for (i in 0 until count) {
            musicGain = approach(musicGain, musicTarget)
            micGain = approach(micGain, micTarget)
            val mic = samples.get(i) * micGain
            val m = if (i < got) musicBlock[i] * musicGain * MUSIC_VOLUME else 0f
            samples.put(i, (mic + m).toInt().coerceIn(-32768, 32767).toShort())
        }
        return pcmBuffer
    }

    private fun approach(value: Float, target: Float): Float = when {
        value < target -> minOf(target, value + fadeUpStep)
        value > target -> maxOf(target, value - fadeDownStep)
        else -> value
    }

    /** Décode la musique en boucle et remplit le tampon (bloque quand il est plein). */
    private fun decodeMusicLoop(file: File) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.path)
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
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
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
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    channels = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }
                if (outIndex >= 0) {
                    val out = codec.getOutputBuffer(outIndex)!!
                    out.position(info.offset)
                    out.limit(info.offset + info.size)
                    val shorts = out.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    var n = shorts.remaining()
                    if (block.size < n * 2) block = ShortArray(n * 2)
                    shorts.get(block, 0, n)
                    codec.releaseOutputBuffer(outIndex, false)
                    if (channels == 1) {
                        // Musique mono : chaque échantillon est dupliqué (gauche = droite)
                        for (i in n - 1 downTo 0) {
                            block[2 * i] = block[i]; block[2 * i + 1] = block[i]
                        }
                        n *= 2
                    }
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

    companion object {
        private const val SAMPLE_RATE = 44_100
        private const val CHANNELS = 2
        /** Fondu micro ↔ musique en ~0,8 s. */
        private const val FADE_STEP = 1f / (SAMPLE_RATE * CHANNELS * 0.8f)
        private const val MUSIC_VOLUME = 0.8f
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
