package com.bts.fairticket.risk.client;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.bts.fairticket.risk.client.AiInferenceClient.AiFeatures;
import com.bts.fairticket.risk.client.AiInferenceClient.AiPredictRequest;
import com.bts.fairticket.risk.client.AiInferenceClient.AiUnavailableException;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.json.JsonMapper;

class AiInferenceClientTests {

	private final JsonMapper json = JsonMapper.builder().build();

	private AiInferenceClient client(HttpServer server) {
		return new AiInferenceClient(RestClient.builder(), "http://127.0.0.1:" + server.getAddress().getPort() + "/",
				Duration.ofSeconds(2), Duration.ofSeconds(5));
	}

	private static void reply(com.sun.net.httpserver.HttpExchange e, int status, byte[] body) throws java.io.IOException {
		e.getResponseHeaders().add("Content-Type", "application/json");
		e.sendResponseHeaders(status, body.length);
		e.getResponseBody().write(body);
		e.close();
	}

	private static AiPredictRequest request(String userId, Double variance) {
		return new AiPredictRequest(userId, "2026-11-24T05:00:00Z", 30, new AiFeatures(7, 0.3, variance, null, 1, 2));
	}

	@Test
	void sendsBatchWithNullFeaturesAndParsesResults() throws Exception {
		var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		List<Map<?, ?>> received = new ArrayList<>();
		server.createContext("/predict/batch", e -> {
			assertEquals("POST", e.getRequestMethod());
			received.add(json.readValue(e.getRequestBody().readAllBytes(), Map.class));
			reply(e, 200, json.writeValueAsBytes(Map.of("results", List.of(
					Map.of("userId", "u1", "status", "OK", "rawAnomalyScore", 0.61, "anomalyScore", 0.72,
							"isAnomaly", true, "modelVersion", "fds-if-v4.0.0", "assessedAt", "2026-11-24T05:00:01+00:00"),
					Map.of("userId", "u2", "status", "INSUFFICIENT_DATA", "modelVersion", "fds-if-v4.0.0",
							"assessedAt", "2026-11-24T05:00:01+00:00")))));
		});
		server.start();
		try {
			var results = client(server).predictBatch(List.of(request("u1", 0.01), request("u2", null)));

			Map<?, ?> first = (Map<?, ?>) ((List<?>) received.get(0).get("requests")).get(0);
			assertEquals("u1", first.get("userId"));
			assertEquals("2026-11-24T05:00:00Z", first.get("calculatedAt"));
			assertEquals(30, first.get("windowDays"));
			Map<?, ?> features = (Map<?, ?>) first.get("features");
			assertEquals(6, features.size());
			assertTrue(features.containsKey("cancelRate") && features.get("cancelRate") == null, "계산 불가 값은 null로 보낸다");
			Map<?, ?> second = (Map<?, ?>) ((List<?>) received.get(0).get("requests")).get(1);
			assertNull(((Map<?, ?>) second.get("features")).get("bookingIntervalVariance"));

			assertEquals("OK", results.get(0).status());
			assertEquals(0.72, results.get(0).anomalyScore(), 1e-9);
			assertTrue(results.get(0).isAnomaly());
			assertEquals("INSUFFICIENT_DATA", results.get(1).status());
			assertNull(results.get(1).anomalyScore());
		} finally {
			server.stop(0);
		}
	}

	@Test
	void serverErrorsAndCountMismatchBecomeAiUnavailable() throws Exception {
		var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/predict/batch", e -> {
			e.getRequestBody().readAllBytes();
			if (e.getRequestURI().getQuery() == null && e.getRequestHeaders().containsKey("X-Fail")) {
				reply(e, 500, "{}".getBytes());
			} else {
				reply(e, 200, json.writeValueAsBytes(Map.of("results", List.of())));
			}
		});
		server.start();
		try {
			var ai = client(server);
			assertThrows(AiUnavailableException.class, () -> ai.predictBatch(List.of(request("u1", null))));
		} finally {
			server.stop(0);
		}
		var down = new AiInferenceClient(RestClient.builder(), "http://127.0.0.1:1", Duration.ofMillis(300), Duration.ofSeconds(1));
		assertThrows(AiUnavailableException.class, () -> down.predictBatch(List.of(request("u1", null))));
	}

	@Test
	void http4xxBecomesAiUnavailable() throws Exception {
		var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/predict/batch", e -> {
			e.getRequestBody().readAllBytes();
			reply(e, 422, json.writeValueAsBytes(Map.of("errors", List.of("requests는 1개 이상"))));
		});
		server.start();
		try {
			assertThrows(AiUnavailableException.class, () -> client(server).predictBatch(List.of(request("u1", null))));
		} finally {
			server.stop(0);
		}
	}
}
