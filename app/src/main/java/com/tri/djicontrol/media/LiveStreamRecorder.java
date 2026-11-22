package com.tri.djicontrol.media;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Ghi luồng camera trực tiếp (H.264 Annex-B từ VideoFeeder) thành file MP4 trên điện thoại
 * trong lúc drone quay video -> quay xong xem được ngay, không phải chờ tải file gốc từ thẻ nhớ.
 *
 * Chất lượng = chất lượng hình live view (thường 720p/1080p), có thể giật khi sóng yếu.
 * Không giải mã / mã hoá lại: chỉ tách khung hình H.264 rồi đóng gói vào MP4 bằng MediaMuxer.
 *
 * Dùng: start() -> feed() mỗi lần nhận dữ liệu từ VideoFeeder -> stop().
 */
public class LiveStreamRecorder {

    public interface Callback {
        /** Chạy trên main thread. file = null nếu không ghi được khung hình nào. */
        void onFinished(File file, long durationMs, String error);
    }

    private static final String TAG = "LiveStreamRecorder";
    private static final int MAX_PENDING_BYTES = 8 * 1024 * 1024;

    private final File outFile;
    private final int fallbackWidth, fallbackHeight;
    private final double lat, lon;
    private final HandlerThread thread;
    private final Handler worker;
    private final Handler main = new Handler(Looper.getMainLooper());

    private volatile boolean stopped = false;

    // --- chỉ truy cập trên luồng worker ---
    private final ByteArrayOutputStream pending = new ByteArrayOutputStream(256 * 1024);
    private byte[] sps, pps;
    private final List<byte[]> frameNals = new ArrayList<>();
    private boolean frameHasVcl = false, frameIsKey = false;
    private long frameTimeUs = 0;
    private MediaMuxer muxer;
    private int track = -1;
    private long firstPtsUs = -1, lastPtsUs = -1;
    private int framesWritten = 0;
    private String error;

    /**
     * @param fallbackWidth/Height dùng khi không đọc được kích thước từ SPS
     * @param lat/lon toạ độ ghi vào MP4 (NaN nếu không có)
     */
    public LiveStreamRecorder(File outFile, int fallbackWidth, int fallbackHeight, double lat, double lon) {
        this.outFile = outFile;
        this.fallbackWidth = fallbackWidth > 0 ? fallbackWidth : 1280;
        this.fallbackHeight = fallbackHeight > 0 ? fallbackHeight : 720;
        this.lat = lat;
        this.lon = lon;
        thread = new HandlerThread("live-rec");
        thread.start();
        worker = new Handler(thread.getLooper());
    }

    public File getFile() {
        return outFile;
    }

    /** Gọi từ callback VideoFeeder (luồng bất kỳ). Dữ liệu được chép lại ngay. */
    public void feed(byte[] data, int size) {
        if (stopped || data == null || size <= 0) return;
        final byte[] copy = new byte[size];
        System.arraycopy(data, 0, copy, 0, size);
        final long nowUs = System.nanoTime() / 1000;
        worker.post(() -> {
            if (stopped) return;
            try {
                consume(copy, nowUs);
            } catch (Exception e) {
                Log.w(TAG, "consume", e);
                error = e.getMessage();
            }
        });
    }

    public void stop(Callback cb) {
        worker.post(() -> {
            stopped = true;
            long durationMs = (firstPtsUs >= 0 && lastPtsUs > firstPtsUs) ? (lastPtsUs - firstPtsUs) / 1000 : 0;
            boolean ok = false;
            if (muxer != null) {
                try {
                    muxer.stop();
                    ok = framesWritten > 0;
                } catch (Exception e) {
                    Log.w(TAG, "muxer.stop", e);
                    error = "Không đóng được file MP4: " + e.getMessage();
                }
                try { muxer.release(); } catch (Exception ignored) {}
                muxer = null;
            } else if (error == null) {
                error = sps == null
                        ? "Không nhận được dữ liệu H.264 hợp lệ từ luồng camera"
                        : "Chưa nhận được khung hình chính (keyframe)";
            }
            if (!ok) outFile.delete();
            final File result = ok ? outFile : null;
            final String err = ok ? null : error;
            final long dur = durationMs;
            main.post(() -> {
                if (cb != null) cb.onFinished(result, dur, err);
            });
            thread.quitSafely();
        });
    }

