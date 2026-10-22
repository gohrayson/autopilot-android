package app.autopilot.android;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.Proxy;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.nio.channels.FileChannel;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.IntSupplier;
import java.util.function.Consumer;
import javax.net.ssl.HttpsURLConnection;

/** Bounded public HTTPS downloads. Redirects stay within the source origin or GitHub's release hosts. */
public final class UpdateDownload {
    public static final int MAX_REDIRECTS = 3;
    public static final int PARALLEL_CONNECTIONS = 4;
    public static final long PARALLEL_MIN_BYTES = 4L * 1024 * 1024;
    private static final int MAX_ATTEMPTS = 3;
    private static final ConcurrentHashMap<String, ReentrantLock> CACHE_LOCKS = new ConcurrentHashMap<>();
    public static final class UpdateException extends IOException {
        public final String code;
        public UpdateException(String code) { super(code); this.code = code; }
    }
    public interface Response extends AutoCloseable {
        int status() throws IOException;
        long contentLength();
        InputStream body() throws IOException;
        default String location() { return null; }
        default String contentRange() { return null; }
        default String contentEncoding() { return null; }
        @Override void close() throws IOException;
    }
    public interface Transport {
        Response open(URI uri) throws IOException;
        default Response open(URI uri, long start, long end) throws IOException { return open(uri); }
    }
    public interface ProgressListener { void onProgress(long downloaded, long total, int activeConnections); }
    private final Transport transport;
    private final long manifestTimeoutMillis;

    public UpdateDownload() { this(() -> 0, null); }
    /**
     * The supplier is called from download workers for every request, including redirects/ranges.
     * It must return a thread-safe snapshot: 0 only when no Autopilot VPN is active, a positive
     * loopback proxy port for the verified current node, or -1 while the VPN has no usable route.
     * A failed VPN request never falls back to a direct connection.
     */
    public UpdateDownload(IntSupplier activeProxyPort, Consumer<String> routeObserver) {
        this(new HttpsTransport(activeProxyPort, routeObserver, (uri, proxy) ->
                (HttpsURLConnection) uri.toURL().openConnection(proxy)));
    }
    public UpdateDownload(Transport transport) { this(transport, 30_000); }
    UpdateDownload(Transport transport, long manifestTimeoutMillis) {
        if (transport == null) throw new IllegalArgumentException("transport");
        if (manifestTimeoutMillis <= 0) throw new IllegalArgumentException("manifestTimeoutMillis");
        this.transport = transport;
        this.manifestTimeoutMillis = manifestTimeoutMillis;
    }

    public UpdateManifest fetchManifest(String manifestUrl, String expectedPackage) throws UpdateException {
        URI source = UpdateManifest.publicHttpsUri(manifestUrl);
        Session session = new Session(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(manifestTimeoutMillis));
        try {
            session.check();
            Response response = openResponse(source, session.deadline, -1, -1, session);
            try {
                requireOk(response);
                if (response.contentLength() > UpdateManifest.MAX_MANIFEST_BYTES) throw error("UPDATE_RESPONSE_SIZE");
                byte[] bytes;
                try (InputStream input = response.body(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        session.check();
                        if ((long) output.size() + read > UpdateManifest.MAX_MANIFEST_BYTES) throw error("UPDATE_RESPONSE_SIZE");
                        output.write(buffer, 0, read);
                    }
                    session.check();
                    bytes = output.toByteArray();
                }
                if (response.contentLength() >= 0 && response.contentLength() != bytes.length) throw error("UPDATE_RESPONSE_SIZE");
                String json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
                return UpdateManifest.parse(json, source.toASCIIString(), expectedPackage);
            } finally { session.release(response); }
        } catch (java.nio.charset.CharacterCodingException failure) { throw error("UPDATE_INVALID_MANIFEST"); }
        catch (IOException failure) { throw normalize(failure, session); }
        finally { session.finish(); }
    }

