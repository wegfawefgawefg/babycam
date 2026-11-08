package com.babycam;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

// Fragmented MP4 writer for live browser playback (MSE) and mpv: one init segment
// (H.264 track 1 + AAC track 2), then one fragment per video frame carrying the audio
// frames that arrived since the previous one.
final class Mp4 {
    static final int VIDEO_SCALE = 90000, AUDIO_SCALE = 48000;

    static final class Out {
        final ByteArrayOutputStream b = new ByteArrayOutputStream();
        final DataOutputStream d = new DataOutputStream(b);
        Out i32(long v) { try { d.writeInt((int) v); } catch (IOException e) { throw new RuntimeException(e); } return this; }
        Out i16(int v) { try { d.writeShort(v); } catch (IOException e) { throw new RuntimeException(e); } return this; }
        Out i8(int v) { try { d.writeByte(v); } catch (IOException e) { throw new RuntimeException(e); } return this; }
        Out i64(long v) { try { d.writeLong(v); } catch (IOException e) { throw new RuntimeException(e); } return this; }
        Out bytes(byte[] v) { try { d.write(v); } catch (IOException e) { throw new RuntimeException(e); } return this; }
        Out zeros(int n) { return bytes(new byte[n]); }
        Out str(String s) { return bytes(s.getBytes(StandardCharsets.US_ASCII)); }
        byte[] get() { return b.toByteArray(); }
    }

    static byte[] box(String type, byte[]... parts) {
        int len = 8;
        for (byte[] p : parts) len += p.length;
        Out o = new Out().i32(len).str(type);
        for (byte[] p : parts) o.bytes(p);
        return o.get();
    }

    static byte[] full(String type, int version, int flags, byte[]... parts) {
        byte[][] all = new byte[parts.length + 1][];
        all[0] = new Out().i32(((long) version << 24) | flags).get();
        System.arraycopy(parts, 0, all, 1, parts.length);
        return box(type, all);
    }

    static final int[] MATRIX = {0x10000, 0, 0, 0, 0x10000, 0, 0, 0, 0x40000000};

    static byte[] matrix() {
        Out o = new Out();
        for (int m : MATRIX) o.i32(m);
        return o.get();
    }

    static byte[] init(byte[] sps, byte[] pps, int width, int height, byte[] aacConfig) {
        byte[] ftyp = box("ftyp", new Out().str("isom").i32(0x200).str("isom").str("iso6").str("avc1").str("mp41").get());
        byte[] mvhd = full("mvhd", 0, 0, new Out().i32(0).i32(0).i32(1000).i32(0).i32(0x10000).i16(0x100)
            .zeros(10).bytes(matrix()).zeros(24).i32(3).get());
        byte[] avcC = box("avcC", new Out().i8(1).i8(sps[1]).i8(sps[2]).i8(sps[3]).i8(0xFF).i8(0xE1)
            .i16(sps.length).bytes(sps).i8(1).i16(pps.length).bytes(pps).get());
        byte[] avc1 = box("avc1", new Out().zeros(6).i16(1).zeros(16).i16(width).i16(height)
            .i32(0x480000).i32(0x480000).i32(0).i16(1).zeros(32).i16(0x18).i16(0xFFFF).get(), avcC);
        byte[] video = trak(1, width, height, VIDEO_SCALE, "vide", "VideoHandler",
            full("vmhd", 0, 1, new Out().zeros(8).get()), avc1);
        byte[] asc = aacConfig;
        byte[] dsi = new Out().i8(5).i8(asc.length).bytes(asc).get();
        byte[] dcd = new Out().i8(4).i8(13 + dsi.length).i8(0x40).i8(0x15).zeros(3).i32(96000).i32(96000).bytes(dsi).get();
        byte[] sl = new Out().i8(6).i8(1).i8(2).get();
        byte[] es = new Out().i8(3).i8(3 + dcd.length + sl.length).i16(2).i8(0).bytes(dcd).bytes(sl).get();
        byte[] esds = full("esds", 0, 0, es);
        byte[] mp4a = box("mp4a", new Out().zeros(6).i16(1).zeros(8).i16(1).i16(16).i16(0).i16(0)
            .i32((long) AUDIO_SCALE << 16).get(), esds);
        byte[] audio = trak(2, 0, 0, AUDIO_SCALE, "soun", "SoundHandler",
            full("smhd", 0, 0, new Out().zeros(4).get()), mp4a);
        byte[] mvex = box("mvex", trex(1), trex(2));
        return concat(ftyp, box("moov", mvhd, video, audio, mvex));
    }

