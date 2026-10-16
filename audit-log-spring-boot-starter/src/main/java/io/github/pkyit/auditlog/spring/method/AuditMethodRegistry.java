package io.github.pkyit.auditlog.spring.method;

import org.springframework.web.method.HandlerMethod;

import java.util.List;
import java.util.Map;

/**
 * 方法级审计元数据注册表，契约与 Web 环境无关。
 *
 * <p>本接口及其顶层 Bean 不得在签名中引用 Servlet 专属类型（如
 * {@code RequestMappingHandlerMapping}）：Spring 内省 Bean 定义时必须解析该类型，
 * 在 classpath 上无 servlet 类的 WebFlux 环境会抛 {@code NoClassDefFoundError}，
 * 导致应用启动失败。
 *
 * <p>因此契约与实现分离：
 * <ul>
 *   <li>本接口只暴露元数据查询能力，仅引用 {@code spring-web} 中的
 *       {@code HandlerMethod}，Servlet 与 WebFlux 均可用；</li>
 *   <li>Servlet 侧的启动期全量扫描由 {@link ServletMethodRegistry} 承担，
 *       且只被带环境条件的配置引用；</li>
 *   <li>WebFlux 侧不做启动扫描（在请求期从 exchange 取已匹配的 {@code HandlerMethod}），
 *       可以没有注册表实现。</li>
 * </ul>
 */
public interface AuditMethodRegistry {

	/**
	 * 精确查找该 Handler 的审计元数据。
	 *
	 * @param handlerMethod Spring MVC 已匹配的 Handler 方法
	 * @return 未命中返回 {@code null}，此时由边界通道兜底，事件不会缺失
	 */
	AuditMethodMetadata find(HandlerMethod handlerMethod);

	/**
	 * 已采集的元数据。
	 *
	 * @return key 为 {@code 类名#方法名(参数签名)} 的只读快照
	 */
	Map<String, AuditMethodMetadata> all();

	/**
	 * 已采集的方法 key 集合。
	 *
	 * @return {@link #all()} 的 key 视图
	 */
	default java.util.Set<String> collectedKeys() {
		return all().keySet();
	}

	/**
	 * 已采集的方法数量。
	 *
	 * @return {@link #all()} 的条目数
	 */
	default int collectedMethodCount() {
		return all().size();
	}

	/**
	 * 标注了注解但未被采集的 Handler，供启动校验告警。
	 *
	 * @return 不可变的诊断描述列表，无失配项时为空列表
	 */
	default List<String> excludedHandlers() {
		return List.of();
	}

	/**
	 * 扫描到的 Handler 总数。
	 *
	 * @return 仅 Servlet 启动扫描会返回非 0 值，其余实现恒为 0
	 */
	default int scannedHandlerCount() {
		return 0;
	}

	/**
	 * 元数据是否已就绪。
	 *
	 * @return 启动期扫描的注册表在 {@code populate} 前返回 {@code false}，按需解析的实现恒为 {@code true}
	 */
	default boolean isPopulated() {
		return true;
	}

	/**
	 * 返回不含任何元数据的空实现，供不支持启动扫描的环境使用。
	 *
	 * <p>该实现的 {@code find} 恒返回 {@code null}，因此调用方必须能接受语义字段缺失，
	 * 不能把它当作「无注解」的判定依据。
	 *
	 * @return 恒空的注册表实现
	 */
	static AuditMethodRegistry empty() {
		return new AuditMethodRegistry() {
			@Override
			public AuditMethodMetadata find(HandlerMethod handlerMethod) {
				return null;
			}

			@Override
			public Map<String, AuditMethodMetadata> all() {
				return Map.of();
			}
		};
	}
}