    // =========================================================
    // TÁCH NAL UNIT TỪ LUỒNG ANNEX-B
    // =========================================================
    private void consume(byte[] chunk, long nowUs) throws Exception {
        pending.write(chunk);
        byte[] buf = pending.toByteArray();
        int n = buf.length;

        int start = findStartCode(buf, 0);
        if (start < 0) {
            if (n > MAX_PENDING_BYTES) pending.reset(); // dữ liệu rác, bỏ
            return;
        }
        int nalStart = start + startCodeLen(buf, start);
        while (true) {
            int next = findStartCode(buf, nalStart);
            if (next < 0) break;
            onNal(buf, nalStart, next, nowUs);
            nalStart = next + startCodeLen(buf, next);
        }
        // Giữ lại NAL cuối (chưa biết đã đủ chưa) kèm start code của nó
        int keepFrom = nalStart - 4 >= 0 && buf[nalStart - 4] == 0 ? nalStart - 4 : nalStart - 3;
        pending.reset();
        pending.write(buf, keepFrom, n - keepFrom);
    }

    /** Vị trí start code 00 00 01 (hoặc 00 00 00 01) đầu tiên từ "from", -1 nếu không có. */
    private static int findStartCode(byte[] b, int from) {
        for (int i = Math.max(0, from); i + 2 < b.length; i++) {
            if (b[i] == 0 && b[i + 1] == 0) {
                if (b[i + 2] == 1) {
                    return (i > 0 && b[i - 1] == 0 && i - 1 >= from) ? i - 1 : i;
                }
            }
        }
        return -1;
    }

    private static int startCodeLen(byte[] b, int pos) {
        return (b[pos + 2] == 1) ? 3 : 4;
    }

    private void onNal(byte[] buf, int from, int to, long nowUs) throws Exception {
        // Bỏ các byte 0 thừa ở cuối NAL (trailing_zero_8bits)
        while (to > from && buf[to - 1] == 0) to--;
        if (to - from < 1) return;
        int type = buf[from] & 0x1F;
        byte[] nal = new byte[to - from];
        System.arraycopy(buf, from, nal, 0, nal.length);

        boolean vcl = (type == 1 || type == 5);
        boolean firstSliceOfFrame = vcl && nal.length > 1 && (nal[1] & 0x80) != 0; // first_mb_in_slice == 0

        // NAL không phải slice, hoặc slice đầu của khung mới -> khung hiện tại đã đủ
        if (frameHasVcl && (!vcl || firstSliceOfFrame)) flushFrame();

        switch (type) {
            case 7: sps = nal; break;
            case 8: pps = nal; break;
            case 9: return; // Access Unit Delimiter: không cần ghi
            default: break;
        }
        if (frameNals.isEmpty()) frameTimeUs = nowUs;
        frameNals.add(nal);
        if (vcl) frameHasVcl = true;
        if (type == 5) frameIsKey = true;
    }

    // =========================================================
    // GHI KHUNG HÌNH VÀO MP4
    // =========================================================
    private void flushFrame() {
        List<byte[]> nals = new ArrayList<>(frameNals);
        boolean key = frameIsKey;
        long t = frameTimeUs;
        frameNals.clear();
        frameHasVcl = false;
        frameIsKey = false;

        if (muxer == null) {
            if (!key || sps == null || pps == null) return; // chờ keyframe đầu tiên
            if (!startMuxer()) return;
        }

        int total = 0;
        for (byte[] n : nals) total += 4 + n.length;
        ByteBuffer bb = ByteBuffer.allocate(total);
        for (byte[] n : nals) {
            bb.put((byte) 0).put((byte) 0).put((byte) 0).put((byte) 1).put(n);
        }
        bb.flip();

        if (firstPtsUs < 0) firstPtsUs = t;
        long pts = t - firstPtsUs;
        if (pts <= lastPtsUs - firstPtsUs && framesWritten > 0) pts = (lastPtsUs - firstPtsUs) + 1000;
        lastPtsUs = pts + firstPtsUs;

        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        info.set(0, total, pts, key ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
        try {
            muxer.writeSampleData(track, bb, info);
            framesWritten++;
        } catch (Exception e) {
            Log.w(TAG, "writeSampleData", e);
        }
    }

    private boolean startMuxer() {
        try {
            int[] wh = SpsParser.size(sps);
            int w = wh != null ? wh[0] : fallbackWidth;
            int h = wh != null ? wh[1] : fallbackHeight;
            MediaFormat fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h);
            fmt.setByteBuffer("csd-0", withStartCode(sps));
            fmt.setByteBuffer("csd-1", withStartCode(pps));

            File dir = outFile.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            muxer = new MediaMuxer(outFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            if (!Double.isNaN(lat) && !Double.isNaN(lon) && !(lat == 0 && lon == 0)) {
                try { muxer.setLocation((float) lat, (float) lon); } catch (Exception ignored) {}
            }
            track = muxer.addTrack(fmt);
            muxer.start();
            Log.d(TAG, "Bắt đầu ghi " + w + "x" + h + " -> " + outFile);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "startMuxer", e);
            error = "Không tạo được file MP4: " + e.getMessage();
            if (muxer != null) {
                try { muxer.release(); } catch (Exception ignored) {}
            }
            muxer = null;
            stopped = true;
            return false;
        }
    }

