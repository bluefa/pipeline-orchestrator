package com.bff.pipeline.client;

import com.bff.pipeline.enums.CloudProvider;
import com.bff.pipeline.enums.TaskOperation;
import com.bff.pipeline.model.HttpExchange;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 실제 외부 경계 대신 호출 순서·정확한 본문·요청 키와 트랜잭션 밖 호출을 검증하는 스크립트 가능한 fake다. */
public class FakeInstallationOperationsClient implements InstallationOperationsClient {
    public final List<Request> recommendations = new ArrayList<>();
    public final List<Request> deletions = new ArrayList<>();
    public final List<ConfirmationRequest> confirmations = new ArrayList<>();
    public boolean available = true;
    public boolean reconfirmationAvailable = true;
    public final Set<CloudProvider> unsupportedProviders = new HashSet<>();
    public Function<Request, Recommendation> recommendation;
    public Function<Request, HttpExchange> deletion;
    public Function<ConfirmationRequest, HttpExchange> confirmation;
    public static final String BODY = "{ \"resources\": [ {\"name\":\"한글\"} ],\n  \"unknown\": true }";

    public FakeInstallationOperationsClient() {
        reset();
    }

    public void reset() {
        recommendations.clear();
        deletions.clear();
        confirmations.clear();
        available = true;
        reconfirmationAvailable = true;
        unsupportedProviders.clear();
        recommendation = request -> new Recommendation(response(200, BODY), new VerifiedContext("approval-1", "generation-1"));
        deletion = request -> response(200, "");
        confirmation = request -> response(201, "");
    }

    public boolean supports(CloudProvider provider, TaskOperation operation) {
        return available && !unsupportedProviders.contains(provider) && operation.usesHttpRetryPolicy();
    }

    public boolean supportsReconfirmation(CloudProvider provider) {
        return reconfirmationAvailable;
    }

    public Recommendation fetchRecommendation(Request request) {
        requireNoTransaction();
        recommendations.add(request);
        return recommendation.apply(request);
    }

    public HttpExchange deleteConfirmedResources(Request request) {
        requireNoTransaction();
        deletions.add(request);
        return deletion.apply(request);
    }

    public HttpExchange confirmResources(ConfirmationRequest request) {
        requireNoTransaction();
        confirmations.add(request);
        return confirmation.apply(request);
    }

    public TestConnectionStartResponse startOrRecoverTestConnection(Request request) {
        throw new UnsupportedOperationException("HTTP fake only");
    }

    public TestConnectionPollResponse pollTestConnection(Request request, String executionVersion) {
        throw new UnsupportedOperationException("HTTP fake only");
    }

    public static HttpExchange response(int status, String body) {
        return HttpExchange.builder().statusCode(status).contentType("application/json; charset=UTF-8")
                .body(body).receivedAt(Instant.parse("2026-09-08T00:00:00Z")).build();
    }

    private void requireNoTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new AssertionError("External call ran inside a transaction");
        }
    }
}
