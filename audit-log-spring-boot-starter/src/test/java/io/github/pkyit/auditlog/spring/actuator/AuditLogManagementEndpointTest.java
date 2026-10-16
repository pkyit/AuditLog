package io.github.pkyit.auditlog.spring.actuator;

import io.github.pkyit.auditlog.core.api.AuditFailureHandler;
import io.github.pkyit.auditlog.core.api.AuditMetrics;
import io.github.pkyit.auditlog.core.chain.Sha256AuditChain;
import io.github.pkyit.auditlog.core.metrics.SimpleAuditMetrics;
import io.github.pkyit.auditlog.core.pipeline.AuditPipeline;
import io.github.pkyit.auditlog.core.pipeline.BackpressureStrategy;
import io.github.pkyit.auditlog.core.store.InMemoryAuditStore;
import io.github.pkyit.auditlog.spring.method.AuditMethodRegistry;
import io.github.pkyit.auditlog.spring.properties.AuditLogProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 诊断端点的健壮性测试。
 *
 * <p>端点用 {@code Map.of(...)} 组装响应，而 {@code Map.of} 不接受 null 值；
 * 哈希链未启用时 {@code chain.currentHead()} 返回 null，端点在该处抛
 * {@code NullPointerException}，对外表现为 HTTP 500。哈希链默认关闭，
 * 该组合属于默认配置下的常态路径，必须被覆盖。
 *
 * <p>诊断端点的职责是暴露运行状态，自身失效会掩盖待诊断的故障，因此缺失的依赖
 * 与为 null 的链头只能降级呈现，不得向上抛异常。
 */
class AuditLogManagementEndpointTest {

	private AuditPipeline pipelineWithChain(boolean chainEnabled) {
		InMemoryAuditStore store = new InMemoryAuditStore(AuditMetrics.noop());
		AuditPipeline.Config config = new AuditPipeline.Config(
			64, 10, 50L, 1, BackpressureStrategy.SPILL, 200L, 0, 50L, chainEnabled, 100);
		return new AuditPipeline(config, store,
			chainEnabled ? new Sha256AuditChain() : Sha256AuditChain.noop(),
			new SimpleAuditMetrics(), AuditFailureHandler.logging(), null);
	}

	@Test
	@DisplayName("哈希链未启用时（默认配置）端点不得返回 500 —— 链头为 null 是正常状态")
	void doesNotFailWhenChainDisabled() {
		AuditPipeline pipeline = pipelineWithChain(false);
		AuditLogManagementEndpoint endpoint = new AuditLogManagementEndpoint(
			AuditMethodRegistry.empty(), pipeline, new AuditLogProperties());

		Map<String, Object> result = assertDoesNotThrow(endpoint::auditLog,
			"链未启用是默认配置，端点必须正常返回而不是抛 NPE");

		assertNotNull(result);
		@SuppressWarnings("unchecked")
		Map<String, Object> chain = (Map<String, Object>) result.get("chain");
		assertNotNull(chain, "应包含 chain 段");
		assertEquals(false, chain.get("enabled"));
		assertNull(chain.get("head"), "未启用时链头应为 null（而不是让端点崩溃）");
	}

	@Test
	@DisplayName("哈希链启用时端点正常返回链头与校验结果")
	void reportsChainHeadWhenEnabled() {
		AuditPipeline pipeline = pipelineWithChain(true);
		AuditLogManagementEndpoint endpoint = new AuditLogManagementEndpoint(
			AuditMethodRegistry.empty(), pipeline, new AuditLogProperties());

		Map<String, Object> result = assertDoesNotThrow(endpoint::auditLog);
		@SuppressWarnings("unchecked")
		Map<String, Object> chain = (Map<String, Object>) result.get("chain");
		assertEquals(true, chain.get("enabled"));
		assertEquals("GENESIS", chain.get("head"), "未链接任何记录时链头应为创世值");

		@SuppressWarnings("unchecked")
		Map<String, Object> verification = (Map<String, Object>) result.get("chainVerification");
		assertNotNull(verification);
		assertEquals(true, verification.get("valid"));
	}

	@Test
	@DisplayName("流水线缺失时端点也不得崩溃（降级为 -1/none）")
	void survivesMissingPipeline() {
		AuditLogManagementEndpoint endpoint = new AuditLogManagementEndpoint(
			AuditMethodRegistry.empty(), null, new AuditLogProperties());
		Map<String, Object> result = assertDoesNotThrow(endpoint::auditLog,
			"流水线尚未就绪时端点应给出降级信息，而不是抛异常");
		assertNotNull(result);
		@SuppressWarnings("unchecked")
		Map<String, Object> store = (Map<String, Object>) result.get("store");
		assertEquals("none", store.get("name"));
		assertTrue(result.containsKey("validation"));
	}
}
