package com.gbhackathon.AICareerCode.service.taxonomy;

import com.gbhackathon.AICareerCode.model.TaxonomySkill;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link SfiaTaxonomyLoader} against a real, JDK-only HTTP server rather than a mock
 * framework - this project has no Mockito or WireMock dependency, and the point of these tests is
 * to prove the actual redirect and byte-cap handling in {@code downloadWithRedirects}, which a
 * stubbed client would not touch at all.
 *
 * <p>No SFIA workbook is committed here. Every workbook these tests read is generated in memory
 * with POI, shaped just enough like the published table (a header row with "Code" and "Skill"
 * columns) to exercise the parser - none of it is SFIA's actual content.
 *
 * <p>Every test builds its own {@link SfiaTaxonomyLoader} with a fresh {@link RecordingStore} and
 * points {@code directory} at a JUnit {@code @TempDir}, so nothing here touches a real filesystem
 * location or a real network host.
 */
class SfiaTaxonomyLoaderTest {

    /** Started by whichever test needs one; always stopped afterwards. */
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    // ---- building a loader under test -------------------------------------------------------

    private static SfiaTaxonomyLoader loaderWith(RecordingStore store, Path directory, String sourceUrl,
                                                  String sourceToken) {
        SfiaTaxonomyLoader loader = new SfiaTaxonomyLoader(store);
        set(loader, "directory", directory.toAbsolutePath().toString());
        set(loader, "sourceUrl", sourceUrl == null ? "" : sourceUrl);
        set(loader, "sourceToken", sourceToken == null ? "" : sourceToken);
        set(loader, "downloadTimeoutSeconds", 5);
        return loader;
    }

    private static void set(SfiaTaxonomyLoader loader, String fieldName, Object value) {
        try {
            Field field = SfiaTaxonomyLoader.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(loader, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "SfiaTaxonomyLoader no longer has a field called " + fieldName, e);
        }
    }

    /** Records every write it is asked to make, without needing a real repository or database. */
    private static class RecordingStore extends TaxonomyStore {
        final List<List<TaxonomySkill>> commits = new ArrayList<>();

        RecordingStore() {
            super(null);
        }

        @Override
        public int replaceSource(String source, List<TaxonomySkill> rows) {
            commits.add(rows);
            return rows.size();
        }
    }

    // ---- building test workbooks -------------------------------------------------------------

    private static final String[] VALID_HEADERS =
            {"Code", "Skill", "Category", "Sub-category", "Overall description", "Level 3", "Level 4"};

