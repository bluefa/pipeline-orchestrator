package com.bff.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bff.pipeline.config.PipelineSettings;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * {@link PipelineSettings} 부팅-시 fail-fast 검증. 특히 {@code maxTerraformPollCallErrors}가 1 미만이면 첫 폴 전에
 * 모든 terraform job을 즉시 관측 불능으로 확정하는 오동작이 되므로, 키 이름과 함께 즉시 시작에 실패해야 한다.
 */
class PipelineSettingsTest {

    private static PipelineSettings.PipelineSettingsBuilder valid() {
        return PipelineSettings.builder()
                .executionTimeout(Duration.ofMinutes(50))
                .pollingInterval(Duration.ofMinutes(10))
                .maxFailCount(2)
                .maxTerraformPollCallErrors(10)
                .startDelay(Duration.ZERO);
    }

    @Test
    void aValidConfigurationConstructs() {
        assertThatCode(() -> valid().build()).doesNotThrowAnyException();
    }

    @Test
    void aMaxTerraformPollCallErrorsBelowOneFailsFastWithItsKey() {
        assertThatThrownBy(() -> valid().maxTerraformPollCallErrors(0).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pipeline.max-terraform-poll-call-errors");
    }

    @Test
    void aMaxFailCountBelowOneFailsFastWithItsKey() {
        assertThatThrownBy(() -> valid().maxFailCount(0).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pipeline.max-fail-count");
    }

    @Test
    void maxTerraformPollCallErrorsBindsToTenWhenThePropertyIsAbsent() {
        // 키를 빼고 바인딩 → @DefaultValue(10)로 채워진다("따로 설정하지 않아도 10").
        PipelineSettings bound = bind(propsWithout("pipeline.max-terraform-poll-call-errors"));
        assertThat(bound.maxTerraformPollCallErrors()).isEqualTo(10);
    }

    @Test
    void maxTerraformPollCallErrorsBindsTheConfiguredValueWhenPresent() {
        Map<String, Object> props = propsWithout(null);
        props.put("pipeline.max-terraform-poll-call-errors", "3");
        assertThat(bind(props).maxTerraformPollCallErrors()).isEqualTo(3);
    }

    @Test
    void everyFieldBindsToItsCodeDefaultWhenAbsent() {
        // pipeline.* 도메인 데드라인 키를 모두 빼고(트리거용 무관 키 하나만) 바인딩 → 전부 @DefaultValue로 채워진다.
        Map<String, Object> onlyTrigger = new HashMap<>();
        onlyTrigger.put("pipeline.max-terraform-poll-call-errors", "5");   // 하나만 명시(prefix 존재 트리거 + 오버라이드 확인)
        PipelineSettings s = bind(onlyTrigger);

        assertThat(s.executionTimeout()).isEqualTo(Duration.ofMinutes(50));
        assertThat(s.pollingInterval()).isEqualTo(Duration.ofMinutes(10));
        assertThat(s.maxFailCount()).isEqualTo(2);
        assertThat(s.maxTerraformPollCallErrors()).isEqualTo(5);   // 명시값이 이긴다
        assertThat(s.startDelay()).isEqualTo(Duration.ofSeconds(15));
    }

    private static PipelineSettings bind(Map<String, Object> props) {
        return new Binder(new MapConfigurationPropertySource(props)).bind("pipeline", PipelineSettings.class).get();
    }

    /** 필수 키를 모두 담되 omitKey는 뺀 프로퍼티 맵(null이면 모두 포함). */
    private static Map<String, Object> propsWithout(String omitKey) {
        Map<String, Object> props = new HashMap<>();
        props.put("pipeline.execution-timeout", "PT50M");
        props.put("pipeline.polling-interval", "PT10M");
        props.put("pipeline.max-fail-count", "2");
        props.put("pipeline.max-terraform-poll-call-errors", "10");
        props.put("pipeline.start-delay", "PT0S");
        if (omitKey != null) {
            props.remove(omitKey);
        }
        return props;
    }
}
