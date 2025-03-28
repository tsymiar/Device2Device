/*
 * Copyright (C) 2015 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tsymiar.device2device.utils;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

import java.nio.ShortBuffer;

public class SamplePlayer {
    public interface OnCompletionListener {
        void onCompletion();
    }

    private final ShortBuffer mSamples;
    private final int mSampleRate;
    private final int mChannels;
    private final int mNumSamples;  // Number of samples per channel.
    private final AudioTrack mAudioTrack;
    private final short[] mBuffer;
    private int mPlaybackStart;  // Start offset, in samples.
    private Thread mPlayThread;
    private boolean mKeepPlaying;
    private OnCompletionListener mListener;

    private SamplePlayer(ShortBuffer samples, int sampleRate, int channels, int numSamples) {
        mSamples = samples;
        mSampleRate = sampleRate;
        mChannels = channels;
        mNumSamples = numSamples;
        mPlaybackStart = 0;

        int bufferSize = AudioTrack.getMinBufferSize(
                mSampleRate,
                mChannels == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT);
        // make sure minBufferSize can contain at least 1 second of audio (16 bits sample).
        if (bufferSize < mChannels * mSampleRate * 2) {
            bufferSize = mChannels * mSampleRate * 2;
        }
        mBuffer = new short[bufferSize / 2]; // 緩衝區大小是以字節為單位.
        mAudioTrack = new AudioTrack(
                AudioManager.STREAM_MUSIC,
                mSampleRate,
                mChannels == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
                mBuffer.length * 2,
                AudioTrack.MODE_STREAM);
        // Check when player played all the given data and notify user if mListener is set.
        mAudioTrack.setNotificationMarkerPosition(mNumSamples - 1);  // Set the marker to the end.设定一个marker位置，当声音播放到这个位置时，就启动onMarkerReached 方法。
        mAudioTrack.setPlaybackPositionUpdateListener(
                new AudioTrack.OnPlaybackPositionUpdateListener() {
                    @Override
                    public void onPeriodicNotification(AudioTrack track) {
                        //LogU.i("1", "1");
                    }

                    @Override
                    public void onMarkerReached(AudioTrack track) {
                        //LogU.i("2", "2");
                        stop();
                        if (mListener != null) {
                            mListener.onCompletion();
                        }
                    }
                });
        mPlayThread = null;
        mKeepPlaying = true;
        mListener = null;
    }

    public SamplePlayer(SoundRecord sf) {
        this(sf.getSamples(), sf.getSampleRate(), sf.getChannels(), sf.getNumSamples());
    }

    public void setOnCompletionListener(OnCompletionListener listener) {
        mListener = listener;
    }

    public boolean isPlaying() {
        return mAudioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING;
    }

    private boolean isPaused() {
        return mAudioTrack.getPlayState() == AudioTrack.PLAYSTATE_PAUSED;
    }

    public void start() {
        if (isPlaying()) {
            return;
        }

        mKeepPlaying = true;
        mAudioTrack.flush();
        mAudioTrack.play();
        // Setting thread feeding the audio samples to the audio hardware.
        // (Assumes mChannels = 1 or 2).
        mPlayThread = new Thread() {
            public void run() {
                int position = mPlaybackStart * mChannels;
                mSamples.position(position);
                int limit = mNumSamples * mChannels;
                while (mSamples.position() < limit && mKeepPlaying) {


                    int numSamplesLeft = limit - mSamples.position();
                    if (numSamplesLeft >= mBuffer.length) {
                        mSamples.get(mBuffer);//-从PCM文件中，以流的形式读出存放在 mBuffer 中，將此緩衝區的短路傳輸到給定的目標數組
                    } else {
                        for (int i = numSamplesLeft; i < mBuffer.length; i++) {
                            mBuffer[i] = 0;
                        }
                        mSamples.get(mBuffer, 0, numSamplesLeft);//取出shortBuffer中的short数组
                    }
                    // 使用以ByteBuffer為參數的write方法
                    mAudioTrack.write(mBuffer, 0, mBuffer.length);//write是从你的buffer里取数据，write到audiotrack的缓冲中去，而且每次只能write有限的长度，因为缓冲空间是有限的

                }
            }
        };
        mPlayThread.start();
    }

    public void pause() {
        if (isPlaying()) {
            mAudioTrack.pause();
            // mAudioTrack.write() should block if it cannot write.
        }
    }

    private int playTag = 0;

    public void stop() {
        if (isPlaying() || isPaused()) {
            mKeepPlaying = false;
            mAudioTrack.pause();  // pause() stops the playback immediately.
            mAudioTrack.stop();   // Unblock mAudioTrack.write() to avoid deadlocks.
            if (mPlayThread != null) {
                try {
                    mPlayThread.join();
                } catch (InterruptedException ignored) {
                }
                mPlayThread = null;
            }
            mAudioTrack.flush();  // just in case...
        }
        if (playTag == 0) { //First tag
            mKeepPlaying = false;
            mAudioTrack.pause();  // pause() stops the playback immediately.
            mAudioTrack.stop();   // Unblock mAudioTrack.write() to avoid deadlocks.
            if (mPlayThread != null) {
                try {
                    mPlayThread.join();
                } catch (InterruptedException ignored) {
                }
                mPlayThread = null;
            }
            mAudioTrack.flush();  // just in case...
            playTag++; //add tag state
        }
    }

    public void release() {
        stop();
        mAudioTrack.release();
    }

    public void seekTo(int msec) {
        playTag = 0;
        boolean wasPlaying = isPlaying();
        stop();
        mPlaybackStart = (int) (msec * (mSampleRate / 1000.0));
        if (mPlaybackStart > mNumSamples) {
            mPlaybackStart = mNumSamples;  // Nothing to play...
        }
        mAudioTrack.setNotificationMarkerPosition(mNumSamples - 1 - mPlaybackStart);
        if (wasPlaying) {
            start();
        }
    }

    public int getCurrentPosition() {
        return (int) ((mPlaybackStart + mAudioTrack.getPlaybackHeadPosition()) * (1000.0 / mSampleRate));
    }
}
