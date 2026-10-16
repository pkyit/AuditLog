package io.github.pkyit.auditlog.spring.identity;

import io.github.pkyit.auditlog.core.api.PrincipalInfo;
import io.github.pkyit.auditlog.core.api.PrincipalResolver;
import io.github.pkyit.auditlog.core.model.PrincipalType;

import java.util.Optional;

/**
 * 从 Spring Security 认证上下文解析主体，解析结果可信。
 *
 * <p>这是审计不变量 I1「身份不可伪造」的主通道：主体来自服务端的认证结果，
 * 客户端无法通过构造请求头伪造。
 *
 * <p>类型层面对 Spring Security 无编译期依赖：是否注册本类由自动配置的
 * {@code @ConditionalOnClass} 决定，运行期只通过 {@link Class#forName} 反射访问上下文，
 * 因此未引入 Spring Security 的应用不会因本类抛出 {@code NoClassDefFoundError}。
 */
public class SecurityContextPrincipalResolver implements PrincipalResolver {

	/**
	 * 解析当前认证上下文中的主体。
	 *
	 * <p>Spring Security 不在类路径、认证对象为 {@code null}、未通过认证、主体为
	 * {@code anonymousUser} 或名称提取失败时返回 {@link Optional#empty()}，交由下一个
	 * 解析器继续尝试；反射调用抛出的异常同样不向调用方传播。
	 *
	 * @param context 解析上下文；本实现只读取认证上下文，不使用其中的请求元数据
	 * @return 主体；{@link Optional#empty()} 表示无法确认身份
	 */
	@Override
	public Optional<PrincipalInfo> resolve(ResolutionContext context) {
		try {
			Class<?> holderClass = Class.forName("org.springframework.security.core.context.SecurityContextHolder");
			Object securityContext = holderClass.getMethod("getContext").invoke(null);
			Object authentication = securityContext.getClass().getMethod("getAuthentication").invoke(securityContext);
			if (authentication == null) {
				return Optional.empty();
			}
			Object authenticated = authentication.getClass().getMethod("isAuthenticated").invoke(authentication);
			if (!Boolean.TRUE.equals(authenticated)) {
				return Optional.empty();
			}
			Object principal = authentication.getClass().getMethod("getPrincipal").invoke(authentication);
			if (principal == null) {
				return Optional.empty();
			}
			String name = extractName(principal);
			if (name == null || name.isBlank() || "anonymousUser".equals(name)) {
				return Optional.empty();
			}
			String authType = authentication.getClass().getSimpleName();
			return Optional.of(new PrincipalInfo(name, name, PrincipalType.AUTHENTICATED, authType, null));
		}
		catch (ClassNotFoundException notPresent) {
			// 未引入 Spring Security：正常降级，不视为错误
			return Optional.empty();
		}
		catch (Throwable ignored) {
			return Optional.empty();
		}
	}

	private String extractName(Object principal) {
		if (principal instanceof CharSequence text) {
			return text.toString();
		}
		// 字符串主体直接取值；其余主体（如 UserDetails）依次尝试 getUsername()、getName()
		for (String methodName : new String[]{"getUsername", "getName"}) {
			try {
				Object value = principal.getClass().getMethod(methodName).invoke(principal);
				if (value != null) {
					return String.valueOf(value);
				}
			}
			catch (Throwable ignored) {
				// 尝试下一个方法名
			}
		}
		return null;
	}

	@Override
	public int getOrder() {
		// 认证上下文优先于任何请求头来源
		return 10;
	}
}