    /** The caller must supply its private cache directory and verify the returned APK's Android signer. */
    public File download(UpdateManifest manifest, File privateCacheDirectory) throws UpdateException {
        return download(manifest, privateCacheDirectory, null);
    }

    public File download(UpdateManifest manifest, File privateCacheDirectory, ProgressListener listener) throws UpdateException {
        URI apk = checkedApk(manifest);
        long deadline = System.nanoTime() + 600_000_000_000L;
        Session session = new Session(deadline);
        ReentrantLock lock = null;
        Path resume = null;
        try {
            session.check();
            Path root = cacheRoot(privateCacheDirectory, true);
            if (manifest.sizeBytes < PARALLEL_MIN_BYTES)
                return single(manifest, root, apk, session, new Progress(manifest.sizeBytes, listener, new long[4]));
            resume = resumePath(manifest, root);
            lock = CACHE_LOCKS.computeIfAbsent(resume.toString(), ignored -> new ReentrantLock());
            if (!lock.tryLock()) throw error("UPDATE_DOWNLOAD_BUSY");
            long[] lengths = initializeResume(manifest, resume);
            Progress progress = new Progress(manifest.sizeBytes, listener, lengths);
            if (sum(lengths) < manifest.sizeBytes) {
                if (!probe(manifest, apk, session, progress)) {
                    clearResume(resume); progress.reset();
                    return single(manifest, root, apk, session, progress);
                }
                try { transferParts(manifest, apk, resume, session, progress); }
                catch (RangeUnsupported unsupported) {
                    // The server changed range support after our probe. Never append a 200 body.
                    session.finish(); clearResume(resume); progress.reset();
                    session = new Session(deadline);
                    return single(manifest, root, apk, session, progress);
                }
            }
            progress.idle();
            return merge(manifest, root, resume, session);
        } catch (IOException failure) {
            UpdateException result = normalize(failure, session);
            if (resume != null && lock != null && lock.isHeldByCurrentThread() && invalidatesResume(result.code)) {
                session.finish();
                try { clearResume(resume); } catch (IOException ignored) { }
            }
            throw result;
        } finally {
            session.finish();
            if (lock != null && lock.isHeldByCurrentThread()) lock.unlock();
        }
    }

    /** Safe local snapshot; invalid/missing/foreign cache entries never count. No network. */
    public long resumableBytes(UpdateManifest manifest, File privateCacheDirectory) {
        if (manifest == null || manifest.sizeBytes < PARALLEL_MIN_BYTES) return 0;
        try { return sum(readLengths(manifest, resumePath(manifest, cacheRoot(privateCacheDirectory, false)))); }
        catch (IOException | RuntimeException invalid) { return 0; }
    }

    /** Only this manifest's hashed cache; call after a canceled download has returned. No network. */
    public void discardResume(UpdateManifest manifest, File privateCacheDirectory) throws UpdateException {
        checkedApk(manifest);
        if (privateCacheDirectory == null) throw error("UPDATE_CACHE_ERROR");
        // An available manifest can exist before any download/cache directory.
        // Do not create that directory merely to discard nonexistent state.
        if (!Files.exists(privateCacheDirectory.toPath(), LinkOption.NOFOLLOW_LINKS)) return;
        ReentrantLock lock = null;
        try {
            Path resume = resumePath(manifest, cacheRoot(privateCacheDirectory, false));
            lock = CACHE_LOCKS.computeIfAbsent(resume.toString(), ignored -> new ReentrantLock());
            if (!lock.tryLock()) throw error("UPDATE_DOWNLOAD_BUSY");
            clearResume(resume);
        } catch (IOException failure) { throw failure instanceof UpdateException ? (UpdateException) failure : error("UPDATE_CACHE_ERROR"); }
        finally { if (lock != null && lock.isHeldByCurrentThread()) lock.unlock(); }
    }

