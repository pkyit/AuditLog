package io.github.pkyit.auditlog.spring.annotation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 固化 Spring 方法级注解查询的可见性边界：仅声明在类上的 {@code @AuditLog}
 * 对 {@code AnnotatedElementUtils.findMergedAnnotation(Method, AuditLog.class)} 不可见。
 *
 * <p>{@code AuditMethodRegistry.populate()} 只对 {@code Method} 执行该方法级查询，
 * 因此类级写法下方法级通道不会采集任何方法，且没有任何告警。边界通道仍会记录事件，
 * 不会漏事件，但 {@code value}、{@code resourceType}、{@code tags} 等语义字段全部为空。
 *
 * <p>类级写法要生效，查询必须回退到 {@code beanType}；只查 {@code Method} 必然静默失效。
 * {@code @AuditLog} 自身声明了 {@code @Target({METHOD, TYPE})} 与 {@code @Inherited}，
 * {@code ignore()} 的契约也以"类级开启、方法级排除"为前提，可见性缺口只来自查询方式。
 */
class ClassLevelAuditLogVisibilityTest {

	@AuditLog(value = "类级标注")
	static class ClassAnnotatedController {

		/** 未标注，但声明类上标了 @AuditLog。 */
		public String unannotatedMethod(String input) {
			return input;
		}

		/** 方法级显式标注（对照组：这条必须能被查到）。 */
		@AuditLog(value = "方法级标注")
		public String annotatedMethod(String input) {
			return input;
		}
	}

	static class PlainController {

		public String plain(String input) {
			return input;
		}
	}

	private Method method(Class<?> type, String name) throws NoSuchMethodException {
		return type.getMethod(name, String.class);
	}

	@Test
	@DisplayName("对照：方法级标注能被 findMergedAnnotation(Method) 查到")
	void findsMethodLevelAnnotation() throws Exception {
		AuditLog found = AnnotatedElementUtils.findMergedAnnotation(
			method(ClassAnnotatedController.class, "annotatedMethod"), AuditLog.class);
		assertNotNull(found, "方法级标注应当能被查到（若这条失败，说明测试本身有问题）");
		assertEquals("方法级标注", found.value());
	}

	@Test
	@DisplayName("关键判定：findMergedAnnotation(Method) 看不到【类级】注解 -> 类级用法静默失效")
	void methodLookupDoesNotSeeClassLevelAnnotation() throws Exception {
		AuditLog found = AnnotatedElementUtils.findMergedAnnotation(
			method(ClassAnnotatedController.class, "unannotatedMethod"), AuditLog.class);

		assertNull(found,
			"若此断言失败，说明 Spring 的方法级查询能看到类级注解，"
				+ "那么类级用法实际生效，类级缺陷的判断不成立");
	}

	@Test
	@DisplayName("补充：类级注解在 Class 上确实可查（说明注解声明本身是有效的）")
	void classLevelAnnotationIsVisibleOnType() throws Exception {
		AuditLog onType = AnnotatedElementUtils.findMergedAnnotation(
			ClassAnnotatedController.class, AuditLog.class);
		assertNotNull(onType, "类级注解应当能在 Class 上查到");
		assertEquals("类级标注", onType.value());

		assertNull(AnnotatedElementUtils.findMergedAnnotation(PlainController.class, AuditLog.class),
			"未标注的类不应查到注解");
	}
}
