package com.babycam;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaRecorder;

// Mic -> AAC (hardware/software MediaCodec) -> live stream.
final class Mic {
    // Mic boost with a soft limiter (tanh): quiet sounds come up, loud ones round off instead of clipping.
    static void boost(byte[] pcm, int n, int gain) {
        if (gain <= 1) return;
        double norm = Math.tanh(gain);
        for (int k = 0; k + 1 < n; k += 2) {
            double x = (short) ((pcm[k] & 0xff) | (pcm[k + 1] << 8)) / 32768.0;
            double y = Math.tanh(x * gain) / Math.max(norm, 1e-6) * Math.min(1, norm);
            int v = (int) Math.round(Math.max(-1, Math.min(1, y)) * 32767);
            pcm[k] = (byte) v;
            pcm[k + 1] = (byte) (v >> 8);
        }
    }

    static void start() {
        new Thread(() -> {
            try {
                int rate = 48000;
                int min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                AudioRecord rec = new AudioRecord.Builder()
                    .setContext(Shell.get())
                    .setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(Math.max(min, rate)).build();
                MediaFormat af = MediaFormat.createAudioFormat("audio/mp4a-latm", rate, 1);
                af.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
                af.setInteger(MediaFormat.KEY_BIT_RATE, 64000);
                af.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
                MediaCodec aac = MediaCodec.createEncoderByType("audio/mp4a-latm");
                aac.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                aac.start();
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                rec.startRecording();
                Server.log("mic state " + rec.getRecordingState());
                byte[] buf = new byte[rate / 50 * 2]; // 20 ms
                long samples = 0;
                while (true) {
                    int n = rec.read(buf, 0, buf.length);
                    if (n <= 0) continue;
                    Level.add(buf, n);
                    boost(buf, n, Settings.micGain);
                    int in = aac.dequeueInputBuffer(20_000);
                    if (in >= 0) {
                        java.nio.ByteBuffer ib = aac.getInputBuffer(in);
                        ib.clear();
                        ib.put(buf, 0, n);
                        aac.queueInputBuffer(in, 0, n, samples * 1_000_000 / rate, 0);
                        samples += n / 2;
                    }
                    int out;
                    while ((out = aac.dequeueOutputBuffer(info, 0)) >= 0) {
                        java.nio.ByteBuffer ob = aac.getOutputBuffer(out);
                        byte[] frame = new byte[info.size];
                        ob.position(info.offset);
                        ob.get(frame);
                        aac.releaseOutputBuffer(out, false);
                        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) Live.setAudioConfig(frame);
                        else if (frame.length > 0) Live.onAudio(frame);
                    }
                }
            } catch (Throwable e) { Server.log("mic failed " + android.util.Log.getStackTraceString(e)); }
        }, "mic").start();
    }
}
