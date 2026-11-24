package com.bts.fairticket.risk.client;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * ticket-fds-ai 추론 서버 클라이언트 (#40). 계약은 ticket-fds-ai app/schemas.py, docs/predict_feature_spec.md.
 * 배치 요청은 항목별로 OK / INSUFFICIENT_DATA / INVALID를 돌려주며, 형식 오류 한 건이 전체를 막지 않는다.
 */
@Component
public class AiInferenceClient {

	private final RestClient http;

	// Spring Boot 4는 RestClient.Builder 빈을 기본 등록하지 않으므로(별도 모듈) 직접 만든다.
	@Autowired
	public AiInferenceClient(@Value("${bts.ai.base-url:http://localhost:8000}") String baseUrl,
			@Value("${bts.ai.connect-timeout:PT2S}") Duration connectTimeout,
			@Value("${bts.ai.read-timeout:PT10S}") Duration readTimeout) {
		this(RestClient.builder(), baseUrl, connectTimeout, readTimeout);
	}

	AiInferenceClient(RestClient.Builder builder, String baseUrl, Duration connectTimeout, Duration readTimeout) {
		HttpClient client = HttpClient.newBuilder().connectTimeout(connectTimeout)
				.followRedirects(HttpClient.Redirect.NEVER).build();
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
		factory.setReadTimeout(readTimeout);
		this.http = builder.baseUrl(baseUrl.replaceAll("/+$", "")).requestFactory(factory).build();
	}

	/** @throws AiUnavailableException 네트워크 오류, 타임아웃, 4xx/5xx, 응답 형식 오류 */
	public List<AiPrediction> predictBatch(List<AiPredictRequest> requests) {
		try {
			AiBatchResponse response = http.post().uri("/predict/batch")
					.contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
					.body(new AiBatchRequest(requests))
					.retrieve()
					.body(AiBatchResponse.class);
			if (response == null || response.results() == null || response.results().size() != requests.size()) {
				throw new AiUnavailableException("AI 배치 응답 개수가 요청과 다릅니다.");
			}
			return response.results();
		} catch (RestClientException exception) {
			throw new AiUnavailableException("AI 추론 서버 호출 실패: " + exception.getClass().getSimpleName());
		}
	}

	public record AiFeatures(Integer bookingCount, Double avgBookingInterval, Double bookingIntervalVariance,
			Double cancelRate, Integer cardReuseCount, Integer ipBookingDensity) { }

	/** calculatedAt은 UTC 오프셋이 필수다 (예: 2026-11-24T05:00:00Z). windowDays는 현재 30만 허용된다. */
	public record AiPredictRequest(String userId, String calculatedAt, int windowDays, AiFeatures features) { }

	public record AiBatchRequest(List<AiPredictRequest> requests) { }

	/** status: OK, INSUFFICIENT_DATA, INVALID. anomalyScore는 0~1 정규화 점수로 0.5 이상이면 isAnomaly=true와 같다. */
	public record AiPrediction(String userId, String status, Double rawAnomalyScore, Double anomalyScore,
			Boolean isAnomaly, String modelVersion, String assessedAt, List<String> errors) { }

	public record AiBatchResponse(List<AiPrediction> results) { }

	public static class AiUnavailableException extends RuntimeException {
		public AiUnavailableException(String message) { super(message); }
	}
}