    static byte[] trex(int track) {
        return full("trex", 0, 0, new Out().i32(track).i32(1).i32(0).i32(0).i32(0).get());
    }

    static byte[] trak(int id, int w, int h, int scale, String handler, String name, byte[] mediaHeader, byte[] sampleEntry) {
        byte[] tkhd = full("tkhd", 0, 3, new Out().i32(0).i32(0).i32(id).i32(0).i32(0).zeros(8).i16(0).i16(0)
            .i16(id == 2 ? 0x100 : 0).i16(0).bytes(matrix()).i32((long) w << 16).i32((long) h << 16).get());
        byte[] mdhd = full("mdhd", 0, 0, new Out().i32(0).i32(0).i32(scale).i32(0).i16(0x55C4).i16(0).get());
        byte[] hdlr = full("hdlr", 0, 0, new Out().i32(0).str(handler).zeros(12).str(name).i8(0).get());
        byte[] dinf = box("dinf", full("dref", 0, 0, new Out().i32(1).get(), full("url ", 0, 1)));
        byte[] stbl = box("stbl",
            full("stsd", 0, 0, new Out().i32(1).get(), sampleEntry),
            full("stts", 0, 0, new Out().i32(0).get()),
            full("stsc", 0, 0, new Out().i32(0).get()),
            full("stsz", 0, 0, new Out().i32(0).i32(0).get()),
            full("stco", 0, 0, new Out().i32(0).get()));
        return box("trak", tkhd, box("mdia", mdhd, hdlr, box("minf", mediaHeader, dinf, stbl)));
    }

    static final class Sample {
        final byte[] data; final long time; final int duration; final boolean key;
        Sample(byte[] data, long time, int duration, boolean key) { this.data = data; this.time = time; this.duration = duration; this.key = key; }
    }

    // video may be null (audio-only fragment), audio may be empty.
    static byte[] fragment(int seq, Sample video, List<Sample> audio) {
        byte[] moof = moof(seq, video, audio, 0);
        moof = moof(seq, video, audio, moof.length + 8);
        int len = 8;
        if (video != null) len += video.data.length;
        for (Sample a : audio) len += a.data.length;
        Out mdat = new Out().i32(len).str("mdat");
        if (video != null) mdat.bytes(video.data);
        for (Sample a : audio) mdat.bytes(a.data);
        return concat(moof, mdat.get());
    }

    static byte[] moof(int seq, Sample video, List<Sample> audio, int dataStart) {
        byte[] mfhd = full("mfhd", 0, 0, new Out().i32(seq).get());
        int offset = dataStart;
        byte[] vtraf = new byte[0], atraf = new byte[0];
        if (video != null) {
            vtraf = box("traf",
                full("tfhd", 0, 0x020000, new Out().i32(1).get()),
                full("tfdt", 1, 0, new Out().i64(video.time).get()),
                full("trun", 0, 0x701, new Out().i32(1).i32(offset).i32(video.duration).i32(video.data.length)
                    .i32(video.key ? 0x02000000 : 0x01010000).get()));
            offset += video.data.length;
        }
        if (!audio.isEmpty()) {
            Out entries = new Out().i32(audio.size()).i32(offset);
            for (Sample a : audio) entries.i32(a.duration).i32(a.data.length);
            atraf = box("traf",
                full("tfhd", 0, 0x020000, new Out().i32(2).get()),
                full("tfdt", 1, 0, new Out().i64(audio.get(0).time).get()),
                full("trun", 0, 0x301, entries.get()));
        }
        return box("moof", mfhd, vtraf, atraf);
    }

    static byte[] concat(byte[]... parts) {
        Out o = new Out();
        for (byte[] p : parts) o.bytes(p);
        return o.get();
    }
}
