package io.github.pkyit.auditlog.spring.reactive;

import io.github.pkyit.auditlog.spring.collect.AuditRequestView;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link AuditRequestView} 的 Reactive（WebFlux）实现。
 *
 * <p>与 Servlet 实现的差异构成使用本类时必须知道的约束：
 * <ul>
 *   <li>查询参数由 {@code ServerHttpRequest.getQueryParams()} 提供已解码的多值映射，
 *       无需解析查询串；{@code queryString()} 仍返回客户端发来的原始串，
 *       以与 Servlet 侧保持同一字段语义；</li>
 *   <li>请求体是 {@code Flux<DataBuffer>}：本类只读取
 *       {@link AuditReactiveSupport#BODY_SINK_ATTRIBUTE} 中由过滤器旁路缓存的内容，
 *       不得在此订阅数据流，否则业务读到的请求体为空；</li>
 *   <li>WebFlux 无 session：缺少 {@code SESSION} Cookie 时 {@code sessionId()} 返回
 *       {@code null}，否则返回该 Cookie 的不可逆摘要。</li>
 * </ul>
 */
public class ReactiveAuditRequestView implements AuditRequestView {

	private final ServerWebExchange exchange;

	public ReactiveAuditRequestView(ServerWebExchange exchange) {
		this.exchange = exchange;
	}

	/** 原始 exchange（仅供 Reactive 适配器自身使用，编排层不应依赖）。 */
	public ServerWebExchange nativeExchange() {
		return exchange;
	}

	private ServerHttpRequest request() {
		return exchange.getRequest();
	}

	@Override
	public String method() {
		return request().getMethod() == null ? null : request().getMethod().name();
	}

	@Override
	public String uri() {
		return request().getURI().getRawPath();
	}

	@Override
	public String queryString() {
		// 返回客户端发来的原始查询串（对应 Servlet 的 getQueryString）；空串归一为 null，
		// 使「无查询参数」在两种环境下取值一致。
		String raw = request().getURI().getRawQuery();
		return raw == null || raw.isEmpty() ? null : raw;
	}

	@Override
	public String header(String name) {
		return request().getHeaders().getFirst(name);
	}

	@Override
	public List<String> headers(String name) {
		List<String> values = request().getHeaders().get(name);
		return values == null ? List.of() : new ArrayList<>(values);
	}

	@Override
	public Map<String, List<String>> allHeaders() {
		Map<String, List<String>> result = new LinkedHashMap<>();
		HttpHeaders headers = request().getHeaders();
		headers.forEach((name, values) -> result.put(name, new ArrayList<>(values)));
		return result;
	}

	@Override
	public String parameter(String name) {
		return request().getQueryParams().getFirst(name);
	}

	@Override
	public Object attribute(String name) {
		// 请求级状态存放在 exchange 属性表：过滤器建立的上下文与语义通道写入的元数据
		// 都依赖该表在 mutate() 派生出的 exchange 之间共享，跨算子、跨线程读取才可靠。
		return exchange.getAttributes().get(name);
	}

	@Override
	public String sessionId() {
		// WebFlux 无 HttpSession，会话标识退化为 SESSION Cookie；只记录不可逆摘要，
		// 会话凭据本身不得进入审计库（与 Servlet 侧语义一致）。
		String cookie = cookie("SESSION");
		if (cookie == null || cookie.isBlank()) {
			return null;
		}
		return "sha256:" + io.github.pkyit.auditlog.core.chain.Hasher.sha256(cookie).substring(0, 16);
	}

	@Override
	public String cookie(String name) {
		HttpCookie found = request().getCookies().getFirst(name);
		return found == null ? null : found.getValue();
	}

	@Override
	public String remoteAddress() {
		java.net.InetSocketAddress address = request().getRemoteAddress();
		return address == null || address.getAddress() == null
			? null : address.getAddress().getHostAddress();
	}

	@Override
	public String cachedBody() {
		// 请求体异步流过，此处读到的是累积容器的当前内容：数据流终止后才完整，
		// 因此只能在收尾阶段调用。
		Object sink = exchange.getAttributes().get(AuditReactiveSupport.BODY_SINK_ATTRIBUTE);
		if (sink instanceof AuditBodyTee.BodySink bodySink) {
			return bodySink.text(charset());
		}
		return null;
	}

	private java.nio.charset.Charset charset() {
		org.springframework.http.MediaType contentType = request().getHeaders().getContentType();
		java.nio.charset.Charset charset = contentType == null ? null : contentType.getCharset();
		return charset == null ? java.nio.charset.StandardCharsets.UTF_8 : charset;
	}

	@Override
	public String environment() {
		return "reactive";
	}
}
