package com.bff.pipeline.repository;

import com.bff.pipeline.entity.InstallEvent;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 자동 설치 이벤트 기록(install_event)의 저장소다. 지금은 InstallEventHandler가 행을 추가하기만 하고 읽는 곳은 없다 —
 * 조회 API나 알림이 필요해지면 그때 질의를 더한다.
 */
public interface InstallEventRepository extends JpaRepository<InstallEvent, Long> {
}
