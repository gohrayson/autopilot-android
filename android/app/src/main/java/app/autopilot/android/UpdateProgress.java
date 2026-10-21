package app.autopilot.android;

/** Thread-safe attempt metrics. Restored bytes are progress, never network throughput. */
public final class UpdateProgress {
    public static final int MAX_CONNECTIONS = 4;
    public final long totalBytes;
    private long restoredBytes, startedNanos;
    private long downloadedBytes;
    private int activeConnections;

    public UpdateProgress(long totalBytes, long restoredBytes, long startedNanos) {
        if(totalBytes<=0||totalBytes>UpdateManifest.MAX_APK_BYTES||restoredBytes<0||restoredBytes>totalBytes)
            throw new IllegalArgumentException("UPDATE_PROGRESS_INVALID");
        this.totalBytes=totalBytes;this.restoredBytes=restoredBytes;this.downloadedBytes=restoredBytes;this.startedNanos=startedNanos;
    }
    /** Reject invalid producer data; delayed callbacks cannot move visible progress backwards. */
    public synchronized boolean update(long downloaded,long total,int connections) {
        if(total!=totalBytes||downloaded<0||downloaded>total||connections<0||connections>MAX_CONNECTIONS)return false;
        downloadedBytes=Math.max(downloadedBytes,downloaded);activeConnections=connections;return true;
    }
    /** A zero/idle core callback explicitly means unusable cached ranges were discarded. */
    public synchronized void restart(long nowNanos){
        restoredBytes=0;downloadedBytes=0;activeConnections=0;startedNanos=nowNanos;
    }
    public synchronized Snapshot snapshot(long nowNanos,boolean downloading) {
        long elapsed=nowNanos-startedNanos;
        long networkBytes=Math.max(0,downloadedBytes-restoredBytes);
        long rate=downloading&&elapsed>0?networkBytes*1_000_000_000L/elapsed:0;
        long remaining=totalBytes-downloadedBytes;
        long eta=remaining==0?0:rate>0?(remaining+rate-1)/rate:-1;
        return new Snapshot(downloadedBytes,totalBytes,(int)(downloadedBytes*100/totalBytes),rate,eta,downloading?activeConnections:0);
    }
    public static final class Snapshot {
        public final long downloadedBytes,totalBytes,bytesPerSecond,etaSeconds;
        public final int percent,activeConnections;
        private Snapshot(long downloaded,long total,int percent,long rate,long eta,int connections){
            this.downloadedBytes=downloaded;this.totalBytes=total;this.percent=percent;this.bytesPerSecond=rate;this.etaSeconds=eta;this.activeConnections=connections;
        }
    }
}