    private static byte[] workbookBytes(String[] headers, List<String[]> rows) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Skills");
            writeRow(sheet, 0, headers);
            for (int i = 0; i < rows.size(); i++) {
                writeRow(sheet, i + 1, rows.get(i));
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        }
    }

    private static void writeRow(Sheet sheet, int index, String[] values) {
        Row row = sheet.createRow(index);
        for (int c = 0; c < values.length; c++) {
            row.createCell(c).setCellValue(values[c]);
        }
    }

    private static byte[] validWorkbook(String code, String name) throws IOException {
        return workbookBytes(VALID_HEADERS, List.of(new String[] {
                code, name, "Development and implementation", "", "A test skill.",
                "Does it under supervision.", "Does it independently."
        }));
    }

    private static byte[] workbookWithNoRecognisableRows() throws IOException {
        return workbookBytes(new String[] {"Notes", "Comment"}, List.of(new String[] {"n/a", "n/a"}));
    }

    private static void writeFile(Path file, byte[] content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, content);
    }

    // ---- local discovery ---------------------------------------------------------------------

    @Test
    void discoversAWorkbookInsideALanguageSubfolder(@TempDir Path dir) throws Exception {
        writeFile(dir.resolve("SFIA 9 Excel - English").resolve("sfia-9_en.xlsx"),
                validWorkbook("PROG", "Programming/software development"));

        RecordingStore store = new RecordingStore();
        SfiaTaxonomyLoader loader = loaderWith(store, dir, null, null);

        int loaded = loader.load(false);

        assertEquals(1, loaded);
        assertEquals(1, store.commits.size());
        assertEquals("PROG", store.commits.get(0).get(0).getCode());
        assertEquals("LOCAL", loader.getActiveSourceType());
    }

    @Test
    void ignoresExcelLockFilesWhenScanningForAWorkbook(@TempDir Path dir) throws Exception {
        writeFile(dir.resolve("~$sfia-9.xlsx"), new byte[] {1, 2, 3});
        writeFile(dir.resolve("sfia-9.xlsx"), validWorkbook("PROG", "Programming/software development"));

        RecordingStore store = new RecordingStore();
        SfiaTaxonomyLoader loader = loaderWith(store, dir, null, null);

        assertEquals(1, loader.load(false));
    }

    // ---- start-up vs. reload semantics -------------------------------------------------------

    @Test
    void startupFetchesFromTheSourceWhenNothingIsUsableLocally(@TempDir Path dir) throws Exception {
        AtomicReference<String> methodSeen = new AtomicReference<>();
        server = startServer(exchange -> {
            methodSeen.set(exchange.getRequestMethod());
            respond(exchange, 200, "application/octet-stream", validWorkbook("PROG", "Programming"));
        });

        RecordingStore store = new RecordingStore();
        SfiaTaxonomyLoader loader = loaderWith(store, dir, url("/asset"), null);

        int loaded = loader.load(false);

        assertEquals(1, loaded);
        assertEquals("GET", methodSeen.get());
        assertEquals("REMOTE", loader.getActiveSourceType());
        assertFalse(loader.isLastAttemptFailed());
    }

    @Test
    void startupUsesTheLocalWorkbookWithoutContactingTheConfiguredSource(@TempDir Path dir) throws Exception {
        writeFile(dir.resolve("sfia-9.xlsx"), validWorkbook("PROG", "Programming/software development"));

        AtomicReference<Integer> requestsSeen = new AtomicReference<>(0);
        server = startServer(exchange -> {
            requestsSeen.set(requestsSeen.get() + 1);
            respond(exchange, 500, "text/plain", "should never be called".getBytes(StandardCharsets.UTF_8));
        });

        RecordingStore store = new RecordingStore();
        SfiaTaxonomyLoader loader = loaderWith(store, dir, url("/asset"), null);

        int loaded = loader.load(false);

        assertEquals(1, loaded);
        assertEquals("LOCAL", loader.getActiveSourceType());
        assertEquals(0, requestsSeen.get(), "start-up must not contact the source when a local copy already parses");
    }

    @Test
    void manualReloadAlwaysRefetchesEvenWhenALocalCopyAlreadyExists(@TempDir Path dir) throws Exception {
        writeFile(dir.resolve("sfia-9.xlsx"), validWorkbook("PROG", "Programming/software development"));

        AtomicReference<Integer> requestsSeen = new AtomicReference<>(0);
        server = startServer(exchange -> {
            requestsSeen.set(requestsSeen.get() + 1);
            respond(exchange, 200, "application/octet-stream", validWorkbook("DTAN", "Data analytics"));
        });

        RecordingStore store = new RecordingStore();
        SfiaTaxonomyLoader loader = loaderWith(store, dir, url("/asset"), null);

        int loaded = loader.load(true);

        assertEquals(1, loaded);
        assertEquals(1, requestsSeen.get(), "a manual reload must fetch fresh from the source");
        assertEquals("REMOTE", loader.getActiveSourceType());
        assertEquals("DTAN", store.commits.get(store.commits.size() - 1).get(0).getCode());
    }

    // ---- redirect and Authorization handling --------------------------------------------------

    @Test
    void redirectToTheSameHostStillCarriesTheAuthorizationHeader(@TempDir Path dir) throws Exception {
        AtomicReference<String> authAtFinalHop = new AtomicReference<>("not reached");
        server = startServer(exchange -> {
            if (exchange.getRequestURI().getPath().equals("/start")) {
                exchange.getResponseHeaders().set("Location", "/final");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
                return;
            }
            authAtFinalHop.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, "application/octet-stream", validWorkbook("PROG", "Programming"));
        });

        RecordingStore store = new RecordingStore();
        SfiaTaxonomyLoader loader = loaderWith(store, dir, url("/start"), "test-token-123");

        int loaded = loader.load(false);

        assertEquals(1, loaded);
        assertEquals("Bearer test-token-123", authAtFinalHop.get());
    }

    @Test
    void redirectToADifferentHostNeverCarriesTheAuthorizationHeader(@TempDir Path dir) throws Exception {
        int port = freePort();
        AtomicReference<String> authAtFinalHop = new AtomicReference<>("not reached");
        // Bound to the wildcard address (not just "127.0.0.1") so that both "127.0.0.1" and
        // "localhost" - whichever loopback address the latter resolves to on this machine - reach
        // the same listening server; only the hostname string in the URL needs to differ for the
        // cross-host check under test.
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/start", exchange -> {
            exchange.getResponseHeaders().set("Location", "http://localhost:" + port + "/final");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/final", exchange -> {
            authAtFinalHop.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, "application/octet-stream", validWorkbook("PROG", "Programming"));
        });
        server.start();

        RecordingStore store = new RecordingStore();
        SfiaTaxonomyLoader loader =
                loaderWith(store, dir, "http://127.0.0.1:" + port + "/start", "test-token-123");

        int loaded = loader.load(false);

        assertEquals(1, loaded, "the download itself must still succeed - only the header is withheld");
        assertNull(authAtFinalHop.get(), "127.0.0.1 and localhost are different hosts; the token must not follow");
    }

    @Test
    void aSourceThatRedirectsForeverIsEventuallyRejected(@TempDir Path dir) throws Exception {
        server = startServer(exchange -> {
            exchange.getResponseHeaders().set("Location", "/loop");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });

        RecordingStore store = new RecordingStore();
        SfiaTaxonomyLoader loader = loaderWith(store, dir, url("/loop"), null);

        int loaded = loader.load(false);

        assertEquals(0, loaded);
        assertTrue(loader.isLastAttemptFailed());
        assertTrue(loader.getLastAttemptError().toLowerCase().contains("redirected"));
    }

    // ---- network and content failures ---------------------------------------------------------

    @Test
    void anUnreachableSourceFailsWithoutALocalFallbackAndWithoutLeakingTheUrl(@TempDir Path dir) throws Exception {
        int closedPort = freePort();

        RecordingStore store = new RecordingStore();
        SfiaTaxonomyLoader loader =
                loaderWith(store, dir, "http://127.0.0.1:" + closedPort + "/asset", "super-secret-token");

        int loaded = loader.load(false);

        assertEquals(0, loaded);
        assertTrue(loader.isLastAttemptFailed());
        assertFalse(loader.getLastAttemptError().contains("super-secret-token"));
        assertFalse(loader.getLastAttemptError().contains(String.valueOf(closedPort)));
    }

    @Test
    void aNonSuccessStatusFromTheSourceIsRejected(@TempDir Path dir) throws Exception {
        server = startServer(exchange ->
                respond(exchange, 503, "text/plain", "maintenance".getBytes(StandardCharsets.UTF_8)));

        RecordingStore store = new RecordingStore();
        SfiaTaxonomyLoader loader = loaderWith(store, dir, url("/asset"), null);

        int loaded = loader.load(false);

        assertEquals(0, loaded);
        assertTrue(loader.getLastAttemptError().contains("503"));
    }

    @Test
    void htmlOrJsonInPlaceOfTheWorkbookIsRejectedRatherThanHalfParsed(@TempDir Path dir) throws Exception {
        server = startServer(exchange -> respond(exchange, 200, "text/html",
                "<html><body>please sign in</body></html>".getBytes(StandardCharsets.UTF_8)));

        RecordingStore store = new RecordingStore();
        SfiaTaxonomyLoader loader = loaderWith(store, dir, url("/asset"), null);

        int loaded = loader.load(false);

        assertEquals(0, loaded);
        assertEquals(0, store.commits.size(), "nothing not-a-workbook may ever reach the store");
        assertTrue(loader.getLastAttemptError().contains("not a workbook"));
    }

    @Test
    void aWorkbookWithNoRecognisableSfiaRowsIsRejected(@TempDir Path dir) throws Exception {
        server = startServer(exchange ->
                respond(exchange, 200, "application/octet-stream", workbookWithNoRecognisableRows()));

        RecordingStore store = new RecordingStore();
        SfiaTaxonomyLoader loader = loaderWith(store, dir, url("/asset"), null);

        int loaded = loader.load(false);

        assertEquals(0, loaded);
        assertEquals(0, store.commits.size());
        assertTrue(loader.getLastAttemptError().contains("recognisable"));
    }

    @Test
    void aDeclaredContentLengthOverTheCapIsRejectedWithoutDownloadingTheBody(@TempDir Path dir) throws Exception {
        server = startServer(exchange -> {
            try {
                exchange.sendResponseHeaders(200, 26L * 1024 * 1024);
                // Deliberately short of the declared length: a real client that trusted the header
                // and stopped reading right after it would never notice, which is exactly the point.
                exchange.getResponseBody().write(new byte[16]);
            } catch (IOException ignored) {
                // The client is expected to disconnect before this response is ever completed.
            } finally {
                exchange.close();
            }
        });

        RecordingStore store = new RecordingStore();
        SfiaTaxonomyLoader loader = loaderWith(store, dir, url("/asset"), null);

        int loaded = loader.load(false);

        assertEquals(0, loaded);
        assertTrue(loader.getLastAttemptError().contains("MB limit"));
    }

    @Test
    void aStreamThatExceedsTheCapWithNoDeclaredLengthIsStoppedMidTransfer(@TempDir Path dir) throws Exception {
        byte[] chunk = new byte[1024 * 1024];
        server = startServer(exchange -> {
            try {
                exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
                exchange.sendResponseHeaders(200, 0); // 0 => chunked, no Content-Length header at all
                OutputStream body = exchange.getResponseBody();
                for (int i = 0; i < 40; i++) { // 40 MB, comfortably over the 25 MB cap
                    body.write(chunk);
                }
            } catch (IOException ignored) {
                // Expected once the client aborts the transfer after the cap is exceeded.
            } finally {
                exchange.close();
            }
        });

        RecordingStore store = new RecordingStore();
        SfiaTaxonomyLoader loader = loaderWith(store, dir, url("/asset"), null);

        int loaded = loader.load(false);

        assertEquals(0, loaded);
        assertTrue(loader.getLastAttemptError().contains("MB limit"));
    }

    // ---- failure never destroys a previous good state -----------------------------------------

    @Test
    void aFailedReloadWithNoLocalFallbackNeverTouchesTheStore(@TempDir Path dir) throws Exception {
        Path workbook = dir.resolve("sfia-9.xlsx");
        writeFile(workbook, validWorkbook("PROG", "Programming/software development"));

        RecordingStore store = new RecordingStore();
        SfiaTaxonomyLoader loader = loaderWith(store, dir, null, null);
        assertEquals(1, loader.load(false));
        assertEquals(1, store.commits.size());

        // The local copy disappears (simulating an ephemeral filesystem being wiped) and the only
        // remaining source is broken.
        Files.delete(workbook);
        int closedPort = freePort();
        set(loader, "sourceUrl", "http://127.0.0.1:" + closedPort + "/asset");

        int loaded = loader.load(true);

        assertEquals(0, loaded);
        assertEquals(1, store.commits.size(), "the good commit from before must still be the only one");
        assertTrue(loader.isLastAttemptFailed());
    }

    @Test
    void aFailedReloadFallsBackToTheStillPresentLocalCopy(@TempDir Path dir) throws Exception {
        writeFile(dir.resolve("sfia-9.xlsx"), validWorkbook("PROG", "Programming/software development"));
        int closedPort = freePort();

        RecordingStore store = new RecordingStore();
        SfiaTaxonomyLoader loader =
                loaderWith(store, dir, "http://127.0.0.1:" + closedPort + "/asset", null);

        int loaded = loader.load(true);

        assertEquals(1, loaded, "a broken source must fall back to the local workbook, not report empty");
        assertEquals("PROG", store.commits.get(store.commits.size() - 1).get(0).getCode());
    }

    // ---- concurrency ---------------------------------------------------------------------------

    @Test
    void aRefreshAlreadyInProgressIsSkippedRatherThanQueued(@TempDir Path dir) throws Exception {
        SfiaTaxonomyLoader loader = loaderWith(new RecordingStore(), dir, null, null);
        ReentrantLock lock = lockOf(loader);

        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            lock.lock();
            try {
                holding.countDown();
                release.await();
            } catch (InterruptedException ignored) {
                // test teardown
            } finally {
                lock.unlock();
            }
        });
        holder.start();
        try {
            assertTrue(holding.await(2, TimeUnit.SECONDS));

            assertEquals(-1, loader.load(false), "load() must not block or queue behind an in-progress refresh");
            assertThrows(IllegalStateException.class, () -> loader.storeUploadedWorkbook(new byte[0]),
                    "an upload must not block or queue either");
        } finally {
            release.countDown();
            holder.join(2000);
        }
    }

    private static ReentrantLock lockOf(SfiaTaxonomyLoader loader) {
        try {
            Field field = SfiaTaxonomyLoader.class.getDeclaredField("refreshLock");
            field.setAccessible(true);
            return (ReentrantLock) field.get(loader);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("SfiaTaxonomyLoader no longer has a field called refreshLock", e);
        }
    }

    // ---- admin upload ---------------------------------------------------------------------------

    @Test
    void uploadingAValidWorkbookCommitsItImmediately(@TempDir Path dir) throws Exception {
        RecordingStore store = new RecordingStore();
        SfiaTaxonomyLoader loader = loaderWith(store, dir, null, null);

        int loaded = loader.storeUploadedWorkbook(validWorkbook("PROG", "Programming/software development"));

        assertEquals(1, loaded);
        assertEquals("ADMIN_UPLOAD", loader.getActiveSourceType());
        assertEquals(1, store.commits.size());
        assertFalse(loader.isLastAttemptFailed());
    }

    @Test
    void uploadingSomethingThatIsNotAWorkbookIsRejectedWithoutTouchingTheStore(@TempDir Path dir) throws Exception {
        RecordingStore store = new RecordingStore();
        SfiaTaxonomyLoader loader = loaderWith(store, dir, null, null);

        byte[] notAWorkbook = "{\"error\":\"nope\"}".getBytes(StandardCharsets.UTF_8);

        assertThrows(IllegalArgumentException.class, () -> loader.storeUploadedWorkbook(notAWorkbook));
        assertEquals(0, store.commits.size());
    }

    @Test
    void uploadingAWorkbookWithNoRecognisableRowsIsRejected(@TempDir Path dir) throws Exception {
        RecordingStore store = new RecordingStore();
        SfiaTaxonomyLoader loader = loaderWith(store, dir, null, null);

        assertThrows(IllegalArgumentException.class,
                () -> loader.storeUploadedWorkbook(workbookWithNoRecognisableRows()));
        assertEquals(0, store.commits.size());
    }

    @Test
    void aBadUploadNeverDestroysAWorkbookThatWasAlreadyWorking(@TempDir Path dir) throws Exception {
        RecordingStore store = new RecordingStore();
        SfiaTaxonomyLoader loader = loaderWith(store, dir, null, null);
        loader.load(false); // nothing local yet; establishes the "already working" baseline is empty-but-attempted

        writeFile(dir.resolve("sfia-9.xlsx"), validWorkbook("PROG", "Programming/software development"));
        assertEquals(1, loader.load(false));
        assertEquals(1, store.commits.size());

        byte[] badUpload = "not a workbook at all".getBytes(StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> loader.storeUploadedWorkbook(badUpload));

        assertEquals(1, store.commits.size(), "the earlier good commit must be untouched by the rejected upload");
        assertEquals("LOCAL", loader.getActiveSourceType(), "the active source type must not flip to a failed upload");
    }

    // ---- small HTTP test server ------------------------------------------------------------------

    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }

    private HttpServer startServer(Handler handler) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/", exchange -> {
            try {
                if (!"GET".equals(exchange.getRequestMethod())) {
                    respond(exchange, 405, "text/plain", new byte[0]);
                    return;
                }
                handler.handle(exchange);
            } catch (Throwable t) {
                try {
                    respond(exchange, 500, "text/plain", new byte[0]);
                } catch (Exception ignored) {
                    // best effort
                }
            }
        });
        s.start();
        return s;
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private static void respond(HttpExchange exchange, int status, String contentType, byte[] body) {
        try {
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } finally {
            exchange.close();
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
