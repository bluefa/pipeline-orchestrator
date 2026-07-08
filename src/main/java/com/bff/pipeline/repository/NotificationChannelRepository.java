package com.bff.pipeline.repository;

import com.bff.pipeline.entity.NotificationChannel;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 알림 채널 설정({@link NotificationChannel}, {@code id = SINGLETON_ID} 단일 행)의 Spring Data 저장소다.
 * id가 수동({@code @GeneratedValue} 아님)이므로 쓰기는 언제나 로드-또는-생성 후 {@code save}로 한다
 * — set-id 엔티티를 곧장 save하면 Hibernate가 detached로 보고 select-then-insert merge를 하기 때문이다.
 */
public interface NotificationChannelRepository extends JpaRepository<NotificationChannel, Long> {
}
