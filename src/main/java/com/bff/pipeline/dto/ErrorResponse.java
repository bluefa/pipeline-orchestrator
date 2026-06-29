package com.bff.pipeline.dto;

/** A minimal error body for the REST layer: a stable code and a human-readable message. */
public record ErrorResponse(String code, String message) {
}
