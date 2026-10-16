package io.github.pkyit.auditlog.spring.reactive;

import io.github.pkyit.auditlog.spring.collect.AuditResponseView;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.server.ServerWebExchange;

/**
 * {@link AuditResponseView} 的 Reactive（WebFlux）实现。
 *
 * <p>状态码优先取响应提交时刻捕获的值，其次取 {@code ServerHttpResponse.getStatusCode()}。
 * 响应提交前该值可能仍为空，此时返回 {@link AuditResponseView#UNKNOWN_STATUS}：
 * 未知必须记为未知，不得回退为 200 之类看似成功的默认值。
 */
public class ReactiveAuditResponseView implements AuditResponseView {

	private final ServerWebExchange exchange;

	public ReactiveAuditResponseView(ServerWebExchange exchange) {
		this.exchange = exchange;
	}

	@Override
	public int status() {
		// 状态码以响应提交（beforeCommit）时刻捕获的值为准：WebFilter 返回的 Mono
		// 在响应体写出之前即完成，收尾时直接读 response 往往仍为空。
		Object captured = exchange.getAttributes().get(AuditReactiveSupport.COMMITTED_STATUS_ATTRIBUTE);
		if (captured instanceof Integer value) {
			return value;
		}
		HttpStatusCode code = exchange.getResponse().getStatusCode();
		if (code == null) {
			return AuditResponseView.UNKNOWN_STATUS;
		}
		int value = code.value();
		// 1xx 是临时响应而非最终结果，记为 UNKNOWN，避免下游统计被误导
		return value >= 100 && value < 200 ? AuditResponseView.UNKNOWN_STATUS : value;
	}

	@Override
	public String environment() {
		return "reactive";
	}
}
