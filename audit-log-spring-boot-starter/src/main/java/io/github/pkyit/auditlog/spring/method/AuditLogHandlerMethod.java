package io.github.pkyit.auditlog.spring.method;

import io.github.pkyit.auditlog.spring.collect.AuditCaptureService;
import io.github.pkyit.auditlog.spring.collect.AuditRecordContext;
import io.github.pkyit.auditlog.spring.collect.AuditRequestContextHolder;
import io.github.pkyit.auditlog.spring.collect.AuditRequestView;
import io.github.pkyit.auditlog.spring.collect.TransactionAuditTracker;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.ServletInvocableHandlerMethod;

/**
 * 语义通道：在 Handler 方法调用前后补齐审计语义。
 *
 * <p>约束如下：
 * <ol>
 *   <li>不发布记录：只往边界通道建立的同一个 {@link AuditRecordContext} 里补字段，
 *       由过滤器在 {@code afterCompletion} 统一发布。按 requestId 事后合并会引入竞态，
 *       且语义记录可能被粗粒度记录覆盖，因此必须在同一上下文内补齐；</li>
 *   <li>不改动异常语义：业务异常照原样抛出，交给 Spring MVC 的异常处理机制，审计只做记录。
 *       采集逻辑自身抛出的异常必须被吞掉，否则会覆盖原始业务异常，形成误导性故障；</li>
 *   <li>可整体关闭：{@code audit.log.collector.method.enabled=false} 时本通道不参与，
 *       避免与宿主自身的 {@code WebMvcRegistrations} 冲突；该冲突只导致语义字段缺失，
 *       边界通道仍保证事件不遗漏。</li>
 * </ol>
 */
public class AuditLogHandlerMethod extends ServletInvocableHandlerMethod {

	private final AuditCaptureService captureService;

	public AuditLogHandlerMethod(HandlerMethod handlerMethod, AuditCaptureService captureService) {
		super(handlerMethod);
		this.captureService = captureService;
	}

	/**
	 * 调用业务方法，并在其前后补齐审计语义。
	 *
	 * <p>语义填充失败只上报到失败通道，不影响业务方法的执行与返回值；
	 * 业务方法抛出的异常经语义收尾后原样抛出。
	 */
	@Override
	protected Object doInvoke(Object... args) throws Exception {
		AuditRecordContext context = AuditRequestContextHolder.get();
		if (context == null) {
			// 非过滤器路径（内部转发、异步派发）：不干预业务执行
			return super.doInvoke(args);
		}
		// 请求视图只解析一次：语义填充与收尾都需要它，用于解析资源 ID
		AuditRequestView requestView = resolveRequestView(args);
		AuditMethodMetadata metadata = null;
		try {
			// 传入请求视图：资源 ID 的请求参数形式在此确定，与 Reactive 侧共用同一段解析逻辑
			metadata = captureService.applySemantic(context, this, requestView, args);
			// 与事务边界对齐：方法在事务内执行时注册回调，回滚结果会被写回上下文
			TransactionAuditTracker.register(context);
		}
		catch (Throwable error) {
			// 语义填充失败不得影响业务方法执行
			captureService.reportFailure("semantic", "审计语义填充失败", error);
		}

		Object returnValue;
		try {
			returnValue = super.doInvoke(args);
		}
		catch (Throwable error) {
			if (metadata != null) {
				safeFinishSemantic(context, requestView, args);
			}
			throw error;
		}
		if (metadata != null) {
			safeFinishSemantic(context, requestView, args);
		}
		return returnValue;
	}

	/** 语义收尾：任何异常都只上报，不得传播到业务调用链。 */
	private void safeFinishSemantic(AuditRecordContext context, AuditRequestView requestView, Object[] args) {
		try {
			captureService.finishSemantic(context, requestView, args, context.elapsedMillis());
		}
		catch (Throwable inner) {
			captureService.reportFailure("semantic", "审计语义收尾失败", inner);
		}
	}

	/**
	 * 从方法实参里找到 {@code HttpServletRequest}，取出过滤器写入的请求视图。
	 *
	 * <p>语义通道位于 HandlerAdapter 内，拿不到过滤器栈上的局部变量，请求视图只能经请求属性传递。
	 * 取不到时返回 {@code null}：此时资源 ID 无法按参数名解析，但记录仍会正常发布，
	 * 即允许缺失单个字段，不允许丢失整条记录。
	 */
	private AuditRequestView resolveRequestView(Object[] args) {
		if (args == null) {
			return null;
		}
		try {
			for (Object arg : args) {
				if (arg instanceof jakarta.servlet.http.HttpServletRequest servletRequest) {
					Object view = servletRequest.getAttribute(
						io.github.pkyit.auditlog.spring.servlet.ServletAuditRequestView.ATTRIBUTE_KEY);
					if (view instanceof AuditRequestView found) {
						return found;
					}
				}
			}
		}
		catch (Throwable ignored) {
			// 取不到请求视图不影响审计记录发布
		}
		return null;
	}

	/**
	 * 返回本通道使用的采集服务。
	 *
	 * @return 构造时注入的采集服务，恒不为 {@code null}
	 */
	public AuditCaptureService getCaptureService() {
		return captureService;
	}
}
