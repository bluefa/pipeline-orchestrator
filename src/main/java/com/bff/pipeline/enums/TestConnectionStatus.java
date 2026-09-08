package com.bff.pipeline.enums;

/** 요청한 연결 테스트 실행의 상태다. 접수 응답과 구분하며 SUCCESS와 FAIL만 실행의 종결을 뜻한다. */
public enum TestConnectionStatus {
    PENDING, RUNNING, SUCCESS, FAIL;

    public boolean isTerminal() { return this == SUCCESS || this == FAIL; }
}
