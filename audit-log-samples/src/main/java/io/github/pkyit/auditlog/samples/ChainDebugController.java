package io.github.pkyit.auditlog.samples;

import io.github.pkyit.auditlog.core.api.AuditChain;
import io.github.pkyit.auditlog.core.chain.Hasher;
import io.github.pkyit.auditlog.core.model.AuditRecord;
import io.github.pkyit.auditlog.core.pipeline.AuditPipeline;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 诊断端点：暴露已投递记录的规范化内容，供外部实现独立复核哈希链。
 *
 * <p>哈希链的防篡改结论不能只由框架自身的校验接口给出，需要外部实现（例如 Python 脚本）
 * 独立重算并比对，这要求规范化内容可复现。本端点原样返回应用真正参与哈希计算的字符串，
 * 以便与外部实现逐字节比对、定位差异。
 *
 * <p>默认关闭：响应包含审计记录的规范化内容与链字段，属于敏感诊断信息，
 * 不应在生产环境暴露，需通过 {@code audit.log.debug-endpoint.enabled=true} 显式开启。
 */
@RestController
@RequestMapping("/debug")
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
	name = "audit.log.debug-endpoint.enabled", havingValue = "true")
public class ChainDebugController {

	private final AuditPipeline pipeline;

	/**
	 * 注入审计流水线。
	 *
	 * @param pipeline 提供最近记录与链状态的审计流水线
	 */
	public ChainDebugController(AuditPipeline pipeline) {
		this.pipeline = pipeline;
	}

	/**
	 * 返回最近记录的条数、链开关与链尾哈希，以及首尾两条记录的规范化内容与链字段。
	 *
	 * <p>只读接口：不会触发投递、链接或状态变更。{@code recomputedSha256} 仅在无签名链
	 * （HMAC 链需要密钥）下给出，其余字段为原始值的拷贝。
	 *
	 * @return 诊断字段映射；无记录时只含 {@code recentCount}、{@code chainEnabled} 与 {@code chainHead}
	 */
	@GetMapping("/chain")
	public Map<String, Object> chain() {
		Map<String, Object> result = new LinkedHashMap<>();
		List<AuditRecord> records = pipeline.recentRecords();
		result.put("recentCount", records.size());
		result.put("chainEnabled", pipeline.chain().isEnabled());
		result.put("chainHead", pipeline.chain().currentHead());
		if (records.isEmpty()) {
			return result;
		}
		AuditRecord first = records.get(0);
		AuditRecord last = records.get(records.size() - 1);
		result.put("first", describe(first));
		result.put("last", describe(last));
		return result;
	}

	private Map<String, Object> describe(AuditRecord record) {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("id", record.getId());
		map.put("seq", record.getSeq());
		map.put("canonicalContent", record.canonicalContent());
		map.put("prevHash", record.getPrevHash());
		map.put("recordHash", record.getRecordHash());
		map.put("signature", record.getSignature());
		// 便于外部实现直接核对：按本框架的算法重算一次。
		// 链有两种实现，公式不同，校验器必须能区分：
		//   无 secret: recordHash = sha256(prevHash + '|' + canonicalContent)
		//   有 secret: recordHash = HMAC-SHA256(secret, prevHash + '|' + canonicalContent)
		//              且 signature = HMAC-SHA256(secret, recordHash)
		map.put("signed", record.getSignature() != null);
		map.put("recomputeFormula", record.getSignature() != null
			? "HMAC-SHA256(secret, prevHash + '|' + canonicalContent)"
			: "sha256(prevHash + '|' + canonicalContent)");
		// 无密钥链可直接在应用内重算；HMAC 链的重算需要密钥，因此只在无签名时给出，
		// 避免对外暴露密钥或输出无法被外部复核的重算值。
		map.put("recomputedSha256", record.getSignature() == null
			? Hasher.sha256(record.getPrevHash() + '|' + record.canonicalContent())
			: null);
		return map;
	}
}