    private static URI checkedApk(UpdateManifest manifest) throws UpdateException {
        if (manifest == null) throw error("UPDATE_INVALID_MANIFEST");
        URI source = UpdateManifest.publicHttpsUri(manifest.manifestUrl);
        URI apk = UpdateManifest.publicHttpsUri(manifest.apkUrl);
        if (!UpdateManifest.sameOrigin(source, apk)) throw error("UPDATE_UNSAFE_URL");
        return apk;
    }
    private static Path cacheRoot(File directory, boolean create) throws IOException {
        if (directory == null || Files.isSymbolicLink(directory.toPath())) throw error("UPDATE_CACHE_ERROR");
        Path root = directory.getCanonicalFile().toPath();
        if (create) Files.createDirectories(root);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw error("UPDATE_CACHE_ERROR");
        return root;
    }
    private static String identity(UpdateManifest m) {
        return "autopilot-resume-v1\n" + m.packageName + "\n" + m.versionCode + "\n" + m.versionName
                + "\n" + m.apkUrl + "\n" + m.sizeBytes + "\n" + m.sha256;
    }
    private static MessageDigest digest() throws UpdateException {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw error("UPDATE_HASH_UNAVAILABLE"); }
    }
    private static Path resumePath(UpdateManifest m, Path root) throws UpdateException {
        StringBuilder hash = new StringBuilder();
        for (byte value : digest().digest(identity(m).getBytes(StandardCharsets.UTF_8))) hash.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return root.resolve("autopilot-resume-" + hash);
    }
    private static long start(UpdateManifest m, int index) { return m.sizeBytes * index / PARALLEL_CONNECTIONS; }
    private static long end(UpdateManifest m, int index) { return start(m, index + 1) - 1; }
    private static long sum(long[] values) { long total = 0; for (long value : values) total += value; return total; }
    private static long[] readLengths(UpdateManifest m, Path resume) throws IOException {
        if (!Files.isDirectory(resume, LinkOption.NOFOLLOW_LINKS)) throw error("UPDATE_CACHE_ERROR");
        Path metadata = resume.resolve("identity");
        if (!Files.isRegularFile(metadata, LinkOption.NOFOLLOW_LINKS) || Files.size(metadata) > 4096
                || !identity(m).equals(new String(Files.readAllBytes(metadata), StandardCharsets.UTF_8))) throw error("UPDATE_CACHE_ERROR");
        long[] lengths = new long[PARALLEL_CONNECTIONS];
        for (int i = 0; i < lengths.length; i++) {
            Path part = resume.resolve("part-" + i);
            if (!Files.exists(part, LinkOption.NOFOLLOW_LINKS)) continue;
            if (!Files.isRegularFile(part, LinkOption.NOFOLLOW_LINKS)) throw error("UPDATE_CACHE_ERROR");
            lengths[i] = Files.size(part);
            if (lengths[i] > end(m, i) - start(m, i) + 1) throw error("UPDATE_RESPONSE_SIZE");
        }
        return lengths;
    }
    private static long[] initializeResume(UpdateManifest m, Path resume) throws IOException {
        if (Files.exists(resume, LinkOption.NOFOLLOW_LINKS)) {
            try { long[] valid = readLengths(m, resume); Files.deleteIfExists(resume.resolve("merge.part")); return valid; }
            catch (IOException invalid) { clearResume(resume); }
        }
        Files.createDirectory(resume);
        try (FileChannel channel = FileChannel.open(resume.resolve("identity"), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            write(channel, identity(m).getBytes(StandardCharsets.UTF_8), identity(m).getBytes(StandardCharsets.UTF_8).length);
            channel.force(true);
        }
        return new long[PARALLEL_CONNECTIONS];
    }
    private static void clearResume(Path resume) throws IOException {
        if (!Files.exists(resume, LinkOption.NOFOLLOW_LINKS)) return;
        if (Files.isSymbolicLink(resume) || !Files.isDirectory(resume, LinkOption.NOFOLLOW_LINKS)) { Files.delete(resume); return; }
        for (int i = 0; i < PARALLEL_CONNECTIONS; i++) Files.deleteIfExists(resume.resolve("part-" + i));
        Files.deleteIfExists(resume.resolve("identity")); Files.deleteIfExists(resume.resolve("merge.part"));
        Files.delete(resume); // Never recursively traverse unexpected files or symlinks.
    }

    private File single(UpdateManifest m, Path root, URI apk, Session session, Progress progress) throws IOException {
        Path partial = Files.createTempFile(root, "autopilot-update-", ".part");
        try {
            progress.begin();
            Response response = null;
            try {
                response = openResponse(apk, session.deadline, -1, -1, session);
                requireOk(response); requireIdentity(response);
                if (response.contentLength() >= 0 && response.contentLength() != m.sizeBytes) throw error("UPDATE_RESPONSE_SIZE");
                MessageDigest hash = digest(); long total = 0;
                try (InputStream input = response.body(); FileOutputStream output = new FileOutputStream(partial.toFile())) {
                    byte[] buffer = new byte[32 * 1024]; int count;
                    while ((count = input.read(buffer)) != -1) {
                        session.check(); total += count;
                        if (total > m.sizeBytes) throw error("UPDATE_RESPONSE_SIZE");
                        output.write(buffer, 0, count); hash.update(buffer, 0, count); progress.update(0, total);
                    }
                    session.check();
                    if (total != m.sizeBytes) throw error("UPDATE_RESPONSE_SIZE");
                    if (!MessageDigest.isEqual(hash.digest(), hexBytes(m.sha256))) throw error("UPDATE_HASH_MISMATCH");
                    output.getFD().sync();
                }
            } finally { if (response != null) session.release(response); progress.end(); }
            progress.idle(); session.check();
            Path complete = root.resolve("autopilot-update-" + m.versionCode + ".apk");
            try { Files.move(partial, complete, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (IOException failure) { throw error("UPDATE_CACHE_ERROR"); }
            return complete.toFile();
        } finally { Files.deleteIfExists(partial); }
    }

    private boolean probe(UpdateManifest m, URI apk, Session session, Progress progress) throws IOException {
        for (int attempt = 0; ; attempt++) {
            Response response = null; progress.begin();
            try {
                response = openResponse(apk, session.deadline, 0, 0, session);
                int status = response.status();
                if (status == 200) return false;
                requireRange(response, status, 0, 0, m.sizeBytes);
                try (InputStream input = response.body()) {
                    if (input.read() == -1 || input.read() != -1) throw error("UPDATE_RESPONSE_SIZE");
                }
                session.check(); return true;
            } catch (IOException failure) {
                session.check();
                if (!retryable(failure) || attempt + 1 >= MAX_ATTEMPTS) throw failure;
            } finally { if (response != null) session.release(response); progress.end(); }
            backoff(attempt, session);
        }
    }

    private void transferParts(UpdateManifest m, URI apk, Path resume, Session session, Progress progress) throws IOException {
        ExecutorService workers = Executors.newFixedThreadPool(PARALLEL_CONNECTIONS, action -> {
            Thread worker = new Thread(action, "Autopilot-update-part"); worker.setDaemon(true); return worker;
        });
        CompletionService<Void> completed = new ExecutorCompletionService<>(workers);
        int submitted = 0;
        try {
            for (int i = 0; i < PARALLEL_CONNECTIONS; i++) {
                final int part = i;
                if (progress.position(i) == end(m, i) - start(m, i) + 1) continue;
                submitted++; completed.submit(() -> { transferPart(m, apk, resume, part, session, progress); return null; });
            }
            for (int done = 0; done < submitted;) {
                session.check();
                Future<Void> result = completed.poll(100, TimeUnit.MILLISECONDS);
                if (result == null) continue;
                try { result.get(); done++; }
                catch (ExecutionException failure) {
                    Throwable cause = failure.getCause();
                    if (cause instanceof IOException) throw (IOException) cause;
                    throw error("UPDATE_NETWORK_ERROR");
                }
            }
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw error("UPDATE_CANCELLED"); }
        finally {
            if (submitted > 0) session.cancel();
            workers.shutdownNow();
            boolean interrupted = Thread.interrupted();
            while (!workers.isTerminated()) {
                try { workers.awaitTermination(100, TimeUnit.MILLISECONDS); }
                catch (InterruptedException again) { interrupted = true; }
            }
            // Successful workers are all stopped before the session is reused for merge.
            session.resumeAfterWorkers();
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private void transferPart(UpdateManifest m, URI apk, Path resume, int part, Session session, Progress progress) throws IOException {
        Path file = resume.resolve("part-" + part);
        for (int attempt = 0; ; attempt++) {
            session.check();
            long offset = Files.exists(file, LinkOption.NOFOLLOW_LINKS) ? Files.size(file) : 0;
            long last = end(m, part), first = start(m, part) + offset;
            if (first > last) return;
            Response response = null; progress.begin();
            try {
                response = openResponse(apk, session.deadline, first, last, session);
                int status = response.status();
                if (status == 200) throw new RangeUnsupported();
                requireRange(response, status, first, last, m.sizeBytes);
                try (InputStream input = response.body(); FileChannel output = FileChannel.open(file,
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND, LinkOption.NOFOLLOW_LINKS)) {
                    byte[] buffer = new byte[32 * 1024]; long received = 0; int count;
                    try {
                        while ((count = input.read(buffer)) != -1) {
                            session.check();
                            if (received + count > last - first + 1) throw error("UPDATE_RESPONSE_SIZE");
                            try { write(output, buffer, count); }
                            catch (IOException disk) { throw error("UPDATE_CACHE_ERROR"); }
                            received += count; progress.update(part, offset + received);
                        }
                        session.check();
                        // A clean socket EOF can still be a network interruption. Keep
                        // its bounded prefix and retry only the missing suffix.
                        if (received != last - first + 1) throw new EOFException("UPDATE_INCOMPLETE_RANGE");
                    } finally { try { output.force(true); } catch (IOException ignored) { } }
                }
                return;
            } catch (IOException failure) {
                session.check();
                if (!retryable(failure) || attempt + 1 >= MAX_ATTEMPTS) throw failure;
            } finally { if (response != null) session.release(response); progress.end(); }
            backoff(attempt, session);
        }
    }

    private static void write(FileChannel channel, byte[] bytes, int length) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(bytes, 0, length);
        while (buffer.hasRemaining()) channel.write(buffer);
    }
    private static File merge(UpdateManifest m, Path root, Path resume, Session session) throws IOException {
        Path merged = resume.resolve("merge.part");
        MessageDigest hash = digest(); long total = 0;
        try (FileChannel output = FileChannel.open(merged, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[32 * 1024];
            for (int i = 0; i < PARALLEL_CONNECTIONS; i++) {
                Path part = resume.resolve("part-" + i);
                long expected = end(m, i) - start(m, i) + 1;
                if (!Files.isRegularFile(part, LinkOption.NOFOLLOW_LINKS) || Files.size(part) != expected) throw error("UPDATE_RESPONSE_SIZE");
                long actual = 0;
                try (InputStream input = Files.newInputStream(part, LinkOption.NOFOLLOW_LINKS)) {
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        session.check(); actual += count; total += count;
                        if (actual > expected || total > m.sizeBytes) throw error("UPDATE_RESPONSE_SIZE");
                        hash.update(buffer, 0, count); write(output, buffer, count);
                    }
                }
                if (actual != expected) throw error("UPDATE_RESPONSE_SIZE");
            }
            if (total != m.sizeBytes) throw error("UPDATE_RESPONSE_SIZE");
            if (!MessageDigest.isEqual(hash.digest(), hexBytes(m.sha256))) throw error("UPDATE_HASH_MISMATCH");
            output.force(true);
        }
        session.check();
        Path complete = root.resolve("autopilot-update-" + m.versionCode + ".apk");
        try { Files.move(merged, complete, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (IOException disk) { throw error("UPDATE_CACHE_ERROR"); }
        try { clearResume(resume); } catch (IOException ignored) { }
        return complete.toFile();
    }
    private static final class RangeUnsupported extends IOException { }
    private static final class ServerFailure extends IOException { }
    /** Keep transient proxy I/O retryable without retaining upstream addresses or exception text. */
    private static final class VpnProxyFailure extends IOException {
        VpnProxyFailure() { super("UPDATE_VPN_PROXY_ERROR"); }
    }
    private static void requireIdentity(Response response) throws UpdateException {
        String encoding = response.contentEncoding();
        if (encoding != null && !encoding.isEmpty() && !"identity".equalsIgnoreCase(encoding.trim())) throw error("UPDATE_RESPONSE_ENCODING");
    }
    private static void requireRange(Response response, int status, long first, long last, long total) throws IOException {
        if (status >= 500 && status <= 599) throw new ServerFailure();
        if (status != 206) throw error("UPDATE_HTTP_ERROR");
        requireIdentity(response);
        Matcher range = Pattern.compile("bytes ([0-9]+)-([0-9]+)/([0-9]+)").matcher(response.contentRange() == null ? "" : response.contentRange());
        try {
            if (!range.matches() || Long.parseLong(range.group(1)) != first || Long.parseLong(range.group(2)) != last
                    || Long.parseLong(range.group(3)) != total) throw error("UPDATE_RANGE_INVALID");
        } catch (NumberFormatException invalid) { throw error("UPDATE_RANGE_INVALID"); }
        if (response.contentLength() >= 0 && response.contentLength() != last - first + 1) throw error("UPDATE_RESPONSE_SIZE");
    }
    private static boolean retryable(IOException failure) {
        return !(failure instanceof UpdateException) && !(failure instanceof RangeUnsupported)
                && (!(failure instanceof InterruptedIOException) || failure instanceof SocketTimeoutException);
    }
    private static void backoff(int attempt, Session session) throws IOException {
        session.check();
        try { Thread.sleep(200L << attempt); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw error("UPDATE_CANCELLED"); }
        session.check();
    }
    private static boolean invalidatesResume(String code) {
        return code.equals("UPDATE_RANGE_INVALID") || code.equals("UPDATE_RESPONSE_SIZE") || code.equals("UPDATE_HASH_MISMATCH")
                || code.equals("UPDATE_RESPONSE_ENCODING") || code.equals("UPDATE_CACHE_ERROR") || code.equals("UPDATE_UNSAFE_URL");
    }
    private static UpdateException normalize(IOException failure, Session session) {
        if (Thread.currentThread().isInterrupted()) return error("UPDATE_CANCELLED");
        if (failure instanceof SocketTimeoutException || System.nanoTime() > session.deadline) return error("UPDATE_TIMEOUT");
        if (failure instanceof UpdateException) return (UpdateException) failure;
        if (failure instanceof InterruptedIOException) return error("UPDATE_CANCELLED");
        if (failure instanceof VpnProxyFailure) return error("UPDATE_VPN_PROXY_ERROR");
        if (failure instanceof ServerFailure) return error("UPDATE_HTTP_ERROR");
        return error("UPDATE_NETWORK_ERROR");
    }
    private static final class Progress {
        final long total; final ProgressListener listener; final long[] positions;
        int active; long reported;
        Progress(long total, ProgressListener listener, long[] initial) { this.total = total; this.listener = listener; positions = initial.clone(); emit(); }
        synchronized long position(int part) { return positions[part]; }
        synchronized void update(int part, long position) { positions[part] = position; emit(); }
        synchronized void begin() { active++; emit(); }
        synchronized void end() { active = Math.max(0, active - 1); emit(); }
        synchronized void idle() { active = 0; emit(); }
        // Switching from discarded ranges to a fresh full response is a new
        // transfer attempt: explicitly reset rather than display stale bytes.
        synchronized void reset() { java.util.Arrays.fill(positions, 0); reported = 0; active = 0; emit(); }
        private void emit() {
            reported = Math.min(total, Math.max(reported, sum(positions)));
            if (listener != null) try { listener.onProgress(reported, total, active); } catch (RuntimeException ignored) { }
        }
    }
    private static final class Session {
        final long deadline; final Thread owner = Thread.currentThread();
        final Set<Response> responses = Collections.newSetFromMap(new ConcurrentHashMap<>());
        final AtomicBoolean canceled = new AtomicBoolean(); final AtomicBoolean finished = new AtomicBoolean();
        final Thread watcher;
        Session(long deadline) {
            this.deadline = deadline;
            watcher = new Thread(() -> {
                while (!finished.get()) {
                    if (owner.isInterrupted() || System.nanoTime() > deadline) { cancel(); return; }
                    try { Thread.sleep(50); } catch (InterruptedException stopped) { return; }
                }
            }, "Autopilot-update-cancel");
            watcher.setDaemon(true); watcher.start();
        }
        void check() throws IOException { checkProgress(deadline); if (canceled.get()) throw error("UPDATE_CANCELLED"); }
        void track(Response response) throws IOException {
            responses.add(response);
            if (canceled.get()) { release(response); throw error("UPDATE_CANCELLED"); }
        }
        void release(Response response) { responses.remove(response); try { response.close(); } catch (IOException ignored) { } }
        void cancel() { if (canceled.compareAndSet(false, true)) for (Response response : responses) release(response); }
        void resumeAfterWorkers() { if (!owner.isInterrupted() && System.nanoTime() <= deadline) canceled.set(false); }
        void finish() {
            cancel(); finished.set(true); watcher.interrupt();
            boolean interrupted = Thread.interrupted();
            while (watcher.isAlive() && watcher != Thread.currentThread()) {
                try { watcher.join(100); } catch (InterruptedException again) { interrupted = true; }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static void requireOk(Response response) throws IOException {
        if (response.status() != 200) throw error("UPDATE_HTTP_ERROR");
    }
    private Response openResponse(URI initial, long deadline, long start, long end, Session session) throws IOException {
        URI current = initial;
        for (int redirects = 0; ; redirects++) {
            checkProgress(deadline);
            if (session != null) session.check();
            Response response = start >= 0 ? transport.open(current, start, end) : transport.open(current);
            if (session != null) session.track(response);
            boolean keep = false;
            try {
                int status = response.status();
                if (status != 301 && status != 302 && status != 303 && status != 307 && status != 308) {
                    keep = true;
                    return response;
                }
                if (redirects >= MAX_REDIRECTS) throw error("UPDATE_REDIRECT_LIMIT");
                String location = response.location();
                if (location == null || location.isEmpty()) throw error("UPDATE_HTTP_ERROR");
                current = redirectUri(initial, current, location);
            } finally { if (!keep) { if (session != null) session.release(response); else response.close(); } }
        }
    }
    private static URI redirectUri(URI initial, URI current, String location) throws UpdateException {
        try {
            if (location.length() > 8192 || location.indexOf('\\') >= 0) throw error("UPDATE_UNSAFE_URL");
            URI next = current.resolve(new URI(location));
            if (!"https".equalsIgnoreCase(next.getScheme()) || next.getHost() == null
                    || next.getRawUserInfo() != null || next.getRawFragment() != null
                    || next.getPort() == 0 || next.getPort() < -1 || next.getPort() > 65535
                    || next.isOpaque()) throw error("UPDATE_UNSAFE_URL");
            if (UpdateManifest.sameOrigin(initial, next)) return next;
            boolean githubRelease = "github.com".equalsIgnoreCase(initial.getHost())
                    && (initial.getPort() == -1 || initial.getPort() == 443)
                    && (next.getPort() == -1 || next.getPort() == 443)
                    && ("github.com".equalsIgnoreCase(next.getHost())
                    || "release-assets.githubusercontent.com".equalsIgnoreCase(next.getHost())
                    || "objects.githubusercontent.com".equalsIgnoreCase(next.getHost()));
            if (!githubRelease) throw error("UPDATE_UNSAFE_URL");
            return next;
        } catch (UpdateException failure) { throw failure; }
        catch (Exception ignored) { throw error("UPDATE_UNSAFE_URL"); }
    }
    private static void checkCancelled() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("UPDATE_CANCELLED");
    }
    private static void checkProgress(long deadline) throws IOException {
        checkCancelled();
        if (System.nanoTime() > deadline) throw error("UPDATE_TIMEOUT");
    }
    private static UpdateException error(String code) { return new UpdateException(code); }
    private static byte[] hexBytes(String value) {
        byte[] bytes = new byte[value.length() / 2];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        return bytes;
    }

    interface ConnectionFactory { HttpsURLConnection open(URI uri, Proxy proxy) throws IOException; }
    /** The factory seam is package-private and used only by transport tests; production uses platform TLS unchanged. */
    static final class HttpsTransport implements Transport {
        private final IntSupplier activeProxyPort;
        private final Consumer<String> routeObserver;
        private final ConnectionFactory connections;
        HttpsTransport(IntSupplier activeProxyPort, Consumer<String> routeObserver, ConnectionFactory connections) {
            if (activeProxyPort == null || connections == null) throw new IllegalArgumentException("transport route");
            this.activeProxyPort = activeProxyPort; this.routeObserver = routeObserver; this.connections = connections;
        }
        private void observed(String route) { if (routeObserver != null) try { routeObserver.accept(route); } catch (RuntimeException ignored) { } }
        public Response open(URI uri) throws IOException {
            return open(uri, -1, -1);
        }
        public Response open(URI uri, long start, long end) throws IOException {
            int port;
            try { port = activeProxyPort.getAsInt(); }
            catch (RuntimeException unavailable) { observed("vpn-unavailable"); throw error("UPDATE_VPN_ROUTE_UNAVAILABLE"); }
            if (port < 0 || port > 65535) { observed("vpn-unavailable"); throw error("UPDATE_VPN_ROUTE_UNAVAILABLE"); }
            final boolean vpn = port > 0;
            Proxy proxy = vpn ? new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", port)) : Proxy.NO_PROXY;
            observed(vpn ? "vpn" : "direct");
            HttpsURLConnection connection;
            try { connection = connections.open(uri, proxy); }
            catch (IOException failure) { throw routedFailure(failure, vpn); }
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(20_000);
            connection.setReadTimeout(20_000);
            connection.setUseCaches(false);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/json, application/vnd.android.package-archive, application/octet-stream");
            connection.setRequestProperty("Accept-Encoding", "identity");
            if (start >= 0) connection.setRequestProperty("Range", "bytes=" + start + "-" + end);
            connection.setRequestProperty("User-Agent", "Autopilot-Android-Updater/1");
            // Provider/session headers are never passed into this independent public transport.
            return new Response() {
                public int status() throws IOException { try { return connection.getResponseCode(); } catch (IOException failure) { throw routedFailure(failure, vpn); } }
                public long contentLength() { return connection.getContentLengthLong(); }
                public InputStream body() throws IOException {
                    try { return new java.io.FilterInputStream(connection.getInputStream()) {
                        @Override public int read() throws IOException { try { return super.read(); } catch (IOException failure) { throw routedFailure(failure, vpn); } }
                        @Override public int read(byte[] bytes, int offset, int length) throws IOException { try { return in.read(bytes, offset, length); } catch (IOException failure) { throw routedFailure(failure, vpn); } }
                    }; } catch (IOException failure) { throw routedFailure(failure, vpn); }
                }
                public String location() { return connection.getHeaderField("Location"); }
                public String contentRange() { return connection.getHeaderField("Content-Range"); }
                public String contentEncoding() { return connection.getHeaderField("Content-Encoding"); }
                public void close() { connection.disconnect(); }
            };
        }
        private static IOException routedFailure(IOException failure, boolean vpn) {
            if (!vpn || failure instanceof UpdateException || failure instanceof VpnProxyFailure || failure instanceof InterruptedIOException
                    || failure instanceof javax.net.ssl.SSLException || Thread.currentThread().isInterrupted()) return failure;
            return new VpnProxyFailure();
        }
    }
}
