package com.smartprint.order;

import com.smartprint.config.MinioProperties;
import com.smartprint.storage.ObjectStorageService;
import io.minio.MinioClient;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderFlowIntegrationTest {

    private static EmbeddedPostgres postgres;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) throws IOException {
        postgres = EmbeddedPostgres.builder().start();
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        registry.add("smartprint.storage.endpoint", () -> "http://127.0.0.1:1");
    }

    @TestConfiguration
    static class StubStorageConfig {
        @Bean
        @Primary
        ObjectStorageService stubObjectStorage(MinioClient client, MinioProperties properties) {
            java.util.Map<String, byte[]> objects = new java.util.concurrent.ConcurrentHashMap<>();
            return new ObjectStorageService(client, properties) {
                @Override
                public void ensureBucket() {}

                @Override
                public void put(String key, InputStream stream, long size, String contentType) {
                    try {
                        objects.put(key, stream.readAllBytes());
                    } catch (IOException ex) {
                        throw new IllegalStateException(ex);
                    }
                }

                @Override
                public InputStream get(String key) {
                    byte[] content = objects.get(key);
                    if (content == null) throw new IllegalStateException("missing object " + key);
                    return new java.io.ByteArrayInputStream(content);
                }

                @Override
                public void delete(String key) { objects.remove(key); }

                @Override
                public boolean isHealthy() { return true; }
            };
        }
    }

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void shopRegistersQuotesOrdersAndPrintsThroughAgent() throws IOException {
        ShopSession session = registerShop("Queue Test Shop");

        ResponseEntity<Map> publicShop = rest.getForEntity(
            "/api/v1/public/shops/" + session.qrToken(), Map.class);
        assertThat(publicShop.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(publicShop.getBody()).containsEntry("shopName", "Queue Test Shop");

        MultiValueMap<String, Object> quoteForm = orderForm(pdf("notes.pdf", pdf(3)), "1-2", 2);
        ResponseEntity<Map> quote = rest.postForEntity(
            "/api/v1/public/shops/" + session.qrToken() + "/quote",
            multipart(quoteForm), Map.class);
        assertThat(quote.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(amount(quote.getBody().get("totalAmount")))
            .isEqualByComparingTo(new BigDecimal("8.00")); // 2 selected pages x 2 copies x 2.00

        MultiValueMap<String, Object> previewForm = new LinkedMultiValueMap<>();
        previewForm.add("file", pdf("notes.pdf", pdf(3)));
        previewForm.add("colorMode", "BW");
        previewForm.add("orientation", "LANDSCAPE");
        previewForm.add("pageSelection", "1-2");
        previewForm.add("previewIndex", "1");
        ResponseEntity<byte[]> preview = rest.postForEntity(
            "/api/v1/public/shops/" + session.qrToken() + "/preview", multipart(previewForm), byte[].class);
        assertThat(preview.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(preview.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_JPEG);
        try (var input = new java.io.ByteArrayInputStream(preview.getBody())) {
            var image = javax.imageio.ImageIO.read(input);
            assertThat(image.getWidth()).isGreaterThan(image.getHeight());
        }

        MultiValueMap<String, Object> form = orderForm(pdf("notes.pdf", pdf(3)), "1-2", 2);
        form.add("paymentMethod", "BKASH");
        form.add("customerName", "Nadia");
        form.add("transactionId", "8N7A2K9Q1M");
        form.add("expectedAmount", "8.00");
        form.add("requestKey", UUID.randomUUID().toString());
        ResponseEntity<Map> created = rest.postForEntity(
            "/api/v1/public/shops/" + session.qrToken() + "/orders",
            multipart(form), Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).containsEntry("status", "PAYMENT_PENDING");
        assertThat(amount(created.getBody().get("totalAmount")))
            .isEqualByComparingTo(new BigDecimal("8.00"));

        String orderNumber = (String) created.getBody().get("orderNumber");
        String publicToken = (String) created.getBody().get("publicToken");
        assertThat(publicToken).isNotBlank();

        ResponseEntity<Map> retried = rest.postForEntity(
            "/api/v1/public/shops/" + session.qrToken() + "/orders", multipart(form), Map.class);
        assertThat(retried.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(retried.getBody()).containsEntry("publicToken", publicToken);
        assertThat((Map<?, ?>) created.getBody().get("payment")).extracting(payment -> payment.get("status"))
            .isEqualTo("SUBMITTED");

        // Customer-only token endpoint rejects an unknown token.
        assertThat(rest.getForEntity("/api/v1/public/orders/" + UUID.randomUUID(), Map.class)
            .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<Map> reference = rest.postForEntity(
            "/api/v1/public/orders/" + publicToken + "/payments/reference",
            Map.of("transactionId", "8N7A2K9Q1M"), Map.class);
        assertThat(reference.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Admin routes require a JWT.
        assertThat(rest.getForEntity("/api/v1/admin/dashboard", Map.class).getStatusCode())
            .isEqualTo(HttpStatus.UNAUTHORIZED);

        // Exercise dispatch using credentials issued by desktop pairing.
        var challenge = rest.postForEntity("/api/v1/admin/agents/pairing",
            new HttpEntity<>(Map.of("name", "Front desk PC"), auth(session.token())), Map.class);
        assertThat(challenge.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var agent = rest.postForEntity("/api/v1/agent/pairing",
            Map.of("code", challenge.getBody().get("code")), Map.class);
        assertThat(agent.getStatusCode()).isEqualTo(HttpStatus.OK);
        String agentCode = (String) agent.getBody().get("agentCode");
        String agentToken = (String) agent.getBody().get("agentToken");

        HttpHeaders agentHeaders = new HttpHeaders();
        agentHeaders.set("X-Agent-Code", agentCode);
        agentHeaders.set("X-Agent-Token", agentToken);
        agentHeaders.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> heartbeat = rest.exchange("/api/v1/agent/heartbeat", HttpMethod.POST,
            new HttpEntity<>(Map.of("printers", List.of(Map.of(
                "code", "HP-LASER",
                "name", "Office_Printer #1",
                "connectionType", "CUPS",
                "uri", "HP-LaserJet",
                "status", "FREE",
                "colorSupported", false,
                "duplexSupported", true,
                "agentDefault", true,
                "model", "Office Model",
                "portName", "USB001"
            ))), agentHeaders), Map.class);
        assertThat(heartbeat.getStatusCode()).isEqualTo(HttpStatus.OK);

        List<?> printerList = rest.exchange("/api/v1/admin/printers", HttpMethod.GET,
            new HttpEntity<>(auth(session.token())), List.class).getBody();
        assertThat(printerList).hasSize(1);
        Long printerId = ((Number) ((Map<?, ?>) printerList.get(0)).get("id")).longValue();

        ResponseEntity<Map> discovery = rest.exchange("/api/v1/admin/printers/discovery", HttpMethod.GET,
            new HttpEntity<>(auth(session.token())), Map.class);
        assertThat(discovery.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<?>) discovery.getBody().get("agents")).hasSize(1);
        Map<?, ?> onlineDevice = (Map<?, ?>) ((List<?>) discovery.getBody().get("agents")).getFirst();
        assertThat(onlineDevice.get("status")).isEqualTo("ONLINE");
        assertThat(onlineDevice.get("lastSeenAt")).isNotNull();
        Map<?, ?> detected = (Map<?, ?>) ((List<?>) discovery.getBody().get("printers")).getFirst();
        assertThat(detected.get("agentId")).isEqualTo(onlineDevice.get("id"));
        assertThat(detected.get("agentDefault")).isEqualTo(true);
        assertThat(detected.get("name")).isEqualTo("Office_Printer #1");
        assertThat(detected.get("model")).isEqualTo("Office Model");
        assertThat(detected.get("portName")).isEqualTo("USB001");
        assertThat(((Map<?, ?>) discovery.getBody().get("routing")).get("defaultPrinterId")).isNull();

        assertThat(rest.exchange("/api/v1/agent/jobs/next", HttpMethod.POST,
            new HttpEntity<>(agentHeaders), Void.class).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        Long paymentId = pendingPaymentId(session.token(), orderNumber);
        ShopSession otherShop = registerShop("Other Shop");
        assertThat(rest.exchange("/api/v1/admin/payments/" + paymentId + "/approve",
            HttpMethod.POST, new HttpEntity<>(auth(otherShop.token())), Map.class).getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<Map> approved = rest.exchange(
            "/api/v1/admin/payments/" + paymentId + "/approve", HttpMethod.POST,
            new HttpEntity<>(auth(session.token())), Map.class);
        assertThat(approved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(approved.getBody()).containsEntry("orderStatus", "QUEUED");
        assertThat(((Number) approved.getBody().get("queueNumber")).intValue()).isEqualTo(1);

        ResponseEntity<Map> queued = rest.getForEntity(
            "/api/v1/public/orders/" + publicToken, Map.class);
        assertThat(queued.getBody()).containsEntry("status", "QUEUED");
        assertThat(((Map<?, ?>) queued.getBody().get("queue")).get("position")).isEqualTo(1);

        RestClient patchClient = RestClient.builder().baseUrl(rest.getRootUri())
            .requestFactory(new JdkClientHttpRequestFactory()).build();
        Number waitingEntry = jdbc.queryForObject(
            "select q.id from print_queue_entries q join orders o on o.id = q.order_id where o.order_number = ?",
            Long.class, orderNumber);
        var printerChoice = patchClient.patch().uri("/api/v1/admin/queue/" + waitingEntry + "/printer")
            .headers(headers -> headers.addAll(auth(session.token())))
            .body(Map.of("printerId", printerId))
            .retrieve().toBodilessEntity();
        assertThat(printerChoice.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(jdbc.queryForObject("select selected_printer_id from print_queue_entries where id = ?",
            Long.class, waitingEntry)).isEqualTo(printerId);

        ResponseEntity<Map> job = rest.exchange("/api/v1/agent/jobs/next", HttpMethod.POST,
            new HttpEntity<>(agentHeaders), Map.class);
        assertThat(job.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(job.getBody()).containsEntry("orderNumber", orderNumber);
        List<?> jobFiles = (List<?>) job.getBody().get("files");
        assertThat(jobFiles).hasSize(1);
        Map<?, ?> jobFile = (Map<?, ?>) jobFiles.get(0);
        assertThat(((Number) jobFile.get("selectedPages")).intValue()).isEqualTo(2);
        assertThat(jobFile.get("printerUri")).isEqualTo("HP-LaserJet");

        Long entryId = ((Number) job.getBody().get("queueEntryId")).longValue();
        ResponseEntity<byte[]> download = rest.exchange(
            "/api/v1/agent/jobs/" + entryId + "/files/" + jobFile.get("fileId") + ".pdf",
            HttpMethod.GET, new HttpEntity<>(agentHeaders), byte[].class);
        assertThat(download.getStatusCode()).isEqualTo(HttpStatus.OK);
        try (PDDocument document = org.apache.pdfbox.Loader.loadPDF(download.getBody())) {
            assertThat(document.getNumberOfPages()).isEqualTo(2);
        }

        assertThat(rest.exchange("/api/v1/agent/jobs/" + entryId + "/complete", HttpMethod.POST,
            new HttpEntity<>(agentHeaders), Map.class).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        agentHeaders.set("X-Submission-Key", (String) jobFile.get("submissionKey"));
        assertThat(rest.exchange("/api/v1/agent/jobs/" + entryId + "/files/" + jobFile.get("fileId") + "/spooler",
            HttpMethod.POST, new HttpEntity<>(Map.of("reference", "91", "status", "COMPLETED"), agentHeaders),
            Void.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rest.exchange(
            "/api/v1/agent/jobs/" + entryId + "/files/" + jobFile.get("fileId") + "/complete",
            HttpMethod.POST, new HttpEntity<>(agentHeaders), Void.class).getStatusCode())
            .isEqualTo(HttpStatus.OK);

        ResponseEntity<Void> completed = rest.exchange(
            "/api/v1/agent/jobs/" + entryId + "/complete", HttpMethod.POST,
            new HttpEntity<>(agentHeaders), Void.class);
        assertThat(completed.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> finished = rest.getForEntity(
            "/api/v1/public/orders/" + publicToken, Map.class);
        assertThat(finished.getBody()).containsEntry("status", "COMPLETED");
        assertThat(finished.getBody()).containsEntry("statusLabel",
            "Print processing completed");

        ResponseEntity<Map> routing = patchClient.patch().uri("/api/v1/admin/printers/routing")
            .headers(headers -> headers.addAll(auth(session.token())))
            .body(Map.of("defaultPrinterId", printerId, "colorPrinterId", printerId, "bwPrinterId", printerId))
            .retrieve().toEntity(Map.class);
        assertThat(routing.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(routing.getBody()).containsEntry("defaultPrinterId", printerId.intValue());

        Map<String, Object> saved = jdbc.queryForMap(
            "select selected_page_count, page_selection from print_configs pc " +
                "join orders o on o.id = pc.order_id where o.order_number = ?", orderNumber);
        assertThat(((Number) saved.get("selected_page_count")).intValue()).isEqualTo(2);
        assertThat(saved.get("page_selection")).isEqualTo("1,2");
    }

    @Test
    void reservesTwoPrintersAndKeepsTheThirdOrderQueuedUntilAReservationClears() throws Exception {
        ShopSession shop = registerShop("Parallel Printing");
        var createdAgent = rest.exchange("/api/v1/admin/agents", HttpMethod.POST,
            new HttpEntity<>(Map.of("name", "Test station"), auth(shop.token())), Map.class).getBody();
        var agent = new HttpHeaders();
        agent.set("X-Agent-Code", (String) createdAgent.get("agentCode"));
        agent.set("X-Agent-Token", (String) createdAgent.get("agentToken"));
        var devices = List.of(
            Map.of("code", "A", "name", "Printer A", "uri", "A", "connectionType", "CUPS",
                "status", "BUSY", "pendingJobs", 2, "activeJobs", 1),
            Map.of("code", "B", "name", "Printer B", "uri", "B", "connectionType", "CUPS", "status", "FREE"),
            Map.of("code", "C", "name", "Printer C", "uri", "C", "connectionType", "CUPS", "status", "FREE")
        );
        assertThat(rest.exchange("/api/v1/agent/heartbeat", HttpMethod.POST,
            new HttpEntity<>(Map.of("printers", devices), agent), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);

        var tokens = new java.util.ArrayList<String>();
        for (int i = 0; i < 3; i++) {
            var form = orderForm(pdf("single.pdf", pdf(1)), "ALL", 1);
            form.add("paymentMethod", "CASH");
            var receipt = rest.postForEntity("/api/v1/public/shops/" + shop.qrToken() + "/orders",
                multipart(form), Map.class);
            assertThat(receipt.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            tokens.add((String) receipt.getBody().get("publicToken"));
            Number paymentId = (Number) ((Map<?, ?>) receipt.getBody().get("payment")).get("id");
            assertThat(rest.exchange("/api/v1/admin/payments/" + paymentId + "/approve", HttpMethod.POST,
                new HttpEntity<>(auth(shop.token())), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        List<?> first;
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var left = executor.submit(() -> assignments(agent));
            var right = executor.submit(() -> assignments(agent));
            first = left.get();
            assertThat(right.get()).isEqualTo(first);
        }
        assertThat(first).hasSize(2);
        assertThat(jdbc.queryForObject("""
            select count(*) from print_job_attempts a join shops s on s.id = a.shop_id
            where s.qr_token = ?::uuid
            """, Integer.class, shop.qrToken())).isEqualTo(2);
        assertThat(first.stream().map(item -> (String) firstFile((Map<?, ?>) item).get("printerName")).toList())
            .containsExactly("Printer B", "Printer C");
        assertThat(rest.getForObject("/api/v1/public/orders/" + tokens.get(2), Map.class))
            .containsEntry("status", "QUEUED");

        Map<?, ?> assignment = (Map<?, ?>) first.getFirst();
        String base = "/api/v1/agent/jobs/" + assignment.get("queueEntryId");
        Map<?, ?> file = firstFile(assignment);
        agent.set("X-Submission-Key", (String) file.get("submissionKey"));
        agent.set("X-Assignment-Key", (String) assignment.get("assignmentKey"));
        assertThat(rest.exchange(base + "/files/" + file.get("fileId") + "/complete",
            HttpMethod.POST, new HttpEntity<>(agent), Void.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rest.exchange(base + "/complete", HttpMethod.POST,
            new HttpEntity<>(agent), Void.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        var third = assignments(agent);
        assertThat(third).hasSize(2);
        assertThat(firstFile((Map<?, ?>) third.getLast()).get("printerName")).isEqualTo("Printer B");
        assertThat(rest.getForObject("/api/v1/public/orders/" + tokens.get(2), Map.class))
            .containsEntry("status", "PRINTING");
    }

    private List<?> assignments(HttpHeaders agent) {
        var result = rest.exchange("/api/v1/agent/jobs/assignments", HttpMethod.POST,
            new HttpEntity<>(agent), List.class);
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        return result.getBody();
    }

    private Map<?, ?> firstFile(Map<?, ?> assignment) {
        return (Map<?, ?>) ((List<?>) assignment.get("files")).getFirst();
    }

    @Test
    void rejectsUnsupportedDocumentBytes() throws IOException {
        ShopSession session = registerShop("Validation Shop");
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("files", new ByteArrayResource("plain text".getBytes()) {
            @Override
            public String getFilename() { return "notes.txt"; }
        });
        form.add("colorMode", "BW");
        form.add("sideMode", "SIMPLEX");
        form.add("copies", "1");
        form.add("orientation", "PORTRAIT");
        form.add("pageSelection", "ALL");
        form.add("paymentMethod", "CASH");

        ResponseEntity<Map> response = rest.postForEntity(
            "/api/v1/public/shops/" + session.qrToken() + "/orders",
            multipart(form), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("message").toString())
            .contains("not a supported document");
    }

    @Test
    void rejectsPagesOutsideTheDocument() throws IOException {
        ShopSession session = registerShop("Paging Shop");
        MultiValueMap<String, Object> form = orderForm(pdf("notes.pdf", pdf(2)), "1-5", 1);
        ResponseEntity<Map> response = rest.postForEntity(
            "/api/v1/public/shops/" + session.qrToken() + "/quote",
            multipart(form), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("message").toString()).contains("does not exist");
    }

    @Test
    void pairingIsSingleUseExpiringAndShopScoped() {
        ShopSession owner = registerShop("Pairing Shop");
        ShopSession other = registerShop("Other Pairing Shop");
        String adminPath = "/api/v1/admin/agents/pairing";
        String pairPath = "/api/v1/agent/pairing";
        var issue = new HttpEntity<>(Map.of("name", "USB station"), auth(owner.token()));
        assertThat(rest.postForEntity(adminPath, issue.getBody(), Map.class).getStatusCode())
            .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(rest.postForEntity(pairPath, Map.of("code", "0123456789ABCDEF0000"), Map.class)
            .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        var expired = rest.postForEntity(adminPath, issue, Map.class);
        assertThat(expired.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        jdbc.update("update print_agent_pairings set expires_at = now() - interval '1 minute' where id = ?",
            UUID.fromString((String) expired.getBody().get("id")));
        assertThat(rest.postForEntity(pairPath, Map.of("code", expired.getBody().get("code")), Map.class)
            .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        var challenge = rest.postForEntity(adminPath, issue, Map.class);
        String statusPath = adminPath + "/" + challenge.getBody().get("id");
        var redeem = Map.of("code", challenge.getBody().get("code"));
        var attempts = List.of(
            java.util.concurrent.CompletableFuture.supplyAsync(() -> rest.postForEntity(pairPath, redeem, Map.class)),
            java.util.concurrent.CompletableFuture.supplyAsync(() -> rest.postForEntity(pairPath, redeem, Map.class)));
        var responses = attempts.stream().map(java.util.concurrent.CompletableFuture::join).toList();
        assertThat(responses.stream().map(ResponseEntity::getStatusCode).toList())
            .containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.UNAUTHORIZED);
        var credentials = responses.stream().filter(r -> r.getStatusCode().is2xxSuccessful())
            .findFirst().orElseThrow().getBody();
        var status = rest.exchange(statusPath, HttpMethod.GET, new HttpEntity<>(auth(owner.token())), Map.class);
        assertThat(status.getStatusCode()).isEqualTo(HttpStatus.OK);
        var id = status.getBody().get("agentId");
        assertThat(id).isNotNull();
        assertThat(rest.exchange(statusPath, HttpMethod.GET, new HttpEntity<>(auth(other.token())), Map.class)
            .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        String revokePath = "/api/v1/admin/agents/" + id;
        assertThat(rest.exchange(revokePath, HttpMethod.DELETE, new HttpEntity<>(auth(other.token())), Void.class)
            .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        var headers = new HttpHeaders();
        headers.set("X-Agent-Code", (String) credentials.get("agentCode"));
        headers.set("X-Agent-Token", (String) credentials.get("agentToken"));
        var heartbeat = new HttpEntity<>(Map.of("printers", List.of(), "platform", "WINDOWS"), headers);
        String heartbeatPath = "/api/v1/agent/heartbeat";
        assertThat(rest.postForEntity(heartbeatPath, heartbeat, Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rest.postForEntity(heartbeatPath, heartbeat, Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rest.exchange(revokePath, HttpMethod.DELETE, new HttpEntity<>(auth(owner.token())), Void.class)
            .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        var revoked = rest.postForEntity(heartbeatPath, heartbeat, Map.class);
        assertThat(revoked.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(revoked.getBody()).containsEntry("message", "Print agent revoked");
    }

    private record ShopSession(String token, String qrToken) {}

    private ShopSession registerShop(String name) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> body = Map.of(
            "shopName", name,
            "ownerName", "Owner " + suffix,
            "phone", "01700000000",
            "email", "owner-" + suffix + "@example.com",
            "address", "12 Print Street",
            "username", "admin_" + suffix,
            "password", "strong-password-123"
        );
        ResponseEntity<Map> response = rest.postForEntity(
            "/api/v1/auth/register-shop", body, Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return new ShopSession((String) response.getBody().get("token"),
            (String) response.getBody().get("qrToken"));
    }

    @SuppressWarnings("unchecked")
    private Long pendingPaymentId(String token, String orderNumber) {
        List<Map<String, Object>> payments = rest.exchange("/api/v1/admin/payments/pending",
            HttpMethod.GET, new HttpEntity<>(auth(token)), List.class).getBody();
        assertThat(payments).isNotNull();
        return payments.stream()
            .filter(row -> orderNumber.equals(row.get("orderNumber")))
            .map(row -> ((Number) row.get("id")).longValue())
            .findFirst()
            .orElseThrow(() -> new AssertionError("No pending payment for " + orderNumber));
    }

    private HttpHeaders auth(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private HttpEntity<MultiValueMap<String, Object>> multipart(MultiValueMap<String, Object> form) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return new HttpEntity<>(form, headers);
    }

    private MultiValueMap<String, Object> orderForm(ByteArrayResource file, String pages, int copies) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("files", file);
        form.add("colorMode", "BW");
        form.add("sideMode", "SIMPLEX");
        form.add("copies", String.valueOf(copies));
        form.add("orientation", "PORTRAIT");
        form.add("pageSelection", pages);
        return form;
    }



    private BigDecimal amount(Object value) {
        return new BigDecimal(String.valueOf(value));
    }

    private ByteArrayResource pdf(String filename, byte[] content) {
        return new ByteArrayResource(content) {
            @Override
            public String getFilename() { return filename; }
        };
    }

    private byte[] pdf(int pages) throws IOException {
        try (PDDocument document = new PDDocument()) {
            for (int i = 0; i < pages; i++) {
                document.addPage(new PDPage(PDRectangle.A4));
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }
}
