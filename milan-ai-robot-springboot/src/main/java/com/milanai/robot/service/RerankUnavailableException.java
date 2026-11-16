package com.milanai.robot.service;

/** Indicates that reranking could not produce a trustworthy result. */
public final class RerankUnavailableException extends RuntimeException {
    public RerankUnavailableException() {
        super("Rerank service unavailable");
    }
}
