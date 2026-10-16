package io.github.pkyit.auditlog.spring.collect;

/**
 * 环境无关的响应视图：只暴露采集真正需要的部分。
 *
 * <p>当前只用到状态码：响应正文的采集必须包装输出流（Servlet）或订阅数据流（Reactive），
 * 在流式与大响应场景会改变原有行为，属于尚未实现的已知局限（见 README「已知局限」）。
 * 因此这里不提供 body 方法：接口一旦存在就隐含「正文可获取」，实现者会按正文语义编码。
 */
public interface AuditResponseView {

	/**
	 * 状态码未知。
	 *
	 * <p>默认值不能取 200：把「拿不到状态码」与「状态码是 200」混为一谈会让审计记录谎报
	 * 成功，下游按 status 统计时无法区分两者。
	 */
	int UNKNOWN_STATUS = -1;

	/** HTTP 状态码；未知时返回 {@link #UNKNOWN_STATUS}。 */
	int status();

	/** 环境名称；默认实现返回 {@code unknown}，具体实现覆盖为 {@code servlet} 或 {@code reactive}。 */
	default String environment() {
		return "unknown";
	}

	/** 状态码未知的响应视图（例如异常透传、容器尚未渲染错误页时）。 */
	static AuditResponseView unknown() {
		return () -> -1;
	}
}
