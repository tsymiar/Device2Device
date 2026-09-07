package com.tsymiar.device2device.game;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

import java.util.ArrayList;
import java.util.List;

/**
 * 推演音频合成器
 * 程序实时合成推演音频（变调 + 叠加），Android 上通过 AudioTrack 播放 PCM
 * （对应 Qt 端 DeductionAudio.h/cpp）
 */
public class DeductionAudio {

    private static final float ECHO_PI = 3.14159265f;

    // 单一声纹层（用于叠加合成）
    public static class AudioLayer {
        public float frequency = 200.0f;   // 基频 Hz
        public float amplitude = 0.5f;     // 0.0-1.0
        public float durationSec = 0.8f;   // 时长
        public float pitchScale = 1.0f;    // 变调系数（1.0 原始，1.2 = 升高约3个半音）
    }

    private final boolean mAudioAvailable = true; // Android 始终有 AudioTrack 后端

    public boolean hasAudioOutput() {
        return mAudioAvailable;
    }

    /**
     * PCM 合成：多层正弦波叠加 + 变调 + 指数衰减包络
     * 返回 16bit 单声道 PCM
     */
    public byte[] generatePCM(List<AudioLayer> layers, int sampleRate) {
        if (layers == null || layers.isEmpty()) return new byte[0];

        // 计算最大时长（限制上限，避免内存爆炸）
        float maxDur = 0.0f;
        for (AudioLayer l : layers) if (l.durationSec > maxDur) maxDur = l.durationSec;
        if (maxDur <= 0.0f) maxDur = 0.5f;
        if (maxDur > 30.0f) maxDur = 30.0f; // 上限 30 秒

        int totalSamples = (int) (maxDur * sampleRate);
        byte[] pcm = new byte[totalSamples * 2];

        for (int i = 0; i < totalSamples; ++i) {
            float t = (float) i / sampleRate;
            float sample = 0.0f;
            int activeCount = 0;
            for (AudioLayer l : layers) {
                if (t > l.durationSec) continue;
                activeCount++;
                // 变调：频率乘 pitchScale
                float f = l.frequency * l.pitchScale;
                // 指数衰减包络（防御 durationSec <= 0 导致除零）
                float dur = l.durationSec > 0.0f ? l.durationSec : 0.5f;
                float env = (float) Math.exp(-3.0f * t / dur);
                // 加入轻微谐波，让声音更"声纹"感
                float v = (float) (Math.sin(2.0f * ECHO_PI * f * t)
                        + 0.3f * Math.sin(2.0f * ECHO_PI * f * 2.0f * t)
                        + 0.15f * Math.sin(2.0f * ECHO_PI * f * 3.0f * t));
                sample += v * l.amplitude * env;
            }
            if (activeCount > 1) sample /= activeCount; // 叠加归一化
            // 限幅
            if (sample > 1.0f) sample = 1.0f;
            if (sample < -1.0f) sample = -1.0f;
            short val = (short) (sample * 32000.0f);
            pcm[2 * i] = (byte) (val & 0xFF);
            pcm[2 * i + 1] = (byte) ((val >> 8) & 0xFF);
        }
        return pcm;
    }

    /**
     * 合成 + 描述。
     * 生成 PCM 并异步播放（不阻塞 UI 线程），返回描述文字。
     */
    public String synthesize(List<AudioLayer> layers, boolean play) {
        StringBuilder layerDesc = new StringBuilder();
        List<String> parts = new ArrayList<>();
        for (AudioLayer l : layers) {
            String extra = (Math.abs(l.pitchScale - 1.0f) > 0.001f) ? "[变调]" : "";
            parts.add(String.format("%dHz(%.2fx)%s", (int) l.frequency, l.pitchScale, extra));
        }
        layerDesc.append("合成声纹层：").append(String.join(" + ", parts));

        if (play) {
            byte[] pcm = generatePCM(layers, 44100);
            if (pcm.length > 0) {
                playPCMAsync(pcm, 44100);
            }
            layerDesc.append("（已合成音频）");
        } else {
            layerDesc.append("（仅参数模拟）");
        }
        return layerDesc.toString();
    }

    private void playPCMAsync(final byte[] pcm, final int sampleRate) {
        // 兼容 API 21：使用旧构造方式
        final int minBuf = AudioTrack.getMinBufferSize(sampleRate,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        final AudioTrack track = new AudioTrack(
                AudioManager.STREAM_MUSIC, sampleRate,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                Math.max(minBuf, pcm.length * 2), AudioTrack.MODE_STATIC);
        Thread t = new Thread(() -> {
            try {
                track.write(pcm, 0, pcm.length);
                track.play();
                // 等待播放完成（时长约 pcm.length / (2*sampleRate) 秒）
                Thread.sleep((long) (pcm.length / (2.0 * sampleRate) * 1000.0) + 100);
            } catch (Exception e) {
                // 忽略播放异常
            } finally {
                try { track.stop(); } catch (Exception ignored) { }
                track.release();
            }
        });
        t.setDaemon(true);
        t.start();
    }
}
