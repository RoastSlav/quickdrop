package org.rostislav.quickdrop.controller;

import org.junit.jupiter.api.Test;
import org.rostislav.quickdrop.entity.ShareTokenEntity;
import org.rostislav.quickdrop.entity.StoredFile;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression test for the share-link download-limit race (docs/test-reports/journeys.md,
 * SEV-1): two truly concurrent requests against a token with a limited download count must
 * not both succeed. Requires a real embedded server (not MockMvc) so the requests actually
 * race over shared DB connections the way a real deployment would.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// This is the only test class in the suite using a real embedded server (RANDOM_PORT) --
// every other QuickdropIntegrationTest subclass uses MOCK, sharing one cached context.
// Without @DirtiesContext, this context (with its own embedded Tomcat and async-dispatch
// machinery) can be torn down non-deterministically relative to a MOCK-environment test
// starting up next, which manifested as an intermittent ConcurrentModificationException
// inside a *different* test's MockHttpServletResponse header map (FileRestControllerTest's
// streaming-download test) on roughly 1 in 3 full-suite runs. Forcing Spring to close and
// never reuse this context avoids that class of cross-context teardown race.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ShareDownloadConcurrencyTest extends ControllerTestSupport {

    @LocalServerPort
    private int port;

    @Test
    void concurrentDownloadsAgainstASingleDownloadTokenOnlyOneSucceeds() throws Exception {
        // AdminPasswordSetupInterceptor forces every non-excluded route to /admin/setup
        // until an admin password exists -- required here since this test uses a real
        // embedded server (RANDOM_PORT), not MockMvc, so it goes through the full
        // interceptor chain rather than a mocked servlet context.
        ensureAdminPasswordSet();
        StoredFile file = createFile("race.txt", "concurrency race payload".getBytes());
        ShareTokenEntity token = createShareToken(file, null, 1);

        List<Integer> statuses = fireConcurrentDownloads(token.shareToken, 2);

        long successes = statuses.stream().filter(status -> status == 200).count();
        assertEquals(1, successes, "expected exactly one 200 among " + statuses);
    }

    @Test
    void concurrentDownloadsAgainstATwoDownloadTokenExactlyTwoSucceed() throws Exception {
        ensureAdminPasswordSet();
        StoredFile file = createFile("race2.txt", "concurrency race payload 2".getBytes());
        ShareTokenEntity token = createShareToken(file, null, 2);

        List<Integer> statuses = fireConcurrentDownloads(token.shareToken, 6);

        long successes = statuses.stream().filter(status -> status == 200).count();
        assertEquals(2, successes, "expected exactly two 200s among " + statuses);
    }

    private List<Integer> fireConcurrentDownloads(String shareToken, int concurrency) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch go = new CountDownLatch(1);
        HttpClient client = HttpClient.newHttpClient();
        URI uri = URI.create("http://localhost:" + port + "/api/file/download/" + shareToken);

        try {
            List<Future<Integer>> futures = IntStream.range(0, concurrency)
                    .mapToObj(i -> executor.submit(() -> {
                        ready.countDown();
                        go.await();
                        HttpRequest request = HttpRequest.newBuilder(uri).GET().build();
                        HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
                        return response.statusCode();
                    }))
                    .toList();

            ready.await(5, TimeUnit.SECONDS);
            go.countDown();

            List<Integer> statuses = new java.util.ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(10, TimeUnit.SECONDS));
            }
            return statuses;
        } finally {
            executor.shutdownNow();
        }
    }
}
