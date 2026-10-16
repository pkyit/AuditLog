package io.github.pkyit.auditlog.spring.reactive;

import io.github.pkyit.auditlog.spring.collect.AuditCaptureService;
import io.github.pkyit.auditlog.spring.collect.AuditRecordContext;
import org.springframework.core.Ordered;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.reactive.HandlerAdapter;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 语义通道（Reactive）：包装 WebFlux 的 {@code HandlerAdapter}，在 Handler 执行前补齐审计语义。
 *
 * <p>语义填充只能有一个入口。仅靠 exchange 属性（如 {@code BEST_MATCHING_HANDLER}）取不到
 * 即将执行的 Handler：{@code DispatcherHandler} 把该属性写在 mutate 派生出的另一个 exchange 上，
 * 而 Spring 6.2 的 {@code ServerWebExchange.Builder} 没有 {@code attributes(...)} 方法，
 * 属性表无法共享到本框架持有的 exchange。两处填充并存则因执行顺序不确定而先到者胜，
 * 先执行的一方拿不到请求，依赖请求参数解析的字段（如资源 ID）将始终为空。
 *
 * <p>包装适配器可同时取得即将执行的 {@code HandlerMethod} 与实际用于执行的 exchange，
 * 因此按请求参数解析资源 ID 也能完成。这与 Servlet 侧包装
 * {@code RequestMappingHandlerAdapter} 的做法对称。
 *
 * <p>只包装 {@code RequestMappingHandlerAdapter}：其他适配器（RouterFunction、静态资源等）
 * 不承载 {@code @AuditLog}，包装它们只增加风险而无收益。
 */
public final class ReactiveSemanticChannelProcessor {

	private ReactiveSemanticChannelProcessor() {
	}

	/**
	 * 包装注解适配器，使其在委派执行前补齐审计语义。
	 *
	 * <p>包装是幂等的：已由本框架包装过的适配器不会被重复包装。
	 *
	 * @param captureService 语义填充使用的采集服务
	 * @param adapter        待包装的适配器
	 * @return 具备语义填充能力的适配器；非 {@code RequestMappingHandlerAdapter} 或已包装时返回原实例
	 */
	public static HandlerAdapter wrap(AuditCaptureService captureService, HandlerAdapter adapter) {
		boolean isRequestMappingAdapter =
			adapter instanceof org.springframework.web.reactive.result.method.annotation.RequestMappingHandlerAdapter;
		if (!isRequestMappingAdapter || adapter instanceof SemanticHandlerAdapter) {
			return adapter;
		}
		return new SemanticHandlerAdapter(captureService, adapter);
	}

	/** 判断适配器是否由本框架包装，用于避免重复包装与诊断。 */
	public static boolean isWrapped(HandlerAdapter adapter) {
		return adapter instanceof SemanticHandlerAdapter;
	}

	/** 把 {@code RequestMappingHandlerAdapter} 包一层，在 handle() 前补审计语义。 */
	static final class SemanticHandlerAdapter implements HandlerAdapter, Ordered {

		private final AuditCaptureService captureService;
		private final HandlerAdapter delegate;

		SemanticHandlerAdapter(AuditCaptureService captureService, HandlerAdapter delegate) {
			this.captureService = captureService;
			this.delegate = delegate;
		}

		@Override
		public boolean supports(Object handler) {
			return delegate.supports(handler);
		}

		@Override
		public Mono<org.springframework.web.reactive.HandlerResult> handle(ServerWebExchange exchange,
				Object handler) {
			if (handler instanceof HandlerMethod handlerMethod) {
				AuditRecordContext context = contextOf(exchange);
				// 仅在尚未填充时填充：上下文由语义通道与边界通道共用，
				// 重复填充没有收益，还会覆盖已解析的结果
				if (context != null && context.getMethodMetadata() == null) {
					fillSemantic(context, handlerMethod, exchange);
				}
			}
			return delegate.handle(exchange, handler);
		}

		private AuditRecordContext contextOf(ServerWebExchange exchange) {
			Object context = exchange.getAttributes().get(AuditReactiveSupport.CONTEXT_ATTRIBUTE);
			return context instanceof AuditRecordContext found ? found : null;
		}

		/**
		 * 填充语义元数据。
		 *
		 * <p>此处传入请求视图：资源 ID 的请求参数形式在 Handler 执行前即可确定，
		 * 不依赖"方法执行结束"这一在反应式下并不可靠的钩子。
		 */
		private void fillSemantic(AuditRecordContext context, HandlerMethod handlerMethod,
				ServerWebExchange exchange) {
			try {
				captureService.applySemantic(context, handlerMethod,
					new ReactiveAuditRequestView(exchange), null);
			}
			catch (Throwable error) {
				// 语义填充失败不得影响业务执行与记录发布；记录的完整性由边界通道保证
				captureService.reportFailure("semantic", "Reactive 语义填充失败", error);
			}
		}

		/** 顺序与 delegate 保持一致，避免改变框架对适配器的排序结果。 */
		@Override
		public int getOrder() {
			return delegate instanceof Ordered ordered ? ordered.getOrder() : 0;
		}
	}
}
