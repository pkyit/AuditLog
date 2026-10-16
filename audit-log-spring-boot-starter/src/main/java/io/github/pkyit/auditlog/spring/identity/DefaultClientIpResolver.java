package io.github.pkyit.auditlog.spring.identity;

import io.github.pkyit.auditlog.core.api.ClientIpResolver;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 客户端 IP 解析。
 *
 * <p>默认不信任任何代理头：{@code X-Forwarded-For} 可由客户端任意设置，直接采信等于
 * 允许攻击者伪造审计记录中的来源地址；而只依赖 TCP 层地址（{@code getRemoteAddr()}）时，
 * 网关之后的所有请求都会记录为网关 IP，同样失去区分度。
 *
 * <p>因此只有当直连对端落在配置的可信代理网段内时，才解析并采用代理头，且从右向左取
 * 第一个非可信地址（最靠近服务端的真实客户端）。网段判定由 {@link #isTrusted(String)}
 * 提供，采集侧据此设置 {@code isTrustedProxy}。
 */
public class DefaultClientIpResolver implements ClientIpResolver {

	private final List<String> trustedPrefixes;

	public DefaultClientIpResolver(List<String> trustedPrefixes) {
		this.trustedPrefixes = trustedPrefixes == null ? List.of() : List.copyOf(trustedPrefixes);
	}

	@Override
	public Optional<String> resolve(ResolutionContext context) {
		String remote = context.getRemoteAddress();
		if (!context.isTrustedProxy()) {
			// 直连对端不可信：代理头一律忽略，用 TCP 层地址（不可伪造）
			return Optional.ofNullable(remote);
		}
		List<String> chain = new ArrayList<>();
		String forwardedFor = context.getHeader("X-Forwarded-For");
		if (forwardedFor != null && !forwardedFor.isBlank()) {
			for (String part : forwardedFor.split(",")) {
				String candidate = part.trim();
				if (!candidate.isEmpty() && !"unknown".equalsIgnoreCase(candidate)) {
					chain.add(candidate);
				}
			}
		}
		// 优先级：X-Forwarded-For 链 > X-Real-IP > 直连地址
		if (!chain.isEmpty()) {
			// 从右向左找第一个不在可信网段内的地址
			for (int index = chain.size() - 1; index >= 0; index--) {
				String candidate = chain.get(index);
				if (!isTrusted(candidate)) {
					return Optional.of(candidate);
				}
			}
			return Optional.of(chain.get(0));
		}
		String realIp = context.getHeader("X-Real-IP");
		if (realIp != null && !realIp.isBlank()) {
			return Optional.of(realIp.trim());
		}
		return Optional.ofNullable(remote);
	}

	/** 判断地址是否落在可信代理网段内。 */
	public boolean isTrusted(String address) {
		if (address == null || address.isBlank()) {
			return false;
		}
		for (String prefix : trustedPrefixes) {
			if (matches(prefix, address)) {
				return true;
			}
		}
		return false;
	}

	private boolean matches(String rule, String address) {
		String normalized = rule.trim();
		if (normalized.isEmpty()) {
			return false;
		}
		if (normalized.contains("/")) {
			return matchesCidr(normalized, address);
		}
		// 简单前缀/精确匹配（支持 "10." 这类常见配置）
		return address.startsWith(normalized) || address.equals(normalized);
	}

	private boolean matchesCidr(String cidr, String address) {
		try {
			String[] parts = cidr.split("/", 2);
			byte[] network = InetAddress.getByName(parts[0]).getAddress();
			int bits = Integer.parseInt(parts[1]);
			byte[] candidate = InetAddress.getByName(address).getAddress();
			if (network.length != candidate.length) {
				return false;
			}
			int fullBytes = bits / 8;
			int remainingBits = bits % 8;
			for (int index = 0; index < fullBytes; index++) {
				if (network[index] != candidate[index]) {
					return false;
				}
			}
			if (remainingBits == 0) {
				return true;
			}
			int mask = 0xFF << (8 - remainingBits);
			return (network[fullBytes] & mask) == (candidate[fullBytes] & mask);
		}
		catch (Exception ignored) {
			return false;
		}
	}

	public List<String> getTrustedPrefixes() {
		return trustedPrefixes;
	}
}