    private static ByteBuffer withStartCode(byte[] nal) {
        ByteBuffer b = ByteBuffer.allocate(nal.length + 4);
        b.put((byte) 0).put((byte) 0).put((byte) 0).put((byte) 1).put(nal);
        b.flip();
        return b;
    }

    // =========================================================
    // ĐỌC KÍCH THƯỚC KHUNG HÌNH TỪ SPS (H.264)
    // =========================================================
    static final class SpsParser {
        private final byte[] d;
        private int bit = 0;

        private SpsParser(byte[] rbsp) { d = rbsp; }

        /** {width, height} hoặc null nếu không đọc được. */
        static int[] size(byte[] spsNal) {
            try {
                SpsParser p = new SpsParser(unescape(spsNal, 1)); // bỏ byte header NAL
                int profile = p.u(8);
                p.u(8); // constraint flags
                p.u(8); // level
                p.ue(); // seq_parameter_set_id
                int chromaFormat = 1;
                if (profile == 100 || profile == 110 || profile == 122 || profile == 244 || profile == 44
                        || profile == 83 || profile == 86 || profile == 118 || profile == 128
                        || profile == 138 || profile == 139 || profile == 134 || profile == 135) {
                    chromaFormat = p.ue();
                    if (chromaFormat == 3) p.u(1);
                    p.ue(); // bit_depth_luma
                    p.ue(); // bit_depth_chroma
                    p.u(1); // qpprime_y_zero_transform_bypass
                    if (p.u(1) == 1) { // seq_scaling_matrix_present
                        int count = chromaFormat != 3 ? 8 : 12;
                        for (int i = 0; i < count; i++) {
                            if (p.u(1) == 1) p.skipScalingList(i < 6 ? 16 : 64);
                        }
                    }
                }
                p.ue(); // log2_max_frame_num
                int pocType = p.ue();
                if (pocType == 0) {
                    p.ue();
                } else if (pocType == 1) {
                    p.u(1);
                    p.se();
                    p.se();
                    int n = p.ue();
                    for (int i = 0; i < n; i++) p.se();
                }
                p.ue(); // max_num_ref_frames
                p.u(1); // gaps_in_frame_num_allowed
                int wMbs = p.ue() + 1;
                int hMapUnits = p.ue() + 1;
                int frameMbsOnly = p.u(1);
                if (frameMbsOnly == 0) p.u(1);
                p.u(1); // direct_8x8_inference
                int w = wMbs * 16;
                int h = (2 - frameMbsOnly) * hMapUnits * 16;
                if (p.u(1) == 1) { // frame_cropping
                    int l = p.ue(), r = p.ue(), t = p.ue(), b = p.ue();
                    int cropX = chromaFormat == 0 ? 1 : 2;
                    int cropY = (chromaFormat == 1 ? 2 : 1) * (2 - frameMbsOnly);
                    w -= (l + r) * cropX;
                    h -= (t + b) * cropY;
                }
                return (w > 0 && h > 0 && w <= 8192 && h <= 8192) ? new int[]{w, h} : null;
            } catch (Exception e) {
                return null;
            }
        }

        /** Bỏ emulation prevention byte (00 00 03 -> 00 00). */
        private static byte[] unescape(byte[] nal, int from) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(nal.length);
            int zeros = 0;
            for (int i = from; i < nal.length; i++) {
                int v = nal[i] & 0xFF;
                if (zeros >= 2 && v == 3) { zeros = 0; continue; }
                out.write(v);
                zeros = (v == 0) ? zeros + 1 : 0;
            }
            return out.toByteArray();
        }

        private int u(int n) {
            int v = 0;
            for (int i = 0; i < n; i++) {
                int byteIdx = bit >> 3;
                if (byteIdx >= d.length) throw new IllegalStateException("hết dữ liệu SPS");
                v = (v << 1) | ((d[byteIdx] >> (7 - (bit & 7))) & 1);
                bit++;
            }
            return v;
        }

        private int ue() {
            int zeros = 0;
            while (u(1) == 0) {
                if (++zeros > 31) throw new IllegalStateException("ue lỗi");
            }
            return zeros == 0 ? 0 : ((1 << zeros) - 1 + u(zeros));
        }

        private int se() {
            int k = ue();
            return (k & 1) == 1 ? (k + 1) / 2 : -(k / 2);
        }

        private void skipScalingList(int size) {
            int last = 8, next = 8;
            for (int j = 0; j < size; j++) {
                if (next != 0) next = (last + se() + 256) % 256;
                last = next == 0 ? last : next;
            }
        }
    }
}
