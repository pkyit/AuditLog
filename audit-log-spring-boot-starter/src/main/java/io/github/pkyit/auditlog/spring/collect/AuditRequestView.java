package io.github.pkyit.auditlog.spring.collect;

import java.util.List;
import java.util.Map;

/**
 * 环境无关的请求视图：采集编排层只依赖它，不直接依赖 Servlet 或 WebFlux 类型。
 *
 * <p>采集逻辑在 Servlet 与 WebFlux 两种环境下必须语义一致，否则同一次操作会因运行环境
 * 产生不同的审计记录。编排层若直接依赖 {@code HttpServletRequest}，WebFlux 环境
 * （classpath 上没有 servlet 类）就无法复用同一份实现，因此把「从请求里取什么」抽成接口：
 * <ul>
 *   <li>Servlet 实现：{@code ServletAuditRequestView}（薄封装 {@code HttpServletRequest}）；</li>
 *   <li>Reactive 实现：{@code ReactiveAuditRequestView}（封装 {@code ServerWebExchange}）。</li>
 * </ul>
 * 两者都返回已按配置上限截断的请求体，脱敏、截断、字段规则等语义共用同一份实现。
 */
public interface AuditRequestView {

	/** HTTP 方法（GET/POST/...）。 */
	String method();

	/** 请求 URI（不含查询串）。 */
	String uri();

	/** 原始查询串；无则为 {@code null}。 */
	String queryString();

	/** 读取首个同名请求头；不存在返回 {@code null}。 */
	String header(String name);

	/** 读取某请求头的全部取值（大小写不敏感）。 */
	List<String> headers(String name);

	/** 全部请求头的名与值（供批量脱敏；值可能多值合并）。 */
	Map<String, List<String>> allHeaders();

	/** 查询参数（单个值）。 */
	String parameter(String name);

	/** 请求属性（Servlet 为 request attribute；Reactive 为 exchange attribute）。 */
	Object attribute(String name);

	/** 会话 ID；无会话返回 {@code null}。 */
	String sessionId();

	/** 名为 {@code name} 的 Cookie 值；不存在返回 {@code null}。 */
	String cookie(String name);

	/** TCP 层来源地址（不可伪造的那一层）。 */
	String remoteAddress();

	/**
	 * 已缓存的请求体文本（已按 {@code max-cached-body-bytes} 截断并带截断标记）。
	 *
	 * <p>没有缓存到内容时返回 {@code null}（例如未启用边界载荷缓存、或请求无体）。
	 */
	String cachedBody();

	/** 环境名称，用于启动日志与诊断（{@code servlet} / {@code reactive}）。 */
	String environment();
}
