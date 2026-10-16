package io.github.pkyit.auditlog.spring.method;

import io.github.pkyit.auditlog.core.model.ActionType;
import io.github.pkyit.auditlog.spring.annotation.AuditLog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.method.HandlerMethod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 注解解析规则：Servlet 与 Reactive 两种 Web 环境共用的同一份逻辑。
 *
 * <p>两种环境的语义通道实现不同（Servlet 启动扫描、Reactive 请求期解析），
 * 但 {@code @AuditLog} 的读取语义必须完全一致，否则同一份注解在更换容器后行为会漂移。
 * 本类固定这份共同规则。
 *
 * <p>类级回退不可省略：{@code @AuditLog} 声明了 {@code @Target(TYPE)}，
 * 而 {@code findMergedAnnotation(Method,...)} 不会向上查找声明类，
 * 缺少显式回退时类级用法将静默失效。
 */
class AuditAnnotationResolverTest {

	@AuditLog(name = "类级操作", actionType = ActionType.QUERY, resourceType = "class-level")
	static class ClassAnnotatedController {

		/** 方法上没有注解：应回退到类级。 */
		public String inherited() {
			return "ok";
		}

		/** 类与方法同时声明时，方法级注解属性覆盖类级。 */
		@AuditLog(name = "方法级操作", actionType = ActionType.MUTATE)
		public String overriding() {
			return "ok";
		}

		/** 声明 {@code ignore = true}，在类级注解生效的前提下排除本方法。 */
		@AuditLog(ignore = true)
		public String ignored() {
			return "ok";
		}
	}

	static class PlainController {

		public String plain() {
			return "ok";
		}
	}

	@Test
	@DisplayName("方法无注解时回退到声明类上的注解（否则类级用法静默失效）")
	void fallsBackToClassLevelAnnotation() throws Exception {
		HandlerMethod handler = handlerOf(ClassAnnotatedController.class, "inherited");

		AuditLog resolved = AuditAnnotationResolver.resolve(handler);

		assertNotNull(resolved, "类级注解必须被解析到，否则该用法完全无效");
		assertEquals("类级操作", resolved.name());
		assertEquals(ActionType.QUERY, resolved.actionType());
		assertEquals("class-level", resolved.resourceType());
	}

	@Test
	@DisplayName("方法级注解覆盖类级注解")
	void methodLevelOverridesClassLevel() throws Exception {
		HandlerMethod handler = handlerOf(ClassAnnotatedController.class, "overriding");

		AuditLog resolved = AuditAnnotationResolver.resolve(handler);

		assertNotNull(resolved);
		assertEquals("方法级操作", resolved.name(), "方法级应覆盖类级");
		assertEquals(ActionType.MUTATE, resolved.actionType());
	}

	@Test
	@DisplayName("无任何注解时返回 null（由边界通道兜底，不是错误）")
	void returnsNullWhenNoAnnotation() throws Exception {
		HandlerMethod handler = handlerOf(PlainController.class, "plain");

		assertNull(AuditAnnotationResolver.resolve(handler),
			"未标注注解应返回 null，交由边界通道采集");
	}

	@Test
	@DisplayName("ignore=true 的解析结果要能被识别出来（由调用方跳过采集）")
	void resolvesIgnoreFlag() throws Exception {
		HandlerMethod handler = handlerOf(ClassAnnotatedController.class, "ignored");

		AuditLog resolved = AuditAnnotationResolver.resolve(handler);

		assertNotNull(resolved);
		// 方法级 ignore 覆盖类级；解析器只返回注解属性，是否跳过采集由注册表决定
		assertEquals(true, resolved.ignore());
	}

	@Test
	@DisplayName("稳定 key 的格式在两种环境下一致（类名#方法名(参数类型)）")
	void keyFormatIsStable() throws Exception {
		HandlerMethod handler = handlerOf(PlainController.class, "plain");

		String key = AuditAnnotationResolver.keyOf(handler);

		assertEquals(PlainController.class.getName() + "#plain()", key);
	}

	@Test
	@DisplayName("请求期解析注册表：命中缓存，且对无注解方法返回 null 而不是抛异常")
	void resolvingRegistryWorksWithoutStartupScan() throws Exception {
		ResolvingAuditMethodRegistry registry = new ResolvingAuditMethodRegistry();
		HandlerMethod annotated = handlerOf(ClassAnnotatedController.class, "overriding");
		HandlerMethod plain = handlerOf(PlainController.class, "plain");

		AuditMethodMetadata first = registry.find(annotated);
		AuditMethodMetadata second = registry.find(annotated);

		assertNotNull(first, "Reactive 环境没有启动扫描，注册表必须在请求期解析出元数据");
		assertSame(first, second, "重复查询应命中缓存，避免每次请求重做注解解析");
		assertNull(registry.find(plain), "无注解方法应返回 null 而不是抛异常");
		assertEquals(first, registry.all().get(first.key()), "已解析的元数据应可枚举（供诊断使用）");
	}

	private static HandlerMethod handlerOf(Class<?> type, String methodName) throws Exception {
		return new HandlerMethod(type.getDeclaredConstructor().newInstance(),
			type.getMethod(methodName));
	}
}
